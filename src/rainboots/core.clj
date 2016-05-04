(ns ^{:author "Daniel Leong"
      :doc "Core interface"} 
  rainboots.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [rainboots
             [color :refer [process-colors]]
             [command :refer [default-on-cmd]]
             [proto :refer [tn-iac wrap-stream]]
             [util :refer [log wrap-fn]]]))

(def default-port 4321)

(def ^:dynamic *svr*)

(declare telnet!)

(defn- make-client
  [stream]
  {:stream stream
   :term-types #{}
   :input-stack (atom[])})

(defmacro with-binds
  [& body]
  `(binding [*svr* ~'svr]
     ~@body))

(defn- handle-telnet
  [cli pkt fallback]
  (cond
    ;; the client can send term type! request it
    (= {:telnet :will 
        :opt :term-type} pkt)
    (telnet! cli {:telnet :term-type
                :opt [:send]})
    ;; the client is sending their term type
    (= :term-type (:telnet pkt))
    (if-not (contains? (:term-types @cli) (:opt pkt))
      (do
        ;; another new term type; keep requesting until
        ;;  we know them all
        (swap! cli update :term-types conj (:opt pkt))
        (telnet! cli {:telnet :term-type :opt [:send]}))
      ;; we've got 'em all (TODO preprocess for color?)
      (log "* Client term types: " (:term-types @cli)))
    ;; else, let the provided callback handle
    :else
    (fallback cli pkt)))

(defn- handler
  [svr opts s info]
  (log "* New Client: " info)
  (let [on-connect (:on-connect opts)
        on-auth (:on-auth opts)
        on-cmd (:on-cmd opts)
        on-404 (:on-404 opts)
        on-telnet (:on-telnet opts)
        on-disconnect (:on-disconnect opts)
        client (atom {})
        wrapped 
        (wrap-stream
          s 
          (fn [s pkt]
            (with-binds
             (if (string? pkt)
               ;; string pkt...
               (if (:ch @client)
                 ;; logged in; use cmd handler
                 (on-cmd client pkt)
                 ;; need auth still
                 (on-auth client pkt))
               ;; simple; telnet pkt
               (handle-telnet client pkt on-telnet)))))] 
    (reset! client (make-client wrapped))
    (swap! (:connected @svr) conj client)
    (with-binds
      (on-connect client))
    (s/on-closed
      s
      (fn []
        (swap! (:connected @svr) disj client)
        (with-binds
          (on-disconnect client))))
    ;; request terminal type
    ;; TODO we could be more generic about this
    ;; (include a set of things we'll ":do" in
    ;; the server opts)
    (telnet! client {:telnet :do
                     :opt :term-type})))

;;
;; Server control
;;

(defn- -start-server
  "NB: You should use (start-server)
  instead of using this directly."
  [& {:keys [port 
             on-auth on-cmd
             on-404
             on-connect on-disconnect
             on-telnet] 
      :as opts}] 
  {:pre [(not (nil? on-connect))
         (not (nil? on-cmd))
         (not (nil? on-auth))]}
  (let [obj (atom {:connected (atom #{})})
        svr (tcp/start-server 
              (partial handler obj opts) 
              opts)]
    (swap! obj assoc :closable svr)
    ;; re-def dynamically so there's some
    ;;  default value for use in REPL; handlers
    ;;  should always get the correct instance
    ;;  thanks to use of (binding) above (although
    ;;  I'm not sure why you'd start more than one
    ;;  server in the same JVM, anyway....)
    (def ^:dynamic *svr* obj)
    obj))

(defmacro start-server
  "Start up a server with the provided
  callbacks and options. This is a macro
  so that you can supply function references
  and still easily update them via repl.
  Callbacks:
  :on-auth (fn [cli line]) Called on each
           raw line of input from the client
           until they have something in the 
           :ch field of the `cli` atom. This 
           should be where you store the 
           character info.
  :on-cmd (fn [cli cmd]) Called on each command
          input from an auth'd client."
  [& {:keys [port 
             on-auth on-cmd 
             on-404
             on-connect on-disconnect
             on-telnet] 
      :or {port default-port
           on-404 `(fn [cli# & etc#]
                     (send! cli# "Huh?"))
           on-disconnect `(constantly nil)
           on-telnet `(constantly nil)}
      :as opts}]
  ;; NB: this lets us call the private function
  ;;  even from a macro:
  (let [on-cmd (if on-cmd
                 on-cmd
                 `(partial default-on-cmd ~on-404))]
    `(#'-start-server
       :port ~port
       :on-auth (wrap-fn ~on-auth)
       :on-connect (wrap-fn ~on-connect)
       :on-cmd (wrap-fn ~on-cmd)
       :on-404 (wrap-fn ~on-404)
       :on-disconnect (wrap-fn ~on-disconnect)
       :on-telnet (wrap-fn ~on-telnet))))

(defn stop-server
  [server]
  (.close (:closable @server)))

;;
;; Communication
;;

(defn close!
  "Disconnect the client"
  [cli]
  (s/close! (:stream @cli)))

(defn send!
  "Send text to the client. You can pass in a variable
  number of args, which may in turn be strings, vectors,
  or functions. Vectors will be treated as additional
  varargs (IE: (apply)'d to this function).  Functions
  will be called with the client as a single argument,
  and the result sent as if it were passed directly.
  Strings, and any string returned by a function argument
  or in a vector, will be processed for color sequences
  (see the colors module).
  Maps will be treated as telnet sequences (see telnet!)"
  [cli & body]
  (when-let [s (:stream @cli)]
    (doseq [p body]
      (when p
        (condp #(%1 %2) p
          vector? (apply send! cli p)
          string? (s/put! s (process-colors p))
          map? (s/put! s p)
          fn? (send! cli (p cli)))))
    (s/put! s "\r\n")))

(defn send-if!
  "Send text to every connected client for which 
  (pred cli) returns true. The arguments will be
  handled in the same way (send!) handles them."
  [pred & body]
  (when-let [clients (seq @(:connected @*svr*))]
    (doseq [cli clients]
      (when (pred cli)
        (apply send! cli body)))))

(defn send-all!
  "Send text to every connected client. This is
  a convenience function."
  [& body]
  (apply send-if! (constantly true) body))

(defn telnet!
  "Send a telnet map (like thsoe received in :on-telnet)
  or a vector of raw bytes as a telnet instruction. Telnet
  maps can also be sent using (send!) for convenience;
  note, however, that send! appends an '\\r\\n' sequence
  to whatever is passed, so if you ONLY want to send a
  telnet sequence, this is the way to go."
  [cli telnet]
  (when-let [s (:stream @cli)]
    (cond
      ;; raw bytes vector
      (and (vector? telnet)
           (= tn-iac (first telnet)))
      (s/put! s (byte-array telnet))
      ;; improper telnet sequence
      (vector? telnet)
      (throw (IllegalArgumentException.
               (str "Telnet byte sequences must start"
                    "with tn-iac constant")))
      ;; it's a map; the protocol will handle it
      (map? telnet)
      (s/put! s telnet))))

(defn push-cmds!
  "Push a new cmdset to the top of the user's
  input stack. Only function at the top of this
  stack receives input. This is only meaningful
  if you haven't provided your own on-cmd handler
  (but why would you?). 
  The provided cmd-set can be anything declared 
  with (defcmdset), or, in fact, any function
  that looks like (fn [on-404 cli input]), where:
  `on-404 is the function configured for
  when a command doesn't exist; 
  `cli` is the client providing the input; and
  `input` is the raw String input line"
  [cli cmd-set]
  (swap! (:input-stack @cli) conj cmd-set))

(defn pop-cmds!
  "Pop the top-most cmdset from the user's
  input stack. See push-cmds!"
  [cli]
  (swap! (:input-stack @cli) pop))

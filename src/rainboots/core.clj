(ns ^{:author "Daniel Leong"
      :doc "Core interface"} 
  rainboots.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [potemkin :refer [import-vars]]
            [rainboots
             [color :refer [determine-colors process-colors
                            strip-colors]]
             [comms :as comms]
             [command :refer [default-on-cmd]]
             [proto :refer [tn-iac wrap-stream]]
             [util :refer [log wrap-fn]]]))

(def default-port 4321)

;; copy from comms for convenience
(import-vars
  [rainboots.comms
   *svr*
   send!
   send-if!
   send-all!])

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

(def default-telnet-opts
  #{:term-type})

(defn- handle-telnet
  [cli pkt accepted-opts fallback]
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
      ;; we've got 'em all; preprocess for color
      (determine-colors cli))
    (= :will (:telnet pkt))
    (if (contains? accepted-opts (:opt pkt))
      (telnet! cli {:telnet :do :opt (:opt pkt)})
      (telnet! cli {:telnet :wont :opt (:opt pkt)}))
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
        telnet-opts (:telnet-opts opts)
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
                (handle-telnet client pkt
                               telnet-opts on-telnet)))))] 
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
    ;; FIXME we should allow specifying more than :do
    (doseq [opt telnet-opts]
      (telnet! client {:telnet :do
                       :opt opt}))))

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
    (comms/redef-svr! obj)
    ;; re-import
    (import-vars [rainboots.comms *svr*])
    obj))

(defmacro start-server
  "Start up a server with the provided
  callbacks and options. This is a macro
  so that you can supply function references
  and still easily update them via repl.
  Options:
  :port Port on which to start the server
  :telnet-opts A set of handled telnet op codes. 
               will automatically specify 'WILL'
               for these opts. May be keywords
               specified in rainboots.proto, or
               the raw int value. By default, we
               will handle :term-type and fill out
               a set in :term-types of the client map
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
             telnet-opts
             on-auth on-cmd 
             on-404
             on-connect on-disconnect
             on-telnet] 
      :or {port default-port
           telnet-opts default-telnet-opts
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
       :telnet-opts ~telnet-opts
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
      (s/put! s telnet))
    (s/put! s "\r")))

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

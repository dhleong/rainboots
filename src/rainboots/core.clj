(ns ^{:author "Daniel Leong"
      :doc "Core interface"}
  rainboots.core
  (:require [clojure.core.async :as async :refer [<! chan mix admix unmix]]
            [aleph.tcp :as tcp]
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

(def default-prompt-delay 15)

;; copy from comms for convenience
(import-vars
  [rainboots.comms
   *svr*
   send!
   send-if!
   send-all!])

;; other imports to sort of hide the public util methods unless you
;; know what you're doing
(import-vars
  [rainboots.hooks
   hook!
   unhook!
   trigger!])

(declare telnet!)

(defn throttle
  "Read from ch until it closes, throttling reads such that only the
   last value emitted without any others following for `duration` ms
   will be accepted; read values will be passed to f."
  [ch f duration]
  (async/go-loop [last-val nil]
    (when last-val
      (<! (async/timeout duration)))

    (if-let [polled (async/poll! ch)]
      ; still more
      (recur polled)

      ; none yet!
      (do
        ; trigger on last-val
        (when last-val
          (f last-val))

        ; try to read; if it is nil, the channel was closed
        (when-let [v (<! ch)]
          (recur v))))))

(defn- make-client
  [svr stream remote-info]
  (let [{:keys [on-prompt prompt-delay]} @svr
        prompt-chan (chan (async/sliding-buffer 1))]
    (when on-prompt
      (throttle
        prompt-chan
        (fn [to-prompt]
          (when-let [prompt (on-prompt to-prompt)]
            (if-let [as-seq (seq prompt)]
              (apply send! {:rainboots/in-prompt true}
                     to-prompt
                     as-seq)
              (send! {:rainboots/in-prompt true}
                     to-prompt
                     prompt))))
        prompt-delay))

    {:stream stream
     :term-types #{}
     :input-stack []
     ::remote remote-info
     ::prompt-chan prompt-chan}))

(defmacro with-binds
  [& body]
  `(binding [*svr* ~'svr]
     ~@body))

(def default-telnet-opts
  #{:term-type})

(defn- handle-telnet
  [cli pkt accepted-opts fallback]
  (cond
    ; the client can send term type! request it
    (= {:telnet :will
        :opt :term-type}
       pkt)
    (telnet! cli {:telnet :term-type
                  :opt [:send]})

    ; the client is sending their term type
    (= :term-type (:telnet pkt))
    (if-not (contains? (:term-types @cli) (:opt pkt))
      (do
        ; another new term type; keep requesting until we know them all
        (swap! cli update :term-types conj (:opt pkt))
        (telnet! cli {:telnet :term-type :opt [:send]}))

      ; we've got 'em all; preprocess for color
      (determine-colors cli))

    ; handle other telopts
    (= :will (:telnet pkt))
    (if (contains? accepted-opts (:opt pkt))
      (telnet! cli {:telnet :do :opt (:opt pkt)})
      (telnet! cli {:telnet :wont :opt (:opt pkt)}))

    ; else, let the provided callback handle
    :else
    (fallback cli pkt)))

(defn- handler
  [svr opts s info]
  (log "* New Client: " info)
  (let [on-connect (:on-connect opts)
        on-auth (:on-auth opts)
        on-cmd (:on-cmd opts)
        telnet-opts (:telnet-opts opts)
        on-telnet (:on-telnet opts)
        on-disconnect (:on-disconnect opts)
        client (atom {})

        wrapped (wrap-stream
                  s
                  (fn [_ pkt]
                    (with-binds
                      (cond
                        ; simple; telnet pkt
                        (not (string? pkt))
                        (handle-telnet client pkt
                                       telnet-opts on-telnet)

                        ; logged in; use cmd handler
                        (:ch @client)
                        (on-cmd client pkt)

                        ; otherwise, still need auth
                        :else
                        (on-auth client pkt)))))]

    (reset! client (make-client svr wrapped info))
    (swap! (:connected @svr) conj client)

    (with-binds
      (on-connect client))

    (s/on-closed
      s
      (fn []
        (swap! (:connected @svr) disj client)
        (when-let [prompt-chan (::prompt-chan @client)]
          ; stop prompting
          (async/close! prompt-chan))

        (with-binds
          (on-disconnect client))))

    ; request terminal type
    ; FIXME we should allow specifying more than :do
    (doseq [opt telnet-opts]
      (telnet! client {:telnet :do
                       :opt opt}))))

;;
;; Server control
;;

(defn- -start-server
  "NB: You should use (start-server) instead of using this directly."
  [& {:keys [port
             on-auth on-cmd
             on-404
             on-connect on-disconnect
             on-prompt prompt-delay
             on-telnet]
      :as opts}]
  {:pre [(number? port)
         (not (nil? on-connect))
         (not (nil? on-cmd))
         (not (nil? on-auth))
         (when on-404
           (fn? on-404))
         (when on-disconnect
           (fn? on-disconnect))
         (when on-telnet
           (fn? on-telnet))]}

  (let [obj (atom {:connected (atom #{})
                   :on-prompt on-prompt
                   :prompt-delay prompt-delay})
        svr (tcp/start-server
              (partial handler obj opts)
              opts)]

    (swap! obj assoc :closable svr)

    ; re-def dynamically so there's some default value for use in REPL;
    ; handlers should always get the correct instance thanks to use of
    ; (binding) above (although I'm not sure why you'd start more than one
    ; server in the same JVM, anyway....)
    (comms/redef-svr! obj)

    ; re-import
    (import-vars [rainboots.comms *svr*])

    obj))

(defmacro start-server
  "Start up a server with the provided callbacks and options. This is a
   macro so that you can supply function references and still easily
   update them via repl.

  Options:
  :port Port on which to start the server
  :prompt-delay Milliseconds to wait after a send! to trigger :on-prompt
  :telnet-opts A set of handled telnet op codes. Rainboots will
               automatically specify 'WILL' for these opts. May be
               keywords specified in rainboots.proto, or the raw int
               value. By default, we will handle :term-type and fill
               out a set in :term-types of the client map

  Callbacks:
  :can-exec? (fn [cli cmd]) Called to check if `cli` is allowed to
             execute `cmd`. Return truthy if yes, else return falsy and
             notify the client why. Only applies when using the default
             :on-cmd
  :on-auth (fn [cli line]) Called on each raw line of input from the
           client until they have something in the :ch field of the
           `cli` atom. This should be where you store the character
           info.
  :on-cmd (fn [cli cmd]) Called on each command input from an auth'd
          client.
  :on-err (fn [cli e]) Called if a client-executed command throw a
          Throwable; only applies when using the default on-cmd
  :on-prompt (fn [cli]) Called when it's time to show the client a
             prompt. Return a string or a sequence and it will be
             passed or (apply)'d to (send!), respectively."
  [& {:keys [port
             telnet-opts
             can-exec?
             on-auth on-cmd
             on-404
             on-err
             on-connect on-disconnect
             on-prompt prompt-delay
             on-telnet]
      :or {port default-port
           telnet-opts default-telnet-opts
           can-exec? `(constantly true)
           on-404 `(fn [cli# & etc#]
                     (send! cli# "Huh?"))
           on-err `(fn [cli# e#]
                     (log e#)
                     (send! cli# "{Y<< {RERROR: {WSomething went wrong. {Y>>{n"))
           on-disconnect `(constantly nil)
           prompt-delay default-prompt-delay
           on-telnet `(constantly nil)}}]

  ;; NB: this lets us call the private function
  ;;  even from a macro:
  (let [on-cmd (if on-cmd
                 on-cmd
                 `(partial default-on-cmd
                           ~on-err ~on-404 ~can-exec?))]
    `(#'-start-server
       :port ~port
       :telnet-opts ~telnet-opts
       :prompt-delay ~prompt-delay
       :on-auth (wrap-fn ~on-auth)
       :on-connect (wrap-fn ~on-connect)
       :on-cmd (wrap-fn ~on-cmd)
       :on-404 (wrap-fn ~on-404)
       :on-disconnect (wrap-fn ~on-disconnect)
       :on-telnet (wrap-fn ~on-telnet)
       :on-prompt ~(when on-prompt
                     `(wrap-fn ~on-prompt)))))

(defn stop-server
  [server]
  (.close (:closable @server)))

;;
;; Communication
;;

(defn close!
  "Disconnect the client"
  [cli]
  (swap! cli assoc ::closed? true)
  (when-let [s (:stream @cli)]
    (s/close! s)))

(defn telnet!
  "Send a telnet map (like thsoe received in :on-telnet) or a vector of
   raw bytes as a telnet instruction. Telnet maps can also be sent
   using (send!) for convenience; note, however, that send! appends an
   '\\r\\n' sequence to whatever is passed, so if you ONLY want to send
   a telnet sequence, this is the way to go."
  [cli telnet]
  (when-let [s (:stream @cli)]
    (cond
      ; raw bytes vector
      (and (vector? telnet)
           (= tn-iac (first telnet)))
      (s/put! s (byte-array telnet))

      ; improper telnet sequence
      (vector? telnet)
      (throw (IllegalArgumentException.
               (str "Telnet byte sequences must start"
                    "with tn-iac constant")))

      ; it's a map; the protocol will handle it
      (map? telnet)
      (s/put! s telnet))

    (s/put! s "\r")))

(defn push-cmds!
  "Push a new cmdset to the top of the user's input stack. Only
   function at the top of this stack receives input. This is only
   meaningful if you haven't provided your own on-cmd handler (but why
   would you?).  The provided cmd-set can be anything declared with
   (defcmdset), or, in fact, any function that looks like (fn [on-404
   can-exec? cli input]), where:

  `on-404` is the function configured for when a command doesn't exist;

  `can-exec?` is a (fn [cli cmd]), where `cmd` is the a fn created by
   defcmd that `cli` is hoping to execute.

  `cli` is the client providing the input; and

  `input` is the raw String input line"
  [cli cmd-set]
  (swap! cli update :input-stack conj cmd-set))

(defn pop-cmds!
  "Pop the top-most cmdset from the user's input stack. See push-cmds!"
  [cli]
  (swap! cli update :input-stack pop))

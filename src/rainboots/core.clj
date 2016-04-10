(ns ^{:author "Daniel Leong"
      :doc "Core interface"} 
  rainboots.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [rainboots
             [color :refer [process-colors]]
             [parse :refer [parse-command]]
             [proto :refer [wrap-stream]]
             [util :refer [log wrap-fn]]]))

(def default-port 4321)

(def ^:dynamic *svr*)

(defn- make-client
  [stream]
  {:stream stream})

(defmacro with-binds
  [& body]
  `(binding [*svr* ~'svr]
     ~@body))

(defn on-incoming
  "Dispatches incoming lines as appropriate"
  [cli pkt on-auth on-cmd]
  (if (:ch @cli)
    (on-cmd cli (parse-command pkt))
    (on-auth cli pkt)))

(defn- handler
  [svr opts s info]
  (log "* New Client: " info)
  (let [on-connect (:on-connect opts)
        on-auth (:on-auth opts)
        on-cmd (:on-cmd opts)
        on-telnet (:on-telnet opts)
        on-disconnect (:on-disconnect opts)
        client (atom {})
        wrapped 
        (wrap-stream
          s 
          (fn [s pkt]
            (if (string? pkt)
              ;; string pkt
              (with-binds
                (on-incoming client pkt 
                             on-auth on-cmd))
              ;; telnet pkt
              (with-binds
                (on-telnet client pkt)))))] 
    (reset! client (make-client wrapped))
    (with-binds
      (on-connect client))
    (s/on-closed
      s
      #(with-binds
         (on-disconnect client)))))

;;
;; Server control
;;

(defn- -start-server
  "NB: You should use (start-server)
  instead of using this directly."
  [& {:keys [port 
             on-auth on-cmd
             on-connect on-disconnect
             on-telnet] 
      :as opts}] 
  {:pre [(not (nil? on-connect))
         (not (nil? on-cmd))
         (not (nil? on-auth))]}
  (let [obj (atom {:connected (atom [])})
        svr (tcp/start-server 
              (partial handler obj opts) 
              opts)]
    (swap! obj assoc :closable svr)
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
             on-connect on-disconnect
             on-telnet] 
      :or {port default-port
           on-disconnect `(constantly nil)
           on-telnet `(constantly nil)}
      :as opts}]
  ;; NB: this lets us call the private function
  ;;  even from a macro:
  `(#'-start-server
     :port ~port
     :on-auth (wrap-fn ~on-auth)
     :on-connect (wrap-fn ~on-connect)
     :on-cmd (wrap-fn ~on-cmd)
     :on-disconnect (wrap-fn ~on-disconnect)
     :on-telnet (wrap-fn ~on-telnet)))

(defn stop-server
  [server]
  (.close (:closable @server)))

;;
;; Communication
;;

(defn send!
  "Send text to the client"
  [cli & body]
  (let [s (:stream @cli)]
    (doseq [p body]
      (if (vector? p)
        (apply send! cli p)
        (s/put! s (process-colors p))))))

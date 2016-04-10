(ns ^{:author "Daniel Leong"
      :doc "Core interface"} 
  rainboots.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [rainboots
             [parse :refer [parse-command]]
             [proto :refer [wrap-stream]]
             [util :refer [log]]]))

(def default-port 4321)

(def ^:dynamic *svr*)

(defn- make-client
  [stream]
  {:stream stream})

(defmacro with-binds
  [& body]
  `(binding [*svr* ~'svr]
     ~@body))

(defn- handler
  [svr opts s info]
  (log "* New Client: " info)
  (let [on-connect (:on-connect opts)
        on-cmd (:on-cmd opts)
        on-disconnect (:on-disconnect opts)
        client (atom {})
        wrapped 
        (wrap-stream
          s 
          (fn [s pkt]
            (when (string? pkt)
              (with-binds
                (on-cmd
                  client
                  (parse-command pkt))))))] 
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

(defn start-server
  "Start up a server with the provided
  callbacks and options."
  [& {:keys [port 
             on-connect on-disconnect
             on-cmd on-telnet] 
      :or {port default-port
           on-disconnect (constantly nil)
           on-telnet (constantly nil)}
      :as opts}]
  {:pre [(not (nil? on-connect))
         (not (nil? on-cmd))]}
  (let [obj (atom {:connected (atom [])})
        svr (tcp/start-server 
              (partial 
                handler obj 
                (assoc opts
                       :on-disconnect on-disconnect
                       :on-telnet on-telnet)) 
              opts)]
    (swap! obj assoc :closable svr)
    obj))

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
        (do
          (println "!!SEND: " p (class p))
          (s/put! s p))))))

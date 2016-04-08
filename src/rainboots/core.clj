(ns rainboots.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [gloss
             [core :as gloss]
             [io :as io]]
            [rainboots.proto :refer [telnet-protocol]]))

(defn wrap-stream
  [s on-packet]
  (let [out (s/stream)]
    (s/connect
      (s/map identity out) ; TODO encode telnet commands 
      s)
    (let [spliced 
          (s/splice 
            out
            (io/decode-stream s telnet-protocol))]
      (s/consume
        #(on-packet s %)
        spliced)
      spliced)))

(def received [])

(defn handler
  [s info]
  (wrap-stream s #(def received (conj received %2))))

(defn start-server
  "I don't do a whole lot."
  [& {:keys [port] :as opts}]
  (tcp/start-server handler opts))

(defn stop-server
  [server]
  (.close server))

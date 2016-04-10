(ns rainboots.sample
  (:require [rainboots
             [color :refer [ansi]]
             [core :refer :all]
             [util :refer [wrap-fn]]]))

(defn on-connect
  [cli]
  (println "! Connected: " cli)
  (send! cli "{WHello!" "\r\n{yLogin {Gnow:{n\r\n"))

(defn on-cmd
  [cli cmd]
  (println "* Received " cmd)
  (send! cli "You said: " cmd "\r\n"))

(defn on-telnet
  [cli pkt]
  (println "# Telnet: " pkt))

(defn start-sample
  []
  (def svr 
    (start-server
      :port 4321
      :on-connect on-connect
      :on-telnet on-telnet
      :on-cmd on-cmd)))

(defn stop-sample
  []
  (stop-server svr))

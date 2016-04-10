(ns rainboots.sample
  (:require [rainboots
             [color :refer [ansi]]
             [core :refer :all]]))

(defn start-sample
  []
  (def svr 
    (start-server
      :port 4321
      :on-connect (fn [cli]
                    (println "! Connected: " cli)
                    (send! cli (ansi :bright-white "Hello!") "\r\nLogin now:\r\n"))
      :on-cmd (fn [cli cmd]
                (println "* Received: " cmd)
                (send! cli "You said: " cmd "\n")))))

(defn stop-sample
  []
  (stop-server svr))

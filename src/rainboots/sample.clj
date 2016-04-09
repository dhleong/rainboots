(ns rainboots.sample
  (:require [rainboots.core :refer :all]))

(defn start-sample
  []
  (def svr 
    (start-server
      :port 4321
      :on-connect (fn [cli]
                    (println "! Connected: " cli)
                    (send! cli "Hello!\nLogin now:"))
      :on-cmd (fn [cli cmd]
                (send! cli "You said: " cmd)))))

(defn stop-sample
  []
  (stop-server svr))

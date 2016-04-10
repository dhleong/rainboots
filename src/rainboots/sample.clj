(ns rainboots.sample
  (:require [rainboots
             [command :refer [defcmd]]
             [color :refer [ansi]]
             [core :refer :all]
             [util :refer [wrap-fn]]]))

(defn on-auth
  [cli line]
  (if-let [u (:user @cli)] 
    (do
      ;; NB: check password
      (println "! Auth:" u)
      (swap! cli assoc :ch {:name "Joe"
                            :user u})
      (send! cli 
             "Logged in as {W" 
             (-> @cli :ch :name)
             "{n"))
    (do
      (swap! cli assoc :user line)
      (send! cli "Password:")
      (println "! User:" line))))

(defn on-connect
  [cli]
  (println "! Connected: " cli)
  (send! cli "{WHello!" "\r\n{yLogin {Gnow:{n"))

(defn on-telnet
  [cli pkt]
  (println "# Telnet: " pkt))

;;
;; Commands
;;

(defcmd look
  [cli & [dir]]
  (send! cli "You look: " dir))

(defcmd quit
  [cli]
  (send! cli "Goodbye!")
  (close! cli))

;;
;; Lifecycle
;;

(defn start-sample
  []
  (def svr 
    (start-server
      :port 4321
      :on-auth on-auth
      :on-connect on-connect
      :on-telnet on-telnet)))

(defn stop-sample
  []
  (stop-server svr))

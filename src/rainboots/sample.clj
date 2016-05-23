(ns rainboots.sample
  (:require [rainboots
             [command :refer [defcmdset defcmd]]
             [color :refer [ansi]]
             [core :refer :all]
             [proto :refer [tn-iac tn-do tn-op-ttype]]
             [util :refer [wrap-fn]]]))

(defn on-auth
  [cli line]
  (if-let [u (:user @cli)] 
    (do
      ;; NB: check password
      (println "! Auth:" u)
      (swap! cli assoc :ch {:name "Joe"
                            :user u})
      (send-all! (-> @cli :ch :name) " has entered the world")
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
;; Sample command set
;;

(defcmdset in-buffer
  (defcmd done
    [cli]
    (pop-cmds! cli)
    (send! cli "Buffer is done!")))

;;
;; Commands
;;

(defcmd look
  [cli & [dir]]
  (send! cli "You look: " dir))

(defcmd buffer
  [cli]
  (push-cmds!
    cli
    (fn [_ cli input]
      (when-not (in-buffer (constantly nil) cli input)
        (send! cli "You said: " input))))
  (send! cli "You're in a buffer now!"))

(defcmd ^:no-abbrv quit
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

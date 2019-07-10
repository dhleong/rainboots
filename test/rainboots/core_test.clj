(ns rainboots.core-test
  (:require [clojure.test :refer :all]
            [manifold.stream :as s]
            [rainboots
             [core :as core :refer :all]
             [color :refer [ansi-esc]]
             [proto :refer :all]]))

(deftest cmds-stack-test
  (testing "Push pop"
    (let [cli (atom {:input-stack (atom [])})]
      (push-cmds! cli :a)
      (push-cmds! cli :b)
      (is (= [:a :b]
             @(:input-stack @cli)))
      (pop-cmds! cli)
      (is (= [:a]
             @(:input-stack @cli)))
      (push-cmds! cli :c)
      (is (= [:a :c]
             @(:input-stack @cli))))))

;;
;; send! is very important and handles
;;  many things, so we put some extra
;;  effort into verifying it
;;

(defmacro with-cli
  [& body]
  `(let [~'*input* (s/stream 10)
         ~'*output* (s/stream 10)
         ~'cli (atom {:stream ~'*input*})]
     (s/connect ~'*input* ~'*output*)
     ~@body))

(defmacro take!
  []
  `(deref
     (s/try-take! ~'*output* :failure
                  25 :timeout)))

(deftest close!-test
  (testing "close!"
    (with-cli
      (close! cli)
      (is (true? (::core/closed? @cli)))
      (is (s/closed? *input*)))))

(deftest send!-test
  (testing "Single string"
    (with-cli
      (send! cli "Mal")
      (is (= (take!) "Mal"))))
  (testing "Vararg strings"
    (with-cli
      (send! cli "Mal" " Reynolds")
      (is (= (take!) "Mal"))
      (is (= (take!) " Reynolds"))))
  (testing "Vector"
    (with-cli
      (send! cli ["Mal" " Reynolds"])
      (is (= (take!) "Mal"))
      (is (= (take!) " Reynolds"))))
  (testing "fn"
    (with-cli
      (send! cli (constantly "Reynolds"))
      (is (= (take!) "Reynolds"))))
  (testing "fn returns vector"
    (with-cli
      (send! cli (constantly ["Reynolds"]))
      (is (= (take!) "Reynolds"))))
  (testing "Process colors (ansi enabled)"
    (with-cli
      (swap! cli assoc :colors :ansi)
      (send! cli "{YReynolds")
      (is (= (take!) (str ansi-esc "1;33mReynolds")))))
  (testing "Process colors (ansi disabled)"
    ;; IE: strip out the color sequence
    (with-cli
      (send! cli "{YReynolds")
      (is (= (take!) "Reynolds")))))

(deftest send-if!-test
  (testing "Send always"
    (with-cli
      (binding [rainboots.comms/*svr*
                (atom {:connected (atom [cli])})]
        (send-if! (constantly true) "Keep Flyin")
        (is (= (take!) "Keep Flyin")))))
  ;
  (testing "Send always with process-extras"
    (with-cli
      (binding [rainboots.comms/*svr*
                (atom {:connected (atom [cli])})]
        (send-if! {:process :extra} (constantly true) "Keep Flyin2")
        (is (= (take!) "Keep Flyin2")))))
  ;
  (testing "send-all!"
    (with-cli
      (binding [rainboots.comms/*svr*
                (atom {:connected (atom [cli])})]
        (send-all! "Keep Flyin")
        (is (= (take!) "Keep Flyin")))))
  ;
  (testing "send-all! with process-extras"
    (with-cli
      (binding [rainboots.comms/*svr*
                (atom {:connected (atom [cli])})]
        (send-all! {:process :extra} "Keep Flyin2")
        (is (= (take!) "Keep Flyin2")))))
  ;
  (testing "send-if before server init'd"
    (with-cli
      (send-all! "Don't crash"))))

(deftest send!-hook-test
  (defn send-hook
    [{:keys [prefix text] :as arg}]
    (assoc arg :text (str prefix text)))
  (hook! :process-send! send-hook)
  (testing "Provide process-extras"
    (with-cli
      (send! {:prefix "Mal "}
             cli "Reynolds")
      (is (= (take!) (str "Mal Reynolds")))))
  (unhook! :process-send! send-hook))

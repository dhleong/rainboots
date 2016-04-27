(ns rainboots.core-test
  (:require [clojure.test :refer :all]
            [manifold.stream :as s]
            [rainboots
             [core :refer :all]
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
    ;; TODO we should handle "ansi supported"
    ;;  vs "not supported" in rainboots.
    )
  (testing "Process colors (ansi disabled)"
    ;; TODO we should handle "ansi supported"
    ;;  vs "not supported" in rainboots.
    ))

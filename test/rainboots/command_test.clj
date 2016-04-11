(ns rainboots.command-test
  (:require [clojure.test :refer :all]
            [rainboots.command :refer :all]))

(deftest build-up-test
  (testing "Build-up"
    (is (= ["l" "lo" "loo" "look"]
           (build-up "look")))))

(deftest cmdset-test
  (defcmd bar [cli] "bar")
  (defcmdset test-set
    (defcmd foo [cli] "foo"))
  (testing "CmdSet is executable"
    (is (= "bar" (exec-command nil nil "bar")))
    (is (= "foo" (test-set nil nil "foo")))))

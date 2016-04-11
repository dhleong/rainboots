(ns rainboots.command-test
  (:require [clojure.test :refer :all]
            [rainboots.command :refer :all]))

(deftest build-up-test
  (testing "Build-up"
    (is (= ["l" "lo" "loo" "look"]
           (build-up "look")))))

(deftest cmdset-test
  (let [returned (atom nil)]
    (defcmd bar [cli] (reset! returned "bar"))
    (defcmdset test-set
      (defcmd foo [cli] (reset! returned "foo")))
    (testing "CmdSet is executable"
      (is (nil? @returned))
      (is (true? (exec-command nil nil "bar")))
      (is (= "bar" @returned))
      (is (true? (test-set nil nil "foo")))
      (is (= "foo" @returned)))))

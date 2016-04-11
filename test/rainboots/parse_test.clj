(ns rainboots.parse-test
  (:require [clojure.test :refer :all]
            [rainboots.parse :refer :all]))

(deftest extract-command-test
  (testing "Single command"
    (is (= "look" (extract-command "look")))
    (is (= "look" (extract-command " look")))
    (is (= "look" (extract-command " look "))))
  (testing "Command with arg"
    (is (= "look" (extract-command "look e")))))

(deftest apply-cmd-test
  (testing "Basic arg splitting"
    (is (= "there" (apply-cmd #(identity %2) nil "look there")))))

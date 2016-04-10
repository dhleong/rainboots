(ns rainboots.command-test
  (:require [clojure.test :refer :all]
            [rainboots.command :refer :all]))

(deftest build-up-test
  (testing "Build-up"
    (is (= ["l" "lo" "loo" "look"]
           (build-up "look")))))

(ns rainboots.hooks-test
  (:require [clojure.test :refer :all]
            [rainboots.hooks :refer :all]))

(deftest basic-hooks-test
  (testing "Install and use a single hook"
    (hook! :inc inc)
    (is (= 2 (trigger! :inc 1)))
    (unhook! :inc inc)
    ;; NB: should no longer be registered
    (is (= 1 (trigger! :inc 1))))
  (testing "Install and use two hooks"
    (let [more (partial + 4)] 
      (hook! :inc inc)
      (hook! :inc more)
      (is (= 6 (trigger! :inc 1)))
      (unhook! :inc more)
      ;; NB: only `inc` is registered
      (is (= 2 (trigger! :inc 1)))
      (unhook! :inc inc)
      ;; now, nothing
      (is (= 1 (trigger! :inc 1))))))


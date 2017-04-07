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

(deftest ordered-hooks-test
  (testing "Install and insert one hook each"
    (let [after (fn [s]
                  (str s " from me"))
          before (fn [s]
                   (str s " the sky"))]
      ; install twice, testing hook! doesn't duplicate
      (hook! :str after)
      (hook! :str after)
      (is (= 1 (count (installed-hooks :str))))
      ; install it after, but then insert it to pull
      ; to the front (just testing that insert-hook!
      ; doesn't duplicate)
      (hook! :str before)
      (is (= [before after] (installed-hooks :str)))
      ;
      (insert-hook! :str before)
      (is (= [after before] (installed-hooks :str)))
      ;
      (is (= 2 (count (installed-hooks :str))))
      (is (= "Can't take the sky from me"
             (trigger! :str "Can't take")))
      ;
      (unhook! :str after)
      (unhook! :str before)
      (is (empty? (installed-hooks :str))))))

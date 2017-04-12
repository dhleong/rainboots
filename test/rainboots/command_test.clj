(ns rainboots.command-test
  (:require [clojure.test :refer :all]
            [rainboots.command :refer :all]))

(deftest build-up-test
  (testing "Build-up"
    (is (= ["l" "lo" "loo" "look"]
           (build-up "look")))))

(deftest cmdset-test
  (let [returned (atom nil)]
    (binding [*commands* (atom {})]
      (defcmd bar [cli] (reset! returned "bar"))
      (defcmdset test-set
        (defcmd foo [cli] (reset! returned "foo")))
      (testing "CmdSet is executable"
        (is (nil? @returned))
        (is (true? (exec-command nil nil "bar")))
        (is (= "bar" @returned))
        (is (true? (test-set nil nil "foo")))
        (is (= "foo" @returned))))))

(deftest cmd-meta-test
  (testing "Single arity"
    (defn cmd-meta-test-cmd-fn
      "Test function"
      [cli ^:item item ^:eq equip text]
      nil)
    (let [m (cmd-meta (var cmd-meta-test-cmd-fn))
          args (first (:arg-lists m))]
      (is (= "cmd-meta-test-cmd-fn" (name (:name m))))
      (is (= "Test function" (:doc m)))
      (is (= :item (first args)))
      (is (= :eq (second args)))
      (is (= nil (last args)))))
  (testing "Multi-arity"
    (defn cmd-meta-test-2arity
      "2Arity Test"
      ([cli ^:item item]
       nil)
      ([cli]
       nil))
    (let [m (cmd-meta (var cmd-meta-test-2arity))
          lists (:arg-lists m)]
      (is (= [:item] (first lists)))
      (is (= [] (second lists))))))

(deftest parameterized-argtype-test
  (testing "Parameterized argtype"
    (defn cmd-meta-test-cmd-fn
      "Test function"
      [cli ^{:item :in-storage} item ^{:eq :held} equip any]
      nil)
    (let [m (cmd-meta (var cmd-meta-test-cmd-fn))
          args (first (:arg-lists m))]
      (is (= "cmd-meta-test-cmd-fn" (name (:name m))))
      (is (= "Test function" (:doc m)))
      (is (= [:item :in-storage] (first args)))
      (is (= [:eq :held] (second args)))
      (is (= nil (last args))))))

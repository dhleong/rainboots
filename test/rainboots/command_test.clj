(ns rainboots.command-test
  (:require [clojure.test :refer :all]
            [rainboots.command :refer :all]))

(deftest build-up-test
  (testing "Build-up"
    (is (= ["l" "lo" "loo" "look"]
           (build-up "look")))))

(deftest abbrev-test
  (testing "default uses build-up"
    (binding [*commands* (atom {})]
      (defcmd every [cli] nil)

      (is (= ["e" "ev" "eve" "ever" "every"]
             (keys @*commands*)))))

  (testing ":no-abbrev"
    (binding [*commands* (atom {})]
      (defcmd ^:no-abbrev onlyfull [cli] nil)

      (is (= ["onlyfull"] (keys @*commands*)))))

  (testing ":abbrev"
    (binding [*commands* (atom {})]
      (defcmd ^{:abbrev ["s" "specific"]} specific [cli] nil)

      (is (= ["s" "specific"] (keys @*commands*)))))
  )

(deftest cmdset-test
  (let [returned (atom nil)]
    (binding [*commands* (atom {})]
      (defcmd bar [cli] (reset! returned "bar"))
      (defcmdset test-set
        (defcmd foo [cli] (reset! returned "foo")))
      (testing "CmdSet is executable"
        (is (nil? @returned))
        (is (true? (exec-command nil (constantly true) nil "bar")))
        (is (= "bar" @returned))
        (is (true? (test-set nil (constantly true) nil "foo")))
        (is (= "foo" @returned))))))

(deftest cmd-meta-test
  (testing "Single arity"
    (defn cmd-meta-test-cmd-fn
      "Test function"
      [cli ^:item item ^:eq equip text]
      nil)
    (let [m (cmd-meta (var cmd-meta-test-cmd-fn) {})
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
    (let [m (cmd-meta (var cmd-meta-test-2arity) {})
          lists (:arg-lists m)]
      (is (= [:item] (first lists)))
      (is (= [] (second lists))))))

(deftest can-execute-test
  (binding [*commands* (atom {})]
    (defcmd
      ^{:if-conscious? false
        :time :machine}
      user-meta-cmd
      "Hey Doc"
      [cli]
      nil)
    (testing "Include user-provided meta info in cmd meta"
      (let [m (meta (get @*commands* "user-meta-cmd"))]
        ; verify we still have the basic stuff:
        (is (identity (:arg-lists m))) ; we verify the mechanics of this elsewhere
        (is (= 5 (:column m)))
        (is (= "Hey Doc" (:doc m)))
        (is (= "rainboots/command_test.clj" (:file m)))
        (is (number? (:line m)))
        (is (= (symbol "user-meta-cmd") (:name m)))
        (is (= (find-ns 'rainboots.command-test) (:ns m)))
        ;
        ; now, do we have our user stuff?
        (is (false? (:if-conscious? m)))
        (is (= :machine (:time m)))))))

(deftest parameterized-argtype-test
  (testing "Parameterized argtype"
    (defn cmd-meta-test-cmd-fn
      "Test function"
      [cli ^{:item :in-storage} item ^{:eq :held} equip any]
      nil)
    (let [m (cmd-meta (var cmd-meta-test-cmd-fn) {})
          args (first (:arg-lists m))]
      (is (= "cmd-meta-test-cmd-fn" (name (:name m))))
      (is (= "Test function" (:doc m)))
      (is (= [:item :in-storage] (first args)))
      (is (= [:eq :held] (second args)))
      (is (= nil (last args))))))

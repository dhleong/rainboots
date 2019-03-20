(ns rainboots.hooks-test
  (:require [clojure.test :refer :all]
            [rainboots.hooks :refer :all]))

(defn defined-fn [])

(deftest clean-fn-name-test
  (testing "name in let"
    (let [f (fn my-name [])]
      (is (= "rainboots.hooks_test/my_name"
             (clean-fn-name f)))))

  (testing "unnamed in let"
    (let [f (fn [])]
      (is (= "rainboots.hooks_test/f"
             (clean-fn-name f)))))

  (testing "name from defn"
    (is (= "rainboots.hooks_test/defined_fn"
           (clean-fn-name defined-fn))))

  (testing "name from clojure.core"
    (is (= "clojure.core/inc"
           (clean-fn-name inc)))))

(deftest basic-hooks-test
  (testing "Install and use a single hook"
    (with-hooks-context
      (hook! :inc inc)
      (is (= 2 (trigger! :inc 1)))

      (unhook! :inc inc)
      ; NB: should no longer be registered
      (is (= 1 (trigger! :inc 1)))))

  (testing "Install and use a nil-returning hook"
    (with-hooks-context
      (let [f (constantly nil)]
        (hook! :->nil f)
        (is (= nil (trigger! :->nil 1)))

        (unhook! :->nil f)
        ; NB: should no longer be registered
        (is (= 1 (trigger! :->nil 1))))))

  (testing "Install and use two hooks"
    (with-hooks-context
      (let [more (partial + 4)]
        (hook! :inc inc)
        (hook! :inc more)
        (is (= 6 (trigger! :inc 1)))

        (unhook! :inc more)
        ; NB: only `inc` is registered
        (is (= 2 (trigger! :inc 1)))

        (unhook! :inc inc)
        ; now, nothing
        (is (= 1 (trigger! :inc 1)))))))

(deftest hook-dedup
  (testing "Dedup hooks by name"
    (with-hooks-context
      (hook! :math (fn my-math [arg] (inc arg)))
      (hook! :math (fn my-math [arg] (dec arg)))
      (is (= 1 (trigger! :math 2)))
      (is (= 1 (count (installed-hooks :math))))
      (is (= ["my-math"]
             (map first (installed-hook-pairs :math))))

      (unhook! :math (fn my-math [arg] (inc arg)))
      (is (empty? (installed-hooks :math)))))

  (testing "Dedup hooks by named anonymous"
    (with-hooks-context
      (let [math1 (fn my-math [arg] (inc arg))
            math2 (fn my-math [arg] (dec arg))]
        (hook! :math math1)
        (hook! :math math2)
        (is (= 1 (trigger! :math 2)))
        (is (= 1 (count (installed-hooks :math))))
        (is (= ["rainboots.hooks_test/my_math"]
               (map first (installed-hook-pairs :math))))

        (unhook! :math math1)
        (is (empty? (installed-hooks :math)))))))

(deftest ordered-hooks-test
  (testing "Install and insert one hook each"
    (with-hooks-context
      (let [after (fn [s]
                    (str s " from me"))
            before (fn [s]
                     (str s " the sky"))]

        ; install twice, testing hook! doesn't duplicate
        (hook! :str after)
        (hook! :str after)
        (is (= 1 (count (installed-hooks :str))))

        ; install it after, but then (later) insert it to
        ; pull to the front (just testing that
        ; insert-hook!  doesn't duplicate)
        (hook! :str {:priority -1} before)
        (is (= [after before] (installed-hooks :str)))
        (is (= "Can't take from me the sky"
               (trigger! :str "Can't take")))

        (insert-hook! :str before)
        (is (= [before after] (installed-hooks :str)))

        (is (= 2 (count (installed-hooks :str))))
        (is (= "Can't take the sky from me"
               (trigger! :str "Can't take")))

        (unhook! :str after)
        (unhook! :str before)
        (is (empty? (installed-hooks :str)))))))

(deftest when-no-result-test
  (testing "There can be only one"
    (with-hooks-context
      (let [f (fn [s] s)]
        (hook! :f #{:when-no-result} f)

        (is (thrown? IllegalArgumentException
                     (hook! :f #{:when-no-result}
                            (fn [s] s)))))))

  (testing "Allow replacing with same-named fn (for repl)"
    (with-hooks-context
      (let [f (fn on-f [s] s)
            f2 (fn on-f [s] (inc s))]
        (hook! :f #{:when-no-result} f)

        ; no error:
        (hook! :f #{:when-no-result} f2)

        (is (= 2 (trigger! :f 1)))

        (unhook! :f f))))

  (testing "Don't trigger :when-no-result when another hook has a wrapped result"
    (with-hooks-context
      (let [other (fn [v]
                    (reduced (dec v)))]
        (hook! :f #{:when-no-result} inc)
        (hook! :f other)
        (is (= 1 (trigger! :f 2)))

        (unhook! :f inc)
        (unhook! :f other))))

  (testing "Don't trigger :when-no-result with nil result"
    (with-hooks-context
      (let [always-nil (constantly (reduced nil))]
        (hook! :f #{:when-no-result} inc)
        (hook! :f always-nil)
        (is (nil? (trigger! :f 2)))

        (unhook! :f inc)
        (unhook! :f always-nil))))


  (testing "Trigger :when-no-result with no other fns"
    (with-hooks-context
      (hook! :f #{:when-no-result} inc)
      (is (= 3 (trigger! :f 2)))

      (unhook! :f inc)))

  (testing "Trigger :when-no-result when no result"
    (with-hooks-context
      (hook! :f #{:when-no-result} inc)
      (hook! :f dec)
      (is (= 2 (trigger! :f 2)))

      (unhook! :f inc)
      (unhook! :f dec))))

(deftest when-only-test
  (testing "There can be only one"
    (with-hooks-context
      (let [f (fn [s] s)]
        (hook! :f #{:when-only} f)

        (is (thrown? IllegalArgumentException
                     (hook! :f #{:when-only}
                            (fn [s] s)))))))

  (testing "Don't trigger :when-only when another hook is installed"
    (with-hooks-context
      (hook! :f #{:when-only} inc)
      (hook! :f dec)
      (is (= 1 (trigger! :f 2)))

      (unhook! :f inc)
      (unhook! :f dec)))

  (testing "Don't trigger :when-only when another hook explicitly returns nil"
    (with-hooks-context
      (let [always-nil (constantly nil)]
        (hook! :f #{:when-only} inc)
        (hook! :f always-nil)
        (is (nil? (trigger! :f 2)))

        (unhook! :f inc)
        (unhook! :f always-nil))))

  (testing "Trigger :when-only when appropriate"
    (with-hooks-context
      (hook! :f #{:when-only} inc)
      (is (= 3 (trigger! :f 2)))

      (unhook! :f inc))))

(ns rainboots.parse-test
  (:require [clojure.test :refer :all]
            [rainboots
             [command :refer [*commands* defcmdset defcmd exec-command]]
             [parse :refer :all]]))

(deftest extract-command-test
  (testing "Single command"
    (is (= ["look" nil] (extract-command "look")))
    (is (= ["look" nil] (extract-command " look")))
    (is (= ["look" nil] (extract-command " look "))))
  (testing "Command with arg"
    (is (= ["look" "e"] (extract-command "look e")))))

(deftest expand-args-test
  (testing "Expand basic types"
    (is (= ["one"] (expand-args :cli "one" [nil])))
    (is (= ["one" "two"] 
           (expand-args :cli "one  two " [nil nil]))))
  (testing "Mixed types"
    (binding [*arg-types* (atom {nil default-argtype-handler})]
      (defargtype :read2
        "Reads two words as one arg"
        [cli input]
        (let [m (re-seq #"(\S+\s+\S+)(\S*.*)$" input)]
          (-> m first rest)))
      (is (= ["one  two"] 
             (expand-args :cli "one  two " [:read2])))
      (is (= ["one  two" "three"] 
             (expand-args :cli "one  two three" [:read2 nil])))
      (is (= ["one" "two three"] 
             (expand-args :cli "one  two three" [nil :read2]))))))

(deftest argtype-test
  (testing "Default handler"
    (is (= ["first" "second  third"]
           (default-argtype-handler 
             nil "first   second  third")))
    (is (= ["second" "third"]
           (default-argtype-handler 
             nil "second  third")))
    (is (= ["third" nil]
           (default-argtype-handler 
             nil "third")))
    ;; TODO probably, support quoted strings
    )
  ;
  (binding [*arg-types* (atom {})
            *commands* (atom {})] 
    (testing "Declare"
      (defargtype :item
        "An item usage"
        [cli input]
        [(str "ITEM:" 
              (first 
                (clojure.string/split input #" +")))
         nil]) ;; lazy
      (is (fn? (:item @*arg-types*))))
    (testing "Use (single arity)"
      (let [result (atom nil)]
        (defcmd argtype-test-cmd
          [cli ^:item item]
          (reset! result item))
        (exec-command :404 :cli "argtype foo")
        (is (= "ITEM:foo" @result))))
    ;
    (testing "Use (2-arity arity)"
      (let [result (atom nil)]
        (defcmd arity2-argtype-test-cmd
          ([cli ^:item item]
           (reset! result item))
          ([cli]
           (reset! result nil)))
        (exec-command :404 :cli "arity foo")
        (is (= "ITEM:foo" @result))
        ;; TODO test:
        ;; (exec-command :404 :cli "arity")
        ;; (is (= nil @result))
        ))))


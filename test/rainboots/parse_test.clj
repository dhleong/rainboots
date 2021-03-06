(ns rainboots.parse-test
  (:require [clojure.test :refer :all]
            [manifold.stream :as s]
            [rainboots
             [command :refer [*commands* defcmdset defcmd exec-command]]
             [core :refer [send!]]
             [parse :refer :all]]))

(deftest extract-command-test
  (testing "Empty input"
    (is (= [nil nil] (extract-command "")))
    (is (= [nil nil] (extract-command "  "))))
  (testing "Single command"
    (is (= ["look" nil] (extract-command "look")))
    (is (= ["look" nil] (extract-command " look")))
    (is (= ["look" nil] (extract-command " look "))))
  (testing "Command with arg"
    (is (= ["look" "e"] (extract-command "look e")))))

(deftest extract-word-test
  (testing "Nothing at all"
    (is (nil? (extract-word ""))))
  (testing "Only world"
    (is (= ["mreynolds" "mreynolds"] (extract-word "mreynolds")))
    (is (= ["mreynolds   " "mreynolds"] (extract-word "mreynolds   "))))
  (testing "Multi word"
    (is (= ["kaylee  " "kaylee"] (extract-word "kaylee  frye"))))
  (testing "Quoted phrase"
    (is (= ["\"keep flying\"", "keep flying"]
           (extract-word "\"keep flying\"")))
    (is (= ["\"keep flying\"  ", "keep flying"]
           (extract-word "\"keep flying\"  ")))))

(deftest expand-args-test
  (testing "Expand basic types"
    (is (= ["one"] (expand-args :cli "one" [nil])))
    (is (= ["one" "two"]
           (expand-args :cli "one  two " [nil nil])))
    (is (= ["one  two" "three"]
           (expand-args :cli "\"one  two\"  three " [nil nil]))))
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
             (expand-args :cli "one  two three" [nil :read2])))))
  (testing "Not enough args is nil"
    (is (nil? (expand-args :cli "one" [nil nil]))))
  (testing "Too many args is nil"
    (is (nil? (expand-args :cli "one two" [nil]))))
  (testing "Parameterized types"
    (binding [*arg-types* (atom {nil default-argtype-handler})]
      (defargtype :read2
        "Reads two words as one arg"
        [cli input & [param]]
        (let [m (re-seq #"(\S+\s+\S+)(\S*.*)$" input)]
          (let [[v r] (-> m first rest)]
            [(str param v) r])))
      (is (= ["one  two"]
             (expand-args :cli "one  two " [:read2])))
      (is (= ["foo:one  two" "three"]
             (expand-args :cli "one  two three"
                          [[:read2 "foo:"] nil]))))))

(deftest argtype-meta-test
  (testing "Nilable input"
    (defargtype :with-nilable-input
      "Doc"
      [cli ^:nilable input]
      ["got it" input])
    (let [m (argtype-meta (var argtype$with-nilable-input))]
      (is (true? (:nilable? m))))))

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
    ; quoted strings
    (is (= ["fourth   fifth" nil]
           (default-argtype-handler
             nil " \"fourth   fifth\"  "))))
  ;
  (binding [*arg-types* (atom {})
            *commands* (atom {})]
    (testing "Declare"
      (defargtype :item
        "An item usage"
        [cli input]
        (let [[v remaining] (default-argtype-handler
                              :cli
                              input)]
          [(str "ITEM:" v)
           remaining])) ;; lazy
      (is (fn? (:item @*arg-types*))))
    (testing "Use (single arity)"
      (let [result (atom nil)]
        (defcmd argtype-test-cmd
          [cli ^:item item]
          (reset! result item))
        (exec-command :404 (constantly true) :cli "argtype foo")
        (is (= "ITEM:foo" @result))))
    ;
    (testing "Use (3-arity arity)"
      (let [result (atom nil)]
        (defcmd arity2-argtype-test-cmd
          ([cli ^:item item1 ^:item item2]
           (reset! result [item1 item2]))
          ([cli ^:item item]
           (reset! result item))
          ([cli]
           (reset! result "nothing!")))
        ;; 3-arity version...
        (exec-command :404 (constantly true) :cli "arity first second")
        (is (= ["ITEM:first" "ITEM:second"] @result))
        ;; 2-arity version...
        (exec-command :404 (constantly true) :cli "arity foo")
        (is (= "ITEM:foo" @result))
        ;; 1-arity version
        (exec-command :404 (constantly true) :cli "arity")
        (is (= "nothing!" @result))))
    ;
    (testing "Implied argtype"
      (defargtype :implied
        "Doesn't use input at all"
        [cli ^:nilable input]
        [(name cli) input])
      (let [result (atom nil)
            on-404 (fn [& _]
                     (reset! result :404))]
        (defcmd implied-argtype-test-cmd
          [cli ^:implied implied-name]
          (reset! result implied-name))
        ; first, pass an extra arg and make sure it 404s
        (exec-command on-404 (constantly true) :cli "implied unused")
        (is (= :404 @result))
        ; now, reset and...
        (reset! result nil)
        ; go!
        (exec-command on-404 (constantly true) :cli "implied")
        (is (= "cli" @result))))))

(deftest arg-exception-test
  (binding [*arg-types* (atom {})
            *commands* (atom {})]
    (defargtype :error
      "Test error results"
      [cli input]
      (let [[v remaining] (default-argtype-handler
                            :cli
                            input)]
        [(Exception. (str "No such " v))
         remaining]))
    (testing "Send exception message"
      (defcmd except-test-cmd
        [cli ^:error error]
        (send! cli "Success!?"))
      (let [stream (s/stream)
            cli (atom {:stream stream})]
        (exec-command :404 (constantly true) cli "except luck")
        (is (= "No such luck"
               @(s/try-take! stream 10)))))))

(deftest parameterized-argtype-test
  (binding [*arg-types* (atom {})
            *commands* (atom {})]
    (defargtype :item
      "An item usage"
      [cli input param]
      (let [[v remaining] (default-argtype-handler
                            :cli
                            input)]
        [(str "ITEM:" v ":" (name param))
         remaining]))
    (testing "Use (single arity)"
      (let [result (atom nil)]
        (defcmd param-argtype-test-cmd
          [cli ^{:item :in-storage} item]
          (reset! result item))
        (exec-command :404 (constantly true) :cli "param foo")
        (is (= "ITEM:foo:in-storage" @result))))))

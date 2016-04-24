(ns rainboots.core-test
  (:require [clojure.test :refer :all]
            [rainboots
             [core :refer :all]
             [proto :refer :all]]
            ))

(deftest cmds-stack-test
  (testing "Push pop"
    (let [cli (atom {:input-stack (atom [])})]
      (push-cmds! cli :a)
      (push-cmds! cli :b)
      (is (= [:a :b]
             @(:input-stack @cli)))
      (pop-cmds! cli)
      (is (= [:a]
             @(:input-stack @cli)))
      (push-cmds! cli :c)
      (is (= [:a :c]
             @(:input-stack @cli))))))
      

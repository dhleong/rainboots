(ns rainboots.hiccup-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [rainboots.hiccup :refer :all]))

(def cli (atom {:ch {:name "Mal Reynolds"}}))

(deftest hiccup-test
  (testing "String pass-through"
    (is (= "mreynolds"
           (process cli "mreynolds"))))

  (testing "Fn inline"
    (let [capify (fn [_ _ children]
                   (str/join (map str/upper-case children)))]
      (is (= "MREYNOLDS"
             (process cli [capify "mreynolds"])))))

  (testing "Hiccup-returning inline fn"
    (let [capify (fn [_ _ children]
                   [:string (map str/upper-case children)])]
      (is (= "MREYNOLDS"
             (process cli [capify "mreynolds"])))))

  (testing "Sequence-returning inline fn"
    (let [capify (fn [_ _ children]
                   (map str/upper-case children))]
      (is (= "MREYNOLDS"
             (process cli [capify "mreynolds"])))))

  (testing "Simple color"
    (is (= "{Ymreynolds{n"
           (process cli [:Y "mreynolds"]))))

  (testing "Nested color"
    (is (= "{YCaptain {rMal{Y Reynolds{n"
           (process cli [:Y "Captain " [:r "Mal"] " Reynolds"])))))


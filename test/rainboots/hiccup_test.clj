(ns rainboots.hiccup-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [rainboots.hiccup :refer :all]))

(def cli (atom {:ch {:name "Mal Reynolds"
                     :knows #{"Zoe" "Kaylee"}}}))

(deftest hiccup-test
  (testing "String pass-through"
    (is (= "mreynolds"
           (process cli "mreynolds"))))

  (testing "Fn inline"
    (let [capify (fn [_ s]
                   (str/upper-case s))]
      (is (= "MREYNOLDS"
             (process cli [capify "mreynolds"])))))

  (testing "Inline fn with attrs"
    (let [caseify (fn [_ {:keys [mode]} s]
                    (case mode
                      :upper (str/upper-case s)
                      :lower (str/lower-case s)))]
      (is (= "MREYNOLDS"
             (process cli [caseify {:mode :upper} "mreynolds"])))
      (is (= "mreynolds"
             (process cli [caseify {:mode :lower} "MREYNOLDS"])))))

  (testing "Hiccup-returning inline fn"
    (let [capify (fn [_ text]
                   [:Y (str/upper-case text)])]
      (is (= "{YMREYNOLDS{n"
             (process cli [capify "mreynolds"])))))

  (testing "Sequence-returning inline fn"
    (let [capify (fn [_ & children]
                   (map str/upper-case children))]
      (is (= "MREYNOLDS"
             (process cli [capify "mreynolds"])))))

  (testing "Simple color"
    (is (= "{Ymreynolds{n"
           (process cli [:Y "mreynolds"]))))

  (testing "Nested color"
    (is (= "{YCaptain {rMal{Y Reynolds{n"
           (process cli [:Y "Captain " [:r "Mal"] " Reynolds"])))))

(deftest handler-test
  (binding [*handlers* (atom {})]
    (defhandler :upper
      "Accepts a single string and converts it to upper-case"
      [cli child]
      (str/upper-case child))

    (testing "Use a declared hiccup handler"
      (is (= "MREYNOLDS"
             (process cli [:upper "mreynolds"]))))

    (testing "Use the provided cli obj"
      (defhandler :name
        "The name of an entity, accounting for knowledge of it"
        [cli entity]
        (if ((-> @cli :ch :knows) (:name @entity))
          (:name @entity)
          "Somebody"))

      (let [zoe (atom {:name "Zoe"})
            badger (atom {:name "Badger"})]

        (is (= "Zoe"
               (process cli [:name zoe])))
        (is (= "Somebody"
               (process cli [:name badger])))))))

(ns rainboots.color-test
  (:require [clojure.test :refer :all]
            [rainboots.color :refer :all]))

(deftest process-colors-test
  (testing "Idempotent"
    (is (= "Hi" (process-colors "Hi"))))
  (testing "Dark red"
    (is (= (str ansi-esc "0;31mHi")
           (process-colors "{rHi"))))
  (testing "Escape { with {{"
    (is (= "{Woo}"
           (process-colors "{{Woo}")))))

(deftest strip-colors-test
  (testing "Idempotent"
    (is (= "Hi" (strip-colors "Hi"))))
  (testing "Escape { with {{"
    (is (= "{Woo}"
           (strip-colors "{{Woo}"))))
  (testing "Remove colors"
    (is (= "WhiteYellow"
           (strip-colors "{WWhite{YYellow")))
    (is (= "{fNot{AColor"
           (strip-colors "{fNot{AColor")))))

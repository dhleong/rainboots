(ns rainboots.proto-test
  (:require [clojure.test :refer :all]
            [gloss.data.bytes.core :refer [create-buf-seq]]
            [rainboots.proto :refer :all]
            [manifold.stream :as s])
  (:import [java.nio ByteBuffer]))

(defn to-seq
  [bytes-vec]
  (create-buf-seq 
    (ByteBuffer/wrap
      (byte-array bytes-vec))))

(defn unsign
  "Take a byte array and return a sequence of 
  unsigned ints, for easy comparison in tests"
  [byte-ar]
  (map
    #(bit-and % 0xFF)
    (seq byte-ar)))

(deftest index-of-test
  (testing "index-of-buffer"
    (is (= 0
           (index-of-buffer
             (first (to-seq [42]))
             42)))
    (is (= 0
           (index-of-buffer
             (first (to-seq [tn-iac]))
             tn-iac))))
  (testing "Empty sequence"
    (is (nil? (index-of 
                (to-seq [])
                42))))
  (testing "Single Sequence"
    (is (= 0
           (index-of 
             (to-seq [42])
             42)))))

(deftest telnet-test
  (testing "Incomplete sequence ONLY"
    (is (= :-incomplete
           (read-telnet-code
             (to-seq [tn-iac]))))
    (is (= :-incomplete
           (read-telnet-code
             (to-seq [tn-iac tn-will])))))
  (testing "Complete sequence ONLY"
    (is (= {:telnet :nop 
            :after nil
            :before nil}
           (read-telnet-code
             (to-seq [tn-iac tn-nop]))))
    (is (= {:telnet :will
            :opt 42
            :after nil
            :before nil}
           (read-telnet-code
             (to-seq [tn-iac tn-will 42]))))
    (is (= {:telnet :will
            :opt :term-type
            :after nil
            :before nil}
           (read-telnet-code
             (to-seq [tn-iac tn-will tn-op-ttype])))))
  (testing "IAC+IAC is nil"
    (is (nil? (read-telnet-code
                (to-seq [tn-iac tn-iac])))))
  (testing "Nested, incomplete sequence"
    (is (= :-incomplete
           (read-telnet-code
             (to-seq [(int \h) (int \i) tn-iac])))))
  (testing "Nested (at end) complete sequence"
    (is (= {:telnet :nop 
            :before (to-seq [(int \h) (int \i)])
            :after nil}
           (read-telnet-code
             (to-seq [(int \h) (int \i) tn-iac tn-nop])))))
  (testing "Nested, complete sequence"
    (is (= {:telnet :nop 
            :before (to-seq [(int \h) (int \e)])
            :after (to-seq [(int \l) (int \l) (int \o)])}
           (read-telnet-code
             (to-seq
               [(int \h) (int \e) 
                tn-iac tn-nop
                (int \l) (int \l) (int \o)])))))
  ;
  (testing "Subnegotiation sequence"
    (is (= {:telnet :term-type
            :opt "serenitty" ; puns :)
            :before nil
            :after nil}
           (read-telnet-code
             (to-seq [tn-iac tn-sb tn-op-ttype
                      (int \s) (int \e) (int \r)
                      (int \e) (int \n) (int \i)
                      (int \t) (int \t) (int \y)
                      tn-iac tn-se]))))))

(deftest split-line-test
  (testing "No newline gets nil"
    (is (nil? (split-line 
                (to-seq [(int \h) (int \i)])))))
  (testing "Newline at end"
    (is (= ["hi" nil]
           (split-line 
             (to-seq [(int \h) (int \i) (int \newline)])))))
  (testing "Trim whitespace"
    (is (= ["hi" nil]
           (split-line 
             (to-seq
               [(int \space) (int \h) (int \i) 
                (int \return) (int \newline)])))))
  (testing "Newline in middle"
    (is (= ["hi" (to-seq [(int \y) (int \o)])]
           (split-line 
             (to-seq
               [(int \h) (int \i) (int \newline)
                (int \y) (int \o)])))))
  (testing "Extra newline"
    (is (= ["hi" (to-seq [(int \y) (int \o) (int \newline)])]
           (split-line 
             (to-seq
               [(int \h) (int \i) (int \newline)
                (int \y) (int \o) (int \newline)]))))))

(deftest encode-packet-tests
  (testing "Byte array pass-through"
    (is (= [1 2 3]
           (unsign
             (encode-packet
               (byte-array
                 [1 2 3]))))))
  (testing "Simple map, no opt"
    (is (= [tn-iac tn-nop]
           (unsign
             (encode-packet
              {:telnet tn-nop})))))
  (testing "Simple map with opt"
    (is (= [tn-iac tn-do tn-op-ttype]
           (unsign
             (encode-packet
              {:telnet tn-do
               :opt tn-op-ttype})))))
  (testing "Simple map with subnegotiation"
    (is (= [tn-iac tn-sb 
            tn-op-ttype 
            (int \h) (int \i)
            tn-iac tn-se]
           (unsign
             (encode-packet
              {:telnet tn-op-ttype
               :opt "hi"}))))
    (is (= [tn-iac tn-sb 
            tn-op-ttype 
            tn-send
            tn-iac tn-se]
           (unsign
             (encode-packet
              {:telnet tn-op-ttype
               :opt [:send]}))))))

;;
;; Full protocol tests
;;

(defmacro with-stream
  [& body]
  `(let [~'*input* (s/stream 10)
         ~'*output* (s/stream 10)
         ~'*s* (wrap-stream 
                 ~'*input*
                 (fn [s# pkt#]
                   @(s/put! ~'*output* pkt#)))]
     ~@body))

(defmacro put!
  [& the-bytes]
  `(deref 
     (s/put! ~'*input*
             (byte-array [~@the-bytes]))))

(defmacro take!
  []
  `(deref
     (s/try-take! ~'*output* :failure
                  25 :timeout)))

(deftest protocol-decode-test
  (testing "Telnet codes"
    (with-stream
      (put! tn-iac tn-nop)
      (is (= {:telnet :nop} (take!))))
    (with-stream
      (put! tn-iac tn-will tn-op-echo)
      (is (= {:telnet :will :opt :echo} 
             (take!))))))

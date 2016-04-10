(ns ^{:author "Daniel Leong"
      :doc "Low-level protocol details"}
  rainboots.proto
  (:require [gloss
             [io :as io]]
            [gloss.core.protocols :refer [Reader read-bytes]]
            [gloss.data.bytes
             [core :refer [BufferSequence create-buf-seq concat-bytes
                           take-bytes drop-bytes byte-count]]
             [delimited :refer [buf->string]]]
            [manifold.stream :as s])
  (:import [java.nio ByteBuffer]))

(def tn-iac 0xff)
(def tn-se 240)
(def tn-nop 241)
(def tn-data-mark 242)
(def tn-break 243)
(def tn-ip 244)

(def tn-will 251)
(def tn-wont 252)
(def tn-do 253)
(def tn-dont 254)

;; option codes for will/wont/etc
(def tn-op-echo 1)

(def tn-codes
  {tn-se :se
   tn-nop :nop
   tn-data-mark :data-mark
   tn-break :break
   tn-ip :interrupt-proc
   ;
   tn-will :will
   tn-wont :wont
   tn-do :do
   tn-dont :dont})

(defn- unsigned-get
  [^ByteBuffer buffer idx]
  (let [raw (.get buffer idx)]
    (int (bit-and raw 0xFF))))

(defn index-of-buffer
  [^ByteBuffer buffer expect]
  (let [len (.remaining buffer)]
    (loop [i (.position buffer)]
      (cond
        (>= i len) nil
        (= expect (unsigned-get buffer i)) (- i (.position buffer))
        :else (recur (inc i))))))

(defn index-of
  [buf-seq expect]
  (loop [i 0
         b (seq buf-seq)]
    (when-not (empty? b) 
      (if-let [idx (index-of-buffer (first b) expect)]
        (+ i idx)
        (recur (+ i (.remaining (first b)))
               (next b))))))

(defn nth-byte
  [buf-seq n]
  {:pre [(< n (byte-count buf-seq))]}
  (when-let [buf-seq (seq buf-seq)]
    (loop [n n
           buffers buf-seq]
      (when (>= n 0)
        (let [this (first buffers)
              remaining (.remaining this)]
          (if (< n remaining)
            (unsigned-get this n)
            (recur (- n remaining) (next buffers))))))))

(defn- has-opt?
  [tn-code]
  (case tn-code
    ;; these are will/wont/do/dont:
    ;; this gives us constant lookup
    (251 252 253 254) true
    false))

(defn read-telnet-code
  "Given a sequence of bytes, attempt to find a telnet
  escape command sequence. If we found what might be an
  incomplete sequence, returns :-incomplete. If we found
  a complete sequence, returns a map with the decoded
  telnet sequence in :telnet, and the byte sequence before
  and after it in :before and :after, respectively"
  [buf-seq]
  (when-let [iac-idx (index-of buf-seq tn-iac)]
    (let [code-idx (inc iac-idx)
          opt-idx (inc code-idx)
          buf-count (byte-count buf-seq)]
      (if (<= buf-count code-idx)
        :-incomplete
        (let [code (nth-byte buf-seq code-idx)
              has-opt? (has-opt? code)]
          (when-not (= tn-iac code)
            (cond
              (and has-opt?
                   (<= buf-count opt-idx))
              :-incomplete
              ;; has an option, and not incomplete
              has-opt?
              {:telnet (get tn-codes code code)
               :opt (nth-byte buf-seq opt-idx)
               :before (take-bytes buf-seq iac-idx)
               :after (drop-bytes buf-seq (inc opt-idx))}
              ;; simple
              :else
              {:telnet (get tn-codes code code)
               :before (take-bytes buf-seq iac-idx)
               :after (drop-bytes buf-seq opt-idx)})))))))

(defn split-line
  "Given a sequence of non-telnet-escape bytes, if there is a 
  \n character, return a tuple of [string, remaining], where
  `string` is the string value of the input, and `remaining`
  is the remaining sequence of bytes."
  [buf-seq]
  (when-let [new-line (index-of buf-seq (int \newline))]
    [(.trim (buf->string (take-bytes buf-seq new-line)))
     (drop-bytes buf-seq (inc new-line))]))

(defn- concat-buf-seqs
  [b1 b2]
  (cond
    (nil? b1) nil ;; b2 must also be nil
    (nil? b2) (create-buf-seq b1) ;; easy case
    :else (concat-bytes b1 b2)))

(def telnet-protocol
  (reify Reader
    (read-bytes [this buf-seq]
      (let [telnet-code (read-telnet-code buf-seq)]
        (cond
          ; there was a telnet code, but it's incomplete
          (= :-incomplete telnet-code) 
          [false this buf-seq]
          ; there was a full telnet code!
          (map? telnet-code) 
          [true 
           (dissoc telnet-code :before :after) 
           (concat-buf-seqs
             (:before telnet-code)
             (:after telnet-code))]
          ;; just string. see if there's a newline
          :else
          (if-let [[result remain] (split-line buf-seq)]
            [true result remain]
            [false this buf-seq]))))))

(defn wrap-stream
  [s on-packet]
  (let [out (s/stream)]
    (s/connect
      (s/map identity out) ; TODO encode telnet commands 
      s)
    (let [spliced 
          (s/splice 
            out
            (io/decode-stream s telnet-protocol))]
      (s/consume
        #(on-packet s %)
        spliced)
      spliced)))

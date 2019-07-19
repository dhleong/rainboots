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

;; shouldn't need more than this for subnegotiation
(def max-sub-bytes 1024)

(def tn-iac 0xff)
(def tn-sb 250) ; subnegotiation begin
(def tn-se 240) ; subnegotiation end
(def tn-nop 241)
(def tn-data-mark 242)
(def tn-break 243)
(def tn-ip 244)

(def tn-will 251)
(def tn-wont 252)
(def tn-do 253)
(def tn-dont 254)

(def tn-send 1)

;; option codes for will/wont/etc
(def tn-op-echo 1)
(def tn-op-ttype 24) ; terminal type
(def tn-op-naws 31) ; window size

(def tn-codes
  {tn-sb :sb
   tn-se :se
   tn-nop :nop
   tn-data-mark :data-mark
   tn-break :break
   tn-ip :interrupt-proc
   ;
   tn-will :will
   tn-wont :wont
   tn-do :do
   tn-dont :dont
   ;
   tn-op-echo :echo
   tn-op-ttype :term-type
   tn-op-naws :window-size
   })

(def tn-keys
  (assoc (zipmap (vals tn-codes)
                 (keys tn-codes))
         :send tn-send))

(defn unsigned-get
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
    ; these are will/wont/do/dont:
    ; this gives us constant lookup
    (251 252 253 254) true
    false))

(defn- has-sub?
  [tn-code]
  (= tn-sb tn-code))

(defn read-telnet-code
  "Given a sequence of bytes, attempt to find a telnet escape command
   sequence.  If we found what might be an incomplete sequence, returns
   :-incomplete. If we found a complete sequence, returns a map with
   the decoded telnet sequence in :telnet, and the byte sequence before
   and after it in :before and :after, respectively"
  [buf-seq]
  (when-let [iac-idx (index-of buf-seq tn-iac)]
    (let [code-idx (inc iac-idx)
          opt-idx (inc code-idx)
          buf-count (byte-count buf-seq)]

      (if (<= buf-count code-idx)
        :-incomplete

        (let [code (nth-byte buf-seq code-idx)
              has-opt? (has-opt? code)
              has-sub? (has-sub? code)
              ;; FIXME actually, we should ensure this
              ;;  is proceeded by tn-iac
              sub-end (when has-sub?
                        (index-of buf-seq tn-se))]

          (when-not (= tn-iac code)
            (cond
              (and has-opt?
                   (<= buf-count opt-idx))
              :-incomplete

              (and has-sub?
                   (nil? sub-end))
              :-incomplete

              ; has an option, and not incomplete
              has-opt?
              (let [opt-byte (nth-byte buf-seq opt-idx)]
                {:telnet (get tn-codes code code)
                 :opt (get tn-codes opt-byte opt-byte)
                 :before (take-bytes buf-seq iac-idx)
                 :after (drop-bytes buf-seq (inc opt-idx))})

              ; has a subnegotiation, and not incomplete
              has-sub?
              {:telnet (get tn-codes
                            (nth-byte buf-seq opt-idx)
                            code)
               :opt (-> buf-seq
                        (drop-bytes (inc opt-idx))
                        (take-bytes
                          (min
                            max-sub-bytes
                            (- sub-end
                               opt-idx
                               ;; drop tn-iac/tn-se
                               2)))
                        buf->string)
               :before (take-bytes buf-seq iac-idx)
               :after (drop-bytes buf-seq (inc sub-end))}

              ; simple
              :else
              {:telnet (get tn-codes code code)
               :before (take-bytes buf-seq iac-idx)
               :after (drop-bytes buf-seq opt-idx)})))))))

(defn split-line
  "Given a sequence of non-telnet-escape bytes, if there is a \n
   character, return a tuple of [string, remaining], where `string` is
   the string value of the input, and `remaining` is the remaining
   sequence of bytes."
  [buf-seq]
  (when-let [new-line (index-of buf-seq (int \newline))]
    [(.trim (buf->string (take-bytes buf-seq new-line)))
     (drop-bytes buf-seq (inc new-line))]))

(defn- concat-buf-seqs
  [b1 b2]
  (cond
    (nil? b1) (create-buf-seq b2)
    (nil? b2) (create-buf-seq b1)
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

          ; just string. see if there's a newline
          :else
          (if-let [[result remain] (split-line buf-seq)]
            [true result remain]
            [false this buf-seq]))))))

(defn- ->bytes
  [& items]
  (->> items
       (map #(cond
               (string? %) (seq (.getBytes %))
               (keyword? %) (get tn-keys %)
               (vector? %) (seq (apply ->bytes %))
               :else %))
       flatten
       byte-array))

(defn- map->pkt
  [m]
  {:pre [(map? m)]}
  (let [kind (get tn-keys (:telnet m) (:telnet m))
        opt (get tn-keys (:opt m) (:opt m))]
    (cond
      ; no :telnet key; must be, eg: {:will :term-type}
      (not kind)
      ;; TODO support this
      (throw (IllegalArgumentException.
               (str "Invalid telnet map: " m)))

      ; simple
      (nil? opt)
      (->bytes tn-iac kind)

      ; subnegotiation
      (or (string? opt)
          (vector? opt))
      (->bytes tn-iac tn-sb kind opt tn-iac tn-se)

      ; simple option
      :else
      (->bytes tn-iac kind opt))))

(defn encode-packet
  [pkt]
  (if (map? pkt)
    ; encode to a byte array
    (map->pkt pkt)

    ; just let it be
    pkt))

(defn wrap-stream
  [s on-packet]
  (let [out (s/stream)]
    (s/connect
      (s/map encode-packet out)
      s)

    (let [spliced
          (s/splice
            out
            (io/decode-stream s telnet-protocol))]
      (s/consume
        #(on-packet s %)
        spliced)

      (doto spliced
        (alter-meta!
          assoc
          ::original s)))))

(defn- reflect-method [clazz method-name]
  (let [m (doto (.getDeclaredMethod clazz
                                    method-name
                                    (into-array Class []))
            (.setAccessible true))
        empty-array (into-array Object [])]
    (fn method-getter [obj]
      (.invoke m obj empty-array))))

(def ^:private channel-getter
  (memoize #(reflect-method io.netty.channel.nio.AbstractNioChannel
                            "javaChannel")))

(def ^:private impl-getter
  (memoize #(reflect-method java.net.Socket "getImpl")))

(def ^:private file-descriptor-getter
  (memoize #(reflect-method java.net.SocketImpl "getFileDescriptor")))

(def ^:private fd-getter
  (memoize (fn []
             (let [field (doto (.getDeclaredField java.io.FileDescriptor "fd")
                           (.setAccessible true))]
               (fn field-getter [obj]
                 (.get field obj))))))

(defn stream->fd [s]
  (-> s
      meta :rainboots.proto/original
      meta :aleph/channel
      ((channel-getter))  ; get the nio SocketChannel
      (#(.socket %))      ; get the underling Socket
      ((impl-getter))
      ((file-descriptor-getter))
      ((fd-getter))))

(def ^:private file-descriptor-factory
  (memoize (fn []
             (let [constructor (doto (.getDeclaredConstructor
                                       java.io.FileDescriptor
                                       (into-array Class [Integer/TYPE]))
                                 (.setAccessible true))]
               (fn create-file-descriptor [^long fd]
                 (.newInstance constructor
                               (into-array Object [(Integer/valueOf fd)])))))))

(def ^:private socket-impl-factory
  (memoize (fn []
             (let [constructor (doto (.getDeclaredConstructor
                                       java.net.PlainSocketImpl
                                       (into-array Class [java.io.FileDescriptor]))
                                 (.setAccessible true))]
               (fn create-socket-impl [^java.io.FileDescriptor fd]
                 (.newInstance constructor
                               (into-array Object [fd])))))))

(def ^:private socket-factory
  (memoize (fn []
             (let [constructor (doto (.getDeclaredConstructor
                                       java.net.Socket
                                       (into-array Class [java.net.SocketImpl]))
                                 (.setAccessible true))
                   set-created (reflect-method java.net.Socket "setCreated")
                   set-connected (reflect-method java.net.Socket "setConnected")]
               (fn create-socket [^java.net.SocketImpl impl]
                 (doto (.newInstance constructor
                                     (into-array Object [impl]))
                   (set-created)
                   (set-connected)))))))

(def ^:private socket-channel-factory
  (memoize (fn []
             ; this bit is ESPECIALLY jvm implementation-dependent :\
             (let [constructor (doto (.getDeclaredConstructor
                                       sun.nio.ch.SocketChannelImpl
                                       (into-array Class [java.nio.channels.spi.SelectorProvider
                                                          java.io.FileDescriptor
                                                          java.net.InetSocketAddress]))
                                 (.setAccessible true))]
               (fn create-socket-channel [^java.io.FileDescriptor fd]
                 (.newInstance constructor
                               (into-array Object [(java.nio.channels.spi.SelectorProvider/provider)
                                                   fd
                                                   nil])))))))

(defn fd->stream [fd]
  (let [file-descriptor ((file-descriptor-factory) fd)
        ;; ch (.getChannel (java.io.FileInputStream. file-descriptor))
        ;; socket (-> file-descriptor
        ;;            ((socket-impl-factory))
        ;;            ((socket-factory)))
        ;; ch (.getChannel socket)
        ch ((socket-channel-factory) file-descriptor)
        ]
    ;; (println ch)
    ;; (instance? java.nio.channels.SelectableChannel ch)
    ch
    #_(s/splice
      (aleph.netty/sink (io.netty.channel.socket.nio.NioSocketChannel. ch))
      (aleph.netty/source (io.netty.channel.socket.nio.NioSocketChannel. ch)))))

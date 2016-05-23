(ns ^{:author "Daniel Leong"
      :doc "Communication functions; copied for convenience into core"}
  rainboots.comms
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [rainboots
             [color :refer [process-colors strip-colors]]]))

(def ^:dynamic *svr*)

(defn send!
  "Send text to the client. You can pass in a variable
  number of args, which may in turn be strings, vectors,
  or functions. Vectors will be treated as additional
  varargs (IE: (apply)'d to this function).  Functions
  will be called with the client as a single argument,
  and the result sent as if it were passed directly.
  Strings, and any string returned by a function argument
  or in a vector, will be processed for color sequences
  (see the colors module).
  Maps will be treated as telnet sequences (see telnet!)"
  [cli & body]
  (when-let [s (:stream @cli)]
    (doseq [p body]
      (when p
        (condp #(%1 %2) p
          vector? (apply send! cli p)
          string? (s/put! s (if (:colors @cli)
                              (process-colors p)
                              (strip-colors p)))
          map? (s/put! s p)
          fn? (send! cli (p cli)))))
    (s/put! s "\r\n")))

(defn send-if!
  "Send text to every connected client for which 
  (pred cli) returns true. The arguments will be
  handled in the same way (send!) handles them."
  [pred & body]
  (when-let [clients (seq @(:connected @*svr*))]
    (doseq [cli clients]
      (when (pred cli)
        (apply send! cli body)))))

(defn send-all!
  "Send text to every connected client. This is
  a convenience function."
  [& body]
  (apply send-if! (constantly true) body))

(defn redef-svr!
  [svr]
  (def ^:dynamic *svr* svr))
(ns ^{:author "Daniel Leong"
      :doc "Communication functions; copied for convenience into core"}
  rainboots.comms
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [rainboots
             [color :refer [process-colors strip-colors]]
             [hooks :refer [hook! trigger!]]]))

(def ^:dynamic *svr*)

(defn default-colorize-hook
  "Default hook fn for colorizing output, installed
  by default. Automatically strips color codes if
  the client has declared it doesn't support them."
  [{:keys [cli text] :as arg}]
  (assoc arg
         :text
         (if (:colors @cli)
           (process-colors text)
           (strip-colors text))))

(hook! :process-send! default-colorize-hook)

(defmacro ^:private with-extras
  "Expects the following vars to be defined in context:
  - process-extras?
  - body
  - <cli-var> (you provide)
  This then declares `process-extras` and shifts vars around
  as appropriate."
  [cli-var & body]
  (let [cli-decl (when cli-var
                   `(~cli-var (if ~'has-extras?
                                ~cli-var
                                ~'process-extras?)))
        no-extras-body (if cli-var
                         `(cons ~cli-var ~'body)
                         `(cons ~'process-extras? ~'body))]
    `(let [~'has-extras? (map? ~'process-extras?)
           ~'process-extras (if ~'has-extras?
                              ~'process-extras?
                              {})
           ~'body (if ~'has-extras?
                    ~'body
                    ~no-extras-body)
           ~@cli-decl]
       ~@body)))

(def
  ^{:doc
    "Send text to the client. You can pass in a variable
    number of args, which may in turn be strings, vectors,
    or functions. Vectors will be treated as additional
    varargs (IE: (apply)'d to this function).  Functions
    will be called with the client as a single argument,
    and the result sent as if it were passed directly.
    Strings, and any string returned by a function argument
    or in a vector, will be processed for color sequences
    (see the colors module).
    Maps will be treated as telnet sequences (see telnet!)
    Strings are processed via the :process-send! hook,
    which is how the colors are applied. :process-send!
    is triggered with a map containing the recipient as
    :cli and the text as :text.
    You may optionally provide a map as the first argument,
    before the client object, whose keys and values will
    also be passed along in the map for the :process-send! hook."
    :arglists '([cli & body]
                [process-extras cli & body])}
  send!
  (fn [process-extras? cli & body]
    (with-extras cli
      (when-let [s (:stream @cli)]
        (doseq [p body]
          (when p
            (condp #(%1 %2) p
              vector? (apply send! cli p)
              string? (s/put! s (:text
                                  (trigger! :process-send!
                                            (assoc process-extras
                                                   :cli cli
                                                   :text p))))
              map? (s/put! s p)
              fn? (send! cli (p cli)))))
        (s/put! s "\r\n")))))

(def
  ^{:doc
    "Send text to every connected client for which
    (pred cli) returns true. The message body will be
    handled in the same way (send!) handles it.
    See send! for the meaning of the optional process-extras."
    :arglists '([pred & body]
                [process-extras pred & body])}
  send-if!
  (fn [process-extras? pred & body]
    (when (bound? #'*svr*)
      (when-let [connected (:connected @*svr*)]
        (when-let [clients (seq @connected)]
          (with-extras pred
            (doseq [cli clients]
              (when (pred cli)
                (apply send! process-extras cli body)))))))))

(def
  ^{:doc
    "Send text to every connected client. This is
    a convenience function. See send! for the meaning
    of the optional process-extras"
    :arglists '([& body]
                [process-extras & body])}
  send-all!
  (fn [process-extras? & body]
    (with-extras nil
      (apply send-if! process-extras (constantly true) body))))

(defn redef-svr!
  [svr]
  (def ^:dynamic *svr* svr))

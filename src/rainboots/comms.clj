(ns ^{:author "Daniel Leong"
      :doc "Communication functions; copied for convenience into core"}
  rainboots.comms
  (:require [clojure.core.async :refer [put!]]
            [manifold.stream :as s]
            [rainboots
             [color :refer [process-colors strip-colors]]
             [hooks :refer [hook! trigger!]]]))

(def ^:dynamic *svr*)

(defn default-colorize-hook
  "Default hook fn for colorizing output, installed by default.
   Automatically strips color codes if the client has declared it
   doesn't support them."
  [{:keys [cli _text] :as arg}]
  (update arg :text (if (:colors @cli)
                      process-colors
                      strip-colors)))

(hook! :process-send! default-colorize-hook)

(defmacro ^:private with-extras
  "Expects the following vars to be defined in context:
  - body
  - <?process-extras> (you provide)
  - <cli-var> (you provide)
  This then declares `process-extras` and shifts vars around
  as appropriate."
  [?process-extras-var cli-var & body]
  (let [cli-decl (when cli-var
                   `(~cli-var (if ~'has-extras?
                                ~cli-var
                                ~?process-extras-var)))
        no-extras-body (if cli-var
                         `(cons ~cli-var ~'body)
                         `(cons ~?process-extras-var ~'body))]
    `(let [~'has-extras? (map? ~?process-extras-var)
           ~'process-extras (if ~'has-extras?
                              ~?process-extras-var
                              {})
           ~'body (if ~'has-extras?
                    ~'body
                    ~no-extras-body)
           ~@cli-decl]
       ~@body)))

;;
;; The following are manually def'd so they can have nice, clean
;; arglists in the help docs, specifying clearly how they may be used:
;;

(def
  ^{:doc
    "Send text to the client. You can pass in a variable number of
     args, which may in turn be strings, vectors, or functions. Vectors
     will be treated as additional varargs (IE: (apply)'d to this
     function).  Functions will be called with the client as a single
     argument, and the result sent as if it were passed directly.
     Strings, and any string returned by a function argument or in a
     vector, will be processed for color sequences (see the colors
     module).

    Maps will be treated as telnet sequences (see telnet!) Strings are
     processed via the :process-send! hook, which is how the colors are
     applied. :process-send!  is triggered with a map containing the
     recipient as :cli and the text as :text.

    You may optionally provide a map as the first argument, before the
     client object, whose keys and values will also be passed along in
     the map for the :process-send! hook."
    :arglists '([cli & body]
                [process-extras cli & body])}
  send!
  (fn [?process-extras cli & body]
    (with-extras ?process-extras cli
      (let [cli' @cli]
        (when-let [s (:stream cli')]
          (doseq [p body]
            (when p
              (condp #(%1 %2) p
                vector? (apply send! process-extras cli p)
                string? (s/put! s (:text
                                    (trigger! :process-send!
                                              (assoc process-extras
                                                     :cli cli
                                                     :text p))))
                map? (s/put! s p)
                fn? (send! process-extras cli (p cli)))))

          (s/put! s "\r\n")

          ; attempt to prompt
          (when-not (:rainboots/in-prompt process-extras)
            (when-let [prompt-chan (:rainboots/prompt-chan cli')]
              (put! prompt-chan cli))))))))

(def
  ^{:doc
    "Send text to every connected client for which (pred cli) returns
     true. The message body will be handled in the same way (send!)
     handles it. See send! for the meaning of the optional
     process-extras."
    :arglists '([pred & body]
                [process-extras pred & body])}
  send-if!
  (fn [?process-extras pred & body]
    (when (bound? #'*svr*)
      (when-let [clients (some-> @*svr* :connected deref seq)]
        (with-extras ?process-extras pred  ; this looks wrong, but it's correct
          (doseq [cli clients]
            (when (pred cli)
              (apply send! process-extras cli body))))))))

(def
  ^{:doc
    "Send text to every connected client. This is a convenience
     function. See send! for the meaning of the optional
     process-extras"
    :arglists '([& body]
                [process-extras & body])}
  send-all!
  (fn [?process-extras & body]
    (with-extras ?process-extras nil
      (apply send-if! process-extras (constantly true) body))))

(defn redef-svr!  [svr]
  (alter-var-root #'*svr* (constantly svr)))

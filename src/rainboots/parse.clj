(ns ^{:author "Daniel Leong"
      :doc "Command parsing"}
  rainboots.parse
  (:require [clojure.string :refer [split] :as str]
            [rainboots.comms :refer [send!]]))

(defn- strim
  "'Safe' trim; if input is nil, returns nil.
  Otherwise, performs (.trim) on it."
  [^CharSequence string]
  (when string
    (.trim string)))

(defn extract-word
  "ASSUMES the input is trim'd. Extract the first 'word',
  returning a sequence of the full match of that 'word'
  plus any whitespace after it, and the actual intended
  input. For quoted input, this means the first item
  will include the quotes and any trailing whitespace,
  and the second item will not"
  [^CharSequence s]
  (when (> (.length s) 0)
    (let [in-quote? (= \" (.charAt s 0))
          len (.length s)
          expand-whitespace
          (fn [i]
            (if (and (< i len)
                     (Character/isWhitespace
                       (.charAt s i)))
              (recur (inc i))
              i))]
      (loop [i (if in-quote?  1 0)]
        (if (>= i len)
          [s s]
          (let [c (.charAt s i)
                quote? (and (= \" c)
                            (or (= i 0)
                                (not= \\ (.charAt s (dec i)))))]
            (cond
              (and quote? in-quote?)
              [(subs s 0 (expand-whitespace (inc i)))
               (subs s 1 i)]
              (and (Character/isWhitespace c)
                   (not in-quote?))
              [(subs s 0 (expand-whitespace (inc i)))
               (subs s 0 i)]
              :else
              (recur (inc i)))))))))

(defn default-argtype-handler
  [cli input]
  (let [input (strim input)
        [full-match value] (extract-word input)
        full-len (count full-match)
        remaining (when (< full-len (count input))
                    (subs input full-len))]
    [value remaining]))

(defonce ^:dynamic *arg-types*
  ;; we include a default arg type
  (atom {nil default-argtype-handler}))

(defn expand-args
  [cli raw-args arg-types]
  ;; deref once
  (let [registered @*arg-types*]
    (loop [to-parse arg-types
           result []
           input (strim raw-args) ]
      (let [next-argtype (first to-parse)
            param (when (vector? next-argtype)
                    (second next-argtype))
            next-argtype (if (vector? next-argtype)
                           (first next-argtype)
                           next-argtype)
            handler (or (get registered next-argtype)
                        (get registered nil))]
        (cond
          ;; done, but if there's still more input then
          ;;  this was for a different arity
          (empty? to-parse) (when (empty? input)
                              result)
          ;; no input? there are still more args, so... fail
          ;; if the next arg isn't nilable
          (and (nil? input)
               (not (:nilable? (meta handler)))) nil
          ;; otherwise...
          :else
          (let [[v else] (if param
                           (handler cli input param)
                           (handler cli input))]
            (cond
              v
              (recur
                (next to-parse)
                (conj result v)
                (strim else)))))))))

(defn apply-cmd
  "Execute the provided cmd-fn, parsing its
  arguments as appropriate with any annotated
  argtypes. See (defargtype). Returns `true`
  if we found a matching function arity, else
  `nil` if we did nothing"
  [cmd-fn cli raw-args]
  (let [arg-lists (:arg-lists (meta cmd-fn))]
    (if (and (= 1 (count arg-lists))
             (empty? (first arg-lists)))
      ;; easy case
      (do (cmd-fn cli)
          true)
      ;; okay, try to expand all arg lists...
      (when-let [args
                 (->> arg-lists
                      (map
                        (partial expand-args cli raw-args))
                      (filter some?)
                      (sort-by count)
                      last)] ;; ... take the longest match
        (if-let [err (some
                       #(when (instance? Throwable %) %)
                       args)]
          ;; error handling a successfully parsed arg;
          ;;  tell the client about it
          (send! cli (.getMessage err))
          ;; everything looks good! make it happen
          (apply cmd-fn cli args))
        true))))

(defn extract-command
  "Splits input into [cmd, REST], where `cmd` is
  the text command input, and REST is the rest of
  the arguments passed, if any"
  [raw-cmd]
  {:pre [(string? raw-cmd)]}
  (default-argtype-handler
    nil (.trim raw-cmd)))

(defn argtype-meta
  [argtype-fn]
  (let [m (meta argtype-fn)
        arglists (:arglists m)]
    ; promote whether the input can be nilable
    (assoc m :nilable? (-> arglists
                           first
                           second
                           meta
                           :nilable))))

;;
;; Public interface
;;

(defmacro defargtype
  "Declare an argument type. When an argument to a command is
  annotated with an arg type, the declared argtype handler will
  be called with the client who's calling, and the input string
  starting from with the argument.

  The handler MUST return a tuple, where the first value is passed
  to the command as the argument, and the second is the remaining,
  unparsed input string (or nil if there's nothing else).
  If the provided input cannot be successfully parsed, you may return
  nil to indicate that the `doc` text should be shown. If it was
  successfully parsed but the input was invalid, a Throwable should
  be returned as the first item in the tuple; the message will be
  sent to the client.

  Argtypes can be used for implementing common functionality; eg:
  ^:item could locate an item object at the right place and pass
  that as the arg (instead of just a string). If the item couldn't
  be located (or didn't exist), that's when a Throwable should be
  returned.

  Such functionality will probably be left to client code (or plugin
  libraries), but there's lots of potential here.
  Note that we need the full input string, as we can't assume a single
  word will apply to a single arg. To continue with ^:item, for
  example, we might want to support \"sword from/in chest\" or
  \"shoe on ground\"-type phrases. We might also want something like
  :^rest to keep all the \"rest\" of the input as a string in a single
  argument.

  You may also accept a parameter to the argtype, for further
  specification per-cmd. For example, if you want an item but only
  if it's on the ground you might annotate the cmd arg with
  ^{:item :on-ground}. The 'value' of the argtype annotation will be
  passed to your argtype as the third parameter (since it is less
  common, and you may wish to make it optional).

  Most argtypes expect *some* input, but it is possible to create
  argtypes that do not---for example, if you have a command to
  'target' a creature, for example, you might want to inject that
  target to commands implicitly, and handle the lack of a target
  in the argtype. In this case, you should annotate the `input`
  parameter as ^:nilable. Such 'pass-through' argtypes should typically
  be *after* explicit argtypes in your command declarations."
  [arg-type doc argv & fn-body]
  {:pre [(keyword? arg-type)
         (vector? argv)
         (>= (count argv) 2)]}
  (let [fn-name (str "argtype$" (name arg-type))
        fn-var (symbol fn-name)]
    `(let [argtype-fn# (defn ~fn-var
                         ~argv
                         ~@fn-body)]
       (swap! *arg-types*
              assoc
              ~arg-type
              (with-meta
                ~fn-var
                (argtype-meta (var ~fn-var)))))))

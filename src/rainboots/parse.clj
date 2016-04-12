(ns ^{:author "Daniel Leong"
      :doc "Command parsing"}
  rainboots.parse
  (:require [clojure.string :refer [split]]))

(declare default-argtype-handler)

(defonce ^:dynamic *arg-types* 
  ;; we include a default arg type
  (atom {nil default-argtype-handler}))

(defn expand-args
  [cli raw-args arg-types]
  ;; deref once
  (let [registered @*arg-types*]
    (loop [to-parse arg-types
           result []
           input (.trim raw-args)]
      (if (empty? to-parse)
        result ; done
        (let [argtype (first to-parse)
              handler (or (get registered argtype)
                          (get registered nil))
              [v else] (handler cli input)]
          (recur
            (next to-parse)
            (conj result v)
            else))))))

(defn apply-cmd
  "Execute the provided cmd-fn, parsing its
  arguments as appropriate with any annotated
  argtypes. See (defargtype)"
  [cmd-fn cli raw-args]
  (let [arg-types (:arg-types (meta cmd-fn))]
    (if (empty? arg-types)
      ;; easy case
      (cmd-fn cli)
      (let [args (expand-args cli raw-args arg-types)]
        (apply cmd-fn cli args)))))

(defn default-argtype-handler
  [cli input]
  (let [[full value] 
        (first
          (re-seq #"(\S+)(\s+|$)" input))
        full-len (count full)
        remaining (when (< full-len (count input))
                    (subs input full-len))]
    ; TODO probably, support quoted strings
    [value remaining]))

(defn extract-command 
  "Splits input into [cmd, REST], where `cmd` is
  the text command input, and REST is the rest of
  the arguments passed, if any"
  [raw-cmd]
  {:pre [(string? raw-cmd)]}
  (default-argtype-handler 
    nil (.trim raw-cmd)))

;;
;; Public interface
;;

(defmacro defargtype
  "Declare an argument type. When an argument
  to a command is annotated with an arg type,
  the declared argtype handler will be called
  with the client who's calling, and the input
  string starting from with the argument. 
  The handler MUST return a tuple, where the first
  value is passed to the command as the argument,
  and the second is the remaining, unparsed 
  input string (or nil if there's nothing else).
  If the provided input cannot be successfully
  parsed, you may return nil to indicate that
  the `doc` text should be shown.
  This can be used for implementing common
  functionality; eg: ^:item could locate an item 
  object at the right place and pass that as 
  the arg (instead of just a string). 
  Such functionality will probably be left to client 
  code (or plugin libraries), but there's lots of 
  potential here.
  Note that we need the full input string, as we can't
  assume a single word will apply to a single arg.
  To continue with ^:item, for example, we might want 
  to support \"sword from/in chest\" or \"shoe on 
  ground\"-type phrases. We might also want something 
  like :^rest to keep all the \"rest\" of the input as
  a string in a single argument."
  [arg-type doc argv & fn-body]
  {:pre [(keyword? arg-type)
         (vector? argv)
         (= 2 (count argv))]}
  `(swap! *arg-types* 
          assoc
          ~arg-type
          (fn ~argv
            ~@fn-body)))

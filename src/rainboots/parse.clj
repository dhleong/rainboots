(ns ^{:author "Daniel Leong"
      :doc "Command parsing"}
  rainboots.parse
  (:require [clojure.string :refer [split]]))

(defn apply-cmd
  [cmd-fn cli input]
  ;; TODO we might like to be able to
  ;;  annotate fields with common
  ;;  functionality; eg: ^:item could
  ;;  locate an item object at the right
  ;;  place and pass that as the arg. 
  ;; Such functionality will probably be
  ;;  left to client code (or plugin libraries),
  ;;  but there's lots of potential here.
  ;; Note that we need the full input string,
  ;;  as we can't assume a single word will
  ;;  apply to a single arg. To continue with
  ;;  ^:item, for example, we might want to
  ;;  support "sword from/in chest" or 
  ;;  "shoe on ground"-type phrases. We might
  ;;  also want something like :^rest to keep
  ;;  all the "rest" of the input as a string
  ;;  in a single argument.
  (apply cmd-fn 
         cli 
         ; TODO probably, support quoted strings
         (rest (split input #" +"))))

(defn extract-command 
  "Simple string parsing"
  [raw-cmd]
  {:pre [(string? raw-cmd)]}
  (let [raw-cmd (.trim raw-cmd)
        first-space (.indexOf raw-cmd " ")]
    (if (= -1 first-space)
      raw-cmd
      (subs raw-cmd 0 first-space))))

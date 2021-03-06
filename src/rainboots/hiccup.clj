(ns ^{:author "Daniel Leong"
      :doc "Hiccup processing for send! arguments"}
  rainboots.hiccup
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :refer [prewalk postwalk]]
            [rainboots.color :refer [color-sequences]]))

(declare ^:dynamic *handlers*)
(declare process)

; ======= spec ============================================

(defn- fn-or-keyword? [v]
  (or (keyword? v)
      (fn? v)))

(s/def ::hiccup-type fn-or-keyword?)

(s/def ::hiccup-simple
  (s/cat :type ::hiccup-type
         :children (s/* ::hiccup-child)))

(s/def ::hiccup
  (s/or :string string?
        :simple ::hiccup-simple
        :attrs map?
        :seq (s/coll-of ::hiccup)))

(s/def ::hiccup-child
  (s/or :hiccup ::hiccup
        :value identity))

(s/def ::all-strings
  (s/* (s/or :string string?
             :string-coll ::all-strings)))

; ======= builtins ========================================

(defn trim-declear [s]
  (if (str/ends-with? s "{n")
    (subs s 0 (- (count s)
                 2))
    s))

(defn- handle-color [color-str _cli & children]
  (let [color-rune (str "{" color-str)]
    (->> children
         (map trim-declear)
         (mapcat (fn [child]
                   (if (and (str/starts-with? child "{")
                            (not (str/starts-with? child "{{")))
                     [child]
                     [color-rune child])))
         (str/join)
         (#(str % "{n")))))

(defn- color-handlers []
  (->> color-sequences
       (reduce-kv
         (fn [m color-str _]
           (assoc m (keyword color-str)
                  (partial handle-color color-str)))
         {})))

(defn- handle-string [cli & children]
  (if (s/valid? ::all-strings children)
    (str/join "" (flatten children))

    (let [do-process (partial process cli)]
      (->> children
           (mapcat (fn [f]
                     (if (and (seq? f)
                              (not (vector? f)))
                       (map do-process f)
                       [(process cli f)])))
           (str/join "")))))


; ======= utils ===========================================

(defn- process-form
  [cli type args]
  (if-some [handler (or (when-not (keyword? type)
                          type)
                        (when (= ::string type)
                          handle-string)
                        (get @*handlers* type))]
    ; re-process, just in case
    (process cli (apply handler cli args))

    (throw (IllegalArgumentException.
             (str "No registered handler for: " type
                  "; form=" [type args])))))

(defn- walk-hiccup [cli hiccup]
  (if (or (map-entry? hiccup)
          (not (vector? hiccup)))
    hiccup

    (let [[type & args] hiccup]
      (when-not type
        (throw (IllegalArgumentException.
                 (str "Invalid hiccup form: " hiccup))))

      (process-form cli type args))))

(defn- normalize-spec [form]
  (if (vector? form)
    (let [[kind arg] form]
      (case kind
        :string arg
        :attrs arg
        :hiccup (normalize-spec arg)
        :value arg
        :seq (->> arg
                  (map (fn [a]
                         (if (and (vector? a)
                                  (= :string (first a)))
                           (rest a) ; unpack [:string] spec'd type
                           a)))
                  (into [::string]))

        :simple
        (let [{:keys [type children]} arg]
          (into [type] children))

        ; probably a map-entry or something
        form))

    ; otherwise, probably the type key, or an argument, etc.
    form))


; ======= public interface ================================

(def ^:dynamic *handlers*
  (atom (assoc (color-handlers)
               ::string handle-string)))

(defn process
  [cli form]
  (let [conformed (s/conform ::hiccup form)]
    (if (= ::s/invalid conformed)
      (throw (IllegalArgumentException.
               (str "Invalid hiccup: " form
                    "\nExplanation: " (s/explain ::hiccup form))))

      (->> conformed
           (prewalk normalize-spec)
           (postwalk (partial walk-hiccup cli))))))

(defmacro defhandler
  [kw doc params & body]
  {:pre [(keyword? kw)
         (string? doc)
         (vector? params)]}
  (let [fn-name (symbol (str (namespace kw) "_" (name kw)))]
    ; NOTE we can't really attach the documentation to the
    ; anonymous fn in a useful way, but it's good to have it
    ; attached in the source
    `(swap! *handlers*
            assoc ~kw
            (fn ^{:doc ~doc} ~fn-name
              ~params
              ~@body))))

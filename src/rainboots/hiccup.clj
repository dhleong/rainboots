(ns ^{:author "Daniel Leong"
      :doc "Hiccup processing for send! arguments"}
  rainboots.hiccup
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
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
         :children (s/* ::hiccup)))

(s/def ::hiccup-with-attrs
  (s/cat :type ::hiccup-type
         :attrs map?
         :children (s/* ::hiccup)))

(s/def ::hiccup
  (s/or :string string?
        :simple ::hiccup-simple
        :has-attrs ::hiccup-with-attrs
        :seq (s/coll-of ::hiccup)))


; ======= builtins ========================================

(defn trim-declear [s]
  (if (str/ends-with? s "{n")
    (subs s 0 (- (count s)
                 2))
    s))

(defn- handle-color [color-str _cli _attrs children]
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

(defn- handle-string [cli _ children]
  (let [do-process (partial process cli)]
    (->> children
         (mapcat (fn [f]
                   (if (and (seq? f)
                            (not (vector? f)))
                     (map do-process f)
                     [(process cli f)])))
         (str/join ""))))


; ======= utils ===========================================

(defn- process-form
  [cli type attrs children]
  (if-some [handler (or (when-not (keyword? type)
                          type)
                        (get @*handlers* type))]
    ; re-process, just in case
    (process cli (handler cli attrs children))

    (throw (IllegalArgumentException.
             (str "No registered handler for: " type)))))

(defn- walk-hiccup [cli hiccup]
  (if (not (vector? hiccup))
    hiccup

    (let [[type attrs children] hiccup]
      (process-form cli type attrs children))))

(defn- normalize-spec [form]
  (if (vector? form)
    (let [[kind arg] form]
      (case kind
        :string arg
        :seq (->> arg
                  (map (fn [a]
                         (if (and (vector? a)
                                  (= :string (first a)))
                           (rest a) ; unpack [:string]
                           a)))
                  (into [:string {}]))

        (let [{:keys [type attrs children] :or {attrs {}}} arg]
          (when-not type (throw (IllegalStateException. (str "HUH: " form))))
          [type attrs (seq children)])))

    ; otherwise, probably the type key, or an argument, etc.
    form))


; ======= public interface ================================

(def ^:dynamic *handlers*
  (atom (assoc (color-handlers)
               :string handle-string)))

(defn process
  [cli form]
  (let [conformed (s/conform ::hiccup form)]
    (if (= ::s/invalid conformed)
      (throw (IllegalArgumentException.
               (str form "INVALID:\n" (s/explain ::hiccup form))))

      (->> conformed
           (prewalk normalize-spec)
           (postwalk (partial walk-hiccup cli))))))

(ns ^{:author "Daniel Leong"
      :doc "Global hooks system"}
  rainboots.hooks
  (:require [clojure.string :as str]))

(def ^:dynamic *hooks* (atom {}))

(defn- try-hook [hook-name fn-name hook-type options fun]
  (swap! *hooks*
         update-in
         [hook-name hook-type]
         (fn [{old-name :name :as existing}]
           (if (and existing
                    (or (nil? old-name)
                        (not= fn-name old-name)))
             (throw (IllegalArgumentException.
                      (str "Overriding existing " hook-type
                           " for " hook-name
                           " (fn: `" fn-name "`)")))

             {:f fun
              :name fn-name
              :opts options}))))

(defn- hook-fn->sort-key [hook-obj]
  (or (->> hook-obj :opts :priority) 0))

(def ^:private descending #(compare %2 %1))

(defn do-hook!
  [hook-name fn-name options fun]
  (cond
    (:when-only options) (try-hook hook-name fn-name :when-only options fun)
    (:when-no-result options) (try-hook hook-name fn-name :when-no-result options fun)

    :else (swap! *hooks*
                 update-in
                 [hook-name :list]
                 (fn [hook-list]
                   (->> hook-list

                        ; remove any existing copies of this fn (by name)
                        (remove (comp (partial = fn-name) :name))

                        ; add the fn
                        (concat [{:f fun
                                  :opts options
                                  :name fn-name}])

                        ; sort by priority
                        (sort-by hook-fn->sort-key descending))))))

(defn- remove-matching [_fn-name fun old]
  (when (not= fun (:f old))
    old))

(defn do-unhook!
  [hook-name fn-name fun]
  (let [remove-fn (partial remove-matching fn-name fun)]
    (swap! *hooks*
           update
           hook-name
           (fn [hook]
             (-> hook
                 (update :when-no-result remove-fn)
                 (update :when-only remove-fn)

                 (update :list
                         (partial remove
                                  (comp (partial = fn-name)
                                        :name))))))))

(defn clean-fn-name [fn-var]
  (let [n (str fn-var)
        first-dollar (str/index-of n "$")
        last-dollar (str/last-index-of n "$")
        name-end (str/last-index-of n "__")]
    (if (and first-dollar
             last-dollar
             name-end)
      (let [fn-ns (subs n 0 first-dollar)
            the-name (subs n (inc last-dollar) name-end)]
        (str fn-ns "/" the-name))

      (when-let [instance-idx (str/index-of n "@")]
        (-> n
            (subs 0 instance-idx)
            (str/replace "$" "/"))))))

(defn- nameof [fun]
  (cond
    (and (seq? fun)
         (contains? #{'clojure.core/fn
                      'clojure.core/defn
                      'defn 'fn}
                    (first fun))
         ; TODO namespace? can we?
         (symbol? (second fun)))
    (name (second fun))

    ; if a symbol, since this is called from a macro
    ; toString should get the resolved name (if any)
    ; for namespace-safe dedup'ing
    (symbol? fun) `(or (clean-fn-name ~fun)
                       ~(str fun))

    ; else, just fallback; this is probably about
    ; the same as the old behavior, and is not recommended
    :else (str fun)))

(defmacro hook!
  "Install a hook. Hooks are fired with undefined ordering, with each
   subsequent hook receiving the results of the one before it. The
   argument(s) passed to a hook are arbitrary, but each hook MUST
   return the same 'type' of data it received, so as to play nicely
   with other installed hooks of the same kind.

  The name of a hook is similarly arbitrary---it may be a String or a
   Keyword or whatever you want, as long as you use it consistently."
  ([hook-name fun] `(hook! ~hook-name nil ~fun))
  ([hook-name options fun]
   (let [fn-name (nameof fun)]
     `(do-hook! ~hook-name ~fn-name ~options ~fun))))

(defmacro insert-hook!
  "DEPRECATED: use `(hook!)` with a priority.

   Insert a fn as the first to be called for the given hook, before any
   already-registered hooks. See hook!"
  [hook-name fun]
  `(hook! ~hook-name {:priority Integer/MAX_VALUE} ~fun))

(defn installed-hook-pairs
  "Get the list of [name, installed hook fn] names for the given hook"
  [hook-name]
  (->> (get @*hooks* hook-name)
       :list
       (map (fn [{:keys [name f]}]
              [name f]))))

(defn installed-hooks
  "Get the list of installed hook funs for the given hook"
  [hook-name]
  (map second (installed-hook-pairs hook-name)))

(defn- run-hook! [hook arg]
  (if-let [installed (seq (:list hook))]
    ; execute hooks
    (let [returned (reduce (fn [v {hook-fn :f}]
                             (let [new-v (hook-fn v)]
                               (if (reduced? new-v)
                                 ; reduced result? great. wrap it in a second
                                 ; (reduced) so we know the returned value *was*
                                 ; reduced
                                 (reduced new-v)

                                 ; just the value
                                 new-v)))
                           arg
                           installed)]
      (if (reduced? returned)
        ; we have a result! return it
        (unreduced returned)

        ; :when-no-result on the last-returned value (if we have it)
        (or (when-let [{hook-fn :f} (:when-no-result hook)]
              (hook-fn returned))

            ; otherwise, just use the returned value
            returned)))

    ; no installed hooks; perhaps there's a :when-only? or :when-no-result?
    (if-let [{hook-fn :f} (or (:when-only hook)
                              (:when-no-result hook))]
      ; there is!
      (hook-fn arg)

      ; nope; return the input
      arg)))

(defn trigger!
  "Trigger a hook kind. hook-name should be a previously installed hook
   via (hook!), but it is not an error to fire a hook with nothing
   installed to it.  Returns the final result returned from the
   last-run hook fn, or the input arg itself if no hooks are
   installed."
  [hook-name arg]
  (if-let [hook (get @*hooks* hook-name)]
    (run-hook! hook arg)

    ; no hooks installed; return the input
    arg))

(defmacro unhook!
  "Uninstall a previously installed hook fn"
  [hook-name fun]
  (let [fn-name (nameof fun)]
    `(do-unhook! ~hook-name ~fn-name ~fun)))

(defmacro with-hooks-context
  [& body]
  `(binding [*hooks* (atom {})]
     ~@body))

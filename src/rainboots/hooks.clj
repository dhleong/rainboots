(ns ^{:author "Daniel Leong"
      :doc "Global hooks system"}
  rainboots.hooks)

(def ^:private hooks (atom {}))

(defn do-hook!
  [hook-name fn-name fun]
  (swap! hooks
         update hook-name
         (fn [hook-list]
           (vec
             (conj
               (remove (comp (partial = fn-name) first)
                       hook-list)
               [fn-name fun])))))

(defn do-insert-hook!
  "Insert a fn as the first to be called for the given hook, before any
   already-registered hooks. See hook!"
  [hook-name fn-name fun]
  (swap! hooks
         update hook-name
         (fn [hook-list]
           (let [hook-list (when hook-list
                             (remove (comp (partial = fn-name) first)
                                     hook-list))]
             (concat hook-list [[fn-name fun]])))))

(defn do-unhook!
  [hook-name fn-name]
  (swap! hooks update hook-name (partial remove
                                         (comp (partial = fn-name)
                                               first))))

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
    (symbol? fun) (str fun)

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
  [hook-name fun]
  (let [fn-name (nameof fun)]
    `(do-hook! ~hook-name ~fn-name ~fun)))

(defmacro insert-hook!
  "Insert a fn as the first to be called for the given hook, before any
   already-registered hooks. See hook!"
  [hook-name fun]
  (let [fn-name (nameof fun)]
    `(do-insert-hook! ~hook-name ~fn-name ~fun)))

(defn installed-hook-pairs
  "Get the list of [name, installed hook fn] names for the given hook"
  [hook-name]
  (get @hooks hook-name))

(defn installed-hooks
  "Get the list of installed hook funs for the given hook"
  [hook-name]
  (map second (installed-hook-pairs hook-name)))

(defn trigger!
  "Trigger a hook kind. hook-name should be a previously installed hook
   via (hook!), but it is not an error to fire a hook with nothing
   installed to it.  Returns the final result returned from the
   last-run hook fn, or the input arg itself if no hooks are
   installed."
  [hook-name arg]
  (if-let [installed (get @hooks hook-name)]
    (let [f (apply comp (map second installed))]
      (f arg))
    arg))

(defmacro unhook!
  "Uninstall a previously installed hook fn"
  [hook-name fun]
  (let [fn-name (nameof fun)]
    `(do-unhook! ~hook-name ~fn-name)))

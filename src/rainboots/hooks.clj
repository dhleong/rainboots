(ns ^{:author "Daniel Leong"
      :doc "Global hooks system"}
  rainboots.hooks)

(def ^:private hooks (atom {}))

(defn hook!
  "Install a hook. Hooks are fired with undefined ordering,
  with each subsequent hook receiving the results of
  the one before it. The argument(s) passed to a hook are
  arbitrary, but each hook MUST return the same 'type'
  of data it received, so as to play nicely with other
  installed hooks of the same kind.
  The name of a hook is similarly arbitrary---it may be a
  String or a Keyword or whatever you want, as long as you
  use it consistently."
  [hook-name fun]
  (swap! hooks update hook-name conj fun))

(defn insert-hook!
  "Insert a fn as the first to be called for the given hook,
  before any already-registered hooks. See hook!"
  [hook-name fun]
  (swap! hooks update hook-name concat [fun]))

(defn trigger!
  "Trigger a hook kind. hook-name should be a previously
  installed hook via (hook!), but it is not an error
  to fire a hook with nothing installed to it. Returns
  the final result returned from the last-run hook fn,
  or the input arg itself if no hooks are installed."
  [hook-name arg]
  (if-let [installed (get @hooks hook-name)]
    (let [f (apply comp installed)]
      (f arg))
    arg))

(defn unhook!
  "Uninstall a previously installed hook fn"
  [hook-name fun]
  (swap! hooks update hook-name (partial remove (partial = fun))))

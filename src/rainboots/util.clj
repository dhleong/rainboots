(ns ^{:author "Daniel Leong"
      :doc "Misc. Utilities"}
  rainboots.util)

(defn log
  [& parts]
  (apply println parts))

(defmacro wrap-fn
  "Wraps a function in another one, so
  the original can be updated by repl
  and have the changes reflected"
  [fun]
  `(fn [& args#]
     (apply ~fun args#)))


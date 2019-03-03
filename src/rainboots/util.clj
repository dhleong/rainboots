(ns ^{:author "Daniel Leong"
      :doc "Misc. Utilities"}
  rainboots.util)

(defn log
  [& parts]
  (apply println parts))

(defmacro wrap-fn
  "Wraps a function in another one, so the original can be updated by
   repl and have the changes reflected"
  [fun]
  (let [wrapped-name
        (when (symbol? fun)
          [(symbol
             (str "wrapped>"
                  (name fun)))])]
    `(fn ~@wrapped-name [& args#]
       (apply ~fun args#))))


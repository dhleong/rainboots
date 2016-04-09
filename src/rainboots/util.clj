(ns ^{:author "Daniel Leong"
      :doc "Misc. Utilities"}
  rainboots.util)

(defn log
  [& parts]
  (apply println parts))

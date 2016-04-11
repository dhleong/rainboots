(ns ^{:author "Daniel Leong"
      :doc "Command parsing"}
  rainboots.parse
  (:require [clojure.string :refer [split]]))


(defn parse-command 
  [raw-cmd]
  ;; FIXME this is not complete
  (split raw-cmd #" +"))

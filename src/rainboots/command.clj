(ns ^{:author "Daniel Leong"
      :doc "Command definitions"} 
  rainboots.command
  (:require [rainboots
             [util :refer [wrap-fn]]]))

(def commands (atom {}))

(defmacro defcmd
  [cmd-name argv & body]
  (let [fn-name (str "cmd-" cmd-name)
        fn-var (symbol fn-name)]
    `(do
       (defn ~fn-var
         ~argv
         ~@body)
       ;; TODO generate abbreviations
       (swap! commands 
              assoc 
              ~(str cmd-name) 
              (wrap-fn ~fn-var)))))

(defn exec-command
  [on-404 cli cmd & args]
  (if-let [fun (get @commands cmd)]
    (apply fun cli args)
    (on-404 cli cmd)))

(ns ^{:author "Daniel Leong"
      :doc "Command definitions"} 
  rainboots.command
  (:require [rainboots
             [util :refer [wrap-fn]]]))

(def commands (atom {}))

(defn build-up
  [string]
  (map
    #(subs string 0 %)
    (range 1 (inc (count string)))))

(defmacro defcmd
  "Declare a command that can be invoked
  from the default on-cmd handler. The
  syntax is similar to (defn). The cmd-name
  may be annotated with ^:no-abbrv if the
  command may only be executed by inputting
  the full name; otherwise, abbreviations
  will be generated starting from the first
  letter, then the first two letters, and so on."
  [cmd-name argv & body]
  (let [no-abbrv? (:no-abbrv (meta cmd-name))
        fn-name (str "cmd-" cmd-name)
        fn-var (symbol fn-name)
        invoke (str cmd-name)
        invocations (if no-abbrv?
                      [invoke]
                      (build-up invoke)) ]
    `(let [defd-fn# 
           (defn ~fn-var
             ~argv
             ~@body)
           ~'wrapped
           (wrap-fn ~fn-var)]
       ;; TODO generate abbreviations
       (swap! commands 
              assoc 
              ~@(mapcat
                  list
                  invocations
                  (repeat (count invocations)
                          'wrapped))))))

(defn exec-command
  [on-404 cli cmd & args]
  (if-let [fun (get @commands cmd)]
    (apply fun cli args)
    (on-404 cli cmd)))

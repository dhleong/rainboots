(ns ^{:author "Daniel Leong"
      :doc "Command definitions"}
  rainboots.command
  (:require [rainboots
             [parse :refer [apply-cmd extract-command]]
             [util :refer [wrap-fn]]]))

(defonce ^:dynamic *commands* (atom {}))

;;
;; Util methods
;;

(defn- drop-obvious
  "If the argtype param is just `true`,
  drop it. Otherwise, return unaltered"
  [item]
  (if (and (= 2 (count item))
           (= true (second item)))
    (first item)
    item))

(defn build-up
  [string]
  (map
    #(subs string 0 %)
    (range 1 (inc (count string)))))

(defn cmd-meta
  "Extract meta information for a command
  function, given its var"
  [fun extra-meta]
  (let [m (meta fun)
        arglists (:arglists m)]
    (-> extra-meta
        (merge m)
        (dissoc :arglists)
        ;; TODO support the & rest-args deconstruction?
        (assoc :arg-lists
               (->> arglists
                    (map
                      #(->> %
                            (drop 1)
                            (map (comp drop-obvious first meta)))))))))

;;
;; Public interface
;;

(defmacro defcmdset
  "Declare a command set. Command sets can be pushed and popped.
   Command sets have a def'd name by which you can refer to them when
   pushing and popping.

  A typical declaration will look like:

  ```
  (defcmdset basic
    (defcmd look
      [cli]
      (send! cli \"You see some stuff\")))
  ```"
  [set-name & cmd-defs]
  `(let [my-commands# (atom {})]
     (binding [*commands* my-commands#]
       ~@cmd-defs)

     (defn ~set-name [& args#]
       (binding [*commands* my-commands#]
         (apply exec-command args#)))))

(defmacro defcmd
  "Declare a command that can be invoked from the default on-cmd
   handler. The syntax is similar to (defn), including support for
   multi-arity. Note, however, that same-arity with different argtypes
   is NOT supported.

   The cmd-name may be annotated with ^:no-abbrev if the command may
   only be executed by inputting the full name; otherwise,
   abbreviations will be generated starting from the first letter, then
   the first two letters, and so on.

   Alternatively, you may annotate with eg `^{:abbrev [\"w\"
   \"west\"]}` to explicitly declare what invocations may be used.
   Here, you *must* include the full name; rainboots will not generate
   *any* abbreviations."
  [cmd-name & decl]
  (let [has-doc? (string? (first decl))
        doc (if has-doc?
              (first decl)
              "")
        body (if has-doc?
               (next decl)
               decl)

        user-meta (meta cmd-name) ; any user-provided meta info
        no-abbrev? (:no-abbrev user-meta)
        user-invocations (:abbrev user-meta)
        fn-name (str cmd-name) ;; let's just use it directly
        fn-var (symbol fn-name)
        invoke (str cmd-name)
        invocations (cond
                      user-invocations user-invocations
                      no-abbrev? [invoke]
                      :else (build-up invoke))]

    `(let [defd-fn# (defn ~fn-var
                      ~doc
                      ~@body)

           ~'wrapped (with-meta
                       (wrap-fn ~fn-var)
                       (cmd-meta (var ~fn-var)
                                 ~user-meta))]
       (swap! *commands*
              assoc
              ~@(mapcat
                  list
                  invocations
                  (repeat (count invocations)
                          'wrapped)))
       [~(vec invocations) ~'wrapped])))

(defn exec-command
  "Attempts to execute a command from the default commands set; returns
   true on success; calls on-404 and returns false on failure"
  [on-404 can-exec? cli input]
  (let [[cmd args] (extract-command input)
        fun (get @*commands* cmd)]
    (if (and fun
             (apply-cmd can-exec? fun cli args))
      ; we found a matching command, and were able to apply the input to it
      true

      ; else, no match :(
      (do
        (on-404 cli input)
        false))))

(defn default-on-cmd
  "The default on-cmd handler; if there is anything on the input stack,
   it will delegate to that. Othewise, it will call (exec-command) with
   the default cmdset"
  [on-err on-404 can-exec? cli input]
  (try
    (if-let [handler (last (:input-stack @cli))]
      (handler on-404 can-exec? cli input)
      (exec-command on-404 can-exec? cli input))
    (catch Throwable e
      (on-err cli e))))

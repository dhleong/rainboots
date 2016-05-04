(ns ^{:author "Daniel Leong"
      :doc "Ansi color convenience"}
  rainboots.color
  (:require [clojure.string :as string]))

(def ansi-esc (String. (byte-array [27 (int \[)])))

(def ansi-codes
  {:black 30
   :red 31
   :green 32
   :yellow 33
   :blue 34
   :magenta 35
   :cyan 36
   :white 37
   ;
   :bright-black [1 30]
   :bright-red [1 31]
   :bright-green [1 32]
   :bright-yellow [1 33]
   :bright-blue [1 34]
   :bright-magenta [1 35]
   :bright-cyan [1 36]
   :bright-white [1 37]
   })

(def color-sequences
  {"n" [0 0]
   "d" [0 30] "D" [1 30]
   "r" [0 31] "R" [1 31]
   "g" [0 32] "G" [1 32]
   "y" [0 33] "Y" [1 33]
   "b" [0 34] "B" [1 34]
   "p" [0 35] "P" [1 35]
   "c" [0 36] "C" [1 36]
   "w" [0 37] "W" [1 37]
})

(defn ansi1
  [n]
  (str ansi-esc n "m"))

(defn ansi2
  [n1 n2]
  (str ansi-esc n1 ";" n2 "m"))

(def ansi-clear1
  (ansi1 39))

(def color-seq-regex
    #"\{([{ndrgybpcwNDRGYBPCW])")

(defmacro ansi-int
  "Wrap some text in ansi"
  [color & body]
  `[(ansi1 ~color) ~@body ansi-clear1])

(defmacro ansi
  "Wrap some text in ansi"
  [color-or-opt & body]
  {:pre [(contains? ansi-codes color-or-opt)]}
  (let [code (get ansi-codes color-or-opt)]
    (if (seq code)
      `[(ansi2 ~@code) ~@body ansi-clear1]
      `(ansi-int ~code ~@body))))

(defn determine-colors
  "Determine what colors the client supports
  (if any) and update the :colors field on the
  client object accordingly. If the field is
  already non-nil, this is a nop (since the
  lib user might want to handle this themself
  with a user pref, for example)"
  [cli]
  (let [{:keys [colors term-types]} @cli
        lower-types (map string/lower-case term-types)
        has-256? (some
               #(string/includes? % "256")
               lower-types)
        ansi? (some 
                #(string/includes? % "ansi")
                lower-types)]
    ;; NB don't override existing colors
    (when-not (and colors
                   (or has-256? ansi?))
      ;; TODO if we support 256, indicate availability
      (swap! cli update :colors (constantly :ansi)))))

(defn process-colors
  "Given a string, replace color sequences
  with the appropriate ansi. We use the
  sequence `{X` for colors, where X is one of:
    - n = Reset color
    - d = Dark (black) color
    - r = Red
    - g = Green
    - y = Yellow
    - b = Blue
    - p = Purple (magenta)
    - c = Cyan
    - w = White
  To get the brighter variant of a color, use
  the capital letter (eg {R for bright red, {W
  for pure white, etc). If you want a literal
  `{` character followed by one of the above
  letters, simply repeat the `{`, like `{{Woo}`."
  [input]
  (string/replace
    input
    color-seq-regex
    #(let [arg (second %)]
       (if (= "{" arg)
         "{" ;; strip the dup char
         (apply ansi2 (get color-sequences arg))))))

(defn strip-colors
  "Given a string, strip out color sequences.
  This should be used for clients that don't
  support ansi. See `process-colors` for 
  color sequences"
  [input]
  (string/replace
    input
    color-seq-regex
    #(let [arg (second %)]
       (if (= "{" arg)
         "{" ;; strip the dup char
         "")))) ;; strip the whole sequence

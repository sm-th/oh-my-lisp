(ns oml.repl
  "The built-in REPL: line reading, deterministic input classification,
  and printing, all dispatching through the shared eval surface in
  oml.kernel."
  (:require [clojure.string :as str]
            [oml.kernel :as kernel]))

(def ^:private eval-marker "/eval")

(def ^:private unhandled-message
  "unhandled natural-language input: nothing was evaluated. Lisp is
  evaluated when it starts with `(` or when it is marked with `/eval`")

(defn- eval-marker-code
  "When `s` is explicitly marked for direct eval, return the marked
  code; otherwise nil. `/eval` must stand alone or be followed by
  whitespace, so that look-alike text is not swallowed as code."
  [s]
  (when (str/starts-with? s eval-marker)
    (let [rest (subs s (count eval-marker))]
      (when (or (str/blank? rest)
                (Character/isWhitespace ^Character (first rest)))
        (str/trim rest)))))

(defn- unhandled-result
  [s]
  {:type :unhandled
   :kind :natural-language
   :input s
   :message unhandled-message})

(defn classify
  "Classify one line of REPL input.

  Deterministic rules, applied to the trimmed line:

  - blank                          -> {:type :blank}
  - starts with `(`                -> {:type :eval, :code ...}
  - `/eval` alone or followed by
    whitespace                     -> {:type :eval, :code ...} with the
                                      rest of the line as the code
  - anything else (ordinary text)  -> {:type :unhandled,
                                      :kind :natural-language, ...}

  Ordinary text is never evaluated; it is reported as unhandled until
  routing for it exists."
  [line]
  (let [s (str/trim (str line))]
    (cond
      (str/blank? s)
      {:type :blank}

      (str/starts-with? s "(")
      {:type :eval :code s}

      :else
      (if-some [code (eval-marker-code s)]
        {:type :eval :code code}
        (unhandled-result s)))))

(defn handle-line
  "Process one line of input. Returns a typed result map:

    {:type :blank}
    {:type :eval, :code code, :value value}   evaluated successfully
    {:type :eval, :code code, :error error}   evaluation failed
    {:type :unhandled, :kind :natural-language, :input s, :message m}

  Eval failures are captured as :error, never thrown, so the REPL can
  keep running."
  [line]
  (let [classified (classify line)]
    (if (= :eval (:type classified))
      (try
        (assoc classified :value (kernel/eval-string (:code classified)))
        (catch Throwable t
          (assoc classified :error t)))
      classified)))

(defn error->string
  "Describe `t` and its full cause chain, one exception per line, so the
  whole story of a failure stays visible."
  [^Throwable t]
  (loop [lines [(str (.getName (class t)) ": " (or (ex-message t) ""))]
         t t]
    (if-some [cause (.getCause t)]
      (recur (conj lines (str "caused by " (.getName (class cause)) ": "
                              (or (ex-message cause) "")))
             cause)
      (str/join "\n" lines))))

(defn print-result
  "Print the outcome of one handled line to `out`."
  [out result]
  (binding [*out* out]
    (case (:type result)
      :blank nil
      :eval (if-some [err (:error result)]
              (println "error:" (error->string err))
              (when-not (nil? (:value result))
                (prn (:value result))))
      :unhandled (println "oml:" (:message result)))))

(def prompt "oml> ")

(defn repl-loop
  "Run the built-in REPL: print a prompt, read a line from `in`, handle
  it, print the result to `out`; repeat until EOF on `in`. Eval and read
  errors are reported and the loop continues. Returns :eof."
  [in out]
  (binding [*in* in, *out* out]
    (loop []
      (print prompt)
      (flush)
      (when-some [line (read-line)]
        (print-result out (handle-line line))
        (recur)))
    :eof))

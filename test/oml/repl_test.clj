(ns oml.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.repl :as repl])
  (:import java.io.StringReader
           java.io.StringWriter
           clojure.lang.LineNumberingPushbackReader))

(defn- run-repl
  "Run the built-in REPL over `input` until EOF and return its output."
  ^String [^String input]
  (let [out (StringWriter.)]
    (repl/repl-loop (LineNumberingPushbackReader. (StringReader. input)) out)
    (str out)))

;; --- classification -------------------------------------------------------

(deftest parenthesized-input-is-direct-eval
  (is (= {:type :eval :code "(+ 1 2)"} (repl/classify "(+ 1 2)")))
  (is (= {:type :eval :code "(+ 1 2)"} (repl/classify "   (+ 1 2)  "))))

(deftest eval-marked-input-is-direct-eval
  (is (= {:type :eval :code "(+ 1 2)"} (repl/classify "/eval (+ 1 2)")))
  (is (= {:type :eval :code "(/ 6 2)"} (repl/classify "/eval\t(/ 6 2)")))
  (is (= {:type :eval :code ""} (repl/classify "/eval"))))

(deftest eval-marker-must-be-whitespace-separated
  (is (= :unhandled (:type (repl/classify "/evaluate this"))))
  (is (= :unhandled (:type (repl/classify "/help")))))

(deftest ordinary-text-is-classified-as-not-lisp
  (doseq [line ["hello there" "some-symbol" "1 + 2" "list files"]]
    (let [r (repl/classify line)]
      (is (= :unhandled (:type r)) line)
      (is (nil? (:kind r)) line)
      (is (= line (:input r)) line)
      (is (string? (:message r)) line)
      (is (str/includes? (:message r) "not Lisp") line)
      (is (str/includes? (:message r) "nothing was evaluated") line)
      (is (str/includes? (:message r) "/eval") line))))

(deftest blank-input-is-a-no-op
  (is (= {:type :blank} (repl/classify "")))
  (is (= {:type :blank} (repl/classify "   \t "))))

;; --- handling ------------------------------------------------------------

(deftest both-direct-eval-forms-reach-the-same-eval-surface
  (is (= 3 (:value (repl/handle-line "(+ 1 2)"))))
  (is (= 3 (:value (repl/handle-line "/eval (+ 1 2)")))))

(deftest ordinary-text-is-never-evaluated
  ;; `rt-unbound-symbol` would throw if it were read and evaluated.
  (is (= :unhandled (:type (repl/handle-line "rt-unbound-symbol")))))

(deftest eval-failures-are-reported-not-thrown
  (let [r (repl/handle-line "(+ 1")]
    (is (= :eval (:type r))
        "unclosed form")
    (is (nil? (:value r)))
    (is (some? (:error r)))))

(deftest the-runtime-keeps-working-after-an-eval-failure
  (repl/handle-line "(/ 1 0)")
  (is (= 3 (:value (repl/handle-line "(+ 1 2)")))))

;; --- the loop -------------------------------------------------------------

(deftest repl-loop-prints-a-prompt-and-its-results
  (let [out (run-repl "(+ 1 2)\n")]
    (is (str/starts-with? out "oml> "))
    (is (str/includes? out "3"))))

(deftest repl-loop-evaluates-until-eof
  (let [out (run-repl "(+ 1 2)\n/eval (+ 3 4)\n")]
    (is (str/includes? out "3"))
    (is (str/includes? out "7"))
    (is (not (str/includes? out "error")))))

(deftest repl-loop-reports-not-lisp-text-without-evaluating-it
  (let [out (run-repl "hello there\n")]
    (is (str/includes? out "not Lisp"))
    (is (not (str/includes? out "hello there")))))

(deftest repl-loop-survives-errors-and-keeps-reading
  (let [out (run-repl "(/ 1 0)\n(+ 1 2)\n")]
    (is (str/includes? out "error"))
    (is (str/includes? out "Divide by zero"))
    (is (str/includes? out "3"))))

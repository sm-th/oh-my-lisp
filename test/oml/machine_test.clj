(ns oml.machine-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.demo :as demo]
            [oml.machine :as m]
            [oml.store :as store]))

(defn- ev [form]
  (let [s (m/run (m/start form))]
    (is (= :done (:status s)) (pr-str (:error s)))
    (:value s)))

(defn- error-of [form]
  (let [s (m/run (m/start form))]
    (is (= :error (:status s)))
    (:error s)))

(deftest pure-evaluation
  (testing "arithmetic, let, if"
    (is (= 7 (ev '(+ 1 (* 2 3)))))
    (is (= 3 (ev '(let [a 1 b (+ a 1)] (+ a b)))))
    (is (= :no (ev '(if (< 2 1) :yes :no))))
    (is (nil? (ev '(if false 1))))
    (is (= :b (ev '(cond (= 1 2) :a (and true (or nil 1)) :b :else :c)))))
  (testing "data literals evaluate their elements"
    (is (= [2 {:k 3} #{4}] (ev '(let [x 1] [(inc x) {:k (+ x 2)} #{(+ x 3)}])))))
  (testing "fn, closures, arities, variadic, named recursion"
    (is (= 15 (ev '(let [make-adder (fn [n] (fn [x] (+ x n)))
                         add5 (make-adder 5)]
                     (add5 10)))))
    (is (= [1 3] (ev '(let [f (fn ([x] x) ([x y] (+ x y)))] [(f 1) (f 1 2)]))))
    (is (= [1 '(2 3)] (ev '((fn [x & more] [x more]) 1 2 3))))
    (is (= 55 (ev '((fn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) 10))))
    (is (= 3 (ev '(let [[a b] [1 2]] (+ a b)))))
    (is (= [1 '(2 3) 4] (ev '(let [[a & r] [1 2 3] {:keys [k]} {:k 4}] [a r k])))))
  (testing "loop/recur in loop and fn, without growing the stack"
    (is (= 3628800 (ev '(loop [n 10 acc 1] (if (zero? n) acc (recur (dec n) (* acc n)))))))
    (is (= 0 (ev '((fn [n] (if (zero? n) n (recur (dec n)))) 10000))))
    (let [s (m/run (m/start '(loop [n 1000] (if (zero? n) :ok (recur (dec n))))))]
      (is (= :ok (:value s)))
      (is (empty? (:kont s)))))
  (testing "process-language doseq/dotimes"
    (is (= "1\n2\n" (with-out-str (ev '(doseq [x [1 2]] (println x))))))
    (is (= "0\n1\n2\n" (with-out-str (ev '(dotimes [i 3] (println i)))))))
  (testing "pure closures may be passed to natives"
    (is (= [2 4 6] (ev '(mapv (fn [x] (* x 2)) [1 2 3]))))
    (is (= 6 (ev '(reduce + 0 [1 2 3]))))
    (is (= [1 2] (ev '(map :a [{:a 1} {:a 2}]))))))

(def answers ["yes" "no" "maybe"])

(defn- drive
  "Run the demo program, answering each `ask` from `answers`; `persist`
  transforms the state at every suspension."
  [persist]
  (loop [s (m/run (m/start demo/program)) [a & more] answers]
    (if (= :suspended (:status s))
      (recur (m/resume (persist s) a) more)
      s)))

(deftest suspend-and-resume
  (let [first-stop (m/run (m/start demo/program))]
    (is (= :suspended (:status first-stop)))
    (is (= {:op 'ask :args ["proceed with?" "step-1"]} (:await first-stop))))
  (let [file (io/file (System/getProperty "java.io.tmpdir") (str "oml-test-" (random-uuid) ".edn"))
        path (str file)
        in-memory (binding [*out* (java.io.StringWriter.)] (drive identity))
        via-edn (binding [*out* (java.io.StringWriter.)]
                  (drive (fn [s] (store/save! path s) (store/load path))))]
    (io/delete-file file true)
    (is (= :done (:status in-memory) (:status via-edn)))
    (is (= [["step-1" "yes"] ["step-2" "no"] ["step-3" "maybe"]]
           (:value in-memory)
           (:value via-edn)))))

(deftest vocabulary-is-deny-by-default
  (let [err (error-of '(slurp "/etc/passwd"))]
    (is (= :unknown-symbol (:rule err)))
    (is (str/includes? (:message err) "Unknown symbol: slurp")))
  (is (= :unknown-symbol (:rule (error-of '(let [x 1] (+ x y))))))
  (testing "forged native references are still checked against the whitelist"
    (is (= :unknown-symbol (:rule (error-of '({:oml/native slurp} "/etc/passwd"))))))
  (is (= :unsupported-form (:rule (error-of '(for [x [1 2]] x))))))

(deftest boundary-rule
  (let [err (error-of demo/boundary-program)]
    (is (= :boundary (:rule err)))
    (is (str/starts-with? (:message err) "Boundary rule: native `map`"))
    (is (str/includes? (:message err) "effect `ask`"))))

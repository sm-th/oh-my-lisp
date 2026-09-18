(ns oml-test
  (:require [clojure.test :refer [deftest is testing run-tests]]))

;; Load the launcher without running its -main (guarded by babashka.file).
(load-file "oml")

(deftest model-selection
  (testing "auto / nil / blank leave the model to the harness"
    (is (= ["omp"] (oml.core/harness-cmd {:harness :omp} {} [])))
    (is (= ["omp"] (oml.core/harness-cmd {:harness :omp :model "auto"} {} [])))
    (is (= ["omp"] (oml.core/harness-cmd {:harness :omp :model ""} {} []))))
  (testing "a concrete model becomes --model"
    (is (= ["omp" "--model" "opus"] (oml.core/harness-cmd {:harness :omp :model "opus"} {} [])))))

(deftest system-prompt-injection
  (testing "omp/pi get --append-system-prompt when a file is provided"
    (is (= ["omp" "--append-system-prompt" "/x/agent.md"]
           (oml.core/harness-cmd {:harness :omp} {:system-prompt-file "/x/agent.md"} [])))
    (is (= ["omp" "pi" "--append-system-prompt" "/x/agent.md"]
           (oml.core/harness-cmd {:harness :pi} {:system-prompt-file "/x/agent.md"} []))))
  (testing "fx does not (no such flag)"
    (is (= ["fx"] (oml.core/harness-cmd {:harness :fx} {:system-prompt-file "/x/agent.md"} [])))))

(deftest arg-order
  (testing "base, model, system-prompt, config :args, then user args"
    (is (= ["omp" "--model" "opus" "--append-system-prompt" "/x" "--allow-home" "-p" "hi"]
           (oml.core/harness-cmd {:harness :omp :model "opus" :args ["--allow-home"]}
                                 {:system-prompt-file "/x"} ["-p" "hi"])))))

(deftest harness-keys
  (testing "harness may be a keyword or a string"
    (is (= ["omp"] (oml.core/harness-cmd {:harness :omp} {} [])))
    (is (= ["omp"] (oml.core/harness-cmd {:harness "omp"} {} []))))
  (testing "unknown harness throws"
    (is (thrown? clojure.lang.ExceptionInfo (oml.core/harness-cmd {:harness :nope} {} [])))))

(deftest config-extraction
  (testing "a fenced edn block is parsed"
    (is (= {:harness :omp :model "auto" :args []}
           (oml.core/extract-config "Sure!\n```edn\n{:harness :omp :model \"auto\" :args []}\n```\nDone."))))
  (testing "a raw map with :harness is parsed"
    (is (= {:harness :fx} (oml.core/extract-config "{:harness :fx}"))))
  (testing "prose without a config yields nil"
    (is (nil? (oml.core/extract-config "Which harness would you like — omp or fx?"))))
  (testing "a map without :harness is rejected"
    (is (nil? (oml.core/extract-config "```edn\n{:model \"auto\"}\n```")))))

(deftest llm-creds-resolution
  (testing "config :openai supplies creds"
    (is (= {:base-url "http://x/v1" :api-key "k" :model "auto"}
           (oml.core/llm-creds {:openai {:base-url "http://x/v1" :api-key "k"}}))))
  (testing "no creds -> nil"
    (is (nil? (oml.core/llm-creds {})))))

(let [{:keys [fail error]} (run-tests 'oml-test)]
  (when (pos? (+ fail error)) (System/exit 1)))

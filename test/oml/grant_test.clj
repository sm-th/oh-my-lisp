(ns oml.grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [oml.grant :as grant]))

(defn- restricted-context
  ([] (restricted-context {}))
  ([overrides]
   (grant/build
    (merge {:vocab {'add +
                    'triple #(* 3 %)}
            :docs {'add "Add numbers."
                   'triple "Multiply a number by three."
                   'not-granted "Must not be disclosed."}
            :context {:mode :restricted}}
           overrides))))

(deftest granted-operations-execute
  (let [ctx (restricted-context)]
    (is (= 6 (grant/eval-string ctx "(add 1 2 3)")))
    (is (= 21 (grant/eval-string ctx "(triple 7)")))))

(deftest grant-introspection-is-limited-to-explicit-data
  (let [ctx (restricted-context)]
    (is (= {:mode :restricted}
           (grant/eval-string ctx "(context)")))
    (is (= {'add "Add numbers."
            'triple "Multiply a number by three."}
           (grant/eval-string ctx "(tools)")))))

(deftest file-io-is-denied
  (let [ctx (restricted-context)]
    (testing "reads"
      (is (thrown? Throwable
                   (grant/eval-string ctx "(slurp \"deps.edn\")"))))
    (testing "writes"
      (is (thrown? Throwable
                   (grant/eval-string ctx "(spit \"grant-test-output\" \"no\")"))))))

(deftest shell-and-process-access-is-denied
  (let [ctx (restricted-context)]
    (testing "shell namespaces"
      (is (thrown? Throwable
                   (grant/eval-string
                    ctx
                    "(require '[clojure.java.shell :as shell]) (shell/sh \"echo\" \"no\")"))))
    (testing "process classes"
      (is (thrown? Throwable
                   (grant/eval-string
                    ctx
                    "(.start (ProcessBuilder. [\"echo\" \"no\"]))"))))))

(deftest jvm-interop-is-denied
  (is (thrown? Throwable
               (grant/eval-string (restricted-context)
                                  "(System/getProperty \"user.home\")"))))

(deftest ungranted-namespace-access-is-denied
  (is (thrown? Throwable
               (grant/eval-string
                (restricted-context)
                "(require '[oml.kernel :as kernel]) (kernel/eval-string \"(+ 1 2)\")"))))

(deftest separate-grants-do-not-share-interpreter-state
  (let [first-context (restricted-context)
        second-context (restricted-context)]
    (is (= 1 (grant/eval-string
              first-context
              "(def private-state (atom 0)) (swap! private-state inc)")))
    (is (thrown? Throwable
                 (grant/eval-string second-context "private-state")))
    (is (= 2 (grant/eval-string first-context
                                "(swap! private-state inc)")))))

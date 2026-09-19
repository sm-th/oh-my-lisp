(ns oml.kernel-test
  (:require [clojure.test :refer [deftest is]]
            [oml.kernel :as kernel])
  (:import java.io.File))

(defn- tmp-init-file
  "Write `sources` (one form per string) to a fresh temp init file."
  (^File [^String name & sources]
   (let [f (File/createTempFile name ".clj")]
     (spit f (clojure.string/join "\n" sources))
     (.deleteOnExit f)
     f)))

(deftest eval-string-evaluates-plain-clojure
  (is (= 3 (kernel/eval-string "(+ 1 2)"))))

(deftest eval-string-returns-the-last-forms-value
  (is (= 11 (kernel/eval-string "(def kt-a 1) (+ kt-a 10)"))))

(deftest runtime-state-persists-across-separate-evals
  (kernel/eval-string "(def kt-persist 40)")
  (is (= 42 (kernel/eval-string "(+ kt-persist 2)"))))

(deftest ensure-runtime-is-idempotent
  (is (= (kernel/ensure-runtime) (kernel/ensure-runtime))))

(deftest init-state-is-visible-to-later-direct-eval
  (let [f (tmp-init-file "kt-init" "(def kt-from-init 7)")]
    (kernel/load-init (.getPath f))
    (is (= 8 (kernel/eval-string "(inc kt-from-init)")))))

(deftest init-is-evaluated-exactly-once
  (let [f (tmp-init-file "kt-once"
                         "(defonce kt-boot-count (atom []))"
                         "(swap! kt-boot-count conj :init)")]
    (kernel/load-init (.getPath f))
    (is (= 1 (kernel/eval-string "(count @kt-boot-count)")))))
(deftest missing-init-file-fails-visibly
  (is (thrown-with-msg? Exception #"init file not found: .*/kt-missing\.clj"
                        (kernel/load-init "/no/such/dir/kt-missing.clj"))))

(deftest invalid-init-fails-with-file-context-and-cause
  (let [f (tmp-init-file "kt-bad" "(def kt-broken (/ 1 0))")]
    (try
      (kernel/load-init (.getPath f))
      (is false "invalid init must fail")
      (catch clojure.lang.ExceptionInfo e
        (is (= (.getPath f) (:init-file (ex-data e))))
        (is (= :init (:oml/error (ex-data e))))
        (is (some? (.getCause e)))))))

(ns oml.kernel-test
  (:require [clojure.test :refer [deftest is]]
            [oml.kernel :as kernel])
  (:import java.io.File))

(defn- tmp-lisp-file
  "Write `sources` (one form per string) to a fresh temp Lisp file."
  (^File [^String name & sources]
   (let [f (File/createTempFile name ".clj")]
     (spit f (clojure.string/join "\n" sources))
     (.deleteOnExit f)
     f)))

(defn- tmp-path
  "Return the path of a fresh temp file with `suffix` that does not yet
  exist, so a save operation can create it, following the
  `deleteOnExit` fixture pattern."
  [^String name ^String suffix]
  (let [f (File/createTempFile name suffix)]
    (.delete f)
    (.deleteOnExit f)
    (.getPath f)))

(defn- fresh-boot!
  "Discard the shared runtime namespace and recreate it empty,
  simulating a fresh process boot within this JVM: no definition
  survives unless it was explicitly saved to a file and re-evaluated."
  []
  (remove-ns kernel/runtime-ns)
  (kernel/ensure-runtime))

(deftest eval-string-evaluates-plain-clojure
  (is (= 3 (kernel/eval-string "(+ 1 2)"))))

(deftest eval-string-returns-the-last-forms-value
  (is (= 11 (kernel/eval-string "(def kt-a 1) (+ kt-a 10)"))))

(deftest runtime-state-persists-across-separate-evals
  (kernel/eval-string "(def kt-persist 40)")
  (is (= 42 (kernel/eval-string "(+ kt-persist 2)"))))

(deftest ensure-runtime-is-idempotent
  (is (= (kernel/ensure-runtime) (kernel/ensure-runtime))))

;; --- evaluate-file -----------------------------------------------------

(deftest evaluate-file-evaluates-every-form-into-the-shared-namespace
  (let [f (tmp-lisp-file "kt-eval" "(def kt-from-file 7)")]
    (kernel/evaluate-file (.getPath f))
    (is (= 8 (kernel/eval-string "(inc kt-from-file)")))))

(deftest evaluate-file-returns-the-last-forms-value
  (let [f (tmp-lisp-file "kt-eval-last" "(def kt-eval-a 1)" "(+ kt-eval-a 10)")]
    (is (= 11 (kernel/evaluate-file (.getPath f))))))

(deftest missing-file-fails-visibly
  (is (thrown-with-msg? Exception #"file not found: .*/kt-missing\.clj"
                        (kernel/evaluate-file "/no/such/dir/kt-missing.clj"))))

(deftest invalid-file-fails-with-file-context-and-cause
  (let [f (tmp-lisp-file "kt-bad" "(def kt-broken (/ 1 0))")]
    (try
      (kernel/evaluate-file (.getPath f))
      (is false "an evaluation error must fail")
      (catch clojure.lang.ExceptionInfo e
        (is (= (.getPath f) (:file (ex-data e))))
        (is (= :evaluate-file (:oml/error (ex-data e))))
        (is (some? (.getCause e)))))))

;; --- save-forms / save-and-evaluate --------------------------------------

(deftest save-forms-writes-a-file-evaluate-file-can-restore
  (let [path (tmp-path "kt-save" ".clj")]
    (kernel/save-forms path ['(def kt-saved-def 5) '(def kt-saved-other :ok)])
    (kernel/evaluate-file path)
    (is (= 5 (kernel/eval-string "kt-saved-def")))
    (is (= :ok (kernel/eval-string "kt-saved-other")))))

(deftest save-forms-overwrites-an-existing-file
  (let [path (tmp-path "kt-save-overwrite" ".clj")]
    (kernel/save-forms path ['(def kt-overwrite-a 1)])
    (kernel/save-forms path ['(def kt-overwrite-a 2)])
    (kernel/evaluate-file path)
    (is (= 2 (kernel/eval-string "kt-overwrite-a")))))

(deftest save-and-evaluate-saves-and-evaluates-in-one-call
  (let [path (tmp-path "kt-save-eval" ".clj")]
    (is (= 9 (kernel/save-and-evaluate
              path ['(def kt-save-eval-a 4) '(+ kt-save-eval-a 5)])))
    (is (= 9 (kernel/eval-string "(+ kt-save-eval-a 5)")))
    (is (.isFile (File. ^String path)) "save-and-evaluate leaves the file behind")))

;; --- save-data / load-data (EDN) -----------------------------------------

(deftest save-data-and-load-data-round-trip-edn
  (let [path (tmp-path "kt-data" ".edn")
        value {:name "kt" :tags #{:a :b} :counts [1 2 3]}]
    (kernel/save-data path value)
    (is (= value (kernel/load-data path)))))

(deftest load-data-missing-file-fails-visibly
  (is (thrown-with-msg? Exception #"data file not found: .*/kt-missing\.edn"
                        (kernel/load-data "/no/such/dir/kt-missing.edn"))))

(deftest load-data-invalid-edn-fails-with-file-context-and-cause
  (let [f (File/createTempFile "kt-bad-data" ".edn")]
    (spit f "{:unterminated")
    (.deleteOnExit f)
    (try
      (kernel/load-data (.getPath f))
      (is false "invalid EDN must fail")
      (catch clojure.lang.ExceptionInfo e
        (is (= (.getPath f) (:file (ex-data e))))
        (is (= :load-data (:oml/error (ex-data e))))
        (is (some? (.getCause e)))))))

;; --- explicit-file persistence across a fresh boot ------------------------

(deftest a-definition-saved-to-a-file-survives-a-fresh-boot
  (let [path (tmp-path "kt-survive" ".clj")]
    (kernel/save-forms path ['(def kt-survives-boot 99)])
    (kernel/evaluate-file path)
    (fresh-boot!)
    (kernel/evaluate-file path)
    (is (= 99 (kernel/eval-string "kt-survives-boot")))))

(deftest an-unsaved-live-definition-does-not-survive-a-fresh-boot
  (kernel/eval-string "(def kt-unsaved-def 1)")
  (fresh-boot!)
  (is (thrown? Exception (kernel/eval-string "kt-unsaved-def"))))

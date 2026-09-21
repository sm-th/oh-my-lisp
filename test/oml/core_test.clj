(ns oml.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.core :as core]
            [oml.kernel :as kernel])
  (:import java.io.File
           java.io.StringReader
           java.io.StringWriter
           clojure.lang.LineNumberingPushbackReader))

(defn- boot-with
  "Boot oml over `input` with the optional init file at `init-path`;
  return {:result boot-result :out repl-output}."
  [^String input init-path]
  (let [out (StringWriter.)]
    {:result (core/boot (LineNumberingPushbackReader. (StringReader. input))
                        out init-path)
     :out    (str out)}))

(defn- tmp-init-file
  "Write `sources` (one form per string) to a fresh temp init file."
  (^File [^String name & sources]
   (let [f (File/createTempFile name ".clj")]
     (spit f (clojure.string/join "\n" sources))
     (.deleteOnExit f)
     f)))

(defn- fresh-boot!
  "Discard the shared runtime namespace and recreate it empty,
  simulating a fresh process boot within this JVM: no definition
  survives unless it was explicitly saved to a file and re-evaluated."
  []
  (remove-ns kernel/runtime-ns)
  (kernel/ensure-runtime))

(deftest boot-without-init-presents-the-built-in-repl
  (let [{:keys [result out]} (boot-with "(+ 1 2)\n" nil)]
    (is (= 0 (:exit result)))
    (is (str/starts-with? out "oml> "))
    (is (str/includes? out "3"))))

(deftest valid-configuration-runs-once-before-the-built-in-repl
  (let [f (tmp-init-file "ct-valid"
                         "(defonce ct-valid-boot-count (atom []))"
                         "(swap! ct-valid-boot-count conj :boot)"
                         "(def ct-valid-value 10)")
        {:keys [result out]} (boot-with "(inc ct-valid-value)\n" (.getPath f))]
    (is (= 0 (:exit result)))
    (is (= 1 (kernel/eval-string "(count @ct-valid-boot-count)"))
        "configuration is evaluated exactly once per boot")
    (is (str/starts-with? out "oml> ")
        "the built-in REPL follows a configuration that returns")
    (is (str/includes? out "11")
        "configuration state is visible to the built-in REPL")))

(deftest blocking-configuration-takes-over-until-it-returns
  (let [client-started (promise)
        client-release (promise)
        _ (intern (kernel/ensure-runtime) 'ct-client-started client-started)
        _ (intern (kernel/ensure-runtime) 'ct-client-release client-release)
        f (tmp-init-file "ct-blocking-client"
                         "(deliver ct-client-started true)"
                         "@ct-client-release")
        out (StringWriter.)
        in (LineNumberingPushbackReader. (StringReader. ""))
        boot-result (future (core/boot in out (.getPath f)))]
    (try
      (is (= true (deref client-started 1000 false))
          "configuration entered its blocking client loop")
      (is (= "" (str out))
          "the built-in REPL does not start while configuration is blocking")
      (finally
        (deliver client-release true)))
    (is (= {:exit 0} (deref boot-result 1000 ::timeout)))
    (is (str/starts-with? (str out) "oml> ")
        "the built-in REPL starts after configuration returns")))

(deftest failing-configuration-is-reported-and-the-recovery-repl-stays-available
  (let [f (tmp-init-file "ct-bad" "(def ct-never 1\n")
        {:keys [result out]} (boot-with "(+ 1 2)\n" (.getPath f))]
    (is (= 0 (:exit result))
        "a failing configuration does not abort the boot")
    (is (str/includes? (:init-failure result) (str "failed to load init file "
                                                    (.getPath f))))
    (is (str/starts-with? out (str "oml: failed to load init file " (.getPath f)))
        "the failure is reported first, on the same stream as the recovery REPL")
    (is (str/includes? out "oml> ")
        "the plain REPL is offered instead of aborting the process")
    (is (str/includes? out "3")
        "the recovery REPL evaluates further input")))

(deftest missing-init-file-is-reported-and-the-recovery-repl-stays-available
  (let [{:keys [result out]} (boot-with "(+ 1 2)\n" "/no/such/dir/ct-init.clj")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:init-failure result) "/no/such/dir/ct-init.clj"))
    (is (str/starts-with? out "oml: failed to load init file /no/such/dir/ct-init.clj"))
    (is (str/includes? out "oml> "))
    (is (str/includes? out "3"))))

;; --- command-line argument parsing -----------------------------------------

(deftest no-arguments-parse-to-no-init-path
  (is (= {:init-path nil} (core/parse-args []))))

(deftest an-init-file-argument-parses-to-that-path
  (is (= {:init-path "some/config.clj"} (core/parse-args ["some/config.clj"]))))

(deftest no-init-flag-parses-to-no-init-path
  (is (= {:init-path nil} (core/parse-args ["--no-init"]))))

(deftest no-init-flag-overrides-any-given-init-file-argument
  (is (= {:init-path nil} (core/parse-args ["--no-init" "some/config.clj"]))))

(deftest unsupported-argument-shapes-are-a-usage-error
  (is (= {:usage-error true} (core/parse-args ["a" "b" "c"])))
  (is (= {:usage-error true} (core/parse-args ["some/config.clj" "extra"]))))

(deftest no-init-flag-boots-the-plain-repl-without-loading-configuration
  (let [f (tmp-init-file "ct-no-init" "(def ct-no-init-var 99)")
        {:keys [init-path]} (core/parse-args ["--no-init" (.getPath f)])
        {:keys [result out]} (boot-with "(+ 1 2)\n" init-path)]
    (is (nil? init-path))
    (is (= 0 (:exit result)))
    (is (str/starts-with? out "oml> "))
    (is (str/includes? out "3"))
    (is (thrown? Exception (kernel/eval-string "ct-no-init-var"))
        "the configuration was never loaded")))

;; --- explicit-file persistence at startup ----------------------------------

(deftest a-definition-saved-to-a-file-is-present-after-a-fresh-boot
  (let [path (.getPath (doto (File/createTempFile "ct-persist" ".clj")
                          .delete
                          .deleteOnExit))]
    (kernel/save-forms path ['(def ct-persisted-def 41)])
    (fresh-boot!)
    (let [{:keys [result]} (boot-with "" path)]
      (is (= 0 (:exit result)))
      (is (nil? (:init-failure result)))
      (is (= 42 (kernel/eval-string "(inc ct-persisted-def)"))
          "the saved definition, evaluated at startup, is visible to later direct eval"))))

(deftest an-unsaved-live-definition-does-not-survive-a-fresh-boot
  (kernel/eval-string "(def ct-unsaved-def 7)")
  (fresh-boot!)
  (let [{:keys [result out]} (boot-with "(inc ct-unsaved-def)\n" nil)]
    (is (= 0 (:exit result)))
    (is (str/includes? out "Unable to resolve symbol")
        "the fresh boot has no memory of the unsaved definition")))

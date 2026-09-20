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

(deftest boot-without-init-presents-the-built-in-repl
  (let [{:keys [result out]} (boot-with "(+ 1 2)\n" nil)]
    (is (= 0 (:exit result)))
    (is (str/starts-with? out "oml> "))
    (is (str/includes? out "3"))))

(deftest valid-configuration-runs-once-and-decides-not-to-start-anything
  (let [f (tmp-init-file "ct-decide-nothing"
                         "(defonce ct-decide-boot-count (atom []))"
                         "(swap! ct-decide-boot-count conj :boot)"
                         "(def ct-decide-value 10)")
        {:keys [result out]} (boot-with "(inc ct-decide-value)\n" (.getPath f))]
    (is (= 0 (:exit result)))
    (is (= 1 (kernel/eval-string "(count @ct-decide-boot-count)"))
        "configuration is evaluated exactly once per boot")
    (is (= "" out)
        "boot never starts the REPL on its own; a configuration that starts
        nothing leaves the REPL input untouched")))

(deftest valid-configuration-can-start-the-built-in-repl-on-the-boot-streams
  (let [f (tmp-init-file "ct-decide-repl"
                         "(defonce ct-repl-boot-count (atom []))"
                         "(swap! ct-repl-boot-count conj :boot)"
                         "(def ct-repl-value 10)"
                         "(require '[oml.repl :as repl])"
                         "(repl/repl-loop *in* *out*)")
        {:keys [result out]} (boot-with "(inc ct-repl-value)\n" (.getPath f))]
    (is (= 0 (:exit result)))
    (is (str/starts-with? out "oml> ")
        "configuration started the REPL on boot's own input/output streams")
    (is (str/includes? out "11")
        "configuration state is visible to the REPL it started")
    (is (= 1 (kernel/eval-string "(count @ct-repl-boot-count)"))
        "configuration is evaluated exactly once per boot")))

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

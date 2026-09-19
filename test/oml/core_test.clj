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

(deftest init-runs-once-before-the-repl-and-shares-its-state
  (let [f (tmp-init-file "ct-init"
                         "(defonce ct-boot-count (atom []))"
                         "(swap! ct-boot-count conj :boot)"
                         "(def ct-value 10)")
        {:keys [result out]} (boot-with "(inc ct-value)\n" (.getPath f))]
    (is (= 0 (:exit result)))
    (is (str/includes? out "11")
        "init state is visible to direct eval in the REPL")
    (is (= 1 (kernel/eval-string "(count @ct-boot-count)"))
        "init is evaluated exactly once per boot")))

(deftest invalid-init-aborts-boot-before-the-repl-starts
  (let [f (tmp-init-file "ct-bad" "(def ct-never 1\n")
        {:keys [result out]} (boot-with "(+ 1 2)\n" (.getPath f))]
    (is (= 1 (:exit result)))
    (is (str/includes? (:message result) (str "failed to load init file "
                                              (.getPath f))))
    (is (= "" out)
        "the REPL must not start when init fails")))

(deftest missing-init-file-aborts-boot
  (let [{:keys [result out]} (boot-with "(+ 1 2)\n" "/no/such/dir/ct-init.clj")]
    (is (= 1 (:exit result)))
    (is (str/includes? (:message result) "/no/such/dir/ct-init.clj"))
    (is (= "" out))))

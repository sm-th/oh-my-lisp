(ns oml.agent-test
  (:refer-clojure :exclude [send])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.agent :as agent]
            [oml.grant :as grant])
  (:import (java.nio.file Files Path)))

(defn- temp-directory [prefix]
  (str (Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fake-command
  ([] (fake-command nil))
  ([capture]
   (cond-> [(str (System/getProperty "java.home") "/bin/java")
            "-cp" (System/getProperty "java.class.path")
            "clojure.main" "-m" "oml.fake-omp"]
     capture (into ["--capture" capture]))))

(defn- options
  ([] (options nil))
  ([capture]
   (let [cwd (temp-directory "oml-agent-cwd-")]
     {:command (fake-command capture)
      :cwd cwd
      :session-dir (str (io/file cwd "sessions"))
      :model "fake/model"
      :thinking :low
      :timeout-ms 5000})))

(defn- captured [path]
  (mapv #(json/read-str % :key-fn keyword)
        (str/split-lines (slurp path))))

(deftest session-lifecycle-and-structured-run
  (let [session (agent/open (options))]
    (try
      (is (agent/session? session))
      (is (= {:session-id "stable-session"
              :session-file "/fake/native-session.jsonl"}
             (agent/session-info session)))
      (let [run (agent/send session "basic")
            event (agent/next-event run 5000)
            result (agent/result run)]
        (is (agent/run? run))
        (is (= "agent_start" (:type event)))
        (is (= :completed (:status result)))
        (is (= "BASIC_OK" (:text result)))
        (is (= "agent_end" (get-in result [:event :type])))
        (is (= (agent/observed-events run) (:events result))))
      (is (= {:exit-code 0 :stderr ""} (agent/close session)))
      (is (= {:exit-code 0 :stderr ""} (agent/close session)))
      (finally
        (agent/close session)))))

(deftest ask-blocks-for-final-assistant-result
  (let [session (agent/open (options))]
    (try
      (is (= {:status :completed :text "BASIC_OK"}
             (select-keys (agent/ask session "basic") [:status :text])))
      (finally
        (agent/close session)))))

(deftest active-prompts-use-omp-native-queue
  (let [capture (str (io/file (temp-directory "oml-agent-capture-") "rpc.jsonl"))
        session (agent/open (options capture))]
    (try
      (let [run (agent/send session "queue-primary")
            _ (is (= "agent_start" (:type (agent/next-event run 5000))))
            queued-run (agent/send session "queue-follow-up" {:active :follow-up})
            result (agent/result queued-run)
            prompt-frames (filter #(= "prompt" (:type %)) (captured capture))]
        (is (= run queued-run))
        (is (= "FOLLOWUP_DONE" (:text result)))
        (is (= [nil "followUp"] (mapv :streamingBehavior prompt-frames)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"no active OMP run"
                              (agent/send session "too-late" {:active :steer}))))
      (finally
        (agent/close session)))))

(deftest active-run-must-choose-native-behavior
  (let [session (agent/open (options))]
    (try
      (let [run (agent/send session "queue-primary")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"choose :follow-up or :steer"
                              (agent/send session "ambiguous")))
        (agent/cancel run)
        (agent/result run))
      (finally
        (agent/close session)))))

(deftest cancellation-uses-abort-and-observes-terminal-run
  (let [session (agent/open (options))]
    (try
      (let [run (agent/send session "cancel")]
        (is (= {:accepted true} (agent/cancel run)))
        (let [result (agent/result run)]
          (is (= :aborted (:status result)))
          (is (some #(= "host_tool_cancel" (:type %)) (:events result)))))
      (finally
        (agent/close session)))))

(deftest resume-is-an-explicit-native-session-launch
  (let [capture (str (io/file (temp-directory "oml-resume-capture-") "rpc.jsonl"))
        session (agent/resume (options capture) "/saved/native.jsonl")]
    (try
      (is (= {:session-id "stable-session"
              :session-file "/saved/native.jsonl"}
             (agent/session-info session)))
      (let [launch (:launch (first (captured capture)))
            resume-index (.indexOf launch "--resume")]
        (is (pos? resume-index))
        (is (= "/saved/native.jsonl" (nth launch (inc resume-index)))))
      (finally
        (agent/close session)))))

(deftest native-tool-selection-is-fixed-at-launch
  (testing "an explicit file-tool set is one --tools launch value"
    (let [capture (str (io/file (temp-directory "oml-tools-capture-") "rpc.jsonl"))
          session (agent/open (assoc (options capture)
                                     :native-tools [:read :write :edit :glob]))]
      (try
        (let [launch (:launch (first (captured capture)))
              tools-index (.indexOf launch "--tools")]
          (is (pos? tools-index))
          (is (= "read,write,edit,glob" (nth launch (inc tools-index))))
          (is (not-any? #{"--no-tools"} launch)))
        (finally
          (agent/close session)))))
  (testing "the default grants no native OMP tools"
    (let [capture (str (io/file (temp-directory "oml-no-tools-capture-") "rpc.jsonl"))
          session (agent/open (options capture))]
      (try
        (let [launch (:launch (first (captured capture)))]
          (is (some #{"--no-tools"} launch))
          (is (not-any? #{"--tools"} launch)))
        (finally
          (agent/close session))))))

(deftest grant-backed-live-eval-is-opt-in
  (let [capture (str (io/file (temp-directory "oml-eval-capture-") "rpc.jsonl"))
        value (atom 41)
        restricted (grant/build {:vocab {'tick #(swap! value inc)}})
        session (agent/open (assoc (options capture) :grant restricted))]
    (try
      (is (= "42" (:text (agent/ask session "eval"))))
      (is (= 42 @value))
      (let [registration (some #(when (= "set_host_tools" (:type %)) %) (captured capture))]
        (is (= ["clojure_eval"] (mapv :name (:tools registration)))))
      (finally
        (agent/close session))))
  (testing "without a grant there is no ambient eval registration"
    (let [capture (str (io/file (temp-directory "oml-no-eval-capture-") "rpc.jsonl"))
          session (agent/open (options capture))]
      (try
        (is (nil? (some #(when (= "set_host_tools" (:type %)) %)
                        (captured capture))))
        (finally
          (agent/close session))))))

(deftest chunked-v2-result-is-reassembled
  (let [session (agent/open (options))]
    (try
      (is (= "CHUNK_OK" (:text (agent/ask session "chunk"))))
      (finally
        (agent/close session)))))

(deftest command-failures-preserve-omp-data
  (let [session (agent/open (options))]
    (try
      (let [failure (try
                      (agent/send session "command-error")
                      nil
                      (catch clojure.lang.ExceptionInfo cause cause))]
        (is (= :command-failed (:kind (ex-data failure))))
        (is (= "fake_rejection" (:code (ex-data failure))))
        (is (= "rejected" (get-in (ex-data failure) [:response :error]))))
      (finally
        (agent/close session)))))

(deftest protocol-errors-are-visible
  (let [session (agent/open (options))]
    (try
      (let [run (agent/send session "protocol-error")
            failure (try
                      (agent/result run)
                      nil
                      (catch clojure.lang.ExceptionInfo cause cause))]
        (is (= :protocol (:kind (ex-data failure)))))
      (finally
        (try
          (agent/close session)
          (catch clojure.lang.ExceptionInfo _))))))

(deftest unexpected-child-exit-is-visible
  (let [session (agent/open (options))]
    (let [run (agent/send session "unexpected-exit")
          failure (try
                    (agent/result run)
                    nil
                    (catch clojure.lang.ExceptionInfo cause cause))]
      (is (= :unexpected-exit (:kind (ex-data failure))))
      (is (= 7 (:exit-code (ex-data failure)))))))

(deftest installed-omp-smoke-boundary
  (when (= "1" (System/getenv "OML_OMP_SMOKE"))
    (let [cwd (temp-directory "oml-installed-omp-")
          session (agent/open {:cwd cwd
                               :session-dir (str (io/file cwd "sessions"))
                               :native-tools []
                               :timeout-ms 30000})]
      (is (string? (:session-id (agent/session-info session))))
      (is (zero? (:exit-code (agent/close session)))))))

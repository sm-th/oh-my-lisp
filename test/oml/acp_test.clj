(ns oml.acp-test
  "Behavior tests for the oml.acp SDK wrapper.

  Every test launches a real fake ACP subprocess and exercises the wrapper
  through its public interface: connect, capabilities, close. The tests do not
  assert against SDK internals; they verify the outcomes and error categories
  the wrapper owns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.acp :as acp])
  (:import [java.io File]
           [java.lang ProcessHandle]))

(defn- java-binary
  "Return the path to the current JVM executable."
  []
  (str (System/getProperty "java.home") "/bin/java"))

(defn- classpath
  "Return the classpath of the current process so the subprocess can load the
  same sources and dependencies."
  []
  (System/getProperty "java.class.path"))

(defn- fake-agent-args
  "Build the argument vector for the fake ACP agent subprocess."
  [behavior]
  ["-cp" (classpath)
   "clojure.main" "-m" "oml.acp-fake"
   behavior])

(defn- connect-fake
  "Connect to the fake agent with the given behavior."
  ([behavior]
   (connect-fake behavior nil))
  ([behavior env]
   (acp/connect (java-binary) (fake-agent-args behavior) env)))

(defn- process-alive? [pid-str]
  (boolean
   (when-let [pid (try (Long/parseLong (str/trim pid-str)) (catch Throwable _))]
     (when-let [handle (ProcessHandle/of pid)]
       (.isPresent handle)))))

(defn- wait-for-process-death
  "Return true if the process whose PID is in `pid-file` is still alive after
  waiting up to `timeout-ms` milliseconds."
  [^File pid-file timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [pid (when (.isFile pid-file) (slurp pid-file))
            alive? (process-alive? pid)]
        (if (and alive? (< (System/currentTimeMillis) deadline))
          (do (Thread/sleep 200)
              (recur))
          alive?)))))

(deftest connect-negotiates-v1-and-exposes-capabilities
  (let [conn (connect-fake "ok")
        caps (acp/capabilities conn)]
    (is (= 1 (:protocol-version caps)))
    (is (:load-session caps))
    (is (:list-sessions caps))
    (is (:close-session caps))
    (is (:resume-session caps))
    (is (:delete-session caps))
    (is (:additional-directories caps))
    (is (:fork-session caps))
    (is (:image-content caps))
    (is (:audio-content caps))
    (is (:embedded-context caps))
    (is (:mcp-http caps))
    (is (:mcp-sse caps))
    (is (false? (:providers caps)))
    (acp/close conn)))

(deftest protocol-version-mismatch-yields-protocol-error
  (let [data (try
               (connect-fake "bad-version")
               nil
               (catch Throwable t
                 (ex-data t)))]
    (is (= :acp/protocol (:oml/error data)))))

(deftest connect-reaps-process-on-protocol-version-mismatch
  (let [pid-file (File/createTempFile "acp-fake" ".pid")]
    (.deleteOnExit pid-file)
    (try
      (connect-fake "bad-version" {"ACP_FAKE_PID_FILE" (.getPath pid-file)})
      (is false "expected connect to throw")
      (catch Throwable _))
    (is (false? (wait-for-process-death pid-file 6000))
        "subprocess should be reaped after connect fails")))

(deftest malformed-frame-yields-stable-error
  (let [data (try
               (connect-fake "malformed")
               nil
               (catch Throwable t
                 (ex-data t)))]
    (is (#{:acp/connection :acp/protocol} (:oml/error data)))
    (is (not= :acp/unknown (:oml/error data)))))

(deftest unexpected-exit-yields-connection-error
  (let [data (try
               (connect-fake "early-exit")
               nil
               (catch Throwable t
                 (ex-data t)))]
    (is (= :acp/connection (:oml/error data)))))

(deftest double-close-is-safe
  (let [conn (connect-fake "ok")]
    (is (acp/close conn))
    (is (false? (acp/close conn)))))

(deftest hanging-agent-close-is-bounded
  (let [conn (connect-fake "hang")
        start (System/currentTimeMillis)
        closed? (acp/close conn)
        elapsed (- (System/currentTimeMillis) start)]
    (is (<= elapsed 15000)
        (str "close of hanging agent took " elapsed " ms; expected bounded"))
    (is (boolean? closed?))))

(defn -main
  "Entry point for running this namespace standalone."
  [& _]
  (require 'clojure.test)
  (let [{:keys [fail error]} (clojure.test/run-tests 'oml.acp-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

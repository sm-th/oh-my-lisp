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

(defn- connect-fake-with
  "Connect to the fake agent with the given behavior and connect `opts`."
  [behavior opts]
  (acp/connect (java-binary) (fake-agent-args behavior) nil opts))

(defn- process-alive? [pid-str]
  (boolean
   (when-let [pid (try (Long/parseLong (str/trim pid-str)) (catch Throwable _))]
     (when-let [handle (ProcessHandle/of pid)]
       (when (.isPresent handle)
         (.isAlive (.get handle)))))))

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
    (is (false? (:delete-session caps)))
    (is (false? (:additional-directories caps)))
    (is (false? (:fork-session caps)))
    (is (false? (:providers caps)))
    (is (false? (:image-content caps)))
    (is (:audio-content caps))
    (is (false? (:embedded-context caps)))
    (is (:mcp-http caps))
    (is (:mcp-sse caps))
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
    (is (false? (wait-for-process-death pid-file 12000))
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

(deftest new-session-returns-session-id
  (let [conn (connect-fake "session-ok")
        session (acp/new-session conn "/tmp")]
    (is (string? (:session-id session)))
    (is (seq (:session-id session)))
    (acp/close conn)))

(deftest new-session-failure-yields-stable-error
  (let [conn (connect-fake "session-error")
        ex (try
             (acp/new-session conn "/tmp")
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "expected new-session to throw on an agent-reported failure")
    (is (contains? #{:acp/protocol :acp/agent :acp/connection} (:oml/error (ex-data ex))))
    (is (not= :acp/unknown (:oml/error (ex-data ex))))
    (is (instance? Throwable (.getCause ^Throwable ex)))
    (acp/close conn)))

(deftest prompt-completes-turn-with-ordered-updates
  (let [conn (connect-fake "prompt-ok")
        sid (:session-id (acp/new-session conn "/tmp"))
        result (acp/prompt conn sid "hi")
        texts (->> (:updates result)
                   (filter #(= :agent-message-chunk (:type %)))
                   (map :text)
                   vec)]
    (is (= :end-turn (:stop-reason result)))
    (is (= ["Hello, " "world!"] texts))
    (acp/close conn)))

(deftest prompt-reports-non-end-turn-stop-reason
  (let [conn (connect-fake "prompt-stop")
        sid (:session-id (acp/new-session conn "/tmp"))
        result (acp/prompt conn sid "hi")]
    (is (= :max-tokens (:stop-reason result)))
    (acp/close conn)))

(deftest prompt-failure-yields-stable-error
  (let [conn (connect-fake "prompt-error")
        sid (:session-id (acp/new-session conn "/tmp"))
        ex (try
             (acp/prompt conn sid "hi")
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "expected prompt to throw on an agent-reported failure")
    (is (contains? #{:acp/protocol :acp/agent :acp/connection} (:oml/error (ex-data ex))))
    (is (not= :acp/unknown (:oml/error (ex-data ex))))
    (acp/close conn)))

(defn- agent-texts [result]
  (->> (:updates result)
       (filter #(= :agent-message-chunk (:type %)))
       (map :text)
       vec))

(deftest permission-allow-lets-turn-proceed
  (let [conn (connect-fake-with "prompt-permission" {:on-permission (fn [_] "allow")})
        sid (:session-id (acp/new-session conn "/tmp"))
        result (acp/prompt conn sid "do a thing")]
    (is (= :end-turn (:stop-reason result)))
    (is (some #{"allow"} (agent-texts result))
        "agent should observe the allowed option")
    (acp/close conn)))

(deftest permission-default-rejects
  (let [conn (connect-fake "prompt-permission")
        sid (:session-id (acp/new-session conn "/tmp"))
        result (acp/prompt conn sid "do a thing")]
    (is (= :end-turn (:stop-reason result)))
    (is (some #{"reject"} (agent-texts result))
        "with no handler configured the request is rejected safely")
    (acp/close conn)))

(deftest permission-callback-error-falls-back-to-reject
  (let [conn (connect-fake-with "prompt-permission"
                                {:on-permission (fn [_] (throw (ex-info "boom" {})))})
        sid (:session-id (acp/new-session conn "/tmp"))
        result (acp/prompt conn sid "do a thing")]
    (is (= :end-turn (:stop-reason result)))
    (is (some #{"reject"} (agent-texts result))
        "a throwing callback should fall back to safe reject")
    (acp/close conn)))

(deftest cancel-ends-in-flight-turn-as-cancelled
  (let [conn (connect-fake "prompt-cancel")
        sid (:session-id (acp/new-session conn "/tmp"))
        fut (future (acp/prompt conn sid "long task"))]
    (Thread/sleep 300)
    (is (nil? (acp/cancel conn sid)))
    (let [result (deref fut 15000 ::timeout)]
      (is (not= ::timeout result) "prompt should return after cancel")
      (is (= :cancelled (:stop-reason result)))
      (acp/close conn))))

(deftest close-session-when-supported
  (let [conn (connect-fake "session-close-ok")
        sid (:session-id (acp/new-session conn "/tmp"))]
    (is (nil? (acp/close-session conn sid)))
    (acp/close conn)))

(deftest close-session-unsupported-yields-capability-error
  (let [conn (connect-fake "session-close-unsupported")
        sid (:session-id (acp/new-session conn "/tmp"))
        data (try
               (acp/close-session conn sid)
               nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :acp/capability (:oml/error data)))
    (acp/close conn)))

(defn -main
  "Entry point for running this namespace standalone."
  [& _]
  (require 'clojure.test)
  (let [{:keys [fail error]} (clojure.test/run-tests 'oml.acp-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

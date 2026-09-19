(ns oml.acp-fake
  "Minimal stdio fake ACP agent for behavior tests.

  Speaks newline-delimited JSON-RPC directly over stdin/stdout so the test does not
  depend on running the SDK's agent runtime inside a Clojure subprocess. The fake
  reads the client's `initialize` request, echoes its JSON-RPC id verbatim (numbers
  stay numbers), and writes a scripted response. Manual malformed/early-exit
  behaviors cover the non-happy paths.

  When ACP_FAKE_DEBUG names a file, lifecycle markers and the raw request line are
  appended to it so a failing test can surface exactly what the subprocess saw."
  (:import [java.lang ProcessHandle])
  (:gen-class))

(defn- debug!
  "Append a diagnostic line to the ACP_FAKE_DEBUG file, if configured."
  [msg]
  (when-let [path (System/getenv "ACP_FAKE_DEBUG")]
    (try
      (spit path (str msg "\n") :append true)
      (catch Throwable _))))

(defn- write-pid
  "Write this process's PID to the file named by ACP_FAKE_PID_FILE, if set."
  []
  (when-let [path (System/getenv "ACP_FAKE_PID_FILE")]
    (try
      (spit path (str (.pid (ProcessHandle/current)) "\n"))
      (catch Throwable _))))

(defn- request-id
  "Extract the JSON-RPC id from a request line verbatim, preserving JSON type.

  Returns the id text exactly as it appears (e.g. `1` for a number, `\"abc\"` for a
  string), or the literal `null` when absent."
  [line]
  (or (some-> (re-find #"\"id\"\s*:\s*(\"(?:[^\"\\]|\\.)*\"|-?\d+)" line) second)
      "null"))

(def ^:private ok-capabilities
  "A small, intentional capability advertisement for the ok behavior."
  (str "{\"loadSession\":true,"
       "\"sessionCapabilities\":{\"list\":{},\"close\":{},\"resume\":{},"
       "\"delete\":{},\"additionalDirectories\":{},\"fork\":{}},"
       "\"promptCapabilities\":{\"image\":true,\"audio\":true,\"embeddedContext\":true},"
       "\"mcpCapabilities\":{\"http\":true,\"sse\":true}}"))

(defn- respond
  "Read one initialize request and write a JSON-RPC result with the given
  `protocol-version` and `capabilities` JSON, echoing the request id verbatim."
  [protocol-version capabilities]
  (let [line (read-line)]
    (debug! (str "request:" line))
    (if (nil? line)
      (debug! "request:EOF")
      (let [id (request-id line)
            response (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                          ",\"result\":{\"protocolVersion\":" protocol-version
                          ",\"agentCapabilities\":" capabilities "}}")]
        (print response)
        (print "\n")
        (flush)
        (debug! (str "response:" response))))))

(defn- run-behavior
  [behavior]
  (write-pid)
  (debug! (str "start:" behavior))
  (case behavior
    "ok" (respond 1 ok-capabilities)
    "bad-version" (do (respond 2 "{}")
                      ;; Stay alive until the client closes stdin so cleanup is observable.
                      (read-line)
                      (debug! "bad-version:stdin-closed"))
    "malformed" (do (print "this is not valid json\n") (flush))
    "early-exit" (System/exit 1)
    "hang" (do (respond 1 "{}")
               (Thread/sleep Long/MAX_VALUE))
    (do (binding [*out* *err*]
          (println "unknown behavior:" behavior))
        (System/exit 2))))

(defn -main
  "Run one scripted ACP stdio behavior."
  [& [behavior]]
  (try
    (run-behavior behavior)
    (catch Throwable t
      (debug! (str "error:" (.getMessage t)))
      (binding [*out* *err*]
        (println "fake agent failed:" (.getMessage t)))
      (System/exit 3))))

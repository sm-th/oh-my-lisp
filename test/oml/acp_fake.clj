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
  "An intentionally mixed capability advertisement for the ok behavior: session
  list/close/resume, audio, and MCP http/sse are supported; delete,
  additionalDirectories, fork, providers, image, and embeddedContext are not, so the
  wrapper's per-flag mapping is verified for both true and false."
  (str "{\"loadSession\":true,"
       "\"sessionCapabilities\":{\"list\":{},\"close\":{},\"resume\":{}},"
       "\"promptCapabilities\":{\"audio\":true},"
       "\"mcpCapabilities\":{\"http\":true,\"sse\":true}}"))

(def ^:private no-close-capabilities
  "Capabilities without session close, for the unsupported close-session path."
  (str "{\"loadSession\":true,"
       "\"sessionCapabilities\":{\"list\":{},\"resume\":{}},"
       "\"promptCapabilities\":{\"audio\":true},"
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

(defn- reply-session
  "Read a session/new request; require an mcpServers array (real ACP agents crash on a
  missing one) and reply with a session id, or a JSON-RPC error when it is absent."
  []
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (let [id (request-id line)
            response (if (.contains line "\"mcpServers\":[")
                       (str "{\"jsonrpc\":\"2.0\",\"id\":" id ",\"result\":{\"sessionId\":\"sess-abc-123\"}}")
                       (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                            ",\"error\":{\"code\":-32602,\"message\":\"missing mcpServers array\"}}"))]
        (print response)
        (print "\n")
        (flush)
        (debug! (str "response:" response))))))

(defn- reply-empty-result
  "Read one request and reply with an empty JSON-RPC result, echoing the id verbatim."
  []
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (let [id (request-id line)
            response (str "{\"jsonrpc\":\"2.0\",\"id\":" id ",\"result\":{}}")]
        (print response)
        (print "\n")
        (flush)
        (debug! (str "response:" response))))))

(defn- reply-error
  "Read one request line and write a JSON-RPC error, echoing the id verbatim."
  [code message]
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (let [id (request-id line)
            response (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                          ",\"error\":{\"code\":" code ",\"message\":\"" message "\"}}")]
        (print response)
        (print "\n")
        (flush)
        (debug! (str "response:" response))))))

(defn- agent-chunk-notification
  "A session/update notification carrying an agent_message_chunk with `text`."
  [text]
  (str "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\","
       "\"params\":{\"sessionId\":\"sess-abc-123\","
       "\"update\":{\"sessionUpdate\":\"agent_message_chunk\","
       "\"content\":{\"type\":\"text\",\"text\":\"" text "\"}}}}"))

(defn- notify
  "Write a JSON-RPC notification line (no id)."
  [json]
  (print json)
  (print "\n")
  (flush)
  (debug! (str "notify:" json)))

(defn- reply-prompt
  "Read a session/prompt request, stream agent message chunks, then respond with the
  given `stop-reason`, echoing the request id verbatim."
  [chunks stop-reason]
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (doseq [t chunks]
        (notify (agent-chunk-notification t)))
      (let [id (request-id line)
            response (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                          ",\"result\":{\"stopReason\":\"" stop-reason "\"}}")]
        (print response)
        (print "\n")
        (flush)
        (debug! (str "response:" response))))))

(defn- decision-from-response
  "Extract the permission decision from the client's JSON-RPC response line: the
  selected optionId, or \"cancelled\", or \"unknown\"."
  [line]
  (or (second (re-find #"\"optionId\":\"([^\"]+)\"" line))
      (when (.contains line "cancelled") "cancelled")
      "unknown"))

(defn- reply-prompt-with-permission
  "Read a session/prompt request, ask the client to approve a tool call, reflect the
  client's decision back as an agent message chunk, then finish the turn."
  [stop-reason]
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (let [id (request-id line)]
        (notify (str "{\"jsonrpc\":\"2.0\",\"id\":100,"
                     "\"method\":\"session/request_permission\","
                     "\"params\":{\"sessionId\":\"sess-abc-123\","
                     "\"options\":[{\"optionId\":\"allow\",\"name\":\"Allow\",\"kind\":\"allow_once\"},"
                     "{\"optionId\":\"reject\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}}"))
        (let [resp (str (read-line))
              decision (decision-from-response resp)]
          (debug! (str "perm-response:" resp))
          (notify (agent-chunk-notification decision))
          (let [response (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                              ",\"result\":{\"stopReason\":\"" stop-reason "\"}}")]
            (print response)
            (print "\n")
            (flush)
            (debug! (str "response:" response))))))))

(defn- reply-prompt-await-cancel
  "Read a session/prompt request, stream a chunk, wait for the client's session/cancel
  notification, then complete the turn as cancelled."
  []
  (let [line (read-line)]
    (debug! (str "request:" line))
    (when (some? line)
      (let [id (request-id line)]
        (notify (agent-chunk-notification "working"))
        (loop []
          (let [l (read-line)]
            (debug! (str "await-cancel:" l))
            (when (and (some? l) (not (.contains l "session/cancel")))
              (recur))))
        (let [response (str "{\"jsonrpc\":\"2.0\",\"id\":" id
                            ",\"result\":{\"stopReason\":\"cancelled\"}}")]
          (print response)
          (print "\n")
          (flush)
          (debug! (str "response:" response)))))))

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
    "session-ok" (do (respond 1 ok-capabilities)
                     (reply-session)
                     ;; Stay alive until the client closes stdin so close is graceful.
                     (read-line))
    "session-error" (do (respond 1 ok-capabilities)
                        (reply-error -32000 "cwd does not exist"))
    "prompt-ok" (do (respond 1 ok-capabilities)
                    (reply-session)
                    (reply-prompt ["Hello, " "world!"] "end_turn")
                    (read-line))
    "prompt-stop" (do (respond 1 ok-capabilities)
                      (reply-session)
                      (reply-prompt ["partial"] "max_tokens")
                      (read-line))
    "prompt-error" (do (respond 1 ok-capabilities)
                       (reply-session)
                       (reply-error -32000 "prompt failed"))
    "prompt-permission" (do (respond 1 ok-capabilities)
                            (reply-session)
                            (reply-prompt-with-permission "end_turn")
                            (read-line))
    "prompt-cancel" (do (respond 1 ok-capabilities)
                        (reply-session)
                        (reply-prompt-await-cancel)
                        (read-line))
    "session-close-ok" (do (respond 1 ok-capabilities)
                           (reply-session)
                           (reply-empty-result)
                           (read-line))
    "session-close-unsupported" (do (respond 1 no-close-capabilities)
                                    (reply-session)
                                    (read-line))
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

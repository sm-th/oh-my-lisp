(ns oml.acp-fake
  "Minimal stdio fake ACP agent for behavior tests.

  Reads one JSON-RPC initialize request from stdin and writes a scripted response
  to stdout. The client transport owns framing; this agent only needs to produce
  valid newline-delimited JSON for the behaviors it implements, plus an explicit
  malformed line for the malformed-frame behavior."
  (:gen-class))

(def ^:private ok-response
  (str "{\"protocolVersion\":1,"
       "\"agentCapabilities\":{"
       "\"loadSession\":true,"
       "\"sessionCapabilities\":{"
       "\"list\":{},\"close\":{},\"resume\":{},\"delete\":{},"
       "\"additionalDirectories\":{},\"fork\":{}},"
       "\"promptCapabilities\":{"
       "\"image\":true,\"audio\":true,\"embeddedContext\":true},"
       "\"mcpCapabilities\":{"
       "\"http\":true,\"sse\":true}}}"))

(def ^:private empty-capabilities-response
  "{\"protocolVersion\":1,\"agentCapabilities\":{}}")

(defn- extract-id
  "Pull the string request id from a JSON-RPC request line. The SDK generates
  string ids, so a simple regex is sufficient for this test fixture."
  [line]
  (or (second (re-find #"\"id\":\"([^\"]+)\"" line))
      "0"))

(defn- write-response
  [out id result]
  (binding [*out* out]
    (printf "{\"jsonrpc\":\"2.0\",\"id\":\"%s\",\"result\":%s}%n" id result)
    (flush)))

(defn -main
  "Run one scripted ACP stdio behavior."
  [& [behavior]]
  (let [out *out*]
    (case behavior
      "ok"
      (let [line (read-line)
            id (extract-id line)]
        (write-response out id ok-response))

      "bad-version"
      (let [line (read-line)
            id (extract-id line)]
        (write-response out id "{\"protocolVersion\":2,\"agentCapabilities\":{}}"))

      "malformed"
      (do (println "this is not valid json")
          (flush))

      "early-exit"
      (System/exit 1)

      "hang"
      (let [line (read-line)
            id (extract-id line)]
        (write-response out id empty-capabilities-response)
        (Thread/sleep Long/MAX_VALUE))

      (do (binding [*out* *err*]
            (println "unknown behavior:" behavior))
          (System/exit 2)))))

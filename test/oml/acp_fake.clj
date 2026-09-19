(ns oml.acp-fake
  "Minimal stdio fake ACP agent for behavior tests.

  Uses the official SDK agent side for every valid ACP exchange, so the client and
  agent correlate through the SDK's own framing and schema. Manual stdout is used only
  for the malformed-frame and early-exit behaviors."
  (:require [clojure.string :as str])
  (:import [com.agentclientprotocol.sdk.agent AcpAgent]
           [com.agentclientprotocol.sdk.agent.transport StdioAcpAgentTransport]
           [com.agentclientprotocol.sdk.spec AcpSchema]
           [java.lang ProcessHandle]
           [java.time Duration])
  (:gen-class))

(defn- write-pid
  "Write this process's PID to the file named by ACP_FAKE_PID_FILE, if set.

  Tests use this to observe that the wrapper reaps the subprocess on failure."
  []
  (when-let [path (System/getenv "ACP_FAKE_PID_FILE")]
    (try
      (spit path (str (.pid (ProcessHandle/current)) "\n"))
      (catch Throwable _))))

(defn- ok-capabilities
  "Return a small, intentional AgentCapabilities advertisement for the ok behavior."
  []
  (AcpSchema/AgentCapabilities. true
    (AcpSchema/SessionCapabilities. (java.util.HashMap.) (java.util.HashMap.) (java.util.HashMap.))
    (AcpSchema/McpCapabilities. true true)
    (AcpSchema/PromptCapabilities. true false false)))

(defn- make-agent
  "Build an AcpSyncAgent that answers initialize with `response`."
  [^AcpSchema$InitializeResponse response]
  (let [transport (StdioAcpAgentTransport.)]
    (-> (AcpAgent/sync transport)
        (.requestTimeout (Duration/ofSeconds 30))
        (.initializeHandler (reify com.agentclientprotocol.sdk.agent.AcpAgent$SyncInitializeHandler
                              (handle [_ _request] response)))
        .build)))

(defn- run-agent
  "Start the agent and block until the transport closes."
  [^AcpSchema$InitializeResponse response]
  (write-pid)
  (.run (make-agent response)))

(defn- malformed
  "Write an explicit non-JSON line so the client sees a framing/parse error."
  []
  (write-pid)
  (println "this is not valid json")
  (flush))

(defn- early-exit
  "Exit before the client can complete the handshake."
  []
  (write-pid)
  (System/exit 1))

(defn- hang
  "Answer initialize with empty capabilities, then block forever so close must reap us."
  []
  (write-pid)
  (let [agent (make-agent (AcpSchema/InitializeResponse/ok))]
    (.start agent)
    (Thread/sleep Long/MAX_VALUE)))

(defn -main
  "Run one scripted ACP stdio behavior."
  [& [behavior]]
  (case behavior
    "ok" (run-agent (AcpSchema/InitializeResponse/ok (ok-capabilities)))
    "bad-version" (run-agent (AcpSchema/InitializeResponse. 2 (AcpSchema/AgentCapabilities.) nil))
    "malformed" (malformed)
    "early-exit" (early-exit)
    "hang" (hang)
    (do (binding [*out* *err*]
          (println "unknown behavior:" behavior))
        (System/exit 2))))

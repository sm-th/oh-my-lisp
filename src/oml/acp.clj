(ns oml.acp
  "Thin Clojure wrapper over the official ACP Java SDK synchronous stdio client.

  The SDK owns JSON framing, request/response correlation, process lifetime, and
  transport mechanics. This namespace only configures a local command, starts the
  initialize handshake, exposes negotiated capabilities, and closes the connection
  idempotently. All failures are translated into ex-info values tagged with
  :oml/error."
  (:import
   [com.agentclientprotocol.sdk.capabilities NegotiatedCapabilities]
   [com.agentclientprotocol.sdk.client AcpClient AcpSyncClient]
   [com.agentclientprotocol.sdk.client.transport AgentParameters StdioAcpClientTransport]
   [com.agentclientprotocol.sdk.error AcpCapabilityException AcpConnectionException
    AcpException AcpProtocolException]
   [java.time Duration]))

(def ^:private default-request-timeout
  "Bounded request timeout applied to every SDK operation initiated by this wrapper."
  (Duration/ofSeconds 10))

(defn- ^AgentParameters build-parameters
  "Build AgentParameters from a command, a sequence of args, and an optional env map."
  [^String command args env]
  (let [builder (AgentParameters/builder command)]
    (when (seq args)
      (.args builder (java.util.ArrayList. args)))
    (when (seq env)
      (.env builder (java.util.HashMap. env)))
    (.build builder)))

(defn- find-acp-cause
  "Return the first AcpException in the cause chain, or nil."
  [^Throwable t]
  (loop [e t]
    (cond
      (instance? AcpException e) e
      (nil? (.getCause e)) nil
      :else (recur (.getCause e)))))

(defn- timeout-cause?
  "Return true if any exception in the cause chain is a timeout."
  [^Throwable t]
  (boolean
   (some #(instance? java.util.concurrent.TimeoutException %)
         (take-while some? (iterate #(.getCause ^Throwable %) t)))))

(defn- error-category
  "Map an SDK exception to an :oml/error category."
  [^Throwable t]
  (let [cause (or (find-acp-cause t) t)]
    (cond
      (timeout-cause? t) :acp/connection
      (instance? AcpProtocolException cause) :acp/protocol
      (instance? AcpConnectionException cause) :acp/connection
      (instance? AcpCapabilityException cause) :acp/capability
      (instance? AcpException cause) :acp/agent
      (instance? java.io.IOException cause) :acp/connection
      :else :acp/unknown)))

(defn- error-data
  "Extract stable context from an SDK failure."
  [^Throwable t]
  (let [cause (or (find-acp-cause t) t)
        base {:oml/error (error-category t)}]
    (cond
      (instance? AcpProtocolException cause)
      (assoc base
             :acp/error-code (.getCode ^AcpProtocolException cause)
             :acp/error-data (.getData ^AcpProtocolException cause))

      (instance? AcpCapabilityException cause)
      (assoc base :acp/capability (.getCapability ^AcpCapabilityException cause))

      :else base)))

(defn- wrap-error
  "Re-throw an SDK exception as ex-info with an :oml/error category."
  [^Throwable t]
  (let [cause (or (find-acp-cause t) t)
        message (or (.getMessage cause) (str (.getClass cause)))]
    (throw (ex-info message (error-data t) t))))

(defn- capability-map
  "Translate an SDK NegotiatedCapabilities object into a Clojure map."
  [^NegotiatedCapabilities caps]
  (when caps
    {:load-session (.supportsLoadSession caps)
     :list-sessions (.supportsListSessions caps)
     :close-session (.supportsCloseSession caps)
     :resume-session (.supportsResumeSession caps)
     :delete-session (.supportsDeleteSession caps)
     :additional-directories (.supportsAdditionalDirectories caps)
     :fork-session (.supportsForkSession caps)
     :providers (.supportsProviders caps)
     :image-content (.supportsImageContent caps)
     :audio-content (.supportsAudioContent caps)
     :embedded-context (.supportsEmbeddedContext caps)
     :mcp-http (.supportsMcpHttp caps)
     :mcp-sse (.supportsMcpSse caps)}))

(defrecord Connection
  [^AcpSyncClient client ^StdioAcpClientTransport transport closed? protocol-version])

(defn connect
  "Launch the configured local agent command and complete the ACP v1 initialize
  handshake.

  `command` is the executable name or path. `args` is a sequence of string
  arguments. Optional `env` is a map of extra environment variables.

  Returns a Connection. Throws ex-info tagged with :oml/error on connection,
  protocol-version mismatch, or other protocol failure."
  ([command args]
   (connect command args nil))
  ([command args env]
   (try
     (let [params (build-parameters command args env)
           transport (StdioAcpClientTransport. params)
           client (-> (AcpClient/sync transport)
                      (.requestTimeout default-request-timeout)
                      .build)
           response (.initialize client)]
       (when (not= 1 (.protocolVersion response))
         (throw (ex-info (str "ACP protocol version mismatch: expected 1, got "
                              (.protocolVersion response))
                         {:oml/error :acp/protocol
                          :acp/protocol-version (.protocolVersion response)})))
       (->Connection client transport (atom false) 1))
     (catch clojure.lang.ExceptionInfo e
       (throw e))
     (catch Throwable t
       (wrap-error t)))))

(defn capabilities
  "Return the negotiated agent capabilities for `conn` as a Clojure map.

  The map includes :protocol-version and the boolean capability flags advertised
  by the agent during initialize. Throws ex-info tagged with :oml/error if the
  connection was not initialized."
  [^Connection conn]
  (if-let [caps (.getAgentCapabilities ^AcpSyncClient (:client conn))]
    (assoc (capability-map caps)
           :protocol-version (:protocol-version conn))
    (throw (ex-info "ACP connection not initialized"
                    {:oml/error :acp/protocol}))))

(defn close
  "Close `conn` idempotently and with a bounded wait.

  The underlying SDK transport sends TERM, waits, and forcibly destroys the
  process if necessary. Returns true if the connection closed gracefully within
  the SDK's timeout, false otherwise. Calling close on an already-closed
  connection is a no-op and returns false."
  [^Connection conn]
  (let [closed? (:closed? conn)]
    (if (compare-and-set! closed? false true)
      (try
        (.closeGracefully ^AcpSyncClient (:client conn))
        (catch Throwable _
          false))
      false)))

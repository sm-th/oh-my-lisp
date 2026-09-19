(ns oml.acp
  "Thin Clojure wrapper over the official ACP Java SDK synchronous stdio client.

  The SDK owns JSON framing, request/response correlation, process lifetime, and
  transport mechanics. This namespace only configures a local command, starts the
  initialize handshake, exposes negotiated capabilities, and closes the connection
  idempotently. All failures are translated into ex-info values tagged with
  :oml/error."
  (:require [clojure.string :as str])
  (:import
   [com.agentclientprotocol.sdk.capabilities NegotiatedCapabilities]
   [com.agentclientprotocol.sdk.client AcpClient AcpSyncClient]
   [com.agentclientprotocol.sdk.client.transport AgentParameters StdioAcpClientTransport]
   [com.agentclientprotocol.sdk.error AcpCapabilityException AcpConnectionException
    AcpException AcpProtocolException]
   [com.agentclientprotocol.sdk.spec AcpSchema$NewSessionRequest AcpSchema$NewSessionResponse
    AcpSchema$PromptRequest AcpSchema$PromptResponse AcpSchema$TextContent AcpSchema$StopReason
    AcpSchema$SessionNotification AcpSchema$AgentMessageChunk AcpSchema$AgentThoughtChunk
    AcpSchema$UserMessageChunk AcpSchema$RequestPermissionRequest AcpSchema$RequestPermissionResponse
    AcpSchema$PermissionOption AcpSchema$PermissionOptionKind AcpSchema$PermissionSelected
    AcpSchema$PermissionCancelled AcpSchema$CancelNotification AcpClientSession$AcpError]
   [java.time Duration]
   [java.util.concurrent ConcurrentLinkedQueue]
   [java.util.function Consumer Function]))

(def ^:private default-request-timeout-ms
  "Default per-request timeout in milliseconds for SDK operations. Generous enough for
  real prompt turns (model inference plus tool calls); override per connection with the
  opts key :request-timeout-ms."
  120000)

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

(defn- find-acp-error
  "Return the first AcpClientSession$AcpError (a JSON-RPC error) in the cause chain, or nil."
  [^Throwable t]
  (loop [e t]
    (cond
      (instance? AcpClientSession$AcpError e) e
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
      (find-acp-error t) :acp/agent
      (instance? AcpProtocolException cause) :acp/protocol
      (instance? AcpConnectionException cause) :acp/connection
      (instance? AcpCapabilityException cause) :acp/capability
      (instance? AcpException cause) :acp/agent
      (instance? java.io.IOException cause) :acp/connection
      :else :acp/unknown)))

(defn- error-data
  "Extract stable context from an SDK failure."
  [^Throwable t]
  (let [acp-error (find-acp-error t)
        cause (or (find-acp-cause t) t)
        base {:oml/error (error-category t)}]
    (cond
      acp-error
      (let [je (.getError ^AcpClientSession$AcpError acp-error)]
        (assoc base
               :acp/error-code (.code je)
               :acp/error-data (.data je)))

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

(defn- content-text
  "Return the text of a ContentBlock when it is a text block, else nil."
  [content]
  (when (instance? AcpSchema$TextContent content)
    (.text ^AcpSchema$TextContent content)))

(defn- camel->kebab-kw
  "Convert a CamelCase class name into a kebab-case keyword."
  [^String s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case
      keyword))

(defn- update->map
  "Convert an SDK SessionUpdate into a Clojure map with :type and, for text-bearing
  chunks, :text."
  [update]
  (cond
    (instance? AcpSchema$AgentMessageChunk update)
    {:type :agent-message-chunk :text (content-text (.content ^AcpSchema$AgentMessageChunk update))}
    (instance? AcpSchema$AgentThoughtChunk update)
    {:type :agent-thought-chunk :text (content-text (.content ^AcpSchema$AgentThoughtChunk update))}
    (instance? AcpSchema$UserMessageChunk update)
    {:type :user-message-chunk :text (content-text (.content ^AcpSchema$UserMessageChunk update))}
    :else
    {:type (camel->kebab-kw (.getSimpleName (class update)))}))

(defn- stop-reason->kw
  "Convert an SDK StopReason enum to a Clojure keyword (e.g. :end-turn)."
  [^AcpSchema$StopReason sr]
  (when sr
    (-> (.name sr) str/lower-case (str/replace "_" "-") keyword)))

(defn- drain-settle
  "Wait until the update queue stops growing, bounded to two seconds, so late-arriving
  session/update consumers are captured without an unbounded wait."
  [^ConcurrentLinkedQueue q]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop [prev -1]
      (let [now (.size q)]
        (when (and (not= now prev) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 40)
          (recur now))))))

(defn- kind->kw
  "Convert a PermissionOptionKind enum to a keyword (e.g. :allow-once)."
  [^AcpSchema$PermissionOptionKind k]
  (when k (-> (.name k) str/lower-case (str/replace "_" "-") keyword)))

(defn- option->map
  "Convert an SDK PermissionOption to a Clojure map."
  [^AcpSchema$PermissionOption o]
  {:option-id (.optionId o) :name (.name o) :kind (kind->kw (.kind o))})

(def ^:private reject-kinds
  #{AcpSchema$PermissionOptionKind/REJECT_ONCE AcpSchema$PermissionOptionKind/REJECT_ALWAYS})

(def ^:private allow-kinds
  #{AcpSchema$PermissionOptionKind/ALLOW_ONCE AcpSchema$PermissionOptionKind/ALLOW_ALWAYS})

(defn- first-option-id
  "Return the optionId of the first option whose kind is in `kinds`, or nil."
  [kinds options]
  (some (fn [^AcpSchema$PermissionOption o] (when (contains? kinds (.kind o)) (.optionId o)))
        options))

(defn- permission-outcome
  "Build a RequestPermissionResponse for `decision` against the offered `options`.

  `decision` may be an option-id string, :allow, :reject, or :cancel. :allow and
  :reject select the first option of that kind; anything without a selectable option
  cancels."
  [decision options]
  (let [selected (cond
                   (string? decision) decision
                   (= :allow decision) (first-option-id allow-kinds options)
                   (= :cancel decision) nil
                   :else (first-option-id reject-kinds options))]
    (AcpSchema$RequestPermissionResponse.
     (if selected
       (AcpSchema$PermissionSelected. selected)
       (AcpSchema$PermissionCancelled.)))))

(defn- permission-handler
  "An SDK request-permission handler that consults `on-permission` (may be nil) and
  falls back to a safe reject on absence or callback error."
  [on-permission]
  (reify Function
    (apply [_ req]
      (let [^AcpSchema$RequestPermissionRequest req req
            options (.options req)
            decision (if on-permission
                       (try
                         (on-permission {:session-id (.sessionId req)
                                         :options (mapv option->map options)})
                         (catch Throwable _ :reject))
                       :reject)]
        (permission-outcome decision options)))))

(defrecord Connection
  [^AcpSyncClient client ^StdioAcpClientTransport transport closed? protocol-version updates])

(defn connect
  "Launch the configured local agent command and complete the ACP v1 initialize
  handshake.

  `command` is the executable name or path. `args` is a sequence of string
  arguments. Optional `env` is a map of extra environment variables. Optional `opts`
  is a map; `:on-permission` is a function called with a permission-request map
  `{:session-id s :options [{:option-id ... :name ... :kind ...} ...]}` that returns
  the decision (an option-id string, or :allow, :reject, or :cancel). Without it,
  permission requests are rejected safely. `:request-timeout-ms` overrides the
  per-request timeout (default 120000); raise it for long agent turns.

  Returns a Connection. Throws ex-info tagged with :oml/error on connection,
  protocol-version mismatch, or other protocol failure."
  ([command args]
   (connect command args nil nil))
  ([command args env]
   (connect command args env nil))
  ([command args env opts]
   (let [client (atom nil)
         updates (ConcurrentLinkedQueue.)]
     (try
       (let [params (build-parameters command args env)
             transport (StdioAcpClientTransport. params)
             consumer (reify Consumer
                        (accept [_ notification]
                          (.add updates
                                (update->map (.update ^AcpSchema$SessionNotification notification)))))
             c (-> (AcpClient/sync transport)
                   (.requestTimeout (Duration/ofMillis (long (or (:request-timeout-ms opts) default-request-timeout-ms))))
                   (.sessionUpdateConsumer consumer)
                   (.requestPermissionHandler (permission-handler (:on-permission opts)))
                   .build)]
         (reset! client c)
         (let [response (.initialize c)]
           (when (not= 1 (.protocolVersion response))
             (throw (ex-info (str "ACP protocol version mismatch: expected 1, got "
                                  (.protocolVersion response))
                             {:oml/error :acp/protocol
                              :acp/protocol-version (.protocolVersion response)})))
           (->Connection c transport (atom false) 1 updates)))
       (catch clojure.lang.ExceptionInfo e
         (when-let [c @client]
           (try (.closeGracefully ^AcpSyncClient c) (catch Throwable _)))
         (throw e))
       (catch Throwable t
         (when-let [c @client]
           (try (.closeGracefully ^AcpSyncClient c) (catch Throwable _)))
         (wrap-error t))))))

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

(defn new-session
  "Create a new ACP session on `conn` with working directory `cwd`.

  `cwd` is an absolute-path string naming the agent's working directory. Sends
  `session/new` through the SDK's synchronous client and returns an immutable map
  {:session-id \"<id>\"}. Throws ex-info tagged with :oml/error on failure."
  [^Connection conn ^String cwd]
  (try
    (let [req (AcpSchema$NewSessionRequest. cwd [] [] nil)
          ^AcpSchema$NewSessionResponse resp (.newSession ^AcpSyncClient (:client conn) req)]
      {:session-id (.sessionId resp)})
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Throwable t
      (wrap-error t))))

(defn prompt
  "Send `text` as a prompt on `session-id` over `conn` and complete the turn.

  Sends `session/prompt` with a single text content block through the SDK's
  synchronous client and returns an immutable map
  {:stop-reason <keyword> :updates [<update-map> ...]}, where :updates are the
  ordered session/update events received during the turn. Throws ex-info tagged
  with :oml/error on failure."
  [^Connection conn ^String session-id ^String text]
  (try
    (let [^ConcurrentLinkedQueue q (:updates conn)]
      (.clear q)
      (let [req (AcpSchema$PromptRequest. session-id [(AcpSchema$TextContent. text)])
            ^AcpSchema$PromptResponse resp (.prompt ^AcpSyncClient (:client conn) req)]
        (drain-settle q)
        {:stop-reason (stop-reason->kw (.stopReason resp))
         :updates (vec (.toArray q))}))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Throwable t
      (wrap-error t))))

(defn cancel
  "Cancel the in-flight turn for `session-id` on `conn`.

  Sends a fire-and-forget `session/cancel` notification and returns nil. An
  in-flight `prompt` for the session (running on another thread) then returns with
  :stop-reason :cancelled once the agent ends the turn. Throws ex-info tagged with
  :oml/error on a wrapper-level failure."
  [^Connection conn ^String session-id]
  (try
    (.cancel ^AcpSyncClient (:client conn) (AcpSchema$CancelNotification. session-id))
    nil
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Throwable t
      (wrap-error t))))

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

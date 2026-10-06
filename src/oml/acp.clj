(ns oml.acp
  "Chapter 7 (basic): the Agent Client Protocol, agent side.

  ACP is JSON-RPC 2.0, one JSON object per line, over the agent's stdin and
  stdout. The client (Toad, Zed, Emacs agent-shell) is the UI; we are the
  agent. stdout carries protocol messages only, so logs go to stderr.

  Implemented: initialize, session/new, session/prompt (streaming
  session/update notifications), session/cancel.
  Not yet: session/request_permission (chapter 6), session/load (chapter 4).

  Spec: https://agentclientprotocol.com/protocol/overview
  Compare with pi: packages/coding-agent/src/modes/rpc/ (pi's own JSON RPC mode)"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [oml.agent :as agent]
            [oml.cancel :as cancel]
            [oml.llm :as llm]
            [oml.tools :as tools]))

(def protocol-version 1)

(defn log [& xs]
  (binding [*out* *err*] (apply println "[oml]" xs)))

;; ---------------------------------------------------------------------------
;; Wire

(defn- writer-fn
  "A thread-safe fn that writes one JSON-RPC message per line to `out`."
  [^java.io.OutputStream out]
  (let [w (io/writer out :encoding "UTF-8")]
    (fn [msg]
      (locking w
        (.write w (json/generate-string (assoc msg :jsonrpc "2.0")))
        (.write w "\n")
        (.flush w)))))

(defn- error-response [id code message]
  {:id id :error {:code code :message message}})

;; ---------------------------------------------------------------------------
;; Mapping agent events to session/update notifications

(defn- text-block [text] {:type "text" :text text})

(defn- event->update
  "The ACP SessionUpdate for an agent-loop event, or nil if it has none."
  [cwd {:keys [type] :as e}]
  (case type
    :text-delta {:sessionUpdate "agent_message_chunk" :content (text-block (:text e))}
    :tool-start (let [path (get-in e [:args :path])]
                  (cond-> {:sessionUpdate "tool_call"
                           :toolCallId (:id e)
                           :title (:title e)
                           :kind (:kind e)
                           :status "in_progress"
                           :rawInput (:args e)}
                    (string? path) (assoc :locations [{:path (tools/resolve-path cwd path)}])))
    :tool-end {:sessionUpdate "tool_call_update"
               :toolCallId (:id e)
               :status (if (:error? e) "failed" "completed")
               :content [{:type "content" :content (text-block (:content e))}]}
    nil))

(def ^:private stop-reasons
  {:end-turn "end_turn" :cancelled "cancelled" :max-turns "max_turn_requests"})

(defn- prompt->text
  "Flatten ACP content blocks into the user message text. Baseline agents
  must accept text and resource_link blocks."
  [blocks]
  (->> blocks
       (keep (fn [{:keys [type text uri resource]}]
               (case type
                 "text" text
                 "resource_link" (str "@" uri)
                 "resource" (or (:text resource) (str "@" (:uri resource)))
                 nil)))
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Handlers

(defn- initialize [_state _params]
  {:protocolVersion protocol-version
   :agentCapabilities {:loadSession false
                       :promptCapabilities {:image false :audio false :embeddedContext false}}
   :authMethods []
   :agentInfo {:name "oml" :title "oml" :version "0.1.0"}})

(defn- new-session [{:keys [sessions]} {:keys [cwd]}]
  (when (str/blank? cwd) (throw (ex-info "cwd is required" {:code -32602})))
  (let [id (str "sess_" (random-uuid))]
    (swap! sessions assoc id {:cwd cwd
                              :messages [{:role "system" :content (agent/system-prompt cwd)}]
                              :cancel nil})
    {:sessionId id}))

(defn- prompt
  "Run one user turn. Streams session/update notifications through `send!`
  and returns the PromptResponse."
  [{:keys [sessions send! llm-fn max-turns]} {:keys [sessionId] :as params}]
  (let [token (cancel/token)
        [old _] (swap-vals! sessions
                            (fn [s] (if (and (contains? s sessionId) (nil? (get-in s [sessionId :cancel])))
                                      (assoc-in s [sessionId :cancel] token)
                                      s)))
        session (get old sessionId)]
    (cond (nil? session) (throw (ex-info (str "Unknown session: " sessionId) {:code -32602}))
          (:cancel session) (throw (ex-info "A prompt is already running in this session" {:code -32602})))
    (try
      (let [cwd (:cwd session)
            messages (conj (:messages session)
                           {:role "user" :content (prompt->text (:prompt params))})
            result (agent/run {:llm llm-fn
                               :tools tools/default-tools
                               :messages messages
                               :cwd cwd
                               :cancel token
                               :max-turns max-turns
                               :on-event (fn [e]
                                           (when-let [u (event->update cwd e)]
                                             (send! {:method "session/update"
                                                     :params {:sessionId sessionId :update u}})))})]
        (swap! sessions update sessionId assoc :messages (:messages result))
        {:stopReason (stop-reasons (:stop-reason result))})
      (catch Exception e
        ;; A cancelled turn may surface as an exception (e.g. a closed socket).
        (if (cancel/cancelled? token) {:stopReason "cancelled"} (throw e)))
      (finally
        (swap! sessions assoc-in [sessionId :cancel] nil)))))

(defn- cancel-session [{:keys [sessions]} {:keys [sessionId]}]
  (some-> (get-in @sessions [sessionId :cancel]) cancel/cancel!))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private request-handlers
  {"initialize" initialize
   "session/new" new-session
   "session/prompt" prompt})

(def ^:private notification-handlers
  {"session/cancel" cancel-session})

(defn- respond [{:keys [send!] :as state} {:keys [id method params]}]
  (if-let [h (request-handlers method)]
    (try
      (send! {:id id :result (h state params)})
      (catch Exception e
        (log "error in" method ":" (ex-message e))
        (send! (error-response id (:code (ex-data e) -32603) (or (ex-message e) (str e))))))
    (send! (error-response id -32601 (str "Method not found: " method)))))

(defn handle-line
  "Handle one incoming line. Prompts run on their own thread so that
  session/cancel can be read while a turn is in flight."
  [{:keys [send!] :as state} line]
  (when-not (str/blank? line)
    (let [msg (try (json/parse-string line true) (catch Exception _ ::invalid))]
      (cond
        (= ::invalid msg) (send! (error-response nil -32700 "Parse error"))
        (not (map? msg)) (send! (error-response nil -32600 "Invalid Request"))
        (and (:method msg) (contains? msg :id))
        (if (= "session/prompt" (:method msg))
          (swap! (:pending state) conj (future (respond state msg)))
          (respond state msg))
        (:method msg) (when-let [h (notification-handlers (:method msg))]
                        (h state (:params msg)))
        :else nil))))   ; a response to a request we never send

(defn serve
  "Serve ACP on the given streams until `in` closes. Then cancel running
  turns (the client is gone) and give them a moment to answer."
  [in out {:keys [llm-fn max-turns]}]
  (let [state {:sessions (atom {})
               :pending (atom [])
               :send! (writer-fn out)
               :llm-fn llm-fn
               :max-turns (or max-turns agent/default-max-turns)}]
    (doseq [line (line-seq (io/reader in :encoding "UTF-8"))]
      (handle-line state line))
    (doseq [{t :cancel} (vals @(:sessions state))] (some-> t cancel/cancel!))
    (doseq [f @(:pending state)] (deref f 5000 nil))))

(defn -main [& _]
  (log "ACP agent ready on stdio")
  ;; Read the configuration per call so a missing OPENAI_MODEL is reported on
  ;; the prompt that needs it, not as a crash at startup.
  (serve System/in System/out {:llm-fn (fn [req] (llm/stream-chat (llm/config) req))})
  (shutdown-agents)
  (System/exit 0))

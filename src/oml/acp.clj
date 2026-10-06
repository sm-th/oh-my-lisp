(ns oml.acp
  "Chapter 7 (basic): the Agent Client Protocol, agent side.

  ACP is JSON-RPC 2.0, one JSON object per line, over the agent's stdin and
  stdout. The client (Toad, Zed, Emacs agent-shell) is the UI; we are the
  agent. stdout carries protocol messages only, so logs go to stderr.

  Implemented: initialize, session/new, session/prompt (streaming
  session/update notifications), session/cancel, slash commands
  (available_commands_update; a prompt starting with /name runs the command).
  Not yet: session/request_permission, session/load.

  Each method is a `handle` multimethod; `after-response` runs after the
  response is sent. Add a method with defmethod, change one by redefining
  it. render-event turns loop events into session updates.

  Spec: https://agentclientprotocol.com/protocol/overview
  Compare with pi: packages/coding-agent/src/modes/rpc/ (pi's own JSON RPC mode)"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [oml.agent :as agent]
            [oml.cancel :as cancel]
            [oml.custom :as custom]
            [oml.init :as init]
            [oml.repl :as repl]))

(def protocol-version 1)

(def log init/log)

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

(defn send-update!
  "Send one session/update notification."
  [{:keys [send!]} session-id update]
  (send! {:method "session/update" :params {:sessionId session-id :update update}}))

(defn text-block [text] {:type "text" :text text})

(defn send-text!
  "Show `text` to the user as an agent message."
  [state session-id text]
  (send-update! state session-id {:sessionUpdate "agent_message_chunk" :content (text-block text)}))

;; ---------------------------------------------------------------------------
;; Mapping agent events to session/update notifications

(defn render-event
  "The ACP SessionUpdate for an agent-loop event, or nil if it has none."
  [{:keys [cwd]} {:keys [type] :as e}]
  (case type
    :text-delta {:sessionUpdate "agent_message_chunk" :content (text-block (:text e))}
    :tool-start (let [path (get-in e [:args :path])]
                  (cond-> {:sessionUpdate "tool_call"
                           :toolCallId (:id e)
                           :title (:title e)
                           :kind (:kind e)
                           :status "in_progress"
                           :rawInput (:args e)}
                    (string? path) (assoc :locations [{:path (agent/resolve-path cwd path)}])))
    :tool-end {:sessionUpdate "tool_call_update"
               :toolCallId (:id e)
               :status (if (:error? e) "failed" "completed")
               :content [{:type "content" :content (text-block (:content e))}]}
    nil))

(def stop-reasons
  {:end-turn "end_turn" :cancelled "cancelled" :max-turns "max_turn_requests"})

(defn prompt->text
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
;; Commands

(defn advertise-commands!
  "Send the discovered commands to the client (available_commands_update)."
  [{:keys [sessions] :as state} session-id]
  (let [infos (mapv custom/command-info (custom/commands))]
    (swap! sessions assoc-in [session-id :commands] infos)
    (send-update! state session-id {:sessionUpdate "available_commands_update"
                                    :availableCommands infos})))

(defn command-turn
  "Run command var `v` for a prompt and stream its output. Then advertises
  the commands again if the command asked for it (ctx :advertise-commands!)
  or changed them."
  [{:keys [sessions] :as state} session-id {:keys [cwd]} v input]
  (let [advertise? (atom false)
        ctx {:cwd cwd :session-id session-id
             :advertise-commands! #(reset! advertise? true)}
        output (agent/run-command ctx v input)]
    (when-not (str/blank? output) (send-text! state session-id output))
    (when (or @advertise?
              (not= (mapv custom/command-info (custom/commands))
                    (get-in @sessions [session-id :commands])))
      (advertise-commands! state session-id))
    {:stopReason "end_turn"}))

(defn agent-turn
  "Send `text` to the model and run the loop, streaming its events."
  [{:keys [sessions] :as state} session-id {:keys [cwd messages]} text token]
  (let [ctx {:cwd cwd :cancel token :session-id session-id
             :on-event (fn [e] (when-let [u (render-event {:cwd cwd} e)]
                                 (send-update! state session-id u)))}
        result (agent/run ctx (conj messages {:role "user" :content text}))]
    (swap! sessions update session-id assoc :messages (:messages result))
    {:stopReason (stop-reasons (:stop-reason result))}))

;; ---------------------------------------------------------------------------
;; Methods

(defmulti handle
  "Handle the ACP method `method`; return the result of a request (ignored
  for notifications). Throw ex-info with :code for a JSON-RPC error."
  (fn [_state method _params] method))

(defmethod handle :default [_ method _]
  (throw (ex-info (str "Method not found: " method) {:code -32601})))

(defmulti after-response
  "Run after the response to `method` has been sent."
  (fn [_state method _params _result] method))

(defmethod after-response :default [_ _ _ _] nil)

(defmethod handle "initialize" [_ _ _]
  {:protocolVersion protocol-version
   :agentCapabilities {:loadSession false
                       :promptCapabilities {:image false :audio false :embeddedContext false}}
   :authMethods []
   :agentInfo {:name "oml" :title "oml" :version "0.1.0"}})

(defmethod handle "session/new" [{:keys [sessions]} _ {:keys [cwd]}]
  (when (str/blank? cwd) (throw (ex-info "cwd is required" {:code -32602})))
  (let [id (str "sess_" (random-uuid))]
    (swap! sessions assoc id {:cwd cwd :messages [] :cancel nil})
    {:sessionId id}))

(defmethod after-response "session/new" [{:keys [init-errors] :as state} _ {:keys [cwd]} {:keys [sessionId]}]
  ;; The client learns the session id from the response, so session updates
  ;; can only follow it.
  (let [errors (into (vec init-errors) (init/load-project-init! cwd))]
    (doseq [e errors] (send-text! state sessionId (str "Warning: " e "\n")))
    (custom/run-hooks #'agent/session-start-hook {:session-id sessionId :cwd cwd})
    (advertise-commands! state sessionId)))

(defmethod handle "session/prompt" [{:keys [sessions] :as state} _ {:keys [sessionId] :as params}]
  (let [token (cancel/token)
        [old _] (swap-vals! sessions
                            (fn [s] (if (and (contains? s sessionId) (nil? (get-in s [sessionId :cancel])))
                                      (assoc-in s [sessionId :cancel] token)
                                      s)))
        session (get old sessionId)]
    (cond (nil? session) (throw (ex-info (str "Unknown session: " sessionId) {:code -32602}))
          (:cancel session) (throw (ex-info "A prompt is already running in this session" {:code -32602})))
    (try
      (let [text (prompt->text (:prompt params))
            {:keys [command input]} (agent/parse-prompt text)]
        (if command
          (command-turn state sessionId session command input)
          (agent-turn state sessionId session text token)))
      (catch Exception e
        ;; A cancelled turn may surface as an exception (e.g. a closed socket).
        (if (cancel/cancelled? token) {:stopReason "cancelled"} (throw e)))
      (finally
        (swap! sessions assoc-in [sessionId :cancel] nil)))))

(defmethod handle "session/cancel" [{:keys [sessions]} _ {:keys [sessionId]}]
  (some-> (get-in @sessions [sessionId :cancel]) cancel/cancel!)
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch

(defn- respond [{:keys [send!] :as state} {:keys [id method params]}]
  (let [[result error] (try [(handle state method params) nil]
                            (catch Exception e [nil e]))]
    (if error
      (do (when-not (= -32601 (:code (ex-data error))) (log "error in" method ":" (ex-message error)))
          (send! (error-response id (:code (ex-data error) -32603)
                                 (or (ex-message error) (str error)))))
      (do (send! {:id id :result result})
          (try (after-response state method params result)
               (catch Exception e (log "error after" method ":" (ex-message e))))))))

(defn- notify [state {:keys [method params]}]
  (try (handle state method params)
       (catch Exception e
         (when-not (= -32601 (:code (ex-data e))) (log "error in" method ":" (ex-message e))))))

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
        (:method msg) (notify state msg)
        :else nil))))   ; a response to a request we never send

(defn serve
  "Serve ACP on the given streams until `in` closes. Then cancel running
  turns (the client is gone) and give them a moment to answer.
  `init-errors` are reported to every new session."
  [in out {:keys [init-errors]}]
  (let [state {:sessions (atom {})
               :pending (atom [])
               :send! (writer-fn out)
               :init-errors init-errors}]
    (doseq [line (line-seq (io/reader in :encoding "UTF-8"))]
      (handle-line state line))
    (doseq [{t :cancel} (vals @(:sessions state))] (some-> t cancel/cancel!))
    (doseq [f @(:pending state)] (deref f 5000 nil))))

(defn -main [& _]
  ;; Whatever user code prints must not corrupt the ACP stream on stdout.
  (binding [*out* *err*]
    (let [errors (init/startup!)]
      (repl/start!)
      (log "ACP agent ready on stdio")
      (serve System/in System/out {:init-errors errors})))
  (shutdown-agents)
  (System/exit 0))

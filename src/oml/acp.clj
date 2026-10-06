(ns oml.acp
  "Chapter 8 (basic): the Agent Client Protocol, agent side.

  ACP is JSON-RPC 2.0, one JSON object per line, over the agent's stdin and
  stdout. The client (Toad, Zed, Emacs agent-shell) is the UI; we are the
  agent. stdout carries protocol messages only, so logs go to stderr.

  Implemented: initialize, session/new, session/prompt (streaming
  session/update notifications), session/cancel, slash commands
  (available_commands_update, sent after session/new and after every
  command). Agent to client: `request!` sends any request and waits for the
  answer; `request-permission` is session/request_permission on top of it.
  The core imposes no permission policy. Not yet: session/load, the
  client's fs/* and terminal/* methods.

  This namespace only speaks the protocol: each method is a `handle`
  multimethod (defmethod adds one), `render-event` turns loop events into
  session updates. What a session does lives in oml.agent, oml.init and
  oml.session.

  Spec: https://agentclientprotocol.com/protocol/overview
  Compare with pi: packages/coding-agent/src/modes/rpc/ (pi's own JSON RPC mode)"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [oml.agent :as agent]
            [oml.cancel :as cancel]
            [oml.init :as init]
            [oml.repl :as repl]
            [oml.session :as session :refer [log]]))

(def protocol-version 1)

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

;; ---------------------------------------------------------------------------
;; Mapping agent events to session/update notifications

(defn render-event
  "The ACP SessionUpdate for an agent-loop event, or nil if it has none."
  [{:keys [type] :as e}]
  (case type
    :text-delta {:sessionUpdate "agent_message_chunk" :content (text-block (:text e))}
    :thought-delta {:sessionUpdate "agent_thought_chunk" :content (text-block (:text e))}
    :tool-start (let [path (get-in e [:args :path])]
                  (cond-> {:sessionUpdate "tool_call"
                           :toolCallId (:id e)
                           :title (:title e)
                           :kind (:kind e)
                           :status "in_progress"
                           :rawInput (:args e)}
                    (string? path) (assoc :locations [{:path (session/resolve-path path)}])))
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
  "Send the commands to the client (available_commands_update)."
  [state session-id]
  (send-update! state session-id
                {:sessionUpdate "available_commands_update"
                 :availableCommands (for [v (agent/commands) :let [{:keys [doc hint]} (meta v)]]
                                      (cond-> {:name (agent/fn-name v) :description (agent/summary doc)}
                                        hint (assoc :input {:hint hint})))}))

;; ---------------------------------------------------------------------------
;; Requests to the client

(defn request!
  "Send the JSON-RPC request `method` with `params` to the ACP client of the
  current session and block until it answers. Returns the result; throws
  ex-info on an error response ({:code :data}), when the client goes away
  ({:closed true}) or when the running prompt is cancelled
  ({:cancelled true}). Options: :client (default: the current session's
  connection), :cancel (default: the current prompt's token).

  Responses are read by the stdin reader thread, so this must not be called
  on it: it works from prompts, commands, tools and nREPL, but not from
  oml.session/on-session-start (it throws there)."
  [method params & {:keys [client cancel]}]
  (let [s (session/session)
        {:keys [send! requests next-id reader closed?] :as client} (or client (:client s))
        cancel (or cancel (:cancel s))]
    (when-not client (throw (ex-info "No ACP client" {})))
    (when (identical? (Thread/currentThread) @reader)
      (throw (ex-info (str "request! on the ACP reader thread would never get its answer: " method) {})))
    (let [id (swap! next-id inc)
          p (promise)]
      (swap! requests assoc id p)
      (when @closed? (deliver p ::closed))
      (let [unregister (cancel/on-cancel cancel #(deliver p ::cancelled))]
        (try
          (send! {:id id :method method :params params})
          (let [r @p]
            (cond (= ::closed r)    (throw (ex-info (str "ACP client closed before answering " method) {:closed true}))
                  (= ::cancelled r) (throw (ex-info (str "Cancelled while waiting for " method) {:cancelled true}))
                  (:error r)        (throw (ex-info (str method " failed: " (get-in r [:error :message]))
                                                    (assoc (:error r) :method method)))
                  :else             (:result r)))
          (finally (unregister) (swap! requests dissoc id)))))))

(def permission-options
  "The default options of request-permission (ACP PermissionOption maps)."
  [{:optionId "allow_once" :name "Allow once" :kind "allow_once"}
   {:optionId "allow_always" :name "Always allow" :kind "allow_always"}
   {:optionId "reject_once" :name "Reject" :kind "reject_once"}
   {:optionId "reject_always" :name "Always reject" :kind "reject_always"}])

(defn request-permission
  "Ask the user of the current session about a tool call
  (session/request_permission). `tool-call` is an ACP ToolCallUpdate:
  {:toolCallId ...} plus optional :title :kind :rawInput etc. `options`
  are PermissionOption maps {:optionId :name :kind}, kind one of
  allow_once, allow_always, reject_once, reject_always (default
  permission-options).

  Returns the ACP outcome: {:outcome \"selected\" :optionId id :kind kind}
  (:kind added from `options`) or {:outcome \"cancelled\"}, also when the
  prompt is cancelled while waiting. No policy here: the caller decides."
  ([tool-call] (request-permission tool-call permission-options))
  ([tool-call options]
   (let [sid (or (:id (session/session)) (throw (ex-info "No current session" {})))
         outcome (try (:outcome (request! "session/request_permission"
                                          {:sessionId sid :toolCall tool-call :options options}))
                      (catch clojure.lang.ExceptionInfo e
                        (if (:cancelled (ex-data e)) {:outcome "cancelled"} (throw e))))]
     (cond-> outcome
       (= "selected" (:outcome outcome))
       (assoc :kind (some #(when (= (:optionId outcome) (:optionId %)) (:kind %)) options))))))

;; ---------------------------------------------------------------------------
;; Methods

(defmulti handle
  "Handle the ACP method `method`; return the result of a request (ignored
  for notifications). Throw ex-info with :code for a JSON-RPC error. A fn
  in the result's metadata under ::then runs after the response is sent."
  (fn [_state method _params] method))

(defmethod handle :default [_ method _]
  (throw (ex-info (str "Method not found: " method) {:code -32601})))

(defmethod handle "initialize" [_ _ _]
  {:protocolVersion protocol-version
   :agentCapabilities {:loadSession false
                       :promptCapabilities {:image false :audio false :embeddedContext false}}
   :authMethods []
   :agentInfo {:name "oml" :title "oml" :version "0.1.0"}})

(defmethod handle "session/new" [state _ {:keys [cwd]}]
  (when (str/blank? cwd) (throw (ex-info "cwd is required" {:code -32602})))
  (let [id (str "sess_" (random-uuid))]
    (session/create! {:id id :cwd cwd :client state
                      :on-event #(binding [session/*session* id]
                                   (some->> (render-event %) (send-update! state id)))})
    ;; The client learns the session id from the response, so session
    ;; updates can only follow it.
    (with-meta {:sessionId id}
      {::then #(session/with-session id
                 (init/session-started! (:init-errors state))
                 (advertise-commands! state id))})))

(defmethod handle "session/prompt" [state _ {:keys [sessionId] :as params}]
  (let [token (cancel/token)
        [old _] (swap-vals! session/sessions
                            (fn [s] (if (and (contains? s sessionId) (nil? (get-in s [sessionId :cancel])))
                                      (assoc-in s [sessionId :cancel] token)
                                      s)))
        session (get old sessionId)]
    (cond (nil? session) (throw (ex-info (str "Unknown session: " sessionId) {:code -32602}))
          (:cancel session) (throw (ex-info "A prompt is already running in this session" {:code -32602})))
    (try
      (session/with-session sessionId
        (let [{:keys [stop-reason command]} (agent/prompt (prompt->text (:prompt params)))]
          (when command (advertise-commands! state sessionId))
          {:stopReason (if (cancel/cancelled? token) "cancelled" (stop-reasons stop-reason))}))
      (catch Exception e
        ;; A cancelled turn may surface as an exception (e.g. a closed socket).
        (if (cancel/cancelled? token) {:stopReason "cancelled"} (throw e)))
      (finally
        (swap! session/sessions assoc-in [sessionId :cancel] nil)))))

(defmethod handle "session/cancel" [_ _ {:keys [sessionId]}]
  (some-> (:cancel (session/session sessionId)) cancel/cancel!)
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
          (when-let [then (::then (meta result))]
            (try (then) (catch Exception e (log "error after" method ":" (ex-message e)))))))))

(defn- notify [state {:keys [method params]}]
  (try (handle state method params)
       (catch Exception e
         (when-not (= -32601 (:code (ex-data e))) (log "error in" method ":" (ex-message e))))))

(defn- deliver-response
  "Hand a response from the client to the request! waiting for it."
  [{:keys [requests]} {:keys [id] :as msg}]
  (some-> (get @requests id) (deliver msg)))

(defn handle-line
  "Handle one incoming line. Prompts run on their own thread so that
  session/cancel, and responses to request!, can be read while a turn is
  in flight."
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
        (and (contains? msg :id) (or (contains? msg :result) (contains? msg :error)))
        (deliver-response state msg)
        :else nil))))

(defn serve
  "Serve ACP on the given streams until `in` closes. Then fail pending
  request!s, cancel running turns (the client is gone) and give them a
  moment to answer. `init-errors` are reported to every new session."
  [in out {:keys [init-errors]}]
  (let [state {:pending (atom [])          ; futures of running prompts
               :requests (atom {})         ; id -> promise of request!
               :next-id (atom 0)
               :closed? (atom false)
               :reader (atom (Thread/currentThread))
               :send! (writer-fn out)
               :init-errors init-errors}]
    (doseq [line (line-seq (io/reader in :encoding "UTF-8"))]
      (handle-line state line))
    (reset! (:closed? state) true)
    (doseq [p (vals @(:requests state))] (deliver p ::closed))
    (doseq [s (vals @session/sessions) :when (identical? state (:client s))]
      (some-> (:cancel s) cancel/cancel!))
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

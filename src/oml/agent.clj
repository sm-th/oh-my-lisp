(ns oml.agent
  "Chapter 2: the agent loop, as named steps (chapter 4).

  The whole idea of a coding agent fits in one loop: send the transcript to
  the model; if it answers with tool calls, run them, append their results
  and ask again; stop when it answers without tool calls.

  Every step is a public function the loop calls through its var, so
  redefining one (defn from init.clj, /eval, nREPL, advise!) changes the
  next turn: system-prompt, collect-tools, build-request, call-model,
  model-turn, run-tool-call, execute-tool, tool-result-message, stop-reason,
  parse-prompt, run-command. Hook variables add behaviour without
  replacing a step. Tools and commands are discovered from metadata (see
  oml.custom); the built-in ones and the prompt sections live in
  oml.ext.core.

  The loop knows nothing about ACP. It reports progress through the
  :on-event fn of its context.

  Compare with pi: packages/agent/src/agent-loop.ts"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [oml.cancel :as cancel]
            [oml.custom :as custom :refer [defsetting]]
            [oml.llm :as llm]))

;; ---------------------------------------------------------------------------
;; Settings and hooks

(defsetting max-turns
  "Model calls allowed for one prompt before stopping with max_turn_requests."
  30)

(defsetting enabled-tools
  "Names of the tools offered to the model, as a set of strings, e.g.
  #{\"read\" \"bash\"}. nil offers every discovered tool."
  nil)

(defonce ^{:oml/hook true
           :doc "Functions (f ctx) that make the system prompt: their non-blank
  results, in order, separated by blank lines. oml.ext.core adds its
  sections here; redefine one of them to change it, add-hook! your own."}
  system-prompt-functions [])

(defonce ^{:oml/hook true
           :doc "Functions (f request ctx) run before each model call. Return a
  replacement request ({:messages :tools}) or nil to keep it."}
  before-model-functions [])

(defonce ^{:oml/hook true
           :doc "Functions (f response request ctx) run after each model call.
  Return a replacement response ({:message :cancelled?}) or nil to keep it."}
  after-model-functions [])

(defonce ^{:oml/hook true
           :doc "Functions (f call ctx) run before each tool call, where call is
  {:id :name :args}. Return a rewritten call, nil to keep it, or the call
  with :block \"reason\" to refuse it; the model then sees the reason."}
  before-tool-functions [])

(defonce ^{:oml/hook true
           :doc "Functions (f result call ctx) run after each tool call, where
  result is {:content string :error? bool}. Return a replacement result or
  nil to keep it."}
  after-tool-functions [])

(defonce ^{:oml/hook true
           :doc "Functions (f session) run when a session starts, with
  {:session-id :cwd}. Return values are ignored."}
  session-start-hook [])

;; ---------------------------------------------------------------------------
;; Steps

(defn resolve-path
  "Resolve `path` against the session cwd (absolute paths and ~ pass through)."
  [cwd path]
  (when (str/blank? path) (throw (ex-info "path is required" {:tool-error true})))
  (let [path (if (str/starts-with? path "~") (str (fs/home) (subs path 1)) path)]
    (str (fs/normalize (fs/absolutize (if (fs/absolute? path) path (fs/path cwd path)))))))

(defn system-prompt
  "The system prompt: the text of every system-prompt-functions entry, in
  order, separated by blank lines."
  [ctx]
  (->> system-prompt-functions
       (keep #(% ctx))
       (remove str/blank?)
       (str/join "\n\n")))

(defn collect-tools
  "The tool vars offered to the model this turn: every discovered tool,
  narrowed to `enabled-tools` when that is set."
  [_ctx]
  (cond->> (custom/tools)
    enabled-tools (filter #(contains? enabled-tools (custom/tool-name %)))))

(defn build-request
  "The model request for a transcript: a fresh system prompt in front of
  `messages`, and the specs of the collected tools."
  [ctx messages]
  (let [prompt (system-prompt ctx)]
    {:messages (cond->> (vec messages)
                 (not (str/blank? prompt)) (into [{:role "system" :content prompt}]))
     :tools (mapv custom/tool-spec (collect-tools ctx))}))

(defn call-model
  "Send `request` to the model. Returns {:message :cancelled?}."
  [ctx request]
  (llm/stream-chat (llm/config)
                   (assoc request :on-event (:on-event ctx (fn [_])) :cancel (:cancel ctx))))

(defn model-turn
  "One model call with its hooks: build-request, before-model-functions,
  call-model, after-model-functions. Returns the response."
  [ctx messages]
  (let [request (custom/run-hook-filter #'before-model-functions (build-request ctx messages) ctx)]
    (custom/run-hook-filter #'after-model-functions (call-model ctx request) request ctx)))

(defn parse-arguments
  "The arguments of a tool call: a JSON object string, parsed to a map."
  [s]
  (if (str/blank? s)
    {}
    (let [v (json/parse-string s true)]
      (if (map? v) v (throw (ex-info "tool arguments must be a JSON object" {}))))))

(defn find-tool
  "The collected tool var named `tool-name`, or nil."
  [ctx tool-name]
  (some #(when (= tool-name (custom/tool-name %)) %) (collect-tools ctx)))

(defn execute-tool
  "Call tool var `v` as (v ctx args). Returns {:content string :error? bool};
  never throws, because the model should see the failure and recover."
  [ctx v args]
  (try
    {:content (str (v ctx args)) :error? false}
    (catch Exception e
      {:content (or (ex-message e) (str e)) :error? true})))

(defn run-tool-call
  "Run one OpenAI-format tool call: before-tool-functions (which may rewrite
  or block it), the tool, after-tool-functions. Emits :tool-start and
  :tool-end. Returns {:content :error?}."
  [ctx {:keys [id function]}]
  (let [emit   (:on-event ctx (fn [_]))
        parsed (try (parse-arguments (:arguments function))
                    (catch Exception e {::error (or (ex-message e) (str e))}))
        call   {:id id :name (:name function) :args (if (::error parsed) {} parsed)}
        call   (try (custom/run-hook-filter #'before-tool-functions call ctx)
                    (catch Exception e (assoc call :block (str "before-tool hook failed: " (ex-message e)))))
        tool   (find-tool ctx (:name call))]
    (emit {:type :tool-start :id id :name (:name call) :args (:args call)
           :kind (if tool (custom/tool-kind tool) "other")
           :title (if tool (custom/tool-title tool (:args call)) (:name call))})
    (let [result (cond (:block call)    {:content (str "Blocked: " (:block call)) :error? true}
                       (::error parsed) {:content (::error parsed) :error? true}
                       (nil? tool)      {:content (str "Unknown tool: " (:name call)) :error? true}
                       :else            (execute-tool ctx tool (:args call)))
          result (try (custom/run-hook-filter #'after-tool-functions result call ctx)
                      (catch Exception e {:content (str "after-tool hook failed: " (ex-message e))
                                          :error? true}))]
      (emit (assoc result :type :tool-end :id id))
      result)))

(defn tool-result-message
  "The transcript message carrying `result` back to the model."
  [{:keys [id]} result]
  {:role "tool" :tool_call_id id :content (:content result)})

(defn stop-reason
  "Whether the turn ends after a model call and its tools: nil to go on, or
  :cancelled, :end-turn (no tool calls) or :max-turns."
  [ctx {:keys [turn response]}]
  (cond (or (:cancelled? response) (cancel/cancelled? (:cancel ctx))) :cancelled
        (empty? (:tool_calls (:message response)))                     :end-turn
        (>= turn max-turns)                                             :max-turns))

(defn run
  "Run the loop on `messages` (the transcript so far, ending with the new
  user message, without a system message) until stop-reason says stop.

  ctx: {:cwd :cancel :on-event :session-id}; tools and hooks receive it.
  :on-event receives {:type :text-delta|:tool-start|:tool-end ...}.

  Returns {:messages <transcript with everything appended>
           :stop-reason :end-turn | :cancelled | :max-turns}."
  [ctx messages]
  (loop [messages (vec messages)
         turn 1]
    (if (cancel/cancelled? (:cancel ctx))
      {:messages messages :stop-reason :cancelled}
      (let [{:keys [message] :as response} (model-turn ctx messages)
            messages (cond-> messages
                       (and message (or (seq (:content message)) (seq (:tool_calls message))))
                       (conj message))
            ;; Every tool call must get a tool message, even when cancelled,
            ;; or the next request is rejected by the API.
            messages (reduce (fn [messages call]
                               (conj messages
                                     (tool-result-message
                                      call
                                      (if (cancel/cancelled? (:cancel ctx))
                                        {:content "Cancelled before it ran." :error? true}
                                        (run-tool-call ctx call)))))
                             messages (:tool_calls message))]
        (if-let [reason (stop-reason ctx {:turn turn :response response :messages messages})]
          {:messages messages :stop-reason reason}
          (recur messages (inc turn)))))))

;; ---------------------------------------------------------------------------
;; Prompts and commands

(defn parse-prompt
  "Classify the user's text: {:command var :input string} when it starts
  with /name of a discovered command, else {:text text}. An unknown /x is
  ordinary text for the model."
  [text]
  (or (when-let [[_ cmd input] (re-matches #"(?s)\s*/(\S+)\s*(.*)" (str text))]
        (when-let [v (some #(when (= cmd (custom/command-name %)) %) (custom/commands))]
          {:command v :input (str/trim input)}))
      {:text text}))

(defn run-command
  "Run command var `v` as (v ctx input). Returns its output: what it printed,
  then its return value (strings as is, other values printed, nil omitted).
  Never throws; an error becomes part of the output."
  [ctx v input]
  (let [out (java.io.StringWriter.)]
    (try
      (let [r (binding [*out* out] (v ctx input))]
        (str out (cond (nil? r) nil (string? r) r :else (pr-str r))))
      (catch Exception e
        (str out "Error in /" (custom/command-name v) ": " (or (ex-message e) (str e)))))))

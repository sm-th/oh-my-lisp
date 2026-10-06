(ns oml.agent
  "Chapter 2: the agent loop, as named steps (chapter 4).

  The whole idea of a coding agent fits in one loop: send the transcript to
  the model; if it answers with tool calls, run them, append their results
  and ask again; stop when it answers without tool calls.

  Every step is a public function the loop calls through its var, so
  redefining it (defn from init.clj, /eval, nREPL) or wrapping it
  (oml.custom/advise!) changes the next turn: system-prompt, tools,
  build-request, call-model, run-tool-call, execute-tool,
  tool-result-message, stop-reason, parse-prompt, run-command, prompt.

  Tools and commands are the public functions with a docstring in
  `tool-namespaces` and `command-namespaces`; see `tools` and `parameters`.

  The loop works on the current session (oml.session): its transcript, its
  cancel token, and its :on-event fn for progress. It knows nothing about
  ACP.

  Compare with pi: packages/agent/src/agent-loop.ts"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [oml.cancel :as cancel]
            [oml.llm :as llm]
            [oml.session :as session]))

(def max-turns
  "Model calls allowed for one prompt before stopping with max_turn_requests."
  30)

(def tool-namespaces
  "Namespaces whose public functions with a docstring are the tools offered
  to the model. On a name clash the later namespace wins."
  '[oml.tools])

(def command-namespaces
  "Namespaces whose public functions with a docstring are the slash
  commands. On a name clash the later namespace wins."
  '[oml.commands])

;; ---------------------------------------------------------------------------
;; Tools and commands are functions in namespaces

(defn- public-fns
  "The public functions with a docstring in namespace `ns-sym`, which is
  required if needed (a failure is logged and the namespace skipped)."
  [ns-sym]
  (when-let [ns (or (find-ns ns-sym)
                    (try (require ns-sym) (find-ns ns-sym)
                         (catch Exception e (session/log "cannot load" ns-sym ":" (ex-message e)))))]
    (for [v (vals (ns-publics ns))
          :let [m (meta v)]
          :when (and (:doc m) (not (:macro m)) (fn? @v))]
      v)))

(defn fn-name
  "The unqualified name of var `v`, as a string: the name of a tool or command."
  [v]
  (str (:name (meta v))))

(defn- by-name [namespaces]
  (vals (reduce #(assoc %1 (fn-name %2) %2) (sorted-map) (mapcat public-fns namespaces))))

(defn tools
  "The tool vars offered to the model: the public functions with a
  docstring in tool-namespaces, sorted by name."
  []
  (by-name tool-namespaces))

(defn commands
  "The command vars: the public functions with a docstring in
  command-namespaces, sorted by name."
  []
  (by-name command-namespaces))

(defn doc-text
  "A docstring as text without source line breaks and indentation;
  paragraphs are kept."
  [s]
  (->> (str/split (str/trim (str s)) #"\n\s*\n")
       (map #(str/replace (str/trim %) #"\s*\n\s*" " "))
       (str/join "\n\n")))

(defn summary
  "The first sentence of a docstring, for one-line listings."
  [s]
  (let [p (first (str/split (doc-text s) #"\n\n"))]
    (or (second (re-find #"^(.*?[.!?])(\s+[A-Z]|$)" p)) p)))

(defn parameters
  "The JSON schema of tool var `v`'s argument map. The keys come from the
  destructuring of its first parameter, {:keys [path offset]}; each is a
  required string unless the optional :params entry of the attr-map says
  otherwise, as [type description & flags], e.g.

    {:params {:path [:string \"File path\"] :offset [:integer \"First line\" :optional]}}

  A key with an :or default, or the flag :optional, is optional. Keys only
  in :params are added."
  [v]
  (let [{:keys [params arglists]} (meta v)
        p (ffirst arglists)
        k #(keyword (name %))
        ks (distinct (concat (map k (when (map? p) (:keys p))) (keys params)))
        defaults (set (map k (when (map? p) (keys (:or p)))))
        optional? #(or (defaults %) (some #{:optional} (drop 2 (get params %))))]
    {:type "object"
     :properties (into {} (for [key ks :let [[t d] (get params key)]]
                            [key (cond-> {:type (name (or t :string))} d (assoc :description d))]))
     :required (mapv name (remove optional? ks))}))

(defn tool-spec
  "What the model sees of tool var `v`: name, docstring, parameter schema."
  [v]
  {:name (fn-name v) :description (doc-text (:doc (meta v))) :parameters (parameters v)})

;; ---------------------------------------------------------------------------
;; Steps

(defn system-prompt
  "The system prompt, built for every model request. Redefine it, or
  advise! it to add a section."
  []
  (str/join "\n\n"
            ["You are oml, a coding agent working in a user's project."
             (str "The working directory is " (session/cwd) ".")
             (str "Use the tools to inspect and change files and to run commands: "
                  "read before you edit; edit needs an exact, unique old_text; prefer edit over write for existing files.\n"
                  "Be concise. When the task is done, answer with a short summary and no tool call.")]))

(defn build-request
  "The model request for `messages`: the system prompt in front, and the
  specs of the tools."
  [messages]
  {:messages (into [{:role "system" :content (system-prompt)}] messages)
   :tools (mapv tool-spec (tools))})

(defn- token [] (:cancel (session/session)))

(defn call-model
  "Send `request` ({:messages :tools}) to the model, streaming its events to
  the session; returns {:message :usage :cancelled?}. A :model in the
  request overrides oml.llm/model; :silent? true shows nothing."
  [{:keys [model silent?] :as request}]
  (llm/stream-chat (cond-> (llm/config) model (assoc :model model))
                   (assoc (select-keys request [:messages :tools])
                          :on-event (if silent? (fn [_]) session/emit!)
                          :cancel (token))))

(defn parse-arguments
  "The arguments of a tool call: a JSON object string, parsed to a map."
  [s]
  (if (str/blank? s)
    {}
    (let [v (json/parse-string s true)]
      (if (map? v) v (throw (ex-info "tool arguments must be a JSON object" {}))))))

(defn find-tool
  "The tool var named `tool-name`, or nil."
  [tool-name]
  (some #(when (= tool-name (fn-name %)) %) (tools)))

(defn execute-tool
  "Run `call` ({:id :name :args}) with its tool; returns {:content string
  :error? bool}, or throws (run-tool-call turns that into an error result
  the model reads). The client already shows the call, so advise! this to
  check, block or rewrite calls (a permission policy) or their results."
  [{:keys [name args]}]
  (if-let [v (find-tool name)]
    {:content (str (v args)) :error? false}
    {:content (str "Unknown tool: " name) :error? true}))

(defn run-tool-call
  "Run one OpenAI-format tool call: show it (:tool-start), execute-tool,
  show the result (:tool-end). Returns {:content :error?}; never throws."
  [{:keys [id function]}]
  (let [args (try (parse-arguments (:arguments function)) (catch Exception e e))
        call {:id id :name (:name function) :args (if (map? args) args {})}
        v (find-tool (:name call))
        title (:title (meta v))]
    (session/emit! {:type :tool-start :id id :name (:name call) :args (:args call)
                    :kind (:kind (meta v) "other")
                    :title (or (when title (try (title (:args call)) (catch Exception _ nil)))
                               (:name call))})
    (let [result (if (map? args)
                   (try (execute-tool call)
                        (catch Exception e {:content (or (ex-message e) (str e)) :error? true}))
                   {:content (ex-message args) :error? true})]
      (session/emit! (assoc result :type :tool-end :id id))
      result)))

(defn tool-result-message
  "The transcript message carrying `result` back to the model."
  [{:keys [id]} result]
  {:role "tool" :tool_call_id id :content (:content result)})

(defn stop-reason
  "Whether the turn ends after a model call and its tools: nil to go on, or
  :cancelled, :end-turn (no tool calls) or :max-turns."
  [{:keys [turn response]}]
  (cond (or (:cancelled? response) (cancel/cancelled? (token))) :cancelled
        (empty? (:tool_calls (:message response)))               :end-turn
        (>= turn max-turns)                                       :max-turns))

(defn run
  "Run the loop on the current session's transcript, which ends with the
  user's message, until stop-reason says stop; return that reason. Each
  assistant message is appended together with its tool results, so code
  appending messages meanwhile never splits a tool_calls message from its
  tool messages."
  []
  (loop [turn 1]
    (if (cancel/cancelled? (token))
      :cancelled
      (let [{:keys [message] :as response} (call-model (build-request (session/transcript)))
            keep? (and message (or (seq (:content message)) (seq (:tool_calls message))))
            ;; Every tool call must get a tool message, even when cancelled,
            ;; or the next request is rejected by the API.
            results (mapv (fn [call]
                            (tool-result-message
                             call
                             (if (cancel/cancelled? (token))
                               {:content "Cancelled before it ran." :error? true}
                               (run-tool-call call))))
                          (:tool_calls message))]
        (apply session/append-message! (cond->> results keep? (cons message)))
        (or (stop-reason {:turn turn :response response})
            (recur (inc turn)))))))

;; ---------------------------------------------------------------------------
;; Calling tools and the model from Lisp

(defn call-tool
  "Call a tool as the model would, through run-tool-call: the client shows
  the call and advice on execute-tool applies. `tool` is a var, a symbol or
  a name; `args` a map. Returns {:content :error?}. With :record? true the
  call and its result are appended to the transcript as if the model had
  made it, so the model sees it next turn."
  [tool args & {:keys [record?]}]
  (let [call {:id (str "call_" (subs (str/replace (str (random-uuid)) "-" "") 0 24))
              :type "function"
              :function {:name (if (var? tool) (fn-name tool) (name tool))
                         :arguments (json/generate-string (or args {}))}}
        result (run-tool-call call)]
    (when record?
      (session/append-message! {:role "assistant" :content nil :tool_calls [call]}
                               (tool-result-message call result)))
    result))

(defn complete
  "One model call without tools; returns the answer text. `prompt` is a
  string (one user message) or a vector of messages. Options: :system (a
  system message in front), :model (instead of oml.llm/model). Goes
  through call-model (so its advice applies) and shows nothing to the
  user. Cancelling the running prompt cancels it (and it throws)."
  [prompt & {:keys [system model]}]
  (let [messages (if (string? prompt) [{:role "user" :content prompt}] (vec prompt))
        {:keys [message cancelled?]}
        (call-model {:messages (cond->> messages system (into [{:role "system" :content system}]))
                     :tools [] :model model :silent? true})]
    (when (or cancelled? (cancel/cancelled? (token)))
      (throw (ex-info "Cancelled" {:cancelled true})))
    (:content message)))

;; ---------------------------------------------------------------------------
;; Prompts and commands

(defn parse-prompt
  "Classify the user's text: {:command var :input string} when it starts
  with /name of a command, else {:text text}. An unknown /x is ordinary
  text for the model."
  [text]
  (or (when-let [[_ cmd input] (re-matches #"(?s)\s*/(\S+)\s*(.*)" (str text))]
        (when-let [v (some #(when (= cmd (fn-name %)) %) (commands))]
          {:command v :input (str/trim input)}))
      {:text text}))

(defn run-command
  "Run command var `v` as (v input) and show what it printed, then its
  return value (strings as is, other values printed, nil omitted). Never
  throws; an error is shown instead."
  [v input]
  (let [out (java.io.StringWriter.)
        r (try (let [r (binding [*out* out] (v input))]
                 (cond (nil? r) nil (string? r) r :else (pr-str r)))
               (catch Exception e
                 (str "Error in /" (fn-name v) ": " (or (ex-message e) (str e)))))
        text (str out r)]
    (when-not (str/blank? text) (session/say text))))

(defn prompt
  "Handle the user's `text` in the current session: run the command it
  names, or append it to the transcript and run the loop. Returns
  {:stop-reason :end-turn|:cancelled|:max-turns :command var-or-nil}."
  [text]
  (let [{:keys [command input]} (parse-prompt text)]
    (if command
      (do (run-command command input) {:stop-reason :end-turn :command command})
      (do (session/append-message! {:role "user" :content text})
          {:stop-reason (run)}))))

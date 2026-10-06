(ns oml.agent
  "Chapter 2: the agent loop.

  The whole idea of a coding agent fits in one loop: send the transcript to
  the model; if it answers with tool calls, run them, append their results
  and ask again; stop when it answers without tool calls.

  The loop knows nothing about HTTP or ACP. It takes an `llm` fn (see
  oml.llm/stream-chat for the real one; tests pass a scripted fake) and a
  vector of tools, and reports progress through `on-event`.

  Compare with pi: packages/agent/src/agent-loop.ts"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [oml.cancel :as cancel]))

(def default-max-turns 30)

(defn system-prompt [cwd]
  (str/join "\n"
            ["You are oml, a coding agent working in a user's project."
             (str "The working directory is " cwd ".")
             "Use the tools to inspect and change files and to run commands:"
             "read before you edit; edit needs an exact, unique old_text; prefer edit over write for existing files."
             "Be concise. When the task is done, answer with a short summary and no tool call."]))

(defn- parse-arguments [s]
  (if (str/blank? s)
    {}
    (let [v (json/parse-string s true)]
      (if (map? v) v (throw (ex-info "tool arguments must be a JSON object" {}))))))

(defn run-tool
  "Execute one OpenAI-format tool call. Returns {:content string :error? bool};
  never throws, because the model should see the failure and recover."
  [tools-by-name ctx {:keys [function]}]
  (let [tool (get tools-by-name (:name function))]
    (if-not tool
      {:content (str "Unknown tool: " (:name function)) :error? true}
      (try
        {:content (str ((:execute tool) ctx (parse-arguments (:arguments function))))
         :error? false}
        (catch Exception e
          {:content (or (ex-message e) (str e)) :error? true})))))

(defn- tool-title [tool args fallback]
  (or (when-let [f (:title tool)] (try (f args) (catch Exception _ nil)))
      fallback))

(defn run
  "Run the loop until the model stops calling tools.

  opts:
    :llm       (fn [{:keys [messages tools on-event cancel]}]) -> {:message :cancelled?}
    :tools     vector of tool maps (oml.tools)
    :messages  transcript so far, ending with the new user message
    :cwd       working directory for tools
    :on-event  receives {:type :text-delta|:tool-start|:tool-end ...}
    :cancel    oml.cancel token
    :max-turns model calls allowed (default 30)

  Returns {:messages <transcript with everything appended>
           :stop-reason :end-turn | :cancelled | :max-turns}."
  [{:keys [llm tools messages cwd on-event cancel max-turns]
    :or {on-event (fn [_]) max-turns default-max-turns}}]
  (let [by-name (into {} (map (juxt :name identity)) tools)
        ctx     {:cwd cwd :cancel cancel}]
    (loop [messages messages
           turn 1]
      (if (cancel/cancelled? cancel)
        {:messages messages :stop-reason :cancelled}
        (let [{:keys [message cancelled?]}
              (llm {:messages messages :tools tools :on-event on-event :cancel cancel})
              messages (cond-> messages
                         (and message (or (seq (:content message)) (seq (:tool_calls message))))
                         (conj message))
              calls (:tool_calls message)]
          (cond
            cancelled?   {:messages messages :stop-reason :cancelled}
            (empty? calls) {:messages messages :stop-reason :end-turn}
            :else
            ;; Every tool call must get a tool message, even when cancelled,
            ;; or the next request is rejected by the API.
            (let [messages
                  (reduce
                   (fn [messages {:keys [id function] :as call}]
                     (let [tool   (get by-name (:name function))
                           args   (try (parse-arguments (:arguments function)) (catch Exception _ {}))
                           result (if (cancel/cancelled? cancel)
                                    {:content "Cancelled before it ran." :error? true}
                                    (do (on-event {:type :tool-start :id id :name (:name function)
                                                   :args args :kind (:kind tool "other")
                                                   :title (tool-title tool args (:name function))})
                                        (let [r (run-tool by-name ctx call)]
                                          (on-event (assoc r :type :tool-end :id id))
                                          r)))]
                       (conj messages {:role "tool" :tool_call_id id :content (:content result)})))
                   messages calls)]
              (cond
                (cancel/cancelled? cancel) {:messages messages :stop-reason :cancelled}
                (>= turn max-turns)        {:messages messages :stop-reason :max-turns}
                :else (recur messages (inc turn))))))))))

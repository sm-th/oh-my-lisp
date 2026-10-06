(ns oml.acp-test
  "End-to-end: run `bb acp` as a subprocess with the scripted client in
  oml.acp-client, against the in-process fake model server."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.acp-client :refer [temp-dir start-agent stop-agent next-msg
                                    collect-until-response request! updates
                                    collect-until-commands new-session! prompt!
                                    message-text system-prompt nrepl-eval]]
            [oml.fake-llm :as fake]))

;; ---------------------------------------------------------------------------

(deftest acp-session-with-a-tool-call
  (let [dir (temp-dir)
        _ (spit (str dir "/notes.txt") "hello from notes\n")
        srv (fake/start! [(concat (fake/text-chunks "Let me look.")
                                  (fake/tool-call-chunks "call_1" "read" "{\"path\":" "\"notes.txt\"}"))
                          (fake/text-chunks "It says " "hello.")])
        agent (start-agent (:url srv))]
    (try
      (let [[_ init] (request! agent 1 "initialize" {:protocolVersion 1 :clientCapabilities {}})
            [_ sess] (request! agent 2 "session/new" {:cwd dir :mcpServers []})
            sid (get-in sess [:result :sessionId])
            _ (collect-until-commands agent)
            [notes resp] (request! agent 3 "session/prompt"
                                   {:sessionId sid :prompt [{:type "text" :text "What is in notes.txt?"}]})
            ups (updates notes)
            [_ unknown] (request! agent 4 "no/such_method" {})]
        (is (= 1 (get-in init [:result :protocolVersion])))
        (is (= [] (get-in init [:result :authMethods])))
        (is (string? sid))
        (is (every? #(and (= "session/update" (:method %)) (= sid (get-in % [:params :sessionId]))) notes))
        (is (= ["agent_message_chunk" "tool_call" "tool_call_update" "agent_message_chunk" "agent_message_chunk"]
               (map :sessionUpdate ups)))
        (is (= {:type "text" :text "Let me look."} (:content (first ups))))
        (is (= {:sessionUpdate "tool_call" :toolCallId "call_1" :title "Read notes.txt"
                :kind "read" :status "in_progress"}
               (select-keys (nth ups 1) [:sessionUpdate :toolCallId :title :kind :status])))
        (is (= "completed" (:status (nth ups 2))))
        (is (str/includes? (get-in (nth ups 2) [:content 0 :content :text]) "hello from notes"))
        (is (= {:stopReason "end_turn"} (:result resp)))
        (is (= -32601 (get-in unknown [:error :code])))
        (is (= ["system" "user" "assistant" "tool"]
               (map :role (:messages (second @(:requests srv)))))
            "the second model call carries the tool result"))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest acp-cancel
  (let [dir (temp-dir)
        srv (fake/start! [[{:choices [{:delta {:content "Working"}}]} {:hang true}]
                          (fake/text-chunks "Again.")])
        agent (start-agent (:url srv))]
    (try
      (let [[sid _] (new-session! agent dir)
            _ ((:send! agent) {:id 3 :method "session/prompt"
                               :params {:sessionId sid :prompt [{:type "text" :text "go"}]}})
            first-chunk (next-msg agent)
            _ ((:send! agent) {:method "session/cancel" :params {:sessionId sid}})
            [_ resp] (collect-until-response agent 3)
            [_ resp2] (prompt! agent sid "again")]
        (is (= "agent_message_chunk" (get-in first-chunk [:params :update :sessionUpdate])))
        (is (= {:stopReason "cancelled"} (:result resp)))
        (is (= {:stopReason "end_turn"} (:result resp2)) "the session is usable after a cancel")
        (is (= ["system" "user" "assistant" "user"]
               (map :role (:messages (second @(:requests srv)))))
            "the partial answer stays in the transcript"))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest slash-commands-and-introspection
  (let [dir (temp-dir)
        srv (fake/start! [(fake/text-chunks "plain answer")
                          (fake/text-chunks "arr")])
        agent (start-agent (:url srv))]
    (try
      (let [[sid ups] (new-session! agent dir)
            cmds (:availableCommands (last ups))
            by-name (into {} (map (juxt :name identity)) cmds)
            say #(message-text (first (prompt! agent sid %)))]
        (testing "available_commands_update advertises the commands"
          (is (= #{"doc" "apropos" "source" "eval" "reload" "settings" "tools" "commands"} (set (keys by-name))))
          (is (= {:name "doc"
                  :description "Show a var's documentation (clojure.repl/doc), its value if it is not a function, its advice and where it is defined."
                  :input {:hint "symbol, e.g. oml.agent/max-turns or run-tool-call"}}
                 (by-name "doc"))))
        (testing "/doc shows doc, current value and source location of a setting"
          (let [[ups resp] (prompt! agent sid "/doc oml.agent/max-turns")
                text (message-text ups)]
            (is (= "end_turn" (get-in resp [:result :stopReason])))
            (is (str/starts-with? text "oml.agent/max-turns\n"))
            (is (str/includes? text "Model calls allowed"))
            (is (str/includes? text "Value: `30`"))
            (is (re-find #"agent\.clj:\d+" text))))
        (testing "secret values are not shown"
          (let [text (say "/doc oml.llm/api-key")]
            (is (str/includes? text "Value: `<hidden>`"))
            (is (not (str/includes? text "test-key"))))
          (is (not (str/includes? (say "/settings") "test-key")))
          (is (str/includes? (say "/settings") "`oml.agent/max-turns` = `30`")))
        (testing "/doc finds unqualified names: steps and tools"
          (is (str/includes? (say "/doc run-tool-call") "([{:keys [id function]}])"))
          (is (str/starts-with? (say "/doc bash") "oml.tools/bash")))
        (testing "/apropos and /source"
          (let [text (say "/apropos tool")]
            (is (str/includes? text "`oml.agent/run-tool-call`"))
            (is (str/includes? text "`oml.agent/call-tool`"))
            (is (str/includes? text "`oml.agent/execute-tool`"))
            (is (not (str/includes? text "clojure.core"))))
          (is (str/starts-with? (say "/source oml.agent/stop-reason") "(defn stop-reason")))
        (testing "/tools and /commands list what is there"
          (is (str/includes? (say "/tools") "- `bash` (oml.tools/bash) Run a bash command"))
          (is (str/includes? (say "/commands") "- `/eval` Evaluate Clojure forms")))
        (testing "/eval evaluates in the agent process"
          (is (= "3" (say "/eval (+ 1 2)")))
          (is (= "hi\n:done" (say "/eval (println \"hi\") :done")))
          (is (str/includes? (say "/eval (/ 1 0)") "Error in /eval: Divide by zero")))
        (is (empty? @(:requests srv)) "commands never call the model")
        (testing "an unknown /x is text for the model"
          (prompt! agent sid "/nope do it")
          (is (= "/nope do it" (:content (last (:messages (first @(:requests srv))))))))
        (testing "a defn from user code changes the next turn"
          (say "/eval (in-ns 'oml.agent) (defn system-prompt [] \"You are a pirate.\")")
          (prompt! agent sid "hello")
          (is (= "You are a pirate." (system-prompt (second @(:requests srv))))))
        (testing "a function defined in a command namespace is a command, advertised after /eval"
          (let [[ups _] (prompt! agent sid "/eval (in-ns 'oml.commands) (defn shout \"Shout it.\" [s] (str s \"!\"))")]
            (is (some #(= "shout" (:name %)) (:availableCommands (last ups)))))
          (is (= "hey!" (say "/shout hey"))))
        (testing "/reload re-reads init files and re-advertises"
          (let [[ups resp] (prompt! agent sid "/reload")]
            (is (str/starts-with? (message-text ups) "Reloaded "))
            (is (= "available_commands_update" (:sessionUpdate (last ups))))
            (is (= "end_turn" (get-in resp [:result :stopReason]))))))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest init-files-load-path-and-tool-namespaces
  (let [config (temp-dir)
        dir (temp-dir)
        _ (fs/create-dirs (fs/path config "oml" "lisp" "my"))
        _ (spit (str (fs/path config "oml" "lisp" "my" "ext.clj"))
                (pr-str '(ns my.ext)
                        '(defn shout "Upper-case the given text."
                           {:params {:text [:string "Text to shout"]}}
                           [{:keys [text]}]
                           (clojure.string/upper-case text))))
        _ (spit (str (fs/path config "oml" "init.clj"))
                (pr-str '(require '[oml.custom :refer [setq]])
                        '(setq oml.llm/model "init/model"
                               oml.agent/tool-namespaces '[oml.tools my.ext])))
        _ (fs/create-dirs (fs/path dir ".oml"))
        _ (spit (str (fs/path dir ".oml" "init.clj"))
                (pr-str '(ns project.init (:require [clojure.string :as str] [oml.custom :as c]))
                        '(c/advise! #'oml.agent/execute-tool :project/no-rm-rf
                                    (fn [execute {:keys [name args] :as call}]
                                      (if (and (= "bash" name) (str/includes? (:command args "") "rm -rf"))
                                        {:content "Blocked: rm -rf is not allowed here" :error? true}
                                        (execute call))))))
        srv (fake/start! [(fake/tool-call-chunks "c1" "shout" "{\"text\":\"hi\"}")
                          (fake/tool-call-chunks "c2" "bash" "{\"command\":\"rm -rf /tmp/nothing-here\"}")
                          (fake/text-chunks "done")
                          (fake/text-chunks "again")])
        agent (start-agent (:url srv) {:config-dir config})]
    (try
      (let [[sid _] (new-session! agent dir)
            [ups resp] (prompt! agent sid "shout hi, then clean up")
            [r1 r2 r3] @(:requests srv)
            tool-msgs (filter #(= "tool" (:role %)) (:messages r3))]
        (is (= "end_turn" (get-in resp [:result :stopReason])))
        (is (= "init/model" (:model r1)) "the user init file changed a setting")
        (is (= {:type "object" :properties {:text {:type "string" :description "Text to shout"}}
                :required ["text"]}
               (:parameters (:function (first (filter #(= "shout" (get-in % [:function :name])) (:tools r1))))))
            "a tool from a namespace on the load path is offered")
        (is (= "HI" (:content (first tool-msgs))) "and runs")
        (is (= "Blocked: rm -rf is not allowed here" (:content (second tool-msgs)))
            "the project init file's advice blocked the call and the model sees why")
        (is (= ["completed" "failed"] (keep :status (filter #(= "tool_call_update" (:sessionUpdate %)) ups))))
        (is (some? r2))
        (testing "/doc shows the advice"
          (is (str/includes? (message-text (first (prompt! agent sid "/doc oml.agent/execute-tool")))
                             "Advised: [:project/no-rm-rf]")))
        (testing "ns-unmap removes a tool"
          (prompt! agent sid "/eval (ns-unmap 'my.ext 'shout)")
          (prompt! agent sid "again")
          (is (not-any? #(= "shout" (get-in % [:function :name])) (:tools (last @(:requests srv)))))))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest init-errors-are-reported
  (let [config (temp-dir)
        dir (temp-dir)
        _ (fs/create-dirs (fs/path config "oml"))
        _ (spit (str (fs/path config "oml" "init.clj")) "(def ok 1)\n(this-is-not-defined)\n")
        _ (fs/create-dirs (fs/path dir ".oml"))
        _ (spit (str (fs/path dir ".oml" "init.clj")) "(defn broken [\n")
        srv (fake/start! [(fake/text-chunks "still here")])
        agent (start-agent (:url srv) {:config-dir config})]
    (try
      (let [[sid ups] (new-session! agent dir)
            warnings (message-text ups)
            [ups2 resp] (prompt! agent sid "hello")]
        (is (str/includes? warnings (str "Warning: Error loading " (fs/path config "oml" "init.clj") " (line 2)")))
        (is (str/includes? warnings "this-is-not-defined"))
        (is (str/includes? warnings (str "Error loading " (fs/path dir ".oml" "init.clj"))))
        (is (= "available_commands_update" (:sessionUpdate (last ups))) "commands still load")
        (is (= "end_turn" (get-in resp [:result :stopReason])))
        (is (= "still here" (message-text ups2)) "the agent keeps working"))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest nrepl-into-the-running-agent
  (let [dir (temp-dir)
        srv (fake/start! [(fake/text-chunks "first") (fake/text-chunks "second")])
        agent (start-agent (:url srv))]
    (try
      (let [[sid _] (new-session! agent dir)
            port-file (fs/path (:state-dir agent) "oml" "nrepl-port")
            _ (is (fs/exists? port-file))
            port (parse-long (str/trim (slurp (str port-file))))
            _ (prompt! agent sid "one")
            values (nrepl-eval port (str "(in-ns 'oml.agent)"
                                         " (defn system-prompt [] \"Redefined over nREPL.\")"
                                         " (system-prompt)"))
            _ (prompt! agent sid "two")
            [r1 r2] @(:requests srv)]
        (is (= "\"Redefined over nREPL.\"" (last values)))
        (is (str/starts-with? (system-prompt r1) "You are oml"))
        (is (str/starts-with? (system-prompt r2) "Redefined over nREPL.")
            "the next prompt uses the function redefined over nREPL"))
      (finally (stop-agent agent) ((:stop! srv))))))

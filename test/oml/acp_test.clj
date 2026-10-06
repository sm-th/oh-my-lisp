(ns oml.acp-test
  "End-to-end: run `bb acp` as a subprocess, talk JSON-RPC over its stdio, and
  point it at the in-process fake model server. Each agent gets its own
  XDG_CONFIG_HOME and XDG_STATE_HOME, so the user's init files are not loaded."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bencode.core :as bencode]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.fake-llm :as fake])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- temp-dir [] (str (fs/create-temp-dir)))

(defn- start-agent
  "Start `bb acp` against the fake server at `base-url`. `config-dir` is
  used as XDG_CONFIG_HOME (default: a fresh empty dir)."
  ([base-url] (start-agent base-url {}))
  ([base-url {:keys [config-dir]}]
   (let [state-dir (temp-dir)
         proc (p/process ["bb" "acp"]
                         {:dir (System/getProperty "user.dir")
                          :extra-env {"OPENAI_BASE_URL" base-url
                                      "OPENAI_MODEL" "fake/model"
                                      "OPENAI_API_KEY" "test-key"
                                      "XDG_CONFIG_HOME" (or config-dir (temp-dir))
                                      "XDG_STATE_HOME" state-dir}
                          :err :inherit})
         inbox (LinkedBlockingQueue.)
         w (io/writer (:in proc))]
     (future (doseq [line (line-seq (io/reader (:out proc)))]
               (.put inbox (json/parse-string line true))))
     {:proc proc :inbox inbox :state-dir state-dir
      :send! (fn [msg] (locking w (.write w (str (json/generate-string (assoc msg :jsonrpc "2.0")) "\n")) (.flush w)))})))

(defn- stop-agent [{:keys [proc]}]
  (.close ^java.io.OutputStream (:in proc))
  (when (= ::timeout (deref proc 5000 ::timeout)) (p/destroy-tree proc)))

(defn- next-msg [{:keys [inbox]}]
  (or (.poll ^LinkedBlockingQueue inbox 10 TimeUnit/SECONDS)
      (throw (ex-info "timed out waiting for the agent" {}))))

(defn- collect-until-response
  "Read messages until the response to `id`. Returns [notifications response]."
  [agent id]
  (loop [notes []]
    (let [m (next-msg agent)]
      (if (and (= id (:id m)) (not (:method m)))
        [notes m]
        (recur (conj notes m))))))

(defn- request! [agent id method params]
  ((:send! agent) {:id id :method method :params params})
  (collect-until-response agent id))

(defn- updates [notes] (map #(get-in % [:params :update]) notes))

(defn- collect-until-commands
  "Read session updates up to and including available_commands_update."
  [agent]
  (loop [ups []]
    (let [u (get-in (next-msg agent) [:params :update])
          ups (conj ups u)]
      (if (= "available_commands_update" (:sessionUpdate u)) ups (recur ups)))))

(defn- new-session!
  "initialize + session/new in `dir`. Returns [session-id updates-after-new]."
  [agent dir]
  (request! agent 1 "initialize" {:protocolVersion 1 :clientCapabilities {}})
  (let [[_ resp] (request! agent 2 "session/new" {:cwd dir :mcpServers []})]
    [(get-in resp [:result :sessionId]) (collect-until-commands agent)]))

(def ^:private ids (atom 100))

(defn- prompt!
  "Send a text prompt. Returns [updates response]."
  [agent sid text]
  (let [[notes resp] (request! agent (swap! ids inc) "session/prompt"
                               {:sessionId sid :prompt [{:type "text" :text text}]})]
    [(updates notes) resp]))

(defn- message-text
  "All agent_message_chunk text in `ups`."
  [ups]
  (apply str (keep #(when (= "agent_message_chunk" (:sessionUpdate %)) (get-in % [:content :text])) ups)))

(defn- system-prompt [request] (:content (first (:messages request))))

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
            by-name (into {} (map (juxt :name identity)) cmds)]
        (testing "available_commands_update advertises the discovered commands"
          (is (every? by-name ["describe" "apropos" "eval" "reload" "settings" "tools" "commands" "hooks"]))
          (is (= {:name "describe"
                  :description "Describe a function, setting, hook, tool or command (like C-h f / C-h v)."
                  :input {:hint "symbol, e.g. oml.agent/max-turns or run-tool-call"}}
                 (by-name "describe")))
          (is (every? #(string? (:description %)) cmds)))
        (testing "/describe shows doc, current value and source of a setting"
          (let [[ups resp] (prompt! agent sid "/describe oml.agent/max-turns")
                text (message-text ups)]
            (is (= "end_turn" (get-in resp [:result :stopReason])))
            (is (str/includes? text "oml.agent/max-turns  (setting, variable)"))
            (is (str/includes? text "Value: `30`"))
            (is (str/includes? text "Model calls allowed"))
            (is (re-find #"agent\.clj:\d+" text))))
        (testing "secret settings are not shown"
          (let [text (message-text (first (prompt! agent sid "/describe oml.llm/api-key")))]
            (is (str/includes? text "Value: `<hidden>`"))
            (is (not (str/includes? text "test-key")))))
        (testing "/describe finds unqualified names and tools"
          (let [text (message-text (first (prompt! agent sid "/describe run-tool-call")))]
            (is (str/includes? text "oml.agent/run-tool-call"))
            (is (str/includes? text "[ctx {:keys [id function]}]")))
          (is (str/includes? (message-text (first (prompt! agent sid "/describe bash")))
                             "tool \"bash\"")))
        (testing "/apropos"
          (let [text (message-text (first (prompt! agent sid "/apropos tool-functions")))]
            (is (str/includes? text "oml.agent/before-tool-functions` (hook"))
            (is (str/includes? text "oml.agent/after-tool-functions"))))
        (testing "/eval evaluates in the agent process"
          (is (= "3" (message-text (first (prompt! agent sid "/eval (+ 1 2)")))))
          (is (= "hi\n:done" (message-text (first (prompt! agent sid "/eval (println \"hi\") :done")))))
          (is (str/includes? (message-text (first (prompt! agent sid "/eval (/ 1 0)")))
                             "Error in /eval: Divide by zero")))
        (is (empty? @(:requests srv)) "commands never call the model")
        (testing "an unknown /x is text for the model"
          (prompt! agent sid "/nope do it")
          (is (= "/nope do it" (:content (last (:messages (first @(:requests srv))))))))
        (testing "a defn from user code changes the next turn"
          (prompt! agent sid (str "/eval (in-ns 'oml.ext.core)"
                                  " (defn identity-section [_] \"You are a pirate.\")"))
          (prompt! agent sid "hello")
          (is (str/starts-with? (system-prompt (second @(:requests srv))) "You are a pirate.")))
        (testing "/eval defining a command re-advertises the commands"
          (let [[ups _] (prompt! agent sid (str "/eval (in-ns 'user)"
                                                " (defn ^:oml/command shout \"Shout it.\" [_ s] (str s \"!\"))"))]
            (is (some #(= "shout" (:name %)) (:availableCommands (last ups)))))
          (is (= "hey!" (message-text (first (prompt! agent sid "/shout hey"))))))
        (testing "/reload re-reads init files and re-advertises"
          (let [[ups resp] (prompt! agent sid "/reload")]
            (is (str/starts-with? (message-text ups) "Reloaded "))
            (is (= "available_commands_update" (:sessionUpdate (last ups))))
            (is (= "end_turn" (get-in resp [:result :stopReason]))))))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest init-files-tools-and-hooks
  (let [config (temp-dir)
        dir (temp-dir)
        _ (fs/create-dirs (fs/path config "oml" "lisp" "my"))
        _ (spit (str (fs/path config "oml" "lisp" "my" "ext.clj"))
                (pr-str '(ns my.ext)
                        '(defn shout "Upper-case the given text."
                           {:oml/tool true :oml/params {:text [:string "Text to shout"]}}
                           [_ {:keys [text]}]
                           (clojure.string/upper-case text))))
        _ (spit (str (fs/path config "oml" "init.clj"))
                (pr-str '(require '[oml.custom :refer [setq add-hook!]] 'my.ext)
                        '(setq oml.llm/model "init/model")))
        _ (fs/create-dirs (fs/path dir ".oml"))
        _ (spit (str (fs/path dir ".oml" "init.clj"))
                (pr-str '(ns project.init (:require [clojure.string :as str] [oml.custom :as c]))
                        '(defn no-rm-rf [call _ctx]
                           (when (and (= "bash" (:name call))
                                      (str/includes? (get-in call [:args :command] "") "rm -rf"))
                             (assoc call :block "rm -rf is not allowed here")))
                        '(c/add-hook! #'oml.agent/before-tool-functions #'no-rm-rf)))
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
        (is (some #(= "shout" (get-in % [:function :name])) (:tools r1))
            "a tool from a module on the load path is offered")
        (is (= {:type "object" :properties {:text {:type "string" :description "Text to shout"}}
                :required ["text"]}
               (:parameters (:function (first (filter #(= "shout" (get-in % [:function :name])) (:tools r1)))))))
        (is (= "HI" (:content (first tool-msgs))) "the discovered tool runs")
        (is (= "Blocked: rm -rf is not allowed here" (:content (second tool-msgs)))
            "the project hook blocked the call and the model sees why")
        (is (= ["completed" "failed"] (keep :status (filter #(= "tool_call_update" (:sessionUpdate %)) ups))))
        (is (some? r2))
        (testing "ns-unmap removes a tool"
          (prompt! agent sid "/eval (ns-unmap 'my.ext 'shout)")
          (prompt! agent sid "again")
          (is (not-any? #(= "shout" (get-in % [:function :name])) (:tools (last @(:requests srv))))))
        (testing "/describe of a hook shows its functions"
          (is (str/includes? (message-text (first (prompt! agent sid "/describe before-tool-functions")))
                             "#'project.init/no-rm-rf"))))
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

;; A tiny nREPL client over bencode.

(defn- nrepl-eval [port code]
  (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream sock)
          in (java.io.PushbackInputStream. (.getInputStream sock))
          s #(if (bytes? %) (String. ^bytes %) %)]
      (bencode/write-bencode out {"op" "eval" "code" code "id" "1"})
      (loop [values []]
        (let [m (update-vals (bencode/read-bencode in) s)
              values (cond-> values (get m "value") (conj (get m "value")))]
          (if (some #{"done"} (map s (get m "status")))
            values
            (recur values)))))))

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
            values (nrepl-eval port (str "(in-ns 'oml.ext.core)"
                                         " (defn identity-section [_] \"Redefined over nREPL.\")"
                                         " (identity-section nil)"))
            _ (prompt! agent sid "two")
            [r1 r2] @(:requests srv)]
        (is (= "\"Redefined over nREPL.\"" (last values)))
        (is (str/starts-with? (system-prompt r1) "You are oml"))
        (is (str/starts-with? (system-prompt r2) "Redefined over nREPL.")
            "the next prompt uses the function redefined over nREPL"))
      (finally (stop-agent agent) ((:stop! srv))))))

(deftest the-example-init-file-loads
  (let [config (temp-dir)
        dir (temp-dir)
        _ (fs/copy-tree "examples/lisp" (fs/path config "oml" "lisp"))
        _ (fs/copy "examples/init.clj" (fs/path config "oml" "init.clj"))
        _ (spit (str dir "/NOTES.md") "remember the milk")
        srv (fake/start! [(fake/tool-call-chunks "c1" "bash" "{\"command\":\"rm -rf build\"}")
                          (fake/text-chunks "ok")])
        agent (start-agent (:url srv) {:config-dir config})]
    (try
      (let [[sid ups] (new-session! agent dir)
            names (set (map :name (:availableCommands (last ups))))
            [ups2 _] (prompt! agent sid "clean")
            [r1 r2] @(:requests srv)]
        (is (= "" (message-text ups)) "no init error")
        (is (every? names ["today" "notes"]))
        (is (some #(= "word-count" (get-in % [:function :name])) (:tools r1)))
        (is (str/includes? (system-prompt r1) "Run the tests after every change."))
        (is (str/includes? (system-prompt r1) "read it before starting"))
        (is (str/starts-with? (:content (last (:messages r2))) "Blocked: rm -rf is not allowed"))
        (is (= "failed" (:status (first (filter #(= "tool_call_update" (:sessionUpdate %)) ups2)))))
        (is (= "remember the milk" (message-text (first (prompt! agent sid "/notes")))))
        (is (str/includes? (message-text (first (prompt! agent sid "/describe oml.agent/max-turns")))
                           "Value: `50`")))
      (finally (stop-agent agent) ((:stop! srv))))))

(ns oml.primitives-test
  "The primitives the core gives Lisp code, end to end over ACP: the
  current session and its transcript, call-tool, say, complete, request!
  and request-permission, and the reasoning stream."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.acp-client :as c :refer [temp-dir start-agent stop-agent new-session!
                                          prompt! prompt-notes! updates agent-requests
                                          message-text eval-text collect-until-response
                                          next-msg]]
            [oml.fake-llm :as fake]))

(defmacro ^:private with-agent
  "Start a fake server with `responses` and an agent; bind `srv`, `agent`."
  [[srv responses agent opts] & body]
  `(let [~srv (fake/start! ~responses)
         ~agent (start-agent (:url ~srv) ~opts)]
     (try ~@body
          (finally (stop-agent ~agent) ((:stop! ~srv))))))

(defn- eval-read
  "The current transcript of session `sid`, read back through /eval."
  [agent sid]
  (read-string (eval-text agent sid "(oml.session/transcript)")))

(deftest the-current-session
  (let [dir (temp-dir)]
    (with-agent [srv [(fake/tool-call-chunks "c1" "whoami" "{}")
                      (fake/text-chunks "ok")
                      (fake/text-chunks "noted")]
                 agent {}]
      (let [[sid _] (new-session! agent dir)]
        (testing "*session* and (session) inside /eval"
          (is (= (pr-str sid) (eval-text agent sid "oml.session/*session*")))
          (is (= (pr-str [sid dir]) (eval-text agent sid "((juxt :id :cwd) (oml.session/session))"))))
        (testing "inside a command"
          (eval-text agent sid (str "(defn ^:oml/command sid \"Session id.\" [_ _]"
                                    " (:id (oml.session/session)))"))
          (is (= sid (message-text (first (prompt! agent sid "/sid"))))))
        (testing "inside a model turn (a tool)"
          (eval-text agent sid (str "(defn ^:oml/tool whoami \"Session id.\" [_ _]"
                                    " oml.session/*session*)"))
          (prompt! agent sid "who am I?")
          (is (= sid (:content (last (:messages (second @(:requests srv)))))))
          (is (= [{:role "user" :content "who am I?"}]
                 (take 1 (map #(select-keys % [:role :content]) (eval-read agent sid))))))
        (testing "nREPL works on the most recently active session"
          (is (= (pr-str sid) (last (c/nrepl-eval (c/nrepl-port agent) "oml.session/*session*")))))
        (testing "append-message! is seen by the next model request"
          (eval-text agent sid "(oml.session/append-message! {:role \"user\" :content \"remember 42\"}) :ok")
          (prompt! agent sid "hi")
          (is (= [{:role "user" :content "remember 42"} {:role "user" :content "hi"}]
                 (take-last 2 (:messages (last @(:requests srv)))))))
        (testing "set-transcript! replaces the transcript"
          (eval-text agent sid "(oml.session/set-transcript! [{:role \"user\" :content \"summary\"}]) :ok")
          (is (= [{:role "user" :content "summary"}] (eval-read agent sid))))))))

(deftest calling-a-tool-from-lisp
  (let [dir (temp-dir)
        _ (spit (str dir "/notes.txt") "hello from notes\n")]
    (with-agent [srv [(fake/text-chunks "seen")] agent {}]
      (let [[sid _] (new-session! agent dir)
            _ (eval-text agent sid (str "(defonce seen (atom []))"
                                        "(defn no-secrets [call _]"
                                        "  (swap! seen conj (:name call))"
                                        "  (when (= \"secret.txt\" (get-in call [:args :path]))"
                                        "    (assoc call :block \"no secrets\")))"
                                        "(oml.custom/add-hook! #'oml.agent/before-tool-functions #'no-secrets)"))
            [ups _] (prompt! agent sid "/eval (:content (oml.agent/call-tool 'read {:path \"notes.txt\"}))")
            [call update] (filter #(#{"tool_call" "tool_call_update"} (:sessionUpdate %)) ups)]
        (testing "the client shows the call"
          (is (= {:sessionUpdate "tool_call" :title "Read notes.txt" :kind "read" :status "in_progress"
                  :rawInput {:path "notes.txt"}}
                 (select-keys call [:sessionUpdate :title :kind :status :rawInput])))
          (is (= (:toolCallId call) (:toolCallId update)))
          (is (= "completed" (:status update)))
          (is (str/includes? (message-text ups) "hello from notes") "the result is returned"))
        (testing "before-tool hooks run"
          (is (= "[\"read\"]" (eval-text agent sid "@seen")))
          (let [[ups _] (prompt! agent sid "/eval (oml.agent/call-tool \"read\" {:path \"secret.txt\"} :record? false)")]
            (is (str/includes? (message-text ups) "{:content \"Blocked: no secrets\", :error? true}"))
            (is (= "failed" (:status (last (filter :status ups)))))))
        (testing "the recorded pair is a valid OpenAI transcript"
          (prompt! agent sid "what did you read?")
          (let [msgs (:messages (first @(:requests srv)))
                [assistant tool user] (rest msgs)
                tc (first (:tool_calls assistant))]
            (is (= ["system" "assistant" "tool" "user"] (map :role msgs))
                "the call with :record? false is not in the transcript")
            (is (nil? (:content assistant)))
            (is (= {:type "function" :function {:name "read" :arguments "{\"path\":\"notes.txt\"}"}}
                   (dissoc tc :id)))
            (is (= (:id tc) (:tool_call_id tool) (:toolCallId call)))
            (is (str/includes? (:content tool) "hello from notes"))
            (is (= "what did you read?" (:content user)))))))))

(deftest say-and-complete
  (with-agent [srv [(concat (fake/text-chunks "short " "answer") [(fake/usage-chunk 5 2)])] agent {}]
    (let [[sid _] (new-session! agent (temp-dir))]
      (testing "say streams agent_message_chunk"
        (let [[ups _] (prompt! agent sid "/eval (oml.session/say \"Hello \" \"there\") :ok")]
          (is (= [{:sessionUpdate "agent_message_chunk" :content {:type "text" :text "Hello there"}}
                  {:sessionUpdate "agent_message_chunk" :content {:type "text" :text ":ok"}}]
                 ups))))
      (testing "complete returns the text and sends no tools"
        (let [[ups _] (prompt! agent sid "/eval (oml.agent/complete \"Summarise\" :system \"Be brief.\")")
              req (first @(:requests srv))]
          (is (= "\"short answer\"" (message-text ups)) "nothing is streamed to the user")
          (is (= [{:role "system" :content "Be brief."} {:role "user" :content "Summarise"}] (:messages req)))
          (is (not (contains? req :tools)))
          (is (= [] (eval-read agent sid)) "the transcript is untouched"))))))

(defn- permission-text
  "Ask for permission from /eval while the client answers with `reply`.
  Returns [the request the client got, the text /eval shows]."
  [agent sid reply]
  (reset! (:on-request agent) (fn [_] reply))
  (let [[notes _] (prompt-notes! agent sid "/eval (oml.acp/request-permission {:toolCallId \"t1\" :title \"Write x\"})")]
    [(first (agent-requests notes)) (message-text (updates notes))]))

(deftest request-permission-round-trips
  (with-agent [srv [] agent {}]
    (let [[sid _] (new-session! agent (temp-dir))]
      (testing "selected allow_once"
        (let [[req text] (permission-text agent sid {:result {:outcome {:outcome "selected" :optionId "allow_once"}}})]
          (is (= "session/request_permission" (:method req)))
          (is (= {:sessionId sid :toolCall {:toolCallId "t1" :title "Write x"}}
                 (dissoc (:params req) :options)))
          (is (= [["allow_once" "allow_once"] ["allow_always" "allow_always"]
                  ["reject_once" "reject_once"] ["reject_always" "reject_always"]]
                 (map (juxt :optionId :kind) (get-in req [:params :options]))))
          (is (every? (comp string? :name) (get-in req [:params :options])))
          (is (= "{:outcome \"selected\", :optionId \"allow_once\", :kind \"allow_once\"}" text))))
      (testing "selected reject_once"
        (is (= "{:outcome \"selected\", :optionId \"reject_once\", :kind \"reject_once\"}"
               (second (permission-text agent sid {:result {:outcome {:outcome "selected" :optionId "reject_once"}}})))))
      (testing "cancelled by the client"
        (is (= "{:outcome \"cancelled\"}"
               (second (permission-text agent sid {:result {:outcome {:outcome "cancelled"}}})))))
      (testing "an error response throws"
        (is (str/includes? (second (permission-text agent sid {:error {:code -32603 :message "boom"}}))
                           "Error in /eval: session/request_permission failed: boom")))
      (testing "session/cancel while waiting gives the cancelled outcome"
        (reset! (:on-request agent) (fn [_] nil))
        ((:send! agent) {:id 900 :method "session/prompt"
                         :params {:sessionId sid :prompt [{:type "text" :text "/eval (oml.acp/request-permission {:toolCallId \"t2\"})"}]}})
        (is (= "session/request_permission" (:method (next-msg agent))))
        ((:send! agent) {:method "session/cancel" :params {:sessionId sid}})
        (let [[notes resp] (collect-until-response agent 900)]
          (is (= "{:outcome \"cancelled\"}" (message-text (updates notes))))
          (is (= "cancelled" (get-in resp [:result :stopReason])))))
      (testing "request! on the reader thread is refused, not deadlocked"
        (eval-text agent sid (str "(def start-error (atom nil))"
                                  "(defn ask-at-start [_]"
                                  "  (try (oml.acp/request! \"x/y\" {}) (catch Exception e (reset! start-error (ex-message e)))))"
                                  "(oml.custom/add-hook! #'oml.agent/session-start-hook #'ask-at-start)"))
        (let [[sid2 _] [(get-in (second (c/request! agent 950 "session/new" {:cwd (temp-dir) :mcpServers []}))
                                [:result :sessionId])
                        (c/collect-until-commands agent)]]
          (is (str/includes? (eval-text agent sid2 "@start-error") "reader thread"))))
      (testing "the client closing unblocks the request with an error"
        ((:send! agent) {:id 990 :method "session/prompt"
                         :params {:sessionId sid :prompt [{:type "text" :text "/eval (oml.acp/request-permission {:toolCallId \"t3\"})"}]}})
        (is (= "session/request_permission" (:method (next-msg agent))))
        (.close ^java.io.OutputStream (:in (:proc agent)))
        (let [[notes resp] (collect-until-response agent 990)]
          (is (str/includes? (message-text (updates notes)) "ACP client closed before answering session/request_permission"))
          (is (contains? (:result resp) :stopReason)))))))

(deftest reasoning-stream
  (with-agent [srv [(concat [{:provider "fake-gateway" :choices [{:index 0 :delta {:role "assistant"}}]}]
                            (fake/reasoning-chunks "Let me " "think.")
                            (fake/text-chunks "Answer."))
                    (fake/text-chunks "again")]
               agent {}]
    (let [[sid _] (new-session! agent (temp-dir))
          _ (eval-text agent sid (str "(defonce providers (atom []))"
                                      "(defn note-provider [chunk] (some->> (:provider chunk) (swap! providers conj)))"
                                      "(oml.custom/add-hook! #'oml.llm/chunk-functions #'note-provider)"))
          [ups _] (prompt! agent sid "think")
          _ (prompt! agent sid "again")]
      (is (= [["agent_thought_chunk" "Let me "] ["agent_thought_chunk" "think."] ["agent_message_chunk" "Answer."]]
             (map (juxt :sessionUpdate (comp :text :content)) ups)))
      (is (= {:role "assistant" :content "Answer."} (nth (:messages (second @(:requests srv))) 2))
          "reasoning is not sent back by default")
      (is (= "[\"fake-gateway\"]" (eval-text agent sid "@providers")) "the chunk hook sees raw chunks"))))

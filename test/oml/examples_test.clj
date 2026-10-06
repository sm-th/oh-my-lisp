(ns oml.examples-test
  "The recipes in examples/ are user code on top of the primitives; run
  them end to end: examples/init.clj and examples/lisp/ as the user config
  of an ACP agent."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.acp-client :refer [temp-dir start-agent stop-agent new-session! prompt!
                                    prompt-notes! updates agent-requests message-text
                                    system-prompt]]
            [oml.fake-llm :as fake]))

(defn- example-config []
  (let [config (temp-dir)]
    (fs/copy-tree "examples/lisp" (fs/path config "oml" "lisp"))
    (fs/copy "examples/init.clj" (fs/path config "oml" "init.clj"))
    config))

(defn- tool-messages [request] (filter #(= "tool" (:role %)) (:messages request)))

(defn- with-usage [chunks] (concat chunks [(fake/usage-chunk 10 1)]))

(deftest the-recipes
  (let [dir (temp-dir)
        _ (spit (str dir "/AGENTS.md") "Always answer in French.")
        _ (spit (str dir "/notes.txt") "buy milk\n")
        _ (fs/create-dirs (fs/path dir "sub"))
        _ (spit (str dir "/sub/a.txt") "nothing\n")
        srv (fake/start! [(with-usage (fake/tool-call-chunks "c1" "ls" "{}"))
                          (with-usage (fake/tool-call-chunks "c2" "find" "{\"pattern\":\"**/*.txt\"}"))
                          (with-usage (fake/tool-call-chunks "c3" "grep" "{\"pattern\":\"mi.k\"}"))
                          (with-usage (fake/text-chunks "done"))
                          (fake/tool-call-chunks "w1" "write" "{\"path\":\"out.txt\",\"content\":\"x\"}")
                          (fake/text-chunks "not written")
                          (fake/tool-call-chunks "w2" "write" "{\"path\":\"out.txt\",\"content\":\"x\"}")
                          (fake/text-chunks "written")
                          (fake/tool-call-chunks "w3" "write" "{\"path\":\"out2.txt\",\"content\":\"y\"}")
                          (fake/text-chunks "written again")
                          (fake/text-chunks "seen")])
        replies (atom [])
        agent (start-agent (:url srv) {:config-dir (example-config)
                                       :on-request (fn [_] (let [r (first @replies)]
                                                             (swap! replies rest)
                                                             {:result {:outcome {:outcome "selected" :optionId r}}}))})
        requests #(deref (:requests srv))]
    (try
      (let [[sid ups] (new-session! agent dir)
            names (set (map :name (:availableCommands (last ups))))]
        (is (= "" (message-text ups)) "no init error")
        (is (every? names ["usage" "model" "ls-here" "doc"]))
        (testing "search tools; project context"
          (prompt! agent sid "look around")
          (let [[r1 & _ :as rs] (requests)
                [ls find grep] (map :content (tool-messages (nth rs 3)))]
            (is (str/includes? (system-prompt r1) "Project instructions (AGENTS.md):\n\nAlways answer in French."))
            (is (every? (set (map #(get-in % [:function :name]) (:tools r1))) ["ls" "find" "grep"]))
            (is (= "AGENTS.md\nnotes.txt\nsub/" ls))
            (is (= "notes.txt\nsub/a.txt" find))
            (is (str/includes? grep "notes.txt:1:buy milk"))))
        (testing "settings from the init file"
          (is (str/includes? (message-text (first (prompt! agent sid "/doc oml.agent/max-turns"))) "Value: `50`")))
        (testing "usage"
          (is (= "4 model calls, 40 prompt tokens, 4 completion tokens"
                 (message-text (first (prompt! agent sid "/usage"))))))
        (testing "model switch"
          (is (= "Model: fake/model" (message-text (first (prompt! agent sid "/model")))))
          (is (= "Model set to other/m" (message-text (first (prompt! agent sid "/model other/m"))))))
        (testing "permission policy: a rejection blocks the write and the model sees why"
          (reset! replies ["reject_once"])
          (let [[notes _] (prompt-notes! agent sid "write out.txt")
                [req] (agent-requests notes)
                r (last (requests))]
            (is (= "other/m" (:model r)) "the switched model is used")
            (is (= "session/request_permission" (:method req)))
            (is (= {:toolCallId "w1" :rawInput {:path "out.txt" :content "x"}} (get-in req [:params :toolCall])))
            (is (= "tool_call" (:sessionUpdate (first (filter :toolCallId (updates notes)))))
                "the card is shown before the question")
            (is (= "ERROR: Blocked: the user rejected it" (:content (last (tool-messages r))))
                "the policy blocked it, and the redefined tool-result-message marked it")
            (is (not (fs/exists? (fs/path dir "out.txt"))))))
        (testing "allow_always is remembered for the session"
          (reset! replies ["allow_always"])
          (let [[notes _] (prompt-notes! agent sid "write it")]
            (is (= 1 (count (agent-requests notes))))
            (is (= "x" (slurp (str (fs/path dir "out.txt"))))))
          (let [[notes _] (prompt-notes! agent sid "write another")]
            (is (empty? (agent-requests notes)) "not asked again")
            (is (= "y" (slurp (str (fs/path dir "out2.txt")))))))
        (testing "/ls-here calls the ls tool as the agent"
          (let [[ups _] (prompt! agent sid "/ls-here")
                [call update] ups]
            (is (= ["tool_call" "tool_call_update" "available_commands_update"] (map :sessionUpdate ups)))
            (is (= {:title "List ." :kind "read" :rawInput {:path "."}} (select-keys call [:title :kind :rawInput])))
            (is (str/includes? (get-in update [:content 0 :content :text]) "notes.txt"))
            (prompt! agent sid "what is here?")
            (let [[assistant tool] (take-last 3 (:messages (last (requests))))]
              (is (= "ls" (get-in assistant [:tool_calls 0 :function :name])))
              (is (= (:toolCallId call) (get-in assistant [:tool_calls 0 :id]) (:tool_call_id tool)))))))
      (finally (stop-agent agent) ((:stop! srv))))))

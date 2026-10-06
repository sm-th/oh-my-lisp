(ns oml.acp-test
  "End-to-end: run `bb acp` as a subprocess, talk JSON-RPC over its stdio, and
  point it at the in-process fake model server."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.fake-llm :as fake])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- start-agent [base-url]
  (let [proc (p/process ["bb" "acp"]
                        {:dir (System/getProperty "user.dir")
                         :extra-env {"OML_BASE_URL" base-url
                                     "OML_MODEL" "fake/model"
                                     "OML_API_KEY" "test-key"}
                         :err :inherit})
        inbox (LinkedBlockingQueue.)
        w (io/writer (:in proc))]
    (future (doseq [line (line-seq (io/reader (:out proc)))]
              (.put inbox (json/parse-string line true))))
    {:proc proc :inbox inbox
     :send! (fn [msg] (locking w (.write w (str (json/generate-string (assoc msg :jsonrpc "2.0")) "\n")) (.flush w)))}))

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

(deftest acp-session-with-a-tool-call
  (let [dir (str (fs/create-temp-dir))
        _ (spit (str dir "/notes.txt") "hello from notes\n")
        srv (fake/start! [(concat (fake/text-chunks "Let me look.")
                                  (fake/tool-call-chunks "call_1" "read" "{\"path\":" "\"notes.txt\"}"))
                          (fake/text-chunks "It says " "hello.")])
        agent (start-agent (:url srv))]
    (try
      (let [[_ init] (request! agent 1 "initialize" {:protocolVersion 1 :clientCapabilities {}})
            [_ sess] (request! agent 2 "session/new" {:cwd dir :mcpServers []})
            sid (get-in sess [:result :sessionId])
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
  (let [dir (str (fs/create-temp-dir))
        srv (fake/start! [[{:choices [{:delta {:content "Working"}}]} {:hang true}]
                          (fake/text-chunks "Again.")])
        agent (start-agent (:url srv))]
    (try
      (request! agent 1 "initialize" {:protocolVersion 1})
      (let [sid (get-in (second (request! agent 2 "session/new" {:cwd dir :mcpServers []})) [:result :sessionId])
            _ ((:send! agent) {:id 3 :method "session/prompt"
                               :params {:sessionId sid :prompt [{:type "text" :text "go"}]}})
            first-chunk (next-msg agent)
            _ ((:send! agent) {:method "session/cancel" :params {:sessionId sid}})
            [_ resp] (collect-until-response agent 3)
            [_ resp2] (request! agent 4 "session/prompt"
                                {:sessionId sid :prompt [{:type "text" :text "again"}]})]
        (is (= "agent_message_chunk" (get-in first-chunk [:params :update :sessionUpdate])))
        (is (= {:stopReason "cancelled"} (:result resp)))
        (is (= {:stopReason "end_turn"} (:result resp2)) "the session is usable after a cancel")
        (is (= ["system" "user" "assistant" "user"]
               (map :role (:messages (second @(:requests srv)))))
            "the partial answer stays in the transcript"))
      (finally (stop-agent agent) ((:stop! srv))))))

(ns oml.agent-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.agent :as agent]
            [oml.session :as session]
            [oml.tools]))

(defn scripted-model
  "A fake oml.agent/call-model that returns the scripted assistant messages in
  order and records the requests it was called with in `requests`."
  [requests & messages]
  (let [queue (atom messages)]
    (fn [request]
      (swap! requests conj request)
      (let [m (first @queue)]
        (swap! queue rest)
        (when-let [t (not-empty (:content m))] (session/emit! {:type :text-delta :text t}))
        {:message m}))))

(defn tool-call [id name args]
  {:id id :type "function" :function {:name name :arguments args}})

(defn run-prompt
  "Run `text` as a prompt in a fresh session in `cwd` against scripted
  model `messages`. Returns {:stop-reason :messages :requests :events}."
  [cwd text & messages]
  (let [requests (atom [])
        events (atom [])]
    (with-redefs [agent/call-model (apply scripted-model requests messages)]
      (session/with-session (session/create! {:cwd cwd :on-event #(swap! events conj %)})
        (let [{:keys [stop-reason]} (agent/prompt text)]
          {:stop-reason stop-reason :messages (session/transcript)
           :requests @requests :events @events})))))

(deftest read-then-answer
  (let [dir (str (fs/create-temp-dir))
        _ (spit (str dir "/notes.txt") "the answer is 42\n")
        {:keys [stop-reason messages requests events]}
        (run-prompt dir "What is the answer?"
                    {:role "assistant" :content nil
                     :tool_calls [(tool-call "c1" "read" "{\"path\":\"notes.txt\"}")]}
                    {:role "assistant" :content "It is 42."})]
    (is (= :end-turn stop-reason))
    (is (= 2 (count requests)))
    (is (= ["user" "assistant" "tool" "assistant"] (map :role messages)))
    (is (= "c1" (:tool_call_id (nth messages 2))))
    (is (str/includes? (:content (nth messages 2)) "the answer is 42"))
    (is (= "system" (:role (first (:messages (first requests))))))
    (is (str/includes? (:content (first (:messages (first requests)))) dir)
        "the system prompt names the working directory")
    (is (= (take 3 messages) (rest (:messages (second requests)))) "the second model call sees the tool result")
    (is (= #{"read" "write" "edit" "bash"} (set (map :name (:tools (first requests))))))
    (is (= [:tool-start :tool-end :text-delta] (map :type events)))
    (is (= {:kind "read" :title "Read notes.txt"} (select-keys (first events) [:kind :title])))))

(deftest tool-errors-go-back-to-the-model
  (let [{:keys [messages]} (run-prompt "." "x"
                                       {:role "assistant"
                                        :tool_calls [(tool-call "c1" "nope" "{}")
                                                     (tool-call "c2" "read" "{not json")
                                                     (tool-call "c3" "read" "{\"path\":\"no/such/file\"}")]}
                                       {:role "assistant" :content "ok"})]
    (is (= "Unknown tool: nope" (:content (nth messages 2))))
    (is (= "c2" (:tool_call_id (nth messages 3))))
    (is (str/starts-with? (:content (nth messages 4)) "File not found"))
    (is (= "ok" (:content (last messages))))))

(deftest stops-at-max-turns
  (let [looping {:role "assistant" :tool_calls [(tool-call "c" "bash" "{\"command\":\"true\"}")]}
        {:keys [stop-reason requests messages]}
        (with-redefs [agent/max-turns 3]
          (apply run-prompt "." "loop" (repeat 10 looping)))]
    (is (= :max-turns stop-reason))
    (is (= 3 (count requests)))
    (is (= "tool" (:role (last messages))) "every tool call still gets its result")))

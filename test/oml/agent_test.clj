(ns oml.agent-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.agent :as agent]
            [oml.tools :as tools]))

(defn- scripted-llm
  "A fake llm fn that returns the scripted assistant messages in order and
  records the transcripts it was called with."
  [calls & messages]
  (let [queue (atom messages)]
    (fn [{:keys [messages on-event]}]
      (swap! calls conj messages)
      (let [m (first @queue)]
        (swap! queue rest)
        (when-let [t (not-empty (:content m))] (on-event {:type :text-delta :text t}))
        {:message m}))))

(defn- tool-call [id name args]
  {:id id :type "function" :function {:name name :arguments args}})

(deftest read-then-answer
  (let [dir (str (fs/create-temp-dir))
        _ (spit (str dir "/notes.txt") "the answer is 42\n")
        calls (atom [])
        events (atom [])
        llm (scripted-llm calls
                          {:role "assistant" :content nil
                           :tool_calls [(tool-call "c1" "read" "{\"path\":\"notes.txt\"}")]}
                          {:role "assistant" :content "It is 42."})
        result (agent/run {:llm llm :tools tools/default-tools :cwd dir
                           :messages [{:role "user" :content "What is the answer?"}]
                           :on-event #(swap! events conj %)})
        msgs (:messages result)]
    (is (= :end-turn (:stop-reason result)))
    (is (= 2 (count @calls)))
    (is (= ["user" "assistant" "tool" "assistant"] (map :role msgs)))
    (is (= "c1" (:tool_call_id (nth msgs 2))))
    (is (str/includes? (:content (nth msgs 2)) "the answer is 42"))
    (is (= (take 3 msgs) (second @calls)) "the second model call sees the tool result")
    (is (= [:tool-start :tool-end :text-delta] (map :type @events)))
    (is (= {:kind "read" :title "Read notes.txt"} (select-keys (first @events) [:kind :title])))))

(deftest tool-errors-go-back-to-the-model
  (let [calls (atom [])
        llm (scripted-llm calls
                          {:role "assistant"
                           :tool_calls [(tool-call "c1" "nope" "{}")
                                        (tool-call "c2" "read" "{not json")]}
                          {:role "assistant" :content "ok"})
        msgs (:messages (agent/run {:llm llm :tools tools/default-tools :cwd "."
                                    :messages [{:role "user" :content "x"}]}))]
    (is (= "Unknown tool: nope" (:content (nth msgs 2))))
    (is (= "c2" (:tool_call_id (nth msgs 3))))
    (is (= "ok" (:content (last msgs))))))

(deftest stops-at-max-turns
  (let [calls (atom [])
        looping {:role "assistant" :tool_calls [(tool-call "c" "bash" "{\"command\":\"true\"}")]}
        llm (apply scripted-llm calls (repeat 10 looping))
        result (agent/run {:llm llm :tools tools/default-tools :cwd "." :max-turns 3
                           :messages [{:role "user" :content "loop"}]})]
    (is (= :max-turns (:stop-reason result)))
    (is (= 3 (count @calls)))
    (is (= "tool" (:role (last (:messages result)))) "every tool call still gets its result")))

(ns oml.agent-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oml.agent :as agent]
            [oml.ext.core]))

(defn scripted-model
  "A fake oml.agent/call-model that returns the scripted assistant messages in
  order and records the requests it was called with in `requests`."
  [requests & messages]
  (let [queue (atom messages)]
    (fn [ctx request]
      (swap! requests conj request)
      (let [m (first @queue)]
        (swap! queue rest)
        (when-let [t (not-empty (:content m))] ((:on-event ctx (fn [_])) {:type :text-delta :text t}))
        {:message m}))))

(defn tool-call [id name args]
  {:id id :type "function" :function {:name name :arguments args}})

(deftest read-then-answer
  (let [dir (str (fs/create-temp-dir))
        _ (spit (str dir "/notes.txt") "the answer is 42\n")
        requests (atom [])
        events (atom [])
        result (with-redefs [agent/call-model
                             (scripted-model requests
                                             {:role "assistant" :content nil
                                              :tool_calls [(tool-call "c1" "read" "{\"path\":\"notes.txt\"}")]}
                                             {:role "assistant" :content "It is 42."})]
                 (agent/run {:cwd dir :on-event #(swap! events conj %)}
                            [{:role "user" :content "What is the answer?"}]))
        msgs (:messages result)]
    (is (= :end-turn (:stop-reason result)))
    (is (= 2 (count @requests)))
    (is (= ["user" "assistant" "tool" "assistant"] (map :role msgs)))
    (is (= "c1" (:tool_call_id (nth msgs 2))))
    (is (str/includes? (:content (nth msgs 2)) "the answer is 42"))
    (is (= "system" (:role (first (:messages (first @requests))))))
    (is (str/includes? (:content (first (:messages (first @requests)))) dir)
        "the system prompt names the working directory")
    (is (= (take 3 msgs) (rest (:messages (second @requests)))) "the second model call sees the tool result")
    (is (= #{"read" "write" "edit" "bash"} (set (map :name (:tools (first @requests))))))
    (is (= [:tool-start :tool-end :text-delta] (map :type @events)))
    (is (= {:kind "read" :title "Read notes.txt"} (select-keys (first @events) [:kind :title])))))

(deftest tool-errors-go-back-to-the-model
  (let [msgs (with-redefs [agent/call-model
                           (scripted-model (atom [])
                                           {:role "assistant"
                                            :tool_calls [(tool-call "c1" "nope" "{}")
                                                         (tool-call "c2" "read" "{not json")]}
                                           {:role "assistant" :content "ok"})]
               (:messages (agent/run {:cwd "."} [{:role "user" :content "x"}])))]
    (is (= "Unknown tool: nope" (:content (nth msgs 2))))
    (is (= "c2" (:tool_call_id (nth msgs 3))))
    (is (= "ok" (:content (last msgs))))))

(deftest stops-at-max-turns
  (let [requests (atom [])
        looping {:role "assistant" :tool_calls [(tool-call "c" "bash" "{\"command\":\"true\"}")]}
        result (with-redefs [agent/max-turns 3
                             agent/call-model (apply scripted-model requests (repeat 10 looping))]
                 (agent/run {:cwd "."} [{:role "user" :content "loop"}]))]
    (is (= :max-turns (:stop-reason result)))
    (is (= 3 (count @requests)))
    (is (= "tool" (:role (last (:messages result)))) "every tool call still gets its result")))

(deftest enabled-tools-narrows-the-offer
  (let [requests (atom [])]
    (with-redefs [agent/enabled-tools #{"read"}
                  agent/call-model (scripted-model requests {:role "assistant" :content "hi"})]
      (agent/run {:cwd "."} [{:role "user" :content "x"}]))
    (is (= ["read"] (map :name (:tools (first @requests)))))))

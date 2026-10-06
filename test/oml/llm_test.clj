(ns oml.llm-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [oml.cancel :as cancel]
            [oml.fake-llm :as fake]
            [oml.llm :as llm]))

(defn- sse-lines [chunks]
  (mapcat (fn [c] [(str "data: " (json/generate-string c)) ""]) chunks))

(deftest sse-parsing
  (testing "comments, blank lines and other fields are skipped; [DONE] ends the stream"
    (let [lines (concat [": keep-alive" "" "event: message"]
                        (sse-lines (fake/text-chunks "Hel" "lo"))
                        ["data: [DONE]" "" "data: {\"after\":\"done\"}"])]
      (is (= ["Hel" "lo"]
             (keep #(-> % :choices first :delta :content) (llm/sse-chunks lines))))
      (is (= 3 (count (llm/sse-chunks lines)))))))

(deftest accumulating-text
  (let [events (atom [])
        acc (llm/consume (concat (fake/text-chunks "Hel" "lo")
                                 [{:choices [] :usage {:prompt_tokens 3 :completion_tokens 2}}])
                         #(swap! events conj %))]
    (is (= [{:type :text-delta :text "Hel"} {:type :text-delta :text "lo"}] @events))
    (is (= "stop" (:finish-reason acc)))
    (is (= {:prompt_tokens 3 :completion_tokens 2} (:usage acc)))
    (is (= {:role "assistant" :content "Hello"} (llm/assistant-message acc)))))

(deftest accumulating-tool-call-fragments
  (let [events (atom [])
        ;; Two parallel calls, interleaved, arguments split mid-token.
        chunks [{:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "read" :arguments "{\"pa"}}]}}]}
                {:choices [{:delta {:tool_calls [{:index 1 :id "c2" :function {:name "bash" :arguments ""}}]}}]}
                {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "th\": \"a.txt\"}"}}]}}]}
                {:choices [{:delta {:tool_calls [{:index 1 :function {:arguments "{\"command\":\"ls\"}"}}]}}]}
                {:choices [{:delta {} :finish_reason "tool_calls"}]}]
        acc (llm/consume chunks #(swap! events conj %))]
    (is (= [{:type :tool-call :id "c1" :name "read" :arguments "{\"path\": \"a.txt\"}"}
            {:type :tool-call :id "c2" :name "bash" :arguments "{\"command\":\"ls\"}"}]
           @events))
    (is (= {:role "assistant" :content nil
            :tool_calls [{:id "c1" :type "function" :function {:name "read" :arguments "{\"path\": \"a.txt\"}"}}
                         {:id "c2" :type "function" :function {:name "bash" :arguments "{\"command\":\"ls\"}"}}]}
           (llm/assistant-message acc)))))

(deftest stream-chat-over-http
  (let [srv (fake/start! [(fake/text-chunks "Hi" " there")])]
    (try
      (let [events (atom [])
            r (llm/stream-chat {:base-url (str (:url srv) "/") :model "fake/model" :api-key "k"}
                               {:messages [{:role "user" :content "hello"}]
                                :tools [{:name "read" :description "d" :parameters {:type "object"}}]
                                :on-event #(swap! events conj %)})
            req (first @(:requests srv))]
        (is (= {:role "assistant" :content "Hi there"} (:message r)))
        (is (= [:text-delta :text-delta :done] (map :type @events)))
        (is (= "fake/model" (:model req)))
        (is (true? (:stream req)))
        (is (= "read" (-> req :tools first :function :name))))
      (finally ((:stop! srv))))))

(deftest stream-chat-errors
  (testing "missing model"
    (is (thrown-with-msg? Exception #"OPENAI_MODEL"
                          (llm/stream-chat {:base-url "http://127.0.0.1:1"} {:messages []}))))
  (testing "HTTP error status carries the body"
    (let [srv (fake/start! [])]
      (try
        (is (thrown-with-msg? Exception #"HTTP 500.*no scripted response"
                              (llm/stream-chat {:base-url (:url srv) :model "m"} {:messages []})))
        (finally ((:stop! srv)))))))

(deftest stream-chat-cancel
  (let [srv (fake/start! [(concat [{:choices [{:delta {:content "partial"}}]}] [{:hang true}])])
        token (cancel/token)]
    (try
      (let [r (future (llm/stream-chat {:base-url (:url srv) :model "m"}
                                       {:messages [] :cancel token
                                        :on-event (fn [e] (when (= :text-delta (:type e))
                                                            (cancel/cancel! token)))}))
            result (deref r 5000 ::timeout)]
        (is (true? (:cancelled? result)))
        (is (= "partial" (-> result :message :content))))
      (finally ((:stop! srv))))))

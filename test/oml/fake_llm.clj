(ns oml.fake-llm
  "An in-process fake of an OpenAI-compatible /chat/completions endpoint.
  Each request pops the next scripted response and replays it as SSE."
  (:require [cheshire.core :as json]
            [org.httpkit.server :as hk]))

;; Script steps: a chunk map is sent as `data: <json>`; {:sleep ms} pauses;
;; {:hang true} stalls until the server stops. `[DONE]` is sent at the end.

(defn text-chunks
  "Chunks that stream `pieces` of text and finish with `stop`."
  [& pieces]
  (concat (for [p pieces] {:choices [{:index 0 :delta {:content p}}]})
          [{:choices [{:index 0 :delta {} :finish_reason "stop"}]}]))

(defn tool-call-chunks
  "Chunks for one tool call whose JSON arguments arrive in fragments."
  [id name & arg-fragments]
  (concat [{:choices [{:index 0 :delta {:role "assistant"
                                        :tool_calls [{:index 0 :id id :type "function"
                                                      :function {:name name :arguments ""}}]}}]}]
          (for [f arg-fragments]
            {:choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments f}}]}}]})
          [{:choices [{:index 0 :delta {} :finish_reason "tool_calls"}]}]))

(defn- replay [ch steps stopped]
  (future
    (try
      (hk/send! ch {:status 200 :headers {"content-type" "text/event-stream"}} false)
      (doseq [s steps]
        (cond (:sleep s) (Thread/sleep (long (:sleep s)))
              (:hang s)  (deref stopped 30000 nil)
              :else      (hk/send! ch (str "data: " (json/generate-string s) "\n\n") false)))
      (hk/send! ch "data: [DONE]\n\n" false)
      (finally (hk/close ch)))))

(defn start!
  "Start a fake server replaying `responses` (a seq of step seqs) in order.
  Returns {:url :requests (atom of parsed request bodies) :stop! fn}."
  [responses]
  (let [queue    (atom (vec responses))
        requests (atom [])
        stopped  (promise)
        handler  (fn [req]
                   (swap! requests conj (json/parse-string (slurp (:body req)) true))
                   (let [steps (first @queue)]
                     (swap! queue #(vec (rest %)))
                     (if steps
                       (hk/as-channel req {:on-open #(replay % steps stopped)})
                       {:status 500 :body "fake-llm: no scripted response left"})))
        server   (hk/run-server handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    {:url (str "http://127.0.0.1:" (hk/server-port server))
     :requests requests
     :stop! (fn [] (deliver stopped true) (hk/server-stop! server))}))

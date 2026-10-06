(ns oml.llm
  "Chapter 1: the model call.

  One streaming POST to an OpenAI-compatible /chat/completions endpoint. The
  response is Server-Sent Events: lines of `data: <json>` separated by blank
  lines, ending with `data: [DONE]`. Each JSON chunk carries a `delta` with a
  bit of text and/or fragments of tool calls; we fold the chunks into one
  assistant message and report progress through an `on-event` callback.

  Compare with pi: packages/ai/src/api/openai-completions.ts"
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [oml.cancel :as cancel]
            [oml.custom :refer [defsetting]]))

;; ---------------------------------------------------------------------------
;; Settings (defaults from the standard OPENAI_* variables)

(defsetting base-url
  "OpenAI-compatible base URL; /chat/completions is appended."
  (or (not-empty (System/getenv "OPENAI_BASE_URL")) "https://api.openai.com/v1"))

(defsetting api-key
  "Bearer token for the model endpoint, or nil."
  (not-empty (System/getenv "OPENAI_API_KEY")))

(defsetting model
  "Model id, e.g. \"gpt-4o-mini\" (or \"openai/gpt-4o-mini\" on OpenRouter).
  Required when prompting."
  (not-empty (System/getenv "OPENAI_MODEL")))

(defn config
  "The endpoint configuration for stream-chat, read from the settings at
  call time."
  []
  {:base-url base-url :api-key api-key :model model})

;; ---------------------------------------------------------------------------
;; SSE parsing

(defn sse-data
  "The payload of one SSE line: the parsed JSON map, :done for `[DONE]`, or nil
  for anything else (blank separators, comments such as `: keep-alive`,
  `event:`/`id:` fields)."
  [line]
  (when (str/starts-with? line "data:")
    (let [payload (str/trim (subs line 5))]
      (cond (= payload "[DONE]") :done
            (str/blank? payload) nil
            :else (json/parse-string payload true)))))

(defn sse-chunks
  "Lazy seq of parsed JSON chunks from a seq of SSE lines, stopping at [DONE]."
  [lines]
  (->> lines
       (keep sse-data)
       (take-while #(not= :done %))))

;; ---------------------------------------------------------------------------
;; Accumulating chunks into one assistant message

(def empty-acc {:text "" :tool-calls (sorted-map) :finish-reason nil :usage nil})

(defn- merge-tool-call-delta
  "Tool calls arrive in pieces keyed by `index`: the first piece has id and
  name, later pieces append to `arguments`."
  [calls {:keys [index id function]}]
  (update calls (or index 0)
          (fn [tc]
            (cond-> (or tc {:id nil :name "" :arguments ""})
              id                   (assoc :id id)
              (:name function)     (update :name str (:name function))
              (:arguments function) (update :arguments str (:arguments function))))))

(defn add-chunk
  "Fold one streamed chunk into the accumulator."
  [acc chunk]
  (let [choice (first (:choices chunk))
        delta  (:delta choice)]
    (cond-> acc
      (:content delta)       (update :text str (:content delta))
      (:tool_calls delta)    (update :tool-calls #(reduce merge-tool-call-delta % (:tool_calls delta)))
      (:finish_reason choice) (assoc :finish-reason (:finish_reason choice))
      (:usage chunk)         (assoc :usage (:usage chunk)))))

(defn assistant-message
  "The OpenAI-format assistant message for an accumulator."
  [{:keys [text tool-calls]}]
  (cond-> {:role "assistant" :content (if (and (empty? text) (seq tool-calls)) nil text)}
    (seq tool-calls)
    (assoc :tool_calls
           (vec (for [{:keys [id name arguments]} (vals tool-calls)]
                  {:id id :type "function"
                   :function {:name name :arguments arguments}})))))

(defn consume
  "Fold a seq of chunks, emitting :text-delta as text arrives and one
  :tool-call per completed call at the end. Returns the accumulator. The
  running accumulator is also kept in the volatile `progress`, so a caller
  whose stream is cut off still has the partial message."
  ([chunks on-event] (consume chunks on-event (volatile! nil)))
  ([chunks on-event progress]
   (vreset! progress empty-acc)
   (doseq [chunk chunks]
     (when-let [t (-> chunk :choices first :delta :content)]
       (when (seq t) (on-event {:type :text-delta :text t})))
     (vswap! progress add-chunk chunk))
   (let [acc @progress]
     (doseq [{:keys [id name arguments]} (vals (:tool-calls acc))]
       (on-event {:type :tool-call :id id :name name :arguments arguments}))
     acc)))

;; ---------------------------------------------------------------------------
;; HTTP

(defn tool-spec
  "OpenAI `tools` entry for one of our tool definitions."
  [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name :description description :parameters parameters}})

(defn- request-body [model messages tools]
  (cond-> {:model model
           :messages messages
           :stream true
           :stream_options {:include_usage true}}
    (seq tools) (assoc :tools (mapv tool-spec tools))))

(defn stream-chat
  "Call the model once. Returns {:message <assistant message> :finish-reason
  :usage :cancelled?}. Emits :text-delta, :tool-call and :done events.
  Cancelling `cancel` closes the response stream, which unblocks the read."
  [{:keys [base-url api-key model]} {:keys [messages tools on-event cancel]
                                     :or {on-event (fn [_])}}]
  (when-not model
    (throw (ex-info "No model configured: set OPENAI_MODEL (e.g. OPENAI_MODEL=gpt-4o-mini) or (setq oml.llm/model ...) in init.clj." {})))
  (let [resp (http/post (str (str/replace base-url #"/+$" "") "/chat/completions")
                        {:headers (cond-> {"Content-Type" "application/json"
                                           "Accept" "text/event-stream"}
                                    api-key (assoc "Authorization" (str "Bearer " api-key)))
                         :body (json/generate-string (request-body model messages tools))
                         :as :stream
                         :throw false})
        body ^java.io.InputStream (:body resp)]
    (when-not (= 200 (:status resp))
      (let [text (slurp body)]
        (throw (ex-info (str "Model request failed: HTTP " (:status resp) ": " text)
                        {:status (:status resp) :body text}))))
    (let [progress (volatile! empty-acc)
          unregister (cancel/on-cancel cancel #(.close body))
          acc (try
                (with-open [r (io/reader body)]
                  (consume (sse-chunks (line-seq r)) on-event progress))
                (catch java.io.IOException e
                  (when-not (cancel/cancelled? cancel) (throw e)))
                (finally (unregister)))]
      (if (cancel/cancelled? cancel)
        ;; Keep whatever text arrived; drop half-received tool calls.
        {:cancelled? true
         :message (assistant-message (assoc @progress :tool-calls {}))}
        (do (on-event {:type :done :finish-reason (:finish-reason acc) :usage (:usage acc)})
            {:message (assistant-message acc)
             :finish-reason (:finish-reason acc)
             :usage (:usage acc)})))))

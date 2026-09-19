(ns oml.fake-omp
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util Base64)))

(defn- option [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- flag? [args flag]
  (boolean (some #{flag} args)))

(defn- emit! [frame]
  (println (json/write-str frame))
  (flush))

(defn- response! [frame & [data]]
  (emit! (cond-> {:id (:id frame)
                  :type "response"
                  :command (:type frame)
                  :success true}
           data (assoc :data data))))

(defn- capture! [path line]
  (when path
    (spit path (str line "\n") :append true)))

(defn- text-message [text]
  {:role "assistant"
   :stopReason "stop"
   :content [{:type "text" :text text}]})

(defn- terminal! [text]
  (emit! {:type "message_end" :message (text-message text)})
  (emit! {:type "agent_end" :isTerminal true
          :messages [(text-message text)]}))

(defn- chunked! [frame]
  (let [bytes (.getBytes (json/write-str frame) "UTF-8")
        midpoint (quot (alength bytes) 2)
        parts [(java.util.Arrays/copyOfRange bytes 0 midpoint)
               (java.util.Arrays/copyOfRange bytes midpoint (alength bytes))]
        chunk-id "fake-chunk"]
    (doseq [[index part] (map-indexed vector parts)]
      (emit! {:type "rpc_chunk"
              :chunkId chunk-id
              :index index
              :count (count parts)
              :byteLength (alength bytes)
              :data (.encodeToString (Base64/getEncoder) part)}))))

(defn -main [& args]
  (let [capture (option args "--capture")
        resume (option args "--resume")
        session-file (or resume "/fake/native-session.jsonl")
        selected-tools (cond
                         (flag? args "--no-tools") []
                         (option args "--tools")
                         (str/split (option args "--tools") #",")
                         :else ["all"])
        last-text (atom nil)
        active (atom nil)
        queued (atom nil)]
    (when capture
      (spit capture (str (json/write-str {:launch (vec args)}) "\n")))
    (emit! {:type "ready"
            :protocolVersion 1
            :supportedProtocolVersions [1 2]
            :maxFrameBytes 1048576
            :maxReassembledFrameBytes 67108864})
    (doseq [line (line-seq (io/reader *in*))]
      (capture! capture line)
      (let [frame (json/read-str line :key-fn keyword)]
        (case (:type frame)
          "negotiate_protocol"
          (response! frame {:protocolVersion 2})

          "get_state"
          (response! frame {:sessionId "stable-session"
                            :sessionFile session-file
                            :isStreaming (some? @active)
                            :queuedMessageCount (if @queued 1 0)
                            :followUpMode "one-at-a-time"
                            :dumpTools (mapv #(hash-map :name %) selected-tools)})

          "set_host_tools"
          (response! frame {:toolNames (mapv :name (:tools frame))})

          "prompt"
          (let [message (:message frame)]
            (cond
              (= "command-error" message)
              (emit! {:id (:id frame) :type "response" :command "prompt"
                      :success false :code "fake_rejection" :error "rejected"})

              (= "unexpected-exit" message)
              (do
                (response! frame)
                (flush)
                (Thread/sleep 50)
                (System/exit 7))

              (= "protocol-error" message)
              (do
                (response! frame)
                (println "not-json")
                (flush))

              (= "eval" message)
              (do
                (reset! active (:id frame))
                (response! frame)
                (emit! {:type "agent_start"})
                (emit! {:type "host_tool_call"
                        :id "eval-call"
                        :toolCallId "tool-eval"
                        :toolName "clojure_eval"
                        :arguments {:expression "(tick)"}}))

              (= "cancel" message)
              (do
                (reset! active (:id frame))
                (response! frame)
                (emit! {:type "agent_start"}))

              (= "queue-primary" message)
              (do
                (reset! active (:id frame))
                (response! frame)
                (emit! {:type "agent_start" :run "queue"}))

              (:streamingBehavior frame)
              (do
                (reset! queued message)
                (response! frame)
                (reset! last-text "FOLLOWUP_DONE")
                (terminal! "FOLLOWUP_DONE")
                (reset! active nil)
                (reset! queued nil))

              :else
              (do
                (reset! active (:id frame))
                (response! frame)
                (emit! {:type "agent_start"})
                (emit! {:type "message_update"
                        :assistantMessageEvent {:type "text_delta"
                                                :delta "BASIC_OK"}})
                (reset! last-text (if (= "chunk" message) "CHUNK_OK" "BASIC_OK"))
                (terminal! @last-text)
                (reset! active nil))))

          "host_tool_result"
          (when (= "eval-call" (:id frame))
            (let [text (get-in frame [:result :content 0 :text])]
              (reset! last-text text)
              (terminal! text)
              (reset! active nil)))

          "abort"
          (do
            (emit! {:type "host_tool_cancel"
                    :id "cancel-notice"
                    :targetId "unused-call"})
            (emit! {:type "agent_end" :isTerminal true
                    :messages [{:role "assistant"
                                :stopReason "aborted"
                                :content []}]})
            (reset! active nil)
            (response! frame {:aborted true}))

          "get_last_assistant_text"
          (if (= "CHUNK_OK" @last-text)
            (chunked! {:id (:id frame) :type "response"
                       :command "get_last_assistant_text"
                       :success true :data {:text @last-text}})
            (response! frame {:text @last-text}))

          (emit! {:id (:id frame) :type "response"
                  :command (:type frame) :success false
                  :error "unsupported fake command"}))))))

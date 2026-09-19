(ns oml.agent.omp
  "OMP RPC process adapter. Protocol and process details stay in this namespace."
  (:refer-clojure :exclude [send])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [oml.grant :as grant])
  (:import (java.io BufferedReader BufferedWriter ByteArrayOutputStream
                    InputStreamReader OutputStreamWriter PushbackReader)
           (java.nio ByteBuffer CharBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files Paths)
           (java.util Base64 UUID)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private default-timeout-ms 30000)
(def ^:private default-max-frame-bytes 1048576)
(def ^:private default-max-reassembled-frame-bytes 67108864)
(def ^:private end-of-events (Object.))
(def ^:private timeout-value (Object.))

(defrecord OmpRun [connection id events history result prompt-ids])
(defrecord OmpConnection
    [process stdin pending ready active-run accepting closing closed ids
     max-frame-bytes max-reassembled-frame-bytes chunk-state stderr-lines
     host-calls grant timeout-ms stdout-task stderr-task exit-task session-state])

(defn- error [message data]
  (ex-info message (assoc data :component :oml.agent.omp)))

(defn- command-id [^OmpConnection connection prefix]
  (str prefix "-" (swap! (:ids connection) inc)))

(defn- utf8-length [^String value]
  (alength (.getBytes value StandardCharsets/UTF_8)))

(defn- write-frame! [^OmpConnection connection frame]
  (when @(:closed connection)
    (throw (error "OMP session is closed" {:kind :closed})))
  (let [line (json/write-str frame)
        size (utf8-length line)]
    (when (> size @(:max-frame-bytes connection))
      (throw (error "Outbound OMP frame exceeds negotiated limit"
                    {:kind :frame-too-large
                     :bytes size
                     :limit @(:max-frame-bytes connection)})))
    (locking (:stdin connection)
      (.write ^BufferedWriter (:stdin connection) line)
      (.newLine ^BufferedWriter (:stdin connection))
      (.flush ^BufferedWriter (:stdin connection)))))

(defn- read-bounded-line [^PushbackReader reader limit]
  (let [builder (StringBuilder.)]
    (loop [count 0]
      (let [value (.read reader)]
        (cond
          (= -1 value) (when (pos? count) (str builder))
          (= 10 value) (str builder)
          (> (inc count) limit)
          (throw (error "Inbound OMP frame exceeds negotiated limit"
                        {:kind :frame-too-large :limit limit}))
          :else (do
                  (when-not (= 13 value)
                    (.append builder (char value)))
                  (recur (inc count))))))))

(defn- strict-utf8 [^bytes value]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap value)))))

(defn- parse-json [line]
  (json/read-str line :key-fn keyword))

(defn- decode-chunk! [^OmpConnection connection frame]
  (let [{:keys [chunkId index count byteLength data]} frame
        current @(:chunk-state connection)]
    (when-not (and (string? chunkId) (seq chunkId)
                   (integer? index) (integer? count) (pos? count)
                   (integer? byteLength) (not (neg? byteLength))
                   (string? data))
      (throw (error "Invalid OMP chunk metadata" {:kind :protocol :frame frame})))
    (let [decoded (try
                    (.decode (Base64/getDecoder) ^String data)
                    (catch IllegalArgumentException cause
                      (throw (error "Invalid OMP chunk encoding"
                                    {:kind :protocol :frame frame :cause cause}))))
          expected (or current {:chunk-id chunkId
                                :next-index 0
                                :count count
                                :byte-length byteLength
                                :parts []
                                :size 0})
          next-size (+ (:size expected) (alength ^bytes decoded))]
      (when-not (and (= chunkId (:chunk-id expected))
                     (= index (:next-index expected))
                     (= count (:count expected))
                     (= byteLength (:byte-length expected))
                     (< index count))
        (throw (error "Broken or interleaved OMP chunk sequence"
                      {:kind :protocol :frame frame :sequence expected})))
      (when (> next-size @(:max-reassembled-frame-bytes connection))
        (throw (error "Reassembled OMP frame exceeds negotiated limit"
                      {:kind :frame-too-large
                       :bytes next-size
                       :limit @(:max-reassembled-frame-bytes connection)})))
      (let [updated (-> expected
                        (update :next-index inc)
                        (update :parts conj decoded)
                        (assoc :size next-size))]
        (if (= (inc index) count)
          (do
            (reset! (:chunk-state connection) nil)
            (when-not (= byteLength next-size)
              (throw (error "OMP chunk byte length mismatch"
                            {:kind :protocol
                             :expected byteLength
                             :actual next-size})))
            (let [output (ByteArrayOutputStream. next-size)]
              (doseq [part (:parts updated)]
                (.write output ^bytes part 0 (alength ^bytes part)))
              (parse-json (strict-utf8 (.toByteArray output)))))
          (do
            (reset! (:chunk-state connection) updated)
            nil))))))

(defn- terminal-event? [frame]
  (and (= "agent_end" (:type frame))
       (not= false (:isTerminal frame))))

(defn- aborted-event? [frame]
  (boolean
   (some #(= "aborted" (:stopReason %)) (:messages frame))))

(defn- deliver-error! [promise throwable]
  (when promise
    (deliver promise throwable)))

(defn- run-error! [^OmpRun run throwable]
  (when (and run (not (realized? (:result run))))
    (.offer ^LinkedBlockingQueue (:events run)
            {:type :error :error throwable})
    (.offer ^LinkedBlockingQueue (:events run) end-of-events)
    (deliver (:result run) throwable)))

(defn- connection-error! [^OmpConnection connection throwable]
  (doseq [[_ response] @(:pending connection)]
    (deliver-error! response throwable))
  (reset! (:pending connection) {})
  (run-error! @(:active-run connection) throwable)
  (reset! (:active-run connection) nil)
  (reset! (:accepting connection) false)
  (when (.isAlive ^Process (:process connection))
    (.destroy ^Process (:process connection))))

(defn- response-error [request response]
  (error "OMP command failed"
         {:kind :command-failed
          :request request
          :response response
          :code (:code response)}))

(defn- await-promise [promise timeout-ms message data]
  (let [value (deref promise timeout-ms timeout-value)]
    (when (identical? timeout-value value)
      (throw (error message (assoc data :kind :timeout :timeout-ms timeout-ms))))
    (if (instance? Throwable value)
      (throw value)
      value)))

(defn- command! [^OmpConnection connection request]
  (let [id (or (:id request) (command-id connection (:type request)))
        request (assoc request :id id)
        response (promise)]
    (swap! (:pending connection) assoc id response)
    (try
      (write-frame! connection request)
      (let [frame (await-promise response (:timeout-ms connection)
                                 "Timed out waiting for OMP response"
                                 {:request request})]
        (when-not (:success frame)
          (throw (response-error request frame)))
        frame)
      (finally
        (swap! (:pending connection) dissoc id)))))

(defn- final-text! [^OmpConnection connection]
  (get-in (command! connection {:type "get_last_assistant_text"}) [:data :text]))

(defn- finish-run! [^OmpConnection connection ^OmpRun run terminal]
  (compare-and-set! (:active-run connection) run nil)
  (doseq [prompt-id @(:prompt-ids run)]
    (swap! (:pending connection) dissoc prompt-id))
  (future
    (try
      (let [text (final-text! connection)
            result {:status (if (aborted-event? terminal) :aborted :completed)
                    :text text
                    :event terminal
                    :events @(:history run)}]
        (.offer ^LinkedBlockingQueue (:events run) end-of-events)
        (deliver (:result run) result))
      (catch Throwable cause
        (run-error! run cause)))))

(defn- record-event! [^OmpConnection connection frame]
  (when-let [run @(:active-run connection)]
    (swap! (:history run) conj frame)
    (.offer ^LinkedBlockingQueue (:events run) frame)
    (when (terminal-event? frame)
      (finish-run! connection run frame))))

(defn- eval-result-frame [id value error?]
  (cond-> {:type "host_tool_result"
           :id id
           :result {:content [{:type "text" :text value}]}}
    error? (assoc :isError true)))

(defn- invoke-eval! [^OmpConnection connection frame]
  (if (and (:grant connection) (= "clojure_eval" (:toolName frame)))
    (let [call-id (:id frame)
          task (future
                 (try
                   (let [value (grant/eval-string
                                (:grant connection)
                                (get-in frame [:arguments :expression]))]
                     (write-frame! connection
                                   (eval-result-frame call-id (pr-str value) false)))
                   (catch Throwable cause
                     (write-frame! connection
                                   (eval-result-frame call-id
                                                      (or (.getMessage cause)
                                                          (str (class cause)))
                                                      true)))
                   (finally
                     (swap! (:host-calls connection) dissoc call-id))))]
      (swap! (:host-calls connection) assoc call-id task))
    (write-frame! connection
                  (eval-result-frame (:id frame)
                                     (str "Unsupported host tool: " (:toolName frame))
                                     true))))

(defn- dispatch-frame! [^OmpConnection connection frame]
  (let [frame (if (= "rpc_chunk" (:type frame))
                (decode-chunk! connection frame)
                (do
                  (when @(:chunk-state connection)
                    (throw (error "OMP chunk sequence was interrupted"
                                  {:kind :protocol :frame frame})))
                  frame))]
    (when frame
      (case (:type frame)
        "ready" (deliver (:ready connection) frame)
        "response" (if-let [response (get @(:pending connection) (:id frame))]
                     (deliver response frame)
                     (when-not (:success frame)
                       (when-let [run @(:active-run connection)]
                         (run-error! run
                                     (response-error {:id (:id frame)
                                                      :type (:command frame)}
                                                     frame))
                         (compare-and-set! (:active-run connection) run nil))))
        "host_tool_call" (do (record-event! connection frame)
                              (invoke-eval! connection frame))
        "host_tool_cancel" (do
                             (record-event! connection frame)
                             (when-let [task (get @(:host-calls connection)
                                                  (:targetId frame))]
                               (future-cancel task)
                               (swap! (:host-calls connection) dissoc (:targetId frame))))
        (record-event! connection frame)))))

(defn- stderr-text [^OmpConnection connection]
  (str/join "\n" @(:stderr-lines connection)))

(defn- start-readers! [^OmpConnection connection]
  (let [stdout (PushbackReader.
                (InputStreamReader. (.getInputStream ^Process (:process connection))
                                    StandardCharsets/UTF_8))
        stderr (BufferedReader.
                (InputStreamReader. (.getErrorStream ^Process (:process connection))
                                    StandardCharsets/UTF_8))
        stdout-task
        (future
          (try
            (loop []
              (when-let [line (read-bounded-line stdout @(:max-frame-bytes connection))]
                (when (seq line)
                  (dispatch-frame! connection (parse-json line)))
                (recur)))
            (catch Throwable cause
              (when-not @(:closing connection)
                (connection-error! connection
                                   (error "OMP protocol reader failed"
                                          {:kind :protocol :cause cause}))))))
        stderr-task
        (future
          (try
            (loop []
              (when-let [line (.readLine stderr)]
                (swap! (:stderr-lines connection)
                       (fn [lines]
                         (let [updated (conj lines line)]
                           (if (> (count updated) 1000)
                             (subvec updated (- (count updated) 1000))
                             updated))))
                (recur)))
            (catch Throwable _)))
        exit-task
        (future
          (let [exit-code (.waitFor ^Process (:process connection))]
            (when-not @(:closing connection)
              (connection-error!
               connection
               (error "OMP process exited unexpectedly"
                      {:kind :unexpected-exit
                       :exit-code exit-code
                       :stderr (stderr-text connection)})))
            exit-code))]
    (assoc connection
           :stdout-task stdout-task
           :stderr-task stderr-task
           :exit-task exit-task)))

(defn- eval-tool-definition []
  {:name "clojure_eval"
   :label "Clojure Eval"
   :description "Evaluate Clojure source in the caller-supplied restricted grant context."
   :loadMode "essential"
   :parameters {:type "object"
                :properties {:expression {:type "string"
                                          :description "Clojure source to evaluate."}}
                :required ["expression"]
                :additionalProperties false}})

(defn- native-tool-args [native-tools]
  (if (seq native-tools)
    ["--tools" (str/join "," (map name native-tools))]
    ["--no-tools"]))

(defn- launch-command [{:keys [command cwd session-dir model thinking native-tools resume]}]
  (into (vec (or command ["omp"]))
        (concat ["--mode" "rpc"
                 "--cwd" (str cwd)
                 "--session-dir" (str session-dir)]
                (when model ["--model" model])
                (when thinking ["--thinking" (name thinking)])
                (native-tool-args native-tools)
                (when resume ["--resume" resume]))))

(defn- validate-open-options! [{:keys [cwd session-dir native-tools]}]
  (when-not cwd
    (throw (IllegalArgumentException. ":cwd is required")))
  (when-not session-dir
    (throw (IllegalArgumentException. ":session-dir is required")))
  (when-not (or (nil? native-tools)
                (and (coll? native-tools)
                     (every? #(or (keyword? %) (string? %)) native-tools)))
    (throw (IllegalArgumentException.
            ":native-tools must contain keyword or string OMP tool names"))))

(defn open
  "Launch and negotiate one OMP RPC child. See `oml.agent/open`."
  [options]
  (validate-open-options! options)
  (let [cwd (str (:cwd options))
        session-dir (str (:session-dir options))
        no-attributes (make-array java.nio.file.attribute.FileAttribute 0)
        _ (Files/createDirectories (Paths/get cwd (make-array String 0))
                                   no-attributes)
        _ (Files/createDirectories (Paths/get session-dir (make-array String 0))
                                   no-attributes)
        command (launch-command options)
        process (try
                  (.start (doto (ProcessBuilder. ^java.util.List command)
                            (.directory (io/file cwd))))
                  (catch Throwable cause
                    (throw (error "Could not launch OMP"
                                  {:kind :launch :command command :cause cause}))))
        base (map->OmpConnection
              {:process process
               :stdin (BufferedWriter.
                       (OutputStreamWriter. (.getOutputStream process)
                                            StandardCharsets/UTF_8))
               :pending (atom {})
               :ready (promise)
               :active-run (atom nil)
               :accepting (atom true)
               :closing (atom false)
               :closed (atom false)
               :ids (atom 0)
               :max-frame-bytes (atom default-max-frame-bytes)
               :max-reassembled-frame-bytes
               (atom default-max-reassembled-frame-bytes)
               :chunk-state (atom nil)
               :stderr-lines (atom [])
               :host-calls (atom {})
               :grant (:grant options)
               :timeout-ms (long (or (:timeout-ms options) default-timeout-ms))
               :session-state (atom nil)})
        connection (start-readers! base)]
    (try
      (let [ready (await-promise (:ready connection) (:timeout-ms connection)
                                 "Timed out waiting for OMP ready"
                                 {:command command})
            supported (set (:supportedProtocolVersions ready))]
        (when-not (contains? supported 2)
          (throw (error "OMP does not support RPC protocol 2"
                        {:kind :unsupported-protocol :ready ready})))
        (reset! (:max-frame-bytes connection)
                (long (or (:maxFrameBytes ready) default-max-frame-bytes)))
        (reset! (:max-reassembled-frame-bytes connection)
                (long (or (:maxReassembledFrameBytes ready)
                          default-max-reassembled-frame-bytes)))
        (command! connection {:type "negotiate_protocol" :protocolVersion 2})
        (when (:grant connection)
          (command! connection {:type "set_host_tools"
                                :tools [(eval-tool-definition)]}))
        (let [state (:data (command! connection {:type "get_state"}))]
          (reset! (:session-state connection)
                  (select-keys state [:sessionId :sessionFile]))
          connection))
      (catch Throwable cause
        (reset! (:closing connection) true)
        (try (.close ^BufferedWriter (:stdin connection)) (catch Throwable _))
        (when (.isAlive process)
          (.destroyForcibly process))
        (throw cause)))))

(defn session-info [^OmpConnection connection]
  (let [{:keys [sessionId sessionFile]} @(:session-state connection)]
    {:session-id sessionId :session-file sessionFile}))

(defn- new-run [connection id]
  (->OmpRun connection id (LinkedBlockingQueue.) (atom []) (promise) (atom #{id})))

(defn send
  [^OmpConnection connection message {:keys [active]}]
  (when-not (and (string? message) (seq message))
    (throw (IllegalArgumentException. "message must be a non-empty string")))
  (when-not @(:accepting connection)
    (throw (error "OMP session is not accepting prompts" {:kind :closed})))
  (when-not (contains? #{nil :follow-up :steer} active)
    (throw (IllegalArgumentException.
            ":active must be :follow-up or :steer when supplied")))
  (let [current @(:active-run connection)]
    (cond
      (and current (nil? active))
      (throw (error "An OMP run is active; choose :follow-up or :steer"
                    {:kind :active-run :run-id (:id current)}))

      (and (nil? current) active)
      (throw (error "There is no active OMP run to queue or steer"
                    {:kind :no-active-run :active active}))

      :else
      (let [id (command-id connection "prompt")
            run (or current (new-run connection id))
            request (cond-> {:id id :type "prompt" :message message}
                      active (assoc :streamingBehavior
                                    (case active
                                      :follow-up "followUp"
                                      :steer "steer")))]
        (when current
          (swap! (:prompt-ids run) conj id))
        (when-not current
          (when-not (compare-and-set! (:active-run connection) nil run)
            (throw (error "Another prompt started concurrently"
                          {:kind :active-run}))))
        (try
          (command! connection request)
          run
          (catch Throwable cause
            (compare-and-set! (:active-run connection) run nil)
            (run-error! run cause)
            (throw cause)))))))

(defn next-event
  ([^OmpRun run]
   (loop []
     (if-let [event (.poll ^LinkedBlockingQueue (:events run))]
       (when-not (identical? end-of-events event) event)
       (if (realized? (:result run))
         nil
         (let [event (.poll ^LinkedBlockingQueue (:events run) 100 TimeUnit/MILLISECONDS)]
           (if (identical? end-of-events event)
             nil
             (if event event (recur))))))))
  ([^OmpRun run timeout-ms]
   (when (neg? timeout-ms)
     (throw (IllegalArgumentException. "timeout-ms must not be negative")))
   (let [event (.poll ^LinkedBlockingQueue (:events run)
                      (long timeout-ms)
                      TimeUnit/MILLISECONDS)]
     (when-not (identical? end-of-events event) event))))

(defn observed-events [^OmpRun run]
  @(:history run))

(defn result [^OmpRun run]
  (let [value @(:result run)]
    (if (instance? Throwable value)
      (throw value)
      value)))

(defn cancel [^OmpRun run]
  (let [connection (:connection run)]
    (when-not (identical? run @(:active-run connection))
      (throw (error "OMP run is no longer active"
                    {:kind :inactive-run :run-id (:id run)})))
    (command! connection {:type "abort"})
    {:accepted true}))

(defn- checked-exit [value]
  (when-not (zero? (:exit-code value))
    (throw (error "OMP process exited non-zero"
                  (assoc value :kind :exit))))
  value)

(defn close [^OmpConnection connection]
  (if-let [value @(:closed connection)]
    (checked-exit value)
    (locking connection
      (if-let [value @(:closed connection)]
        (checked-exit value)
        (do
          (reset! (:accepting connection) false)
          (when-let [run @(:active-run connection)]
            (try (command! connection {:type "abort"}) (catch Throwable _))
            (doseq [[_ task] @(:host-calls connection)]
              (future-cancel task)))
          (reset! (:closing connection) true)
          (try (.close ^BufferedWriter (:stdin connection)) (catch Throwable _))
          (let [process ^Process (:process connection)
                exited (.waitFor process 30 TimeUnit/SECONDS)]
            (when-not exited
              (.destroy process)
              (when-not (.waitFor process 5 TimeUnit/SECONDS)
                (.destroyForcibly process)
                (.waitFor process)))
            (deref (:stdout-task connection) 5000 nil)
            (deref (:stderr-task connection) 5000 nil)
            (let [value {:exit-code (.exitValue process)
                         :stderr (stderr-text connection)}]
              (reset! (:closed connection) value)
              (checked-exit value))))))))

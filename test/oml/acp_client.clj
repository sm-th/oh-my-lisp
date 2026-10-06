(ns oml.acp-client
  "A scripted ACP client for end-to-end tests: run `bb acp` as a
  subprocess, talk JSON-RPC over its stdio, and point it at the in-process
  fake model server. Each agent gets its own XDG_CONFIG_HOME and
  XDG_STATE_HOME, so the user's init files are not loaded.

  Requests from the agent (session/request_permission, ...) are answered by
  the fn in the agent's :on-request atom, called with the request; it
  returns {:result ...} or {:error ...} to reply, or nil to stay silent.
  They also go to the inbox, like every other message. :wire records every
  message in both directions as [:in msg] (agent to client) or [:out msg]."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bencode.core :as bencode]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn temp-dir [] (str (fs/create-temp-dir)))

(defn method-not-found [_request]
  {:error {:code -32601 :message "Method not found"}})

(defn start-agent
  "Start `bb acp` against the fake server at `base-url`. `config-dir` is
  used as XDG_CONFIG_HOME (default: a fresh empty dir); `on-request`
  answers the agent's requests (default: method not found)."
  ([base-url] (start-agent base-url {}))
  ([base-url {:keys [config-dir on-request]}]
   (let [state-dir (temp-dir)
         proc (p/process ["bb" "acp"]
                         {:dir (System/getProperty "user.dir")
                          :extra-env {"OPENAI_BASE_URL" base-url
                                      "OPENAI_MODEL" "fake/model"
                                      "OPENAI_API_KEY" "test-key"
                                      "XDG_CONFIG_HOME" (or config-dir (temp-dir))
                                      "XDG_STATE_HOME" state-dir}
                          :err :inherit})
         inbox (LinkedBlockingQueue.)
         wire (atom [])
         responder (atom (or on-request method-not-found))
         w (io/writer (:in proc))
         send! (fn [msg]
                 (let [msg (assoc msg :jsonrpc "2.0")]
                   (swap! wire conj [:out msg])
                   (locking w (.write w (str (json/generate-string msg) "\n")) (.flush w))))]
     (future (doseq [line (line-seq (io/reader (:out proc)))]
               (let [m (json/parse-string line true)]
                 (swap! wire conj [:in m])
                 (.put inbox m)
                 (when (and (:method m) (contains? m :id))
                   (when-let [reply (@responder m)]
                     (send! (assoc reply :id (:id m))))))))
     {:proc proc :inbox inbox :state-dir state-dir :wire wire :on-request responder
      :send! send!})))

(defn stop-agent [{:keys [proc]}]
  (.close ^java.io.OutputStream (:in proc))
  (when (= ::timeout (deref proc 5000 ::timeout)) (p/destroy-tree proc)))

(defn next-msg [{:keys [inbox]}]
  (or (.poll ^LinkedBlockingQueue inbox 10 TimeUnit/SECONDS)
      (throw (ex-info "timed out waiting for the agent" {}))))

(defn collect-until-response
  "Read messages until the response to `id`. Returns [notifications response]."
  [agent id]
  (loop [notes []]
    (let [m (next-msg agent)]
      (if (and (= id (:id m)) (not (:method m)))
        [notes m]
        (recur (conj notes m))))))

(defn request! [agent id method params]
  ((:send! agent) {:id id :method method :params params})
  (collect-until-response agent id))

(defn updates
  "The session updates among `notes` (requests from the agent are skipped)."
  [notes]
  (keep #(when (= "session/update" (:method %)) (get-in % [:params :update])) notes))

(defn agent-requests
  "The requests from the agent among `notes`."
  [notes]
  (filter #(and (:method %) (contains? % :id)) notes))

(defn collect-until-commands
  "Read session updates up to and including available_commands_update."
  [agent]
  (loop [ups []]
    (let [u (get-in (next-msg agent) [:params :update])
          ups (conj ups u)]
      (if (= "available_commands_update" (:sessionUpdate u)) ups (recur ups)))))

(defn new-session!
  "initialize + session/new in `dir`. Returns [session-id updates-after-new]."
  [agent dir]
  (request! agent 1 "initialize" {:protocolVersion 1 :clientCapabilities {}})
  (let [[_ resp] (request! agent 2 "session/new" {:cwd dir :mcpServers []})]
    [(get-in resp [:result :sessionId]) (collect-until-commands agent)]))

(def ^:private ids (atom 100))

(defn prompt-notes!
  "Send a text prompt. Returns [notes response]: every message before the
  response, requests from the agent included."
  [agent sid text]
  (request! agent (swap! ids inc) "session/prompt"
            {:sessionId sid :prompt [{:type "text" :text text}]}))

(defn prompt!
  "Send a text prompt. Returns [updates response]."
  [agent sid text]
  (let [[notes resp] (prompt-notes! agent sid text)]
    [(updates notes) resp]))

(defn message-text
  "All agent_message_chunk text in `ups`."
  [ups]
  (apply str (keep #(when (= "agent_message_chunk" (:sessionUpdate %)) (get-in % [:content :text])) ups)))

(defn system-prompt [request] (:content (first (:messages request))))

(defn eval-text
  "Run /eval `code` and return the text it shows."
  [agent sid code]
  (message-text (first (prompt! agent sid (str "/eval " code)))))

;; A tiny nREPL client over bencode.

(defn nrepl-eval [port code]
  (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream sock)
          in (java.io.PushbackInputStream. (.getInputStream sock))
          s #(if (bytes? %) (String. ^bytes %) %)]
      (bencode/write-bencode out {"op" "eval" "code" code "id" "1"})
      (loop [values []]
        (let [m (update-vals (bencode/read-bencode in) s)
              values (cond-> values (get m "value") (conj (get m "value")))]
          (if (some #{"done"} (map s (get m "status")))
            values
            (recur values)))))))

(defn nrepl-port [agent]
  (parse-long (str/trim (slurp (str (fs/path (:state-dir agent) "oml" "nrepl-port"))))))

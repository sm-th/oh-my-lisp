(ns oml.chat
  "The chat: an OpenAI-compatible client and an interactive loop."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def system-prompt
  "You are oml (\"oh my lisp\"), a personal assistant. Be direct and helpful.")

(defn request
  "Pure: build the chat/completions request (url + body) from creds and messages."
  [creds messages]
  {:url  (str (:base-url creds) "/chat/completions")
   :body {:model (or (:model creds) "auto")
          :messages (into [{:role "system" :content system-prompt}] messages)}})

(defn complete
  "One chat/completions call. Returns the assistant text."
  [creds messages]
  (let [{:keys [url body]} (request creds messages)
        resp (http/post url {:headers {"Authorization" (str "Bearer " (:api-key creds))
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :throw false})]
    (if (= 200 (:status resp))
      (or (get-in (json/parse-string (:body resp) true) [:choices 0 :message :content]) "")
      (throw (ex-info (str "oml: request failed (HTTP " (:status resp) ")")
                      {:status (:status resp) :body (:body resp)})))))

(defn run!
  "Interactive chat loop over stdin/stdout. Ends on EOF (Ctrl-D)."
  [creds]
  (println "oml — talk to me. (Ctrl-D to exit)")
  (loop [history []]
    (print "\nyou> ") (flush)
    (let [line (read-line)]
      (cond
        (nil? line) (println)
        (str/blank? line) (recur history)
        :else
        (let [history (conj history {:role "user" :content line})
              reply (complete creds history)]
          (println (str "\noml> " reply))
          (recur (conj history {:role "assistant" :content reply})))))))

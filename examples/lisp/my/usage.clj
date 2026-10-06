(ns my.usage
  "Token usage as user code: an after-model hook adds up the usage the
  endpoint reports, per session; /usage shows it."
  (:require [oml.agent :as agent]
            [oml.custom :refer [add-hook!]]))

(defonce ^{:doc "session id -> {:calls :prompt_tokens :completion_tokens}"} usage (atom {}))

(defn count-usage
  "After each model call, add its usage to the session's total."
  [response _request {:keys [session-id]}]
  (when-let [u (:usage response)]
    (swap! usage update session-id
           (partial merge-with +)
           {:calls 1 :prompt_tokens (:prompt_tokens u 0) :completion_tokens (:completion_tokens u 0)}))
  nil)

(add-hook! #'agent/after-model-functions #'count-usage)

(defn usage-command
  "Show the tokens used in this session."
  {:oml/command true :oml/name "usage"}
  [{:keys [session-id]} _input]
  (if-let [{:keys [calls prompt_tokens completion_tokens]} (get @usage session-id)]
    (str calls " model calls, " prompt_tokens " prompt tokens, "
         completion_tokens " completion tokens")
    "No model calls yet."))

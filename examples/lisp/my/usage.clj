(ns my.usage
  "Token usage as user code: advice on call-model adds up the usage the
  endpoint reports, per session. /usage (my.commands) shows it."
  (:require [oml.agent :as agent]
            [oml.custom :refer [advise!]]
            [oml.session :as session]))

(defonce ^{:doc "session id -> {:calls :prompt_tokens :completion_tokens}"} totals (atom {}))

(advise! #'agent/call-model ::count
         (fn [call-model request]
           (let [{:keys [usage] :as response} (call-model request)]
             (when usage
               (swap! totals update (:id (session/session)) (partial merge-with +)
                      {:calls 1 :prompt_tokens (:prompt_tokens usage 0)
                       :completion_tokens (:completion_tokens usage 0)}))
             response)))

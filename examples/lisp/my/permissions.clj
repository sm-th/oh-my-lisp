(ns my.permissions
  "A permission policy as user code: advice on execute-tool asks the user
  (session/request_permission) before write, edit and bash. The client
  already shows the call. A rejection blocks it and the model reads why;
  \"always\" answers are remembered per tool for the session."
  (:require [oml.acp :as acp]
            [oml.agent :as agent]
            [oml.custom :refer [advise!]]
            [oml.session :as session]))

(def ask-tools
  "Tools that need the user's permission."
  #{"write" "edit" "bash"})

(defonce ^{:doc "session id -> tool name -> :allow or :reject"} remembered (atom {}))

(defn- decide
  "Nil to run the call, or why it is blocked."
  [{:keys [id name args]}]
  (let [k [(:id (session/session)) name]]
    (case (get-in @remembered k)
      :allow nil
      :reject (str "the user always rejects " name)
      (let [{:keys [outcome kind]} (acp/request-permission {:toolCallId id :rawInput args})]
        (case kind
          "allow_once" nil
          "allow_always" (do (swap! remembered assoc-in k :allow) nil)
          "reject_always" (do (swap! remembered assoc-in k :reject) "the user rejected it")
          (if (= "cancelled" outcome)
            "the prompt was cancelled before the user answered"
            "the user rejected it"))))))

(advise! #'agent/execute-tool ::ask
         (fn [execute-tool {:keys [name] :as call}]
           (if-let [reason (and (contains? ask-tools name) (decide call))]
             {:content (str "Blocked: " reason) :error? true}
             (execute-tool call))))

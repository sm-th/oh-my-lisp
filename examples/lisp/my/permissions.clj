(ns my.permissions
  "A permission policy as user code: before write, edit and bash, ask the
  user through the client (session/request_permission). A rejection blocks
  the call and the model reads why; \"always\" answers are remembered per
  tool for the session."
  (:require [oml.acp :as acp]
            [oml.agent :as agent]
            [oml.custom :refer [add-hook! defsetting]]))

(defsetting ask-tools
  "Tools that need the user's permission."
  #{"write" "edit" "bash"})

(defonce ^{:doc "session id -> tool name -> :allow or :reject"} remembered (atom {}))

(defn ask-permission
  "A before-tool hook: ask before the tools in ask-tools."
  [{:keys [id name args block] :as call} {:keys [session-id]}]
  (when (and (not block) (contains? ask-tools name))
    (case (get-in @remembered [session-id name])
      :allow nil
      :reject (assoc call :block (str "the user always rejects " name))
      (let [{:keys [outcome kind]} (acp/request-permission {:toolCallId id :rawInput args})
            remember! #(swap! remembered assoc-in [session-id name] %)]
        (case kind
          "allow_once" nil
          "allow_always" (do (remember! :allow) nil)
          "reject_always" (do (remember! :reject) (assoc call :block "the user rejected it"))
          (assoc call :block (if (= "cancelled" outcome)
                               "the prompt was cancelled before the user answered"
                               "the user rejected it")))))))

(add-hook! #'agent/before-tool-functions #'ask-permission)

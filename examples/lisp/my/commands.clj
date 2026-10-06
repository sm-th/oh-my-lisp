(ns my.commands
  "Slash commands as user code. Every public function with a docstring here
  is a command once my.commands is in oml.agent/command-namespaces (see
  examples/init.clj): /name text calls (name \"text\")."
  (:require [clojure.string :as str]
            [my.usage]
            [oml.agent :as agent]
            [oml.custom :refer [setq]]
            [oml.llm :as llm]
            [oml.session :as session]))

(defn model
  "Show the model, or switch to another one: /model <id>."
  {:hint "model id (empty: show the current one)"}
  [input]
  (if (str/blank? input)
    (str "Model: " (or llm/model "none"))
    (do (setq oml.llm/model input)
        (str "Model set to " input))))

(defn usage
  "Show the tokens used in this session."
  [_]
  (if-let [{:keys [calls prompt_tokens completion_tokens]} (get @my.usage/totals (:id (session/session)))]
    (str calls " model calls, " prompt_tokens " prompt tokens, " completion_tokens " completion tokens")
    "No model calls yet."))

(defn ls-here
  "List the working directory with the ls tool, as the agent: the client
  shows the tool call and the model sees it on the next turn."
  [_]
  (agent/call-tool 'ls {:path "."} :record? true)
  nil)

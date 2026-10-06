(ns my.model
  "Model switch as user code: /model shows the model, /model <id> sets it."
  (:require [clojure.string :as str]
            [oml.custom :refer [setq]]
            [oml.llm :as llm]))

(defn model
  "Show the model, or switch to another one: /model <id>."
  {:oml/command true :oml/hint "model id (empty: show the current one)"}
  [_ctx input]
  (if (str/blank? input)
    (str "Model: " (or llm/model "none"))
    (do (setq oml.llm/model input)
        (str "Model set to " input))))

(ns oml.main
  "Entry point: wire config to the chat."
  (:require [oml.config :as config]
            [oml.chat :as chat]))

(defn -main [& _argv]
  (if-let [creds (config/creds (config/read-config))]
    (chat/run! creds)
    (binding [*out* *err*]
      (println "oml: set OPENAI_API_KEY and OPENAI_BASE_URL"
               "(or an :openai map in ~/.oml/config.edn).")
      (System/exit 1))))

(ns oml.config
  "Reading ~/.oml/config.edn and resolving credentials."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(defn home [] (or (System/getenv "HOME") (str (fs/home))))
(defn path [] (fs/path (home) ".oml" "config.edn"))

(defn read-config
  "Read ~/.oml/config.edn, or {} if absent/unreadable."
  []
  (let [p (path)]
    (if (fs/exists? p)
      (try (edn/read-string (slurp (str p))) (catch Throwable _ {}))
      {})))

(defn creds
  "Resolve OpenAI-compatible credentials from config :openai or the environment.
   Returns {:base-url :api-key :model} or nil when unavailable."
  [cfg]
  (let [o (:openai cfg)
        base (or (:base-url o) (System/getenv "OPENAI_BASE_URL"))
        key  (or (some-> (:api-key-env o) System/getenv)
                 (:api-key o)
                 (System/getenv "OPENAI_API_KEY"))]
    (when (and base key)
      {:base-url base :api-key key :model (or (:model o) "auto")})))

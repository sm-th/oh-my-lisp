(ns oml.store
  "Persist machine states as EDN files."
  (:refer-clojure :exclude [load])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn ->edn
  "Print `state` as EDN text, or throw if it does not read back equal
  (e.g. a host object leaked into the state)."
  [state]
  (let [text (binding [*print-length* nil
                       *print-level* nil
                       *print-meta* false
                       *print-namespace-maps* false]
               (pr-str state))
        back (try (edn/read-string text) (catch Exception e e))]
    (when-not (= state back)
      (throw (ex-info "State is not pure EDN data" {:edn-error (when (instance? Exception back)
                                                                  (ex-message back))})))
    text))

(defn save!
  "Write `state` to `path` as EDN. Returns `path`."
  [path state]
  (io/make-parents path)
  (spit path (->edn state))
  path)

(defn load
  "Read a state previously written by `save!`."
  [path]
  (edn/read-string (slurp path)))

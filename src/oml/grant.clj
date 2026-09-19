(ns oml.grant
  "Deny-by-default evaluation for code with an explicit grant."
  (:require [clojure.set :as set]
            [sci.core :as sci]))

(def ^:private introspection-symbols '#{context tools})

(defn- granted-docs [vocab docs]
  (into {}
        (map (fn [operation]
               [operation (get docs operation)]))
        (keys vocab)))

(defn build
  "Build an isolated SCI context from explicit vocabulary, documentation,
  and context data.

  `:vocab` maps unqualified symbols to the values they name. `:docs` maps
  those same symbols to documentation. Inside the resulting context,
  `(context)` returns the configured `:context` value and `(tools)` returns
  exactly the granted vocabulary names and their documentation."
  [{:keys [vocab docs context]
    :or {vocab {} docs {} context {}}}]
  (when-let [reserved (seq (set/intersection introspection-symbols
                                              (set (keys vocab))))]
    (throw (ex-info "grant vocabulary uses reserved names"
                    {:reserved (set reserved)})))
  (let [tool-docs (granted-docs vocab docs)]
    (sci/init
     {:namespaces
      {'user (assoc vocab
                    'context (fn [] context)
                    'tools (fn [] tool-docs))}})))

(defn eval-string
  "Evaluate `source` in a context returned by `build`."
  [grant source]
  (sci/eval-string* grant source))

(ns oml.custom
  "Chapter 4: customisation the Lisp way.

  There is no registry. Behaviour lives in named functions and documented
  variables that the core reaches through their vars, so user code changes
  it by plain redefinition (`defn`, `def`, `alter-var-root`), from init.clj,
  a REPL or the agent itself. This namespace only adds the few conventions
  Emacs has on top of that:

  - settings (defcustom): vars defined with `defsetting`, changed with `setq`
  - hooks: vars holding a vector of functions, run by the core
  - advice: around-wrappers on any function var, removable by key
  - discovery: tools, commands, settings and hooks are public vars carrying
    metadata, found by scanning loaded namespaces. The metadata is part of
    the definition, like Emacs' (interactive): a defn without ^:oml/tool is
    no longer a tool.

  Compare with pi: packages/coding-agent/src/core/extensions/ (a registry
  API; this is the alternative)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Settings

(defmacro defsetting
  "Define a documented user setting, like Emacs' defcustom.

  Like `defonce` (and Emacs' defvar), re-evaluating a defsetting keeps a value
  that is already there, so re-loading a namespace does not undo the user's
  configuration. The default form is kept as :oml/default for /describe."
  [name doc default]
  `(do (defonce ~name ~default)
       (alter-meta! (var ~name) assoc :doc ~doc :oml/setting true :oml/default '~default)
       (var ~name)))

(defmacro setq
  "Set settings (or any vars) by name, like Emacs' setq:

    (setq oml.agent/max-turns 10
          oml.llm/model \"gpt-4o-mini\")"
  [& pairs]
  (assert (even? (count pairs)) "setq takes symbol/value pairs")
  `(do ~@(for [[sym v] (partition 2 pairs)]
           `(alter-var-root (var ~sym) (constantly ~v)))))

;; ---------------------------------------------------------------------------
;; Hooks: a hook is a var holding a vector of functions, marked ^:oml/hook.

(defn add-hook!
  "Add `f` to the functions of `hook` (a var) unless it is already there:
  at the end, or at the front with `where` :prepend. Prefer a var (#'my-fn)
  to an anonymous fn: a var stays late-bound when you redefine my-fn, and
  adding it again (e.g. on /reload) is a no-op."
  ([hook f] (add-hook! hook f :append))
  ([hook f where]
   (alter-var-root hook (fn [fs]
                          (cond (some #(= f %) fs) (vec fs)
                                (= :prepend where) (into [f] fs)
                                :else (conj (vec fs) f))))))

(defn remove-hook!
  "Remove `f` from the functions of `hook` (a var)."
  [hook f]
  (alter-var-root hook (fn [fs] (vec (remove #(= f %) fs)))))

(defn run-hooks
  "Call every function of `hook` with `args`, for side effects."
  [hook & args]
  (doseq [f @hook] (apply f args)))

(defn run-hook-filter
  "Thread `value` through the functions of `hook`: each is called as
  (f value & args) and returns a replacement value, or nil to keep it."
  [hook value & args]
  (reduce (fn [v f] (if-some [r (apply f v args)] r v)) value @hook))

;; ---------------------------------------------------------------------------
;; Advice: around-wrappers on a function var, like Emacs' advice-add :around.

(defn- install-advice! [v original advices]
  (alter-var-root v (constantly (reduce (fn [g [_ a]] (fn [& args] (apply a g args)))
                                        original advices)))
  (if (seq advices)
    (alter-meta! v assoc :oml/advice {:original original :advices advices})
    (alter-meta! v dissoc :oml/advice)))

(defn advise!
  "Wrap the function in var `v` with around advice `f`, called as
  (f original-fn & args). `key` names the advice: advising again with the
  same key replaces it instead of stacking. The unadvised function is kept
  in the var's metadata (:oml/advice) until the last advice is removed.
  Redefining the function with defn drops its advice."
  [v key f]
  (let [{:keys [original advices]} (or (:oml/advice (meta v)) {:original @v :advices []})
        advices (if (some #(= key (first %)) advices)
                  (mapv (fn [[k a]] (if (= k key) [k f] [k a])) advices)
                  (conj advices [key f]))]
    (install-advice! v original advices)
    v))

(defn unadvise!
  "Remove the advice named `key` from var `v`. Without a key, remove all."
  ([v] (when-let [{:keys [original]} (:oml/advice (meta v))]
         (install-advice! v original []))
   v)
  ([v key]
   (when-let [{:keys [original advices]} (:oml/advice (meta v))]
     (install-advice! v original (vec (remove #(= key (first %)) advices))))
   v))

(defn advice-keys
  "Keys of the advice on var `v`, innermost first."
  [v]
  (mapv first (:advices (:oml/advice (meta v)))))

;; ---------------------------------------------------------------------------
;; Discovery

(defn var-name
  "The fully qualified symbol of var `v`."
  [v]
  (let [m (meta v)]
    (if (:ns m)
      (symbol (str (ns-name (:ns m))) (str (:name m)))
      (symbol (subs (str v) 2)))))   ; "#'ns/name"

(defn public-vars
  "Every public var of every loaded namespace."
  []
  (for [ns (all-ns) v (vals (ns-publics ns))] v))

(defn vars-with
  "Public vars whose metadata has a truthy `k`, sorted by qualified name."
  [k]
  (->> (public-vars) (filter #(get (meta %) k)) (sort-by (comp str var-name))))

(defn settings [] (vars-with :oml/setting))
(defn hooks [] (vars-with :oml/hook))

(defn doc-text
  "A docstring as text without source line breaks and indentation;
  paragraphs are kept."
  [s]
  (->> (str/split (str/trim (str s)) #"\n\s*\n")
       (map #(str/replace (str/trim %) #"\s*\n\s*" " "))
       (str/join "\n\n")))

(defn summary
  "The first sentence of a docstring, for one-line listings."
  [s]
  (let [p (first (str/split (doc-text s) #"\n\n"))]
    (or (second (re-find #"^(.*?[.!?])(\s+[A-Z]|$)" p)) p)))

(defn- by-name
  "Index vars by (name-fn v). On a name clash, the var that sorts last wins."
  [name-fn vars]
  (vals (into (sorted-map) (map (juxt name-fn identity)) vars)))

;; Tools: ^:oml/tool fns of [ctx args] returning a string for the model.

(defn tool-name [v]
  (or (:oml/name (meta v)) (str (:name (meta v)))))

(defn json-schema
  "The JSON schema of a tool's :oml/params. A map with :type is used as is;
  otherwise it is the compact form {param [type description & flags]} where
  the flag :optional leaves the param out of `required`:

    {:path [:string \"File path\"] :limit [:integer \"Max lines\" :optional]}"
  [params]
  (if (or (nil? params) (:type params))
    (or params {:type "object" :properties {}})
    {:type "object"
     :properties (into {} (for [[k [t d]] params]
                            [k (cond-> {:type (name t)} d (assoc :description d))]))
     :required (vec (for [[k [_ _ & flags]] params
                          :when (not (some #{:optional} flags))]
                      (name k)))}))

(defn tool-spec
  "What the model sees of tool var `v`: name, docstring, parameter schema."
  [v]
  (let [m (meta v)]
    {:name (tool-name v)
     :description (doc-text (:doc m))
     :parameters (json-schema (:oml/params m))}))

(defn tool-kind
  "The ACP tool kind (read, edit, execute, ...) of tool var `v`."
  [v]
  (:oml/kind (meta v) "other"))

(defn tool-title
  "The UI title of a call to tool var `v`, from its :oml/title fn of args."
  [v args]
  (or (when-let [f (:oml/title (meta v))] (try (f args) (catch Exception _ nil)))
      (tool-name v)))

(defn tools
  "Every discovered tool var, one per name."
  []
  (by-name tool-name (vars-with :oml/tool)))

;; Commands: ^:oml/command fns of [ctx input-string], for the human.

(defn command-name [v]
  (or (:oml/name (meta v)) (str (:name (meta v)))))

(defn commands
  "Every discovered command var, one per name."
  []
  (by-name command-name (vars-with :oml/command)))

(defn command-info
  "The ACP AvailableCommand for command var `v`."
  [v]
  (let [m (meta v)]
    (cond-> {:name (command-name v) :description (summary (:doc m))}
      (:oml/hint m) (assoc :input {:hint (:oml/hint m)}))))

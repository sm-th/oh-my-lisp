(ns oml.commands
  "The built-in slash commands: introspection on top of clojure.repl, /eval
  and /reload.

  Every public function with a docstring in a namespace of
  oml.agent/command-namespaces is a command: `/name text` calls (name
  \"text\"). What it prints, then what it returns, is shown as an agent
  message. The first sentence of its docstring and an optional :hint in
  its attr-map are advertised to the client. Helpers are private.

  Compare with pi: packages/coding-agent/src/core/slash-commands.ts"
  (:refer-clojure :exclude [eval])
  (:require [babashka.fs :as fs]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [oml.agent :as agent]
            [oml.custom :as custom]
            [oml.init :as init]
            [oml.session :as session]))

(defn- var-sym [v]
  (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))

(defn- lookup
  "The var symbol `s` names: resolved in `user`, else in oml's namespaces
  and the tool and command namespaces (so `bash` finds oml.tools/bash)."
  [s]
  (let [sym (symbol (str/trim s))
        nss (concat ['user]
                    (filter #(str/starts-with? (str %) "oml.") (map ns-name (all-ns)))
                    agent/tool-namespaces agent/command-namespaces)]
    (some #(let [v (try (ns-resolve % sym) (catch Exception _ nil))] (when (var? v) v)) nss)))

(defn- shown
  "The printed value of var `v`; ^:secret values are hidden."
  [v]
  (if (and (:secret (meta v)) (some? @v)) "<hidden>" (pr-str @v)))

(defn- own?
  "Whether var `v` comes from oml or user code (a source file or the REPL),
  not from babashka or a library bundled with it."
  [v]
  (let [f (:file (meta v))]
    (boolean (or (= "NO_SOURCE_PATH" f) (and f (fs/absolute? f))))))

(defn- setting? [v]
  (let [m (meta v)]
    (and (:doc m) (own? v) (not (:macro m)) (not (:dynamic m))
         (let [x @v]
           (not (or (fn? x) (instance? clojure.lang.MultiFn x) (instance? clojure.lang.IDeref x)))))))

(defn- listing [vs label]
  (str/join "\n" (for [v vs] (str "- " (label v) " " (agent/summary (:doc (meta v)))))))

(defn doc
  "Show a var's documentation (clojure.repl/doc), its value if it is not a
  function, its advice and where it is defined."
  {:hint "symbol, e.g. oml.agent/max-turns or run-tool-call"}
  [input]
  (if-let [v (lookup input)]
    (let [m (meta v)]
      (str (str/replace-first (with-out-str (clojure.core/eval `(repl/doc ~(var-sym v)))) #"^-+\n" "")
           (when-not (fn? @v) (str "Value: `" (shown v) "`\n"))
           (when-let [ks (seq (custom/advice-keys v))] (str "Advised: " (pr-str (vec ks)) "\n"))
           (when (:file m) (str "Defined in " (:file m) (when (:line m) (str ":" (:line m)))))))
    (str "No var named " input)))

(defn apropos
  "List the documented vars of oml and user code whose name matches a regex
  (clojure.repl/apropos)."
  {:hint "regex"}
  [input]
  (let [vs (for [s (repl/apropos (re-pattern (str/trim input)))
                 :let [v (resolve s)]
                 :when (and (var? v) (own? v) (:doc (meta v)))]
             v)]
    (if (empty? vs) (str "Nothing matches " (pr-str input)) (listing vs #(str "`" (var-sym %) "`")))))

(defn source
  "Show the source of a function of oml or user code (clojure.repl/source-fn)."
  {:hint "symbol"}
  [input]
  (let [v (lookup input)]
    (or (when (and v (own? v)) (repl/source-fn (var-sym v)))
        (str "No source for " input))))

(defn eval
  "Evaluate Clojure forms in the agent process (like M-:) and show the last value."
  {:hint "forms, e.g. (+ 1 2)"}
  [input]
  (pr-str (binding [*ns* (the-ns 'user)] (load-string input))))

(defn reload
  "Load the user and project init files again."
  [_]
  (str/join "\n" (cons (str "Reloaded " (init/user-init-file)
                            (when init/load-project-init? (str " and " (init/project-init-file (session/cwd)))))
                       (for [e (init/reload! (session/cwd))] (str "- " e)))))

(defn settings
  "List the settings (documented vars holding data) and their values."
  [_]
  (listing (sort-by (comp str var-sym) (filter setting? (for [ns (all-ns) v (vals (ns-publics ns))] v)))
           #(str "`" (var-sym %) "` = `" (shown %) "`")))

(defn tools
  "List the tools offered to the model."
  [_]
  (listing (agent/tools) #(str "`" (agent/fn-name %) "` (" (var-sym %) ")")))

(defn commands
  "List the slash commands."
  [_]
  (listing (agent/commands) #(str "`/" (agent/fn-name %) "`")))

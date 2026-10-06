(ns oml.ext.help
  "Introspection commands, like Emacs' describe-function, apropos and M-:.

  These are ordinary ^:oml/command functions of [ctx input]: `input` is the
  text after the command name, ctx is {:cwd :session-id :cancel :on-event
  :advertise-commands!}. A command prints or returns its output, which the
  client shows as an agent message.

  Compare with pi: packages/coding-agent/src/core/slash-commands.ts"
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as str]
            [oml.agent :as agent]
            [oml.custom :as custom]
            [oml.init :as init]))

(defn- kinds
  "What var `v` is to oml, as labels."
  [v]
  (let [m (meta v)]
    (cond-> []
      (:oml/setting m)        (conj "setting")
      (:oml/hook m)           (conj "hook")
      (:oml/tool m)           (conj (str "tool \"" (custom/tool-name v) "\""))
      (:oml/command m)        (conj (str "command /" (custom/command-name v)))
      (:oml/advice m)         (conj (str "advised " (custom/advice-keys v)))
      (:macro m)              (conj "macro")
      (and (not (:macro m)) (fn? @v)) (conj "function")
      (not (fn? @v))          (conj "variable"))))

(defn- shown
  "The printed value of setting `v`; secrets are hidden."
  [v]
  (if (and (:oml/secret (meta v)) (some? @v)) "<hidden>" (pr-str @v)))

(defn- user-var?
  "A var defined from source (oml, extensions, init files), not built into bb."
  [v]
  (not (:sci/built-in (meta v))))

(defn- lookup
  "Vars a symbol may mean: a qualified name, else every public var with that
  name (non-built-in ones first), else what it resolves to in `user`."
  [s]
  (let [sym (symbol s)]
    (if (namespace sym)
      (some-> (resolve sym) vector)
      (let [named (filter #(= (name sym) (str (:name (meta %)))) (custom/public-vars))
            {own true builtin false} (group-by user-var? named)]
        (or (seq (sort-by (comp str custom/var-name) own))
            (seq builtin)
            (some-> (binding [*ns* (the-ns 'user)] (resolve sym)) vector))))))

(defn describe-var
  "Documentation of var `v` as text."
  [v]
  (let [m (meta v)]
    (str/join
     "\n"
     (concat
      [(str "## " (custom/var-name v) "  (" (str/join ", " (kinds v)) ")")
       ""]
      (when (:oml/setting m)
        [(str "Value: `" (shown v) "`  Default: `" (pr-str (:oml/default m)) "`") ""])
      (when (:oml/hook m)
        [(str "Functions: `" (pr-str @v) "`") ""])
      (when-let [a (:arglists m)]
        [(str "`" (str/join " " (map pr-str a)) "`") ""])
      [(or (:doc m) "Not documented.")]
      (when (:file m)
        ["" (str "Defined in " (:file m) (when (:line m) (str ":" (:line m))))])))))

(defn describe
  "Describe a function, setting, hook, tool or command (like C-h f / C-h v)."
  {:oml/command true :oml/hint "symbol, e.g. oml.agent/max-turns or run-tool-call"}
  [_ctx input]
  (if (str/blank? input)
    "Usage: /describe <symbol>"
    (if-let [vs (lookup (str/trim input))]
      (str/join "\n\n" (map describe-var vs))
      (str "No var named " (str/trim input)))))

(defn apropos
  "List functions, settings, hooks, tools and commands whose name matches a regex."
  {:oml/command true :oml/hint "regex"}
  [_ctx input]
  (let [re (re-pattern (str/trim input))
        hits (->> (custom/public-vars)
                  (filter user-var?)
                  (filter #(re-find re (str (custom/var-name %))))
                  (sort-by (comp str custom/var-name)))]
    (if (empty? hits)
      (str "Nothing matches " (pr-str (str/trim input)))
      (str/join "\n" (for [v hits]
                       (str "- `" (custom/var-name v) "` (" (str/join ", " (kinds v)) ") "
                            (custom/summary (:doc (meta v)))))))))

(defn eval
  "Evaluate Clojure forms in the agent process (like M-:) and show the last value."
  {:oml/command true :oml/hint "forms, e.g. (+ 1 2)"}
  [_ctx input]
  (pr-str (binding [*ns* (the-ns 'user)] (load-string input))))

(defn reload
  "Load the user and project init files again."
  {:oml/command true}
  [{:keys [cwd advertise-commands!]} _input]
  (let [errors (init/reload! cwd)]
    (when advertise-commands! (advertise-commands!))
    (str/join "\n" (concat [(str "Reloaded " (init/user-init-file)
                                 (when (and cwd init/load-project-init?)
                                   (str " and " (init/project-init-file cwd))))]
                           (for [e errors] (str "- " e))))))

(defn settings
  "List the settings and their current values."
  {:oml/command true}
  [_ctx _input]
  (str/join "\n" (for [v (custom/settings)]
                   (str "- `" (custom/var-name v) "` = `" (shown v) "` "
                        (custom/summary (:doc (meta v)))))))

(defn tools
  "List the tools and whether they are offered to the model."
  {:oml/command true}
  [ctx _input]
  (let [enabled (set (agent/collect-tools ctx))]
    (str/join "\n" (for [v (custom/tools)]
                     (str "- `" (custom/tool-name v) "` (" (custom/var-name v)
                          (when-not (enabled v) ", disabled") ") "
                          (custom/summary (:doc (meta v))))))))

(defn commands
  "List the slash commands."
  {:oml/command true}
  [_ctx _input]
  (str/join "\n" (for [v (custom/commands)]
                   (str "- `/" (custom/command-name v) "` " (custom/summary (:doc (meta v)))))))

(defn hooks
  "List the hook variables and the functions on them."
  {:oml/command true}
  [_ctx _input]
  (str/join "\n" (for [v (custom/hooks)]
                   (str "- `" (custom/var-name v) "` " (pr-str @v)))))

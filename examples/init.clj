;; An example oml init file. Copy it to ~/.config/oml/init.clj (or
;; $XDG_CONFIG_HOME/oml/init.clj), and examples/lisp/ to ~/.config/oml/lisp/.
;; A project can have its own <project>/.oml/init.clj and .oml/lisp/.
;;
;; It is evaluated in the `user` namespace of the running agent, like
;; Emacs' init.el. /reload evaluates it again, so everything here is safe to
;; repeat: setq and defn just set again, add-hook! and advise! do not stack.

(require '[clojure.string :as str]
         '[oml.custom :refer [setq add-hook! advise!]]
         'oml.agent
         'oml.ext.core)

;; --- Change a setting (defcustom / setq) -----------------------------------
;; /settings lists them all; /describe oml.agent/max-turns documents one.

(setq oml.agent/max-turns 50
      oml.ext.core/bash-timeout 300)

;; --- Override a system prompt section (redefine a defun) -------------------
;; The section functions sit on oml.agent/system-prompt-functions as vars, so
;; redefining one changes the system prompt of the next turn.

(in-ns 'oml.ext.core)
(defn guidelines-section
  "How to use the built-in tools and how to answer."
  [_ctx]
  (str/join "\n"
            ["Read before you edit; prefer edit over write for existing files."
             "Run the tests after every change."
             "Answer in one short paragraph."]))
(in-ns 'user)

;; --- Add a tool --------------------------------------------------------------
;; A public fn with ^:oml/tool metadata. The docstring is what the model reads;
;; :oml/params is a compact JSON schema. Remove it with (ns-unmap 'user 'word-count).

(defn word-count
  "Count the words in a text file."
  {:oml/tool true
   :oml/kind "read"
   :oml/title (fn [{:keys [path]}] (str "Count words in " path))
   :oml/params {:path [:string "File path, relative to the working directory"]}}
  [{:keys [cwd]} {:keys [path]}]
  (str (count (re-seq #"\S+" (slurp (oml.agent/resolve-path cwd path)))) " words"))

;; --- Add a slash command -----------------------------------------------------
;; A public fn of [ctx input] with ^:oml/command metadata. Typing "/today"
;; in the client runs it; what it returns (or prints) is shown to you.

(defn today
  "Show today's date."
  {:oml/command true}
  [_ctx _input]
  (str (java.time.LocalDate/now)))

;; --- A hook that refuses dangerous commands ----------------------------------
;; Functions on before-tool-functions get {:id :name :args}; returning the
;; call with :block refuses it, and the model sees the reason.

(defn refuse-rm-rf [call _ctx]
  (when (and (= "bash" (:name call))
             (re-find #"\brm\s+-[a-zA-Z]*r[a-zA-Z]*f|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r" (get-in call [:args :command] "")))
    (assoc call :block "rm -rf is not allowed; delete specific files instead")))

(add-hook! #'oml.agent/before-tool-functions #'refuse-rm-rf)

;; --- Advice: log every model call to stderr ----------------------------------
;; Around advice gets the original function first. Remove it with
;; (oml.custom/unadvise! #'oml.agent/call-model :user/log-model-calls).

(advise! #'oml.agent/call-model :user/log-model-calls
         (fn [call-model ctx request]
           (binding [*out* *err*]
             (println "[init] model call:" (count (:messages request)) "messages,"
                      (count (:tools request)) "tools"))
           (call-model ctx request)))

;; --- Require a module from the load path -------------------------------------
;; ~/.config/oml/lisp (and <project>/.oml/lisp) are on the classpath, so
;; ~/.config/oml/lisp/my/notes.clj is the namespace my.notes.

(require 'my.notes)

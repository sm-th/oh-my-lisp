;; An example oml init file. Copy it to ~/.config/oml/init.clj (or
;; $XDG_CONFIG_HOME/oml/init.clj), and examples/lisp/ to ~/.config/oml/lisp/.
;; A project can have its own <project>/.oml/init.clj and .oml/lisp/.
;;
;; It is evaluated in the `user` namespace of the running agent, like
;; Emacs' init.el. /reload evaluates it again, so everything here is safe to
;; repeat: setq and defn set again, advise! replaces advice with the same key.

(require '[oml.custom :refer [setq advise!]])

;; --- Settings are documented vars ------------------------------------------
;; /settings lists them; /doc oml.agent/max-turns documents one.

(setq oml.agent/max-turns 50
      oml.tools/bash-timeout 300)

;; --- Modules from the load path ---------------------------------------------
;; ~/.config/oml/lisp is on the classpath, so lisp/my/tools.clj is my.tools.
;; Tools and commands are the documented public functions of the namespaces
;; listed in tool-namespaces and command-namespaces.

(require 'my.context       ; AGENTS.md / CLAUDE.md in the system prompt
         'my.permissions)  ; ask before write, edit and bash

(setq oml.agent/tool-namespaces '[oml.tools my.tools]           ; ls, find, grep
      oml.agent/command-namespaces '[oml.commands my.commands]) ; /model, /usage, /ls-here

;; --- Redefine a function ------------------------------------------------------
;; Any step of the loop is a defn; this one changes every model request.

(in-ns 'oml.agent)
(defn tool-result-message
  "The transcript message carrying `result` back to the model; errors are
  marked so the model notices them."
  [{:keys [id]} {:keys [content error?]}]
  {:role "tool" :tool_call_id id :content (if error? (str "ERROR: " content) content)})
(in-ns 'user)

;; --- Wrap a function: log every model call to stderr -------------------------
;; Remove it with (oml.custom/unadvise! #'oml.agent/call-model :user/log).

(advise! #'oml.agent/call-model ::log
         (fn [call-model request]
           (binding [*out* *err*]
             (println "[init] model call:" (count (:messages request)) "messages,"
                      (count (:tools request)) "tools"))
           (call-model request)))

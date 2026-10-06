(ns my.context
  "Project context as user code: advice on system-prompt appends the
  project's AGENTS.md (or CLAUDE.md) from the session's directory."
  (:require [babashka.fs :as fs]
            [oml.agent :as agent]
            [oml.custom :refer [advise!]]
            [oml.session :as session]))

(def context-files
  "Files read into the system prompt; the first one found in the working
  directory is used."
  ["AGENTS.md" "CLAUDE.md"])

(advise! #'agent/system-prompt ::project-context
         (fn [system-prompt]
           (let [f (some #(let [f (fs/path (session/cwd) %)] (when (fs/regular-file? f) f)) context-files)]
             (cond-> (system-prompt)
               f (str "\n\nProject instructions (" (fs/file-name f) "):\n\n" (slurp (str f)))))))

(ns my.context
  "Project context as user code: a system prompt section with the
  project's AGENTS.md (or CLAUDE.md) from the session's directory."
  (:require [babashka.fs :as fs]
            [oml.agent :as agent]
            [oml.custom :refer [add-hook! defsetting]]))

(defsetting context-files
  "Files read into the system prompt; the first one found in the working
  directory is used."
  ["AGENTS.md" "CLAUDE.md"])

(defn project-context-section
  "The first of context-files in the working directory, as a prompt section."
  [{:keys [cwd]}]
  (when-let [f (and cwd (some #(let [f (fs/path cwd %)] (when (fs/regular-file? f) f)) context-files))]
    (str "Project instructions (" (fs/file-name f) "):\n\n" (slurp (str f)))))

(add-hook! #'agent/system-prompt-functions #'project-context-section)

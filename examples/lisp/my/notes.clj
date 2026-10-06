(ns my.notes
  "An example module on the oml load path (~/.config/oml/lisp/my/notes.clj).
  It adds a prompt section and a command."
  (:require [babashka.fs :as fs]
            [oml.agent :as agent]
            [oml.custom :refer [add-hook!]]))

(defn notes-section
  "Ask the model to read NOTES.md when the project has one."
  [{:keys [cwd]}]
  (when (and cwd (fs/exists? (fs/path cwd "NOTES.md")))
    "This project keeps working notes in NOTES.md; read it before starting."))

(add-hook! #'agent/system-prompt-functions #'notes-section)

(defn notes
  "Show the project's NOTES.md."
  {:oml/command true}
  [{:keys [cwd]} _input]
  (let [f (fs/path cwd "NOTES.md")]
    (if (fs/exists? f) (slurp (str f)) "No NOTES.md here.")))

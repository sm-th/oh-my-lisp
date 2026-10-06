(ns oml.init
  "Configuration is a program, like Emacs' init.el.

  At startup: load the tool and command namespaces, add the user lisp dir
  to the classpath (Emacs' load-path) and load the user init file. When a
  session starts: add the project lisp dir and load the project init file
  (if `load-project-init?`), report init errors to the session, call
  oml.session/on-session-start. /reload loads both init files again.

    $XDG_CONFIG_HOME/oml/init.clj   (default ~/.config/oml/init.clj)
    $XDG_CONFIG_HOME/oml/lisp/      on the classpath: (require 'my.ext)
    <cwd>/.oml/init.clj
    <cwd>/.oml/lisp/

  Init files are evaluated in the `user` namespace. An error in one is
  logged to stderr and shown in the session as a warning (Emacs' init
  error warning); the agent keeps running."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [oml.agent :as agent]
            [oml.session :as session :refer [log]]))

(def load-project-init?
  "Load <cwd>/.oml/init.clj when a session starts. It runs arbitrary code
  with your permissions as soon as an ACP client opens that directory, so
  set this to false (in the user init file) before opening projects you do
  not trust."
  true)

(defn- xdg [var fallback]
  (str (or (not-empty (System/getenv var)) (fs/path (fs/home) fallback))))

(defn config-dir
  "The user's oml config directory: $XDG_CONFIG_HOME/oml."
  []
  (str (fs/path (xdg "XDG_CONFIG_HOME" ".config") "oml")))

(defn state-dir
  "The user's oml state directory: $XDG_STATE_HOME/oml."
  []
  (str (fs/path (xdg "XDG_STATE_HOME" ".local/state") "oml")))

(defn user-init-file [] (str (fs/path (config-dir) "init.clj")))
(defn project-init-file [cwd] (str (fs/path cwd ".oml" "init.clj")))

(defonce ^:private added-dirs (atom #{}))

(defn add-load-path!
  "Put `dir` on the classpath (once), so namespaces below it can be required."
  [dir]
  (let [dir (str (fs/absolutize dir))]
    (when-not (contains? @added-dirs dir)
      (cp/add-classpath dir)
      (swap! added-dirs conj dir))))

(defn load-init-file
  "Load `file`, if it exists, in the user namespace. Returns nil, or an error
  message (also logged to stderr)."
  [file]
  (when (fs/exists? file)
    (try
      (binding [*ns* (the-ns 'user)]
        (load-file (str file)))
      nil
      (catch Throwable e
        (let [{:keys [line]} (ex-data e)
              msg (str "Error loading " file (when line (str " (line " line ")")) ": "
                       (or (ex-message e) (str e)))]
          (log msg)
          msg)))))

(defn load-user-init!
  "Load the user init file. Returns a vector of error messages."
  []
  (add-load-path! (fs/path (config-dir) "lisp"))
  (vec (keep load-init-file [(user-init-file)])))

(defn load-project-init!
  "Load the project init file of `cwd` when load-project-init? is true.
  Returns a vector of error messages."
  [cwd]
  (when load-project-init?
    (add-load-path! (fs/path cwd ".oml" "lisp"))
    (vec (keep load-init-file [(project-init-file cwd)]))))

(defn startup!
  "Load the default tool and command namespaces (so init code can redefine
  what is in them), then the user init file. Returns error messages."
  []
  (run! require (concat agent/tool-namespaces agent/command-namespaces))
  (load-user-init!))

(defn reload!
  "Load the user init file and the project init file of `cwd` again.
  Returns error messages."
  [cwd]
  (into (load-user-init!) (load-project-init! cwd)))

(defn session-started!
  "Set up the current, new session: load its project init file, show
  `errors` and its own as warnings, call on-session-start."
  [errors]
  (doseq [e (into (vec errors) (load-project-init! (session/cwd)))]
    (session/say "Warning: " e "\n"))
  (session/on-session-start))

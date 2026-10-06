(ns oml.init
  "Configuration is a program, like Emacs' init.el.

  At startup: add the user lisp dir to the classpath (Emacs' load-path),
  load the user init file, then require the `extensions`. When a session is
  created and its cwd known: add the project lisp dir and load the project
  init file (if `load-project-init?`). /reload loads both init files again.

    $XDG_CONFIG_HOME/oml/init.clj   (default ~/.config/oml/init.clj)
    $XDG_CONFIG_HOME/oml/lisp/      on the classpath: (require 'my.ext)
    <cwd>/.oml/init.clj
    <cwd>/.oml/lisp/

  Init files are evaluated in the `user` namespace. An error in one is
  logged to stderr and returned as a message for the session (Emacs' init
  error warning); the agent keeps running."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [oml.custom :refer [defsetting]]))

(defn log [& xs]
  (binding [*out* *err*] (apply println "[oml]" xs)))

(defsetting load-project-init?
  "Load <cwd>/.oml/init.clj when a session starts. It runs arbitrary code
  with your permissions as soon as an ACP client opens that directory, so
  set this to false (in the user init file) before opening projects you do
  not trust."
  true)

(defsetting extensions
  "Namespaces required at startup, after the user init file. Built-in tools
  and the system prompt are in oml.ext.core, introspection commands in
  oml.ext.help; drop one here (from the user init file) to go without it."
  '[oml.ext.core oml.ext.help])

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
(defn user-lisp-dir [] (str (fs/path (config-dir) "lisp")))
(defn project-init-file [cwd] (str (fs/path cwd ".oml" "init.clj")))
(defn project-lisp-dir [cwd] (str (fs/path cwd ".oml" "lisp")))

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
  (add-load-path! (user-lisp-dir))
  (vec (keep load-init-file [(user-init-file)])))

(defn load-project-init!
  "Load the project init file of `cwd` when load-project-init? is true.
  Returns a vector of error messages."
  [cwd]
  (when load-project-init?
    (add-load-path! (project-lisp-dir cwd)))
  (vec (when load-project-init? (keep load-init-file [(project-init-file cwd)]))))

(defn load-extensions!
  "Require every namespace in `extensions`. Returns a vector of error messages."
  []
  (vec (for [ns extensions
             :let [err (try (require ns) nil
                            (catch Throwable e
                              (str "Error loading extension " ns ": " (ex-message e))))]
             :when err]
         (do (log err) err))))

(defn startup!
  "Load the user init file, then the extensions. Returns error messages."
  []
  (into (load-user-init!) (load-extensions!)))

(defn reload!
  "Load the user init file and, for `cwd`, the project init file again.
  Returns error messages."
  [cwd]
  (into (load-user-init!) (when cwd (load-project-init! cwd))))

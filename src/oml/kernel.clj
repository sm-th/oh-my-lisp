(ns oml.kernel
  "The shared oml runtime.

  One mutable namespace, `oml.user`, is home to everything the image
  defines. Configuration is evaluated into it, and so is every later
  direct eval, so state defined at boot stays visible to everything
  that acts through the same eval surface.

  Persistence is explicit: `evaluate-file` reads and evaluates a Lisp
  file, `save-forms`/`save-and-evaluate` write definitions to a Lisp
  file that later evaluation can restore, and `save-data`/`load-data`
  round-trip a value through a readable EDN file. Nothing survives a
  restart unless it was explicitly saved; there is no automatic
  journal or snapshot of the running image."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def runtime-ns 'oml.user)

(defn ensure-runtime
  "Return the shared runtime namespace, creating it on first use with
  clojure.core referred in, so init code and direct eval can use plain
  Clojure. Idempotent: later calls return the existing namespace."
  []
  (or (find-ns runtime-ns)
      (binding [*ns* (create-ns runtime-ns)]
        (refer 'clojure.core)
        *ns*)))

(def ^:private eof (Object.))

(defn eval-string
  "Read and evaluate every form in `s`, in order, in the shared runtime
  namespace. Returns the value of the last form, or nil when `s`
  contains no forms. Throws on unreadable source or evaluation errors."
  [s]
  (let [ns (ensure-runtime)
        rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. s))]
    (loop [value nil]
      (let [form (read rdr false eof)]
        (if (identical? form eof)
          value
          (recur (binding [*ns* ns] (eval form))))))))

(defn evaluate-file
  "Read and evaluate every form in the file at `path`, in order, in the
  shared runtime namespace, so definitions and data it produces are
  visible to later direct eval. Returns the value of the last form, or
  nil when the file contains no forms. Generalizes the previous
  init-only file load: any persisted Lisp file — configuration or a
  file written by `save-forms` — is evaluated the same way.

  Throws ex-info tagged {:oml/error :evaluate-file :file path} when the
  file does not exist, or when a form fails to read or evaluate,
  chaining the original error as the cause in the latter case."
  [^String path]
  (let [f (java.io.File. path)]
    (when-not (.isFile f)
      (throw (ex-info (str "file not found: " path)
                      {:oml/error :evaluate-file :file path})))
    (try
      (eval-string (slurp f))
      (catch Throwable t
        (throw (ex-info (str "file failed to evaluate: " path)
                        {:oml/error :evaluate-file :file path}
                        t))))))

(defn save-forms
  "Write `forms` — a sequence of Lisp data such as quoted `def` forms —
  to a readable Lisp file at `path`, one printed form per line, so the
  file can later be evaluated with `evaluate-file` to restore them.
  Overwrites any file already at `path`. Returns `path`."
  [^String path forms]
  (spit path (str (str/join "\n" (map pr-str forms)) "\n"))
  path)

(defn save-and-evaluate
  "Save `forms` to `path` with `save-forms`, then evaluate the saved
  file with `evaluate-file`, as one operation. Returns the value of the
  last form, or nil when `forms` is empty. Throws as `evaluate-file`
  does when the saved file fails to evaluate."
  [^String path forms]
  (save-forms path forms)
  (evaluate-file path))

(defn save-data
  "Write `value` to a readable EDN file at `path`, so it can later be
  restored with `load-data`. Overwrites any file already at `path`.
  Returns `path`."
  [^String path value]
  (spit path (pr-str value))
  path)

(defn load-data
  "Read and return the EDN value saved at `path` by `save-data`.

  Throws ex-info tagged {:oml/error :load-data :file path} when the
  file does not exist or its contents are not valid EDN, chaining the
  original error as the cause in the latter case."
  [^String path]
  (let [f (java.io.File. path)]
    (when-not (.isFile f)
      (throw (ex-info (str "data file not found: " path)
                      {:oml/error :load-data :file path})))
    (try
      (edn/read-string (slurp f))
      (catch Throwable t
        (throw (ex-info (str "data file failed to read: " path)
                        {:oml/error :load-data :file path}
                        t))))))

(ns oml.kernel
  "The shared oml runtime.

  One mutable namespace, `oml.user`, is home to everything the image
  defines. Init configuration is evaluated into it, and so is every
  later direct eval, so state defined at boot stays visible to
  everything that acts through the same eval surface.")

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

(defn load-init
  "Evaluate the init file at `path` once, in order, in the shared runtime
  namespace, so definitions it makes are visible to later direct eval.

  Throws ex-info tagged {:oml/error :init :init-file path} when the
  file does not exist or any form fails to read or evaluate, chaining
  the original error as the cause."
  [^String path]
  (let [f (java.io.File. path)]
    (when-not (.isFile f)
      (throw (ex-info (str "init file not found: " path)
                      {:oml/error :init :init-file path})))
    (try
      (eval-string (slurp f))
      (catch Throwable t
        (throw (ex-info (str "init file failed to evaluate: " path)
                        {:oml/error :init :init-file path}
                        t))))))

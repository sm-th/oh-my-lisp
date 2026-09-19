(ns oml.core
  "oml entry point.

  Boots the shared runtime, optionally evaluates executable init
  configuration once before the REPL starts, then runs the built-in
  REPL on the same state. Init extends the runtime; it never replaces
  it — the REPL is built in."
  (:require [oml.kernel :as kernel]
            [oml.repl :as repl]))

(def usage "usage: oml [init-file]")

(defn boot
  "Boot oml.

  Evaluates the init file at `init-path` (when given) once, in the
  shared runtime namespace, then runs the built-in REPL on `in` until
  EOF. An init failure aborts the boot before the REPL starts.

  Returns {:exit 0} on success, or {:exit 1, :message s} describing
  the failed init. Does not throw or exit the JVM itself."
  ([] (boot *in* *out* nil))
  ([in out] (boot in out nil))
  ([in out init-path]
   (kernel/ensure-runtime)
   (let [failure (when init-path
                   (try
                     (kernel/load-init init-path)
                     nil
                     (catch Throwable t
                       {:exit 1
                        :message (str "failed to load init file " init-path
                                      "\n" (repl/error->string t))})))]
     (if failure
       failure
       (do (repl/repl-loop in out)
           {:exit 0})))))

(defn -main
  "Start oml with at most one argument: the path to executable init
  configuration.

  A supplied init file that is missing or invalid fails startup visibly
  on stderr and exits non-zero. On success -main returns rather than
  exiting, so ordinary non-daemon threads started by init keep the JVM
  alive after stdin reaches EOF."
  [& args]
  (case (count args)
    0 (boot)
    1 (let [{:keys [exit message]} (boot *in* *out* (first args))]
        (when (pos? exit)
          (binding [*out* *err*]
            (println "oml:" message))
          (System/exit exit)))
    (do
      (binding [*out* *err*]
        (println usage))
      (System/exit 2))))

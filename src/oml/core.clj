(ns oml.core
  "oml entry point.

  Boots the shared runtime and the plain Lisp REPL — the recovery
  floor that is always available. Optional configuration — including
  any file previously written by `oml.kernel/save-forms` — is
  evaluated once, via `oml.kernel/evaluate-file`, and decides what
  runs next; it extends the runtime and never replaces the built-in
  REPL. Configuration that fails to load or evaluate is reported and
  the plain REPL starts anyway, so the image stays reachable for
  repair."
  (:require [oml.kernel :as kernel]
            [oml.repl :as repl]))

(def usage "usage: oml [--no-init] [init-file]")

(defn- report-init-failure
  "Report the init failure `t` for `init-path` to the current `*out*`
  and return the message that was reported."
  [init-path t]
  (let [message (str "failed to load init file " init-path
                      "\n" (repl/error->string t))]
    (println "oml:" message)
    message))

(defn boot
  "Boot oml.

  With no `init-path`, brings up the plain Lisp REPL directly on
  `in`/`out` — the recovery floor. With `init-path`, evaluates that
  configuration once in the shared runtime namespace, with `*in*` and
  `*out*` bound to `in`/`out` so the configuration can itself start the
  REPL, a service, or another client on the same streams; the
  configuration decides what runs and boot does not start the REPL for
  it. When the configuration fails to load or evaluate, the failure is
  reported to `out` and the plain REPL starts anyway, so the image
  stays reachable for repair — boot never aborts before offering it.

  Returns {:exit 0}, or {:exit 0, :init-failure message} when a
  supplied init file failed and the recovery REPL ran. Does not throw
  or exit the JVM itself."
  ([] (boot *in* *out* nil))
  ([in out] (boot in out nil))
  ([in out init-path]
   (kernel/ensure-runtime)
   (binding [*in* in, *out* out]
     (if-not init-path
       (do (repl/repl-loop in out)
           {:exit 0})
       (let [failure (try
                       (kernel/evaluate-file init-path)
                       nil
                       (catch Throwable t
                         (report-init-failure init-path t)))]
         (if failure
           (do (repl/repl-loop in out)
               {:exit 0 :init-failure failure})
           {:exit 0}))))))

(defn parse-args
  "Parse oml's command-line arguments into a boot request.

  Supports at most one positional init-file path and the --no-init
  flag, which selects the plain REPL and makes any init-file argument
  given alongside it irrelevant. Returns {:init-path path-or-nil} for
  a supported shape, or {:usage-error true} otherwise."
  [args]
  (let [args (vec args)]
    (cond
      (= args []) {:init-path nil}
      (= args ["--no-init"]) {:init-path nil}
      (and (= 2 (count args)) (= "--no-init" (first args))) {:init-path nil}
      (= 1 (count args)) {:init-path (first args)}
      :else {:usage-error true})))

(defn -main
  "Start oml with the command line described by `usage`.

  A malformed command line prints usage to stderr and exits non-zero.
  Otherwise boots oml and returns rather than exiting, so ordinary
  non-daemon threads started by configuration keep the JVM alive after
  stdin reaches EOF."
  [& args]
  (let [{:keys [init-path usage-error]} (parse-args args)]
    (if usage-error
      (do (binding [*out* *err*]
            (println usage))
          (System/exit 2))
      (boot *in* *out* init-path))))

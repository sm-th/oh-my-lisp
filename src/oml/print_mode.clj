(ns oml.print-mode
  "One prompt, no UI: stream the answer and tool activity to stdout.
  `bb prompt \"text\"` runs in the current directory. Ctrl-C exits.
  Init files load as in ACP mode, and `/command ...` runs a command.

  Compare with pi: packages/coding-agent/src/modes/print-mode.ts"
  (:require [clojure.string :as str]
            [oml.agent :as agent]
            [oml.init :as init]
            [oml.session :as session]))

(defn- first-line [s]
  (let [l (first (str/split-lines (str s)))]
    (if (> (count l) 120) (str (subs l 0 120) "...") (or l ""))))

(defn- on-event [{:keys [type] :as e}]
  (case type
    :text-delta (do (print (:text e)) (flush))
    :tool-start (println (str "\n> " (:name e) ": " (:title e)))
    :tool-end   (println (str (if (:error? e) "  failed: " "  ok: ") (first-line (:content e))))
    nil))

(defn -main [& args]
  (let [text (str/join " " args)]
    (when (str/blank? text)
      (binding [*out* *err*] (println "usage: bb prompt <text>"))
      (System/exit 2))
    (try
      (let [errors (init/startup!)]
        (session/with-session (session/create! {:cwd (System/getProperty "user.dir") :on-event on-event})
          (init/session-started! errors)
          (let [{:keys [stop-reason]} (agent/prompt text)]
            (println)
            (when (not= :end-turn stop-reason)
              (binding [*out* *err*] (println "[stopped:" (name stop-reason) "]"))))))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println "error:" (ex-message e)))
        (System/exit 1)))))

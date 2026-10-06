(ns oml.print-mode
  "One prompt, no UI: stream the answer and tool activity to stdout.
  `bb prompt \"text\"` runs in the current directory. Ctrl-C exits.
  Init files load as in ACP mode, and `/command ...` runs a command.

  Compare with pi: packages/coding-agent/src/modes/print-mode.ts"
  (:require [clojure.string :as str]
            [oml.agent :as agent]
            [oml.init :as init]))

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
  (let [text (str/join " " args)
        cwd (System/getProperty "user.dir")]
    (when (str/blank? text)
      (binding [*out* *err*] (println "usage: bb prompt <text>"))
      (System/exit 2))
    (init/startup!)
    (init/load-project-init! cwd)
    (try
      (let [ctx {:cwd cwd :on-event on-event}
            {:keys [command input]} (agent/parse-prompt text)]
        (if command
          (println (agent/run-command ctx command input))
          (let [result (agent/run ctx [{:role "user" :content text}])]
            (println)
            (when (not= :end-turn (:stop-reason result))
              (binding [*out* *err*] (println "[stopped:" (name (:stop-reason result)) "]"))))))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println "error:" (ex-message e)))
        (System/exit 1)))))

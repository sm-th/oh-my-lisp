(ns oml.print-mode
  "One prompt, no UI: stream the answer and tool activity to stdout.
  `bb prompt \"text\"` runs in the current directory. Ctrl-C exits.

  Compare with pi: packages/coding-agent/src/modes/print-mode.ts"
  (:require [clojure.string :as str]
            [oml.agent :as agent]
            [oml.llm :as llm]
            [oml.tools :as tools]))

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
      (let [cfg (llm/config)
            cwd (System/getProperty "user.dir")
            result (agent/run {:llm #(llm/stream-chat cfg %)
                               :tools tools/default-tools
                               :messages [{:role "system" :content (agent/system-prompt cwd)}
                                          {:role "user" :content text}]
                               :cwd cwd
                               :on-event on-event})]
        (println)
        (when (not= :end-turn (:stop-reason result))
          (binding [*out* *err*] (println "[stopped:" (name (:stop-reason result)) "]"))))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println "error:" (ex-message e)))
        (System/exit 1)))))

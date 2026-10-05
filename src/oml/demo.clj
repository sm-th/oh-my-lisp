(ns oml.demo
  "CLI demo: a process that survives OS process exits between await points.

    bb demo start            run until the first `ask`, save, exit
    bb demo answer <text>    load, resume with <text>, run to next `ask` or done
    bb demo show             print the saved state
    bb demo boundary         show the boundary-rule error"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [oml.machine :as m]
            [oml.store :as store]))

(def state-path ".oml/state.edn")

(def program
  '(let [plan ["step-1" "step-2" "step-3"]]
     (loop [ps plan acc []]
       (if (empty? ps)
         (do (println "done" acc) acc)
         (let [answer (ask "proceed with?" (first ps))]
           (recur (rest ps) (conj acc [(first ps) answer])))))))

(def boundary-program
  '(map (fn [x] (ask "?" x)) [1 2]))

(defn- report [state]
  (case (:status state)
    :suspended (let [{:keys [op args]} (:await state)]
                 (store/save! state-path state)
                 (println (str "[" op "] " (str/join " " args)))
                 (println (str "suspended after " (:steps state) " steps; state saved to "
                               state-path " (" (.length (io/file state-path)) " bytes)"))
                 (println "continue with: bb demo answer <text>"))
    :done (do (io/delete-file state-path true)
              (println "result:" (pr-str (:value state))))
    :error (do (println "error:" (get-in state [:error :message]))
               (System/exit 1))))

(defn- usage []
  (println "usage: bb demo start | answer <text> | show | boundary")
  (System/exit 2))

(defn -main [& [cmd & args]]
  (case cmd
    "start" (report (m/run (m/start program)))
    "answer" (if (.exists (io/file state-path))
               (report (m/resume (store/load state-path) (str/join " " args)))
               (do (println "no saved process; run: bb demo start") (System/exit 1)))
    "show" (pprint/pprint (store/load state-path))
    "boundary" (do (println "program:" (pr-str boundary-program))
                   (println "error:" (get-in (m/run (m/start boundary-program)) [:error :message])))
    (usage)))

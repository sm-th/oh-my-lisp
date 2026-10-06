(ns oml.custom-test
  "Customisation in-process: user code redefines and advises functions and
  adds tools by namespace, and the next turn of the loop sees it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.agent :as agent]
            [oml.agent-test :refer [run-prompt tool-call]]
            [oml.custom :as custom]))

(def ^:private answer {:role "assistant" :content "ok"})

(defn- tool-names [request] (set (map :name (:tools request))))

(defn- system-prompt [request] (:content (first (:messages request))))

(deftest defn-redefinition-changes-the-next-turn
  (let [orig-prompt @#'agent/system-prompt
        orig-result @#'agent/tool-result-message]
    (try
      (load-string "(in-ns 'oml.agent) (defn system-prompt [] \"Rule: tabs.\")")
      (is (= "Rule: tabs." (system-prompt (first (:requests (run-prompt "." "x" answer))))))
      (load-string "(in-ns 'oml.agent)
                    (defn tool-result-message [{:keys [id]} result]
                      {:role \"tool\" :tool_call_id id :content (str \"<<\" (:content result) \">>\")})")
      (let [{:keys [messages]} (run-prompt "." "x" {:role "assistant" :tool_calls [(tool-call "c1" "nope" "{}")]} answer)]
        (is (= "<<Unknown tool: nope>>" (:content (nth messages 2)))))
      (finally
        (alter-var-root #'agent/system-prompt (constantly orig-prompt))
        (alter-var-root #'agent/tool-result-message (constantly orig-result))))))

(deftest tools-are-the-functions-of-tool-namespaces
  (load-string "(ns oml.custom-test.tools)
                (defn shout \"Upper-case the text.\"
                  {:kind \"other\" :params {:text [:string \"Text\"] :times [:integer \"Repeat\"]}}
                  [{:keys [text times] :or {times 1}}]
                  (apply str (repeat times (clojure.string/upper-case text))))
                (defn- helper \"Not a tool: private.\" [_] nil)
                (defn undocumented [_] nil)
                (def limit \"Not a tool: not a function.\" 3)")
  (with-redefs [agent/tool-namespaces '[oml.tools oml.custom-test.tools]]
    (let [call {:role "assistant" :tool_calls [(tool-call "c1" "shout" "{\"text\":\"hi\",\"times\":2}")]}
          {:keys [messages requests]} (run-prompt "." "x" call answer)]
      (is (= #{"read" "write" "edit" "bash" "shout"} (tool-names (first requests))))
      (is (= {:name "shout" :description "Upper-case the text."
              :parameters {:type "object"
                           :properties {:text {:type "string" :description "Text"}
                                        :times {:type "integer" :description "Repeat"}}
                           :required ["text"]}}
             (first (filter #(= "shout" (:name %)) (:tools (first requests))))))
      (is (= "HIHI" (:content (nth messages 2))) "the tool runs")
      (testing "a plain defn redefinition keeps it a tool, with string parameters"
        (load-string "(in-ns 'oml.custom-test.tools)
                      (defn shout \"Shout quietly.\" [{:keys [text volume]}] (str text \"!\"))")
        (let [{:keys [messages requests]} (run-prompt "." "x" call answer)]
          (is (= {:type "object" :properties {:text {:type "string"} :volume {:type "string"}}
                  :required ["text" "volume"]}
                 (:parameters (first (filter #(= "shout" (:name %)) (:tools (first requests)))))))
          (is (= "hi!" (:content (nth messages 2))))))
      (testing "ns-unmap removes it"
        (ns-unmap 'oml.custom-test.tools 'shout)
        (let [{:keys [messages requests]} (run-prompt "." "x" call answer)]
          (is (not (contains? (tool-names (first requests)) "shout")))
          (is (= "Unknown tool: shout" (:content (nth messages 2))))))))
  (testing "leaving the namespace out of tool-namespaces removes its tools"
    (is (= #{"read" "write" "edit" "bash"} (set (map agent/fn-name (agent/tools)))))))

(defn greet [who] (str "hello " who))

(deftest advice
  (custom/advise! #'greet ::shout (fn [f & args] (str/upper-case (apply f args))))
  (is (= "HELLO BOB" (greet "bob")))
  (custom/advise! #'greet ::bang (fn [f & args] (str (apply f args) "!")))
  (is (= "HELLO BOB!" (greet "bob")) "the newest advice is outermost")
  (custom/advise! #'greet ::shout (fn [f & args] (str "<" (apply f args) ">")))
  (is (= "<hello bob>!" (greet "bob")) "the same key replaces instead of stacking")
  (is (= [::shout ::bang] (custom/advice-keys #'greet)))
  (custom/unadvise! #'greet ::shout)
  (is (= "hello bob!" (greet "bob")))
  (custom/unadvise! #'greet)
  (is (= "hello bob" (greet "bob")))
  (is (empty? (custom/advice-keys #'greet)) "the original is restored"))

(deftest a-permission-policy-is-advice-on-execute-tool
  (custom/advise! #'agent/execute-tool ::no-rm-rf
                  (fn [execute {:keys [args] :as call}]
                    (if (str/includes? (:command args "") "rm -rf")
                      {:content "Blocked: no rm -rf" :error? true}
                      (execute call))))
  (custom/advise! #'agent/execute-tool ::redact
                  (fn [execute call] (update (execute call) :content str/replace "secret" "[redacted]")))
  (try
    (let [{:keys [messages]}
          (run-prompt "." "x"
                      {:role "assistant"
                       :tool_calls [(tool-call "c1" "bash" "{\"command\":\"rm -rf /nothing\"}")
                                    (tool-call "c2" "bash" "{\"command\":\"echo secret\"}")]}
                      answer)
          [blocked echoed] (filter #(= "tool" (:role %)) messages)]
      (is (= "Blocked: no rm -rf" (:content blocked)) "the model sees the reason")
      (is (= "[redacted]" (:content echoed)) "advice can replace the result"))
    (finally (custom/unadvise! #'agent/execute-tool)))
  (is (empty? (custom/advice-keys #'agent/execute-tool))))

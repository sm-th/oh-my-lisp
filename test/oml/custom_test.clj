(ns oml.custom-test
  "Customisation in-process: user code redefines, hooks, advises and adds
  tools, and the next turn of the loop sees it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oml.agent :as agent]
            [oml.agent-test :refer [scripted-model tool-call]]
            [oml.custom :as custom :refer [defsetting setq]]
            [oml.ext.core]))

(defn- run-with
  "Run one prompt against scripted model messages. Returns [result requests]."
  [text & messages]
  (let [requests (atom [])
        result (with-redefs [agent/call-model (apply scripted-model requests messages)]
                 (agent/run {:cwd "."} [{:role "user" :content text}]))]
    [result @requests]))

(def ^:private answer {:role "assistant" :content "ok"})

(defn- tool-names [request] (set (map :name (:tools request))))

(deftest defn-redefinition-changes-the-next-turn
  (load-string "(ns oml.custom-test.user)
                (defn house-rules [_ctx] \"Rule: tabs.\")
                (oml.custom/add-hook! #'oml.agent/system-prompt-functions #'house-rules)")
  (try
    (let [[_ [r1]] (run-with "x" answer)
          _ (load-string "(ns oml.custom-test.user) (defn house-rules [_ctx] \"Rule: spaces.\")")
          [_ [r2]] (run-with "x" answer)]
      (is (str/ends-with? (:content (first (:messages r1))) "Rule: tabs."))
      (is (str/ends-with? (:content (first (:messages r2))) "Rule: spaces.")
          "the hook holds the var, so the redefinition is used"))
    (testing "a core step redefined from user code"
      (let [orig @#'agent/tool-result-message]
        (try
          (load-string "(in-ns 'oml.agent)
                        (defn tool-result-message [{:keys [id]} result]
                          {:role \"tool\" :tool_call_id id :content (str \"<<\" (:content result) \">>\")})")
          (let [[result] (run-with "x" {:role "assistant" :tool_calls [(tool-call "c1" "nope" "{}")]} answer)]
            (is (= "<<Unknown tool: nope>>" (:content (nth (:messages result) 2)))))
          (finally (alter-var-root #'agent/tool-result-message (constantly orig))))))
    (finally
      (custom/remove-hook! #'agent/system-prompt-functions
                           (resolve 'oml.custom-test.user/house-rules)))))

(deftest tools-are-discovered-and-ns-unmap-removes-them
  (load-string "(ns oml.custom-test.tools)
                (defn shout \"Upper-case the text.\"
                  {:oml/tool true :oml/kind \"other\"
                   :oml/params {:text [:string \"Text\"] :times [:integer \"Repeat\" :optional]}}
                  [_ctx {:keys [text]}]
                  (clojure.string/upper-case text))")
  (let [[result [r1]] (run-with "x" {:role "assistant" :tool_calls [(tool-call "c1" "shout" "{\"text\":\"hi\"}")]}
                                answer)
        spec (first (filter #(= "shout" (:name %)) (:tools r1)))]
    (is (= {:name "shout" :description "Upper-case the text."
            :parameters {:type "object"
                         :properties {:text {:type "string" :description "Text"}
                                      :times {:type "integer" :description "Repeat"}}
                         :required ["text"]}}
           spec))
    (is (= "HI" (:content (nth (:messages result) 2))))
    (ns-unmap 'oml.custom-test.tools 'shout)
    (let [[result [r2]] (run-with "x" {:role "assistant" :tool_calls [(tool-call "c2" "shout" "{\"text\":\"hi\"}")]}
                                  answer)]
      (is (not (contains? (tool-names r2) "shout")))
      (is (= "Unknown tool: shout" (:content (nth (:messages result) 2)))))))

(defn- block-rm-rf [call _ctx]
  (when (str/includes? (get-in call [:args :command] "") "rm -rf")
    (assoc call :block "no rm -rf")))

(defn- redact [result _call _ctx]
  (update result :content str/replace "secret" "[redacted]"))

(deftest hooks
  (custom/add-hook! #'agent/before-tool-functions #'block-rm-rf)
  (custom/add-hook! #'agent/before-tool-functions #'block-rm-rf)
  (custom/add-hook! #'agent/after-tool-functions #'redact)
  (try
    (is (= [#'block-rm-rf] agent/before-tool-functions) "adding a var twice is a no-op")
    (let [[result] (run-with "x"
                             {:role "assistant"
                              :tool_calls [(tool-call "c1" "bash" "{\"command\":\"rm -rf /nothing\"}")
                                           (tool-call "c2" "bash" "{\"command\":\"echo secret\"}")]}
                             answer)
          [blocked echoed] (filter #(= "tool" (:role %)) (:messages result))]
      (is (= "Blocked: no rm -rf" (:content blocked)) "the model sees the block reason")
      (is (= "[redacted]" (:content echoed)) "an after-tool hook replaced the result"))
    (finally
      (custom/remove-hook! #'agent/before-tool-functions #'block-rm-rf)
      (custom/remove-hook! #'agent/after-tool-functions #'redact)))
  (is (= [] agent/before-tool-functions agent/after-tool-functions))
  (testing "before-model-functions rewrite the request"
    (let [f (fn [req _ctx] (assoc req :tools []))]
      (custom/add-hook! #'agent/before-model-functions f)
      (try
        (let [[_ [r]] (run-with "x" answer)]
          (is (empty? (:tools r))))
        (finally (custom/remove-hook! #'agent/before-model-functions f))))))

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
  (custom/unadvise! #'greet ::bang)
  (is (= "hello bob" (greet "bob")))
  (is (nil? (:oml/advice (meta #'greet))) "the original is restored and forgotten")
  (testing "advice on a loop step"
    (let [calls (atom 0)]
      (custom/advise! #'agent/model-turn ::count (fn [f & args] (swap! calls inc) (apply f args)))
      (try (run-with "x" answer)
           (finally (custom/unadvise! #'agent/model-turn ::count)))
      (is (= 1 @calls))
      (is (empty? (custom/advice-keys #'agent/model-turn))))))

(defsetting test-level "A setting for tests." 1)

(deftest settings
  (is (= 1 test-level))
  (is (= {:doc "A setting for tests." :oml/setting true :oml/default 1}
         (select-keys (meta #'test-level) [:doc :oml/setting :oml/default])))
  (setq test-level 5)
  (load-string "(in-ns 'oml.custom-test) (oml.custom/defsetting test-level \"A setting for tests.\" 1)")
  (is (= 5 test-level) "re-evaluating a defsetting keeps the user's value")
  (is (some #{#'test-level} (custom/settings)) "settings are discovered")
  (is (some #{#'agent/max-turns} (custom/settings))))

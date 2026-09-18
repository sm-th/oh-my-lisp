(require '[clojure.test :as t] 'oml.config-test 'oml.chat-test)

(let [{:keys [fail error]} (t/run-tests 'oml.config-test 'oml.chat-test)]
  (when (pos? (+ fail error)) (System/exit 1)))

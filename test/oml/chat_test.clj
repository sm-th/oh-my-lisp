(ns oml.chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [oml.chat :as chat]))

(deftest request-shape
  (let [r (chat/request {:base-url "http://x/v1" :model "auto"}
                        [{:role "user" :content "hi"}])]
    (testing "endpoint is base-url + /chat/completions"
      (is (= "http://x/v1/chat/completions" (:url r))))
    (testing "model carried through"
      (is (= "auto" (get-in r [:body :model]))))
    (testing "system prompt is prepended, then the conversation"
      (is (= "system" (get-in r [:body :messages 0 :role])))
      (is (= {:role "user" :content "hi"} (get-in r [:body :messages 1]))))))

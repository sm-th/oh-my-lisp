(ns oml.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [oml.config :as config]))

(deftest credential-resolution
  (testing "config :openai supplies creds"
    (is (= {:base-url "http://x/v1" :api-key "k" :model "auto"}
           (config/creds {:openai {:base-url "http://x/v1" :api-key "k"}}))))
  (testing "an explicit :model is kept"
    (is (= "gpt-x" (:model (config/creds {:openai {:base-url "http://x/v1" :api-key "k" :model "gpt-x"}})))))
  (testing "no creds -> nil"
    (is (nil? (config/creds {})))))

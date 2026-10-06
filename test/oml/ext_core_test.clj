(ns oml.ext-core-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [oml.cancel :as cancel]
            [oml.ext.core :as core]))

(defn- run [tool ctx args] (tool ctx args))

(deftest edit-requires-a-unique-match
  (let [dir (str (fs/create-temp-dir))
        f (str dir "/a.txt")
        ctx {:cwd dir}]
    (spit f "alpha\nbeta\nbeta\n")
    (testing "zero matches"
      (is (thrown-with-msg? Exception #"not found" (run core/edit ctx {:path "a.txt" :old_text "gamma" :new_text "x"}))))
    (testing "several matches"
      (is (thrown-with-msg? Exception #"matches 2 times" (run core/edit ctx {:path "a.txt" :old_text "beta" :new_text "x"}))))
    (is (= "alpha\nbeta\nbeta\n" (slurp f)) "failed edits leave the file alone")
    (testing "unique match"
      (run core/edit ctx {:path "a.txt" :old_text "alpha\nbeta" :new_text "ALPHA\nbeta"})
      (is (= "ALPHA\nbeta\nbeta\n" (slurp f))))))

(deftest read-pages-with-line-numbers
  (let [dir (str (fs/create-temp-dir))]
    (spit (str dir "/r.txt") "a\nb\nc\n")
    (is (= "     2\tb\n[lines 2-2 of 3; use offset=3 to continue]"
           (run core/read {:cwd dir} {:path "r.txt" :offset 2 :limit 1})))))

(deftest bash-output-exit-and-timeout
  (is (= "out\nerr" (run core/bash {:cwd "."} {:command "echo out; echo err >&2"})))
  (is (thrown-with-msg? Exception #"\[exit code 3\]" (run core/bash {:cwd "."} {:command "exit 3"})))
  (let [t0 (System/currentTimeMillis)]
    (is (thrown-with-msg? Exception #"timed out" (run core/bash {:cwd "."} {:command "sleep 30" :timeout 0.5})))
    (is (< (- (System/currentTimeMillis) t0) 5000) "the process tree is killed, not waited for")))

(deftest bash-cancel-kills-the-command
  (let [token (cancel/token)
        t0 (System/currentTimeMillis)]
    (future (Thread/sleep 300) (cancel/cancel! token))
    (is (thrown-with-msg? Exception #"cancelled" (run core/bash {:cwd "." :cancel token} {:command "sleep 30"})))
    (is (< (- (System/currentTimeMillis) t0) 5000))))

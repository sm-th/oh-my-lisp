(ns oml.tools-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [oml.cancel :as cancel]
            [oml.session :as session]
            [oml.tools :as tools]))

(defmacro ^:private in-session
  "Run `body` in a fresh session with `opts` ({:cwd :cancel})."
  [opts & body]
  `(session/with-session (session/create! {:cwd (:cwd ~opts)})
     (swap! session/sessions assoc-in [session/*session* :cancel] (:cancel ~opts))
     ~@body))

(deftest edit-requires-a-unique-match
  (let [dir (str (fs/create-temp-dir))
        f (str dir "/a.txt")]
    (spit f "alpha\nbeta\nbeta\n")
    (in-session {:cwd dir}
      (testing "zero matches"
        (is (thrown-with-msg? Exception #"not found" (tools/edit {:path "a.txt" :old_text "gamma" :new_text "x"}))))
      (testing "several matches"
        (is (thrown-with-msg? Exception #"matches 2 times" (tools/edit {:path "a.txt" :old_text "beta" :new_text "x"}))))
      (is (= "alpha\nbeta\nbeta\n" (slurp f)) "failed edits leave the file alone")
      (testing "unique match"
        (tools/edit {:path "a.txt" :old_text "alpha\nbeta" :new_text "ALPHA\nbeta"})
        (is (= "ALPHA\nbeta\nbeta\n" (slurp f)))))))

(deftest read-pages-with-line-numbers
  (let [dir (str (fs/create-temp-dir))]
    (spit (str dir "/r.txt") "a\nb\nc\n")
    (in-session {:cwd dir}
      (is (= "     2\tb\n[lines 2-2 of 3; use offset=3 to continue]"
             (tools/read {:path "r.txt" :offset 2 :limit 1}))))))

(deftest bash-output-exit-and-timeout
  (in-session {:cwd "."}
    (is (= "out\nerr" (tools/bash {:command "echo out; echo err >&2"})))
    (is (thrown-with-msg? Exception #"\[exit code 3\]" (tools/bash {:command "exit 3"})))
    (let [t0 (System/currentTimeMillis)]
      (is (thrown-with-msg? Exception #"timed out" (tools/bash {:command "sleep 30" :timeout 0.5})))
      (is (< (- (System/currentTimeMillis) t0) 5000) "the process tree is killed, not waited for"))))

(deftest bash-cancel-kills-the-command
  (let [token (cancel/token)
        t0 (System/currentTimeMillis)]
    (future (Thread/sleep 300) (cancel/cancel! token))
    (in-session {:cwd "." :cancel token}
      (is (thrown-with-msg? Exception #"cancelled" (tools/bash {:command "sleep 30"}))))
    (is (< (- (System/currentTimeMillis) t0) 5000))))

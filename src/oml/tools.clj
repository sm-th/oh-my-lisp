(ns oml.tools
  "The built-in tools: read, write, edit, bash.

  Every public function with a docstring in a namespace of
  oml.agent/tool-namespaces is a tool, these included: redefine one with
  defn to change it, ns-unmap it to remove it. A tool takes the argument
  map the model sent; its name and docstring are what the model sees, and
  its parameters come from the destructuring (see oml.agent/parameters).
  The optional attr-map gives :params types and descriptions, the ACP
  :kind and a :title fn of the arguments for the UI. A tool returns a
  string for the model and throws ex-info on failure; the loop turns that
  into an error result the model can read. Context (cwd, cancel token)
  comes from oml.session. Helpers are private.

  Compare with pi: packages/coding-agent/src/core/tools/{read,write,edit,bash}.ts"
  (:refer-clojure :exclude [read])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [oml.cancel :as cancel]
            [oml.session :as session :refer [resolve-path]]))

(def read-max-chars
  "Cap on the characters `read` returns; the beginning is kept."
  50000)

(def read-default-limit
  "Lines `read` returns when the model gives no limit."
  2000)

(def bash-max-chars
  "Cap on the characters of `bash` output; the end is kept."
  50000)

(def bash-timeout
  "Seconds a `bash` command may run when the model gives no timeout."
  120)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- fail [msg] (throw (ex-info msg {})))

(defn- cap-head
  "Keep the first `n` characters of `s`, noting what was dropped."
  [n s]
  (if (<= (count s) n)
    s
    (str (subs s 0 n) "\n[output truncated: " (- (count s) n) " more characters]")))

(defn- cap-tail
  "Keep the last `n` characters of `s` (the useful part of command output)."
  [n s]
  (if (<= (count s) n)
    s
    (str "[output truncated: first " (- (count s) n) " characters dropped]\n"
         (subs s (- (count s) n)))))

;; ---------------------------------------------------------------------------
;; read

(defn read
  "Read a text file. Output is line-numbered. Use offset (1-based line) and
  limit (number of lines) to page through large files."
  {:kind "read"
   :title (fn [{:keys [path]}] (str "Read " path))
   :params {:path [:string "File path, relative to the working directory or absolute"]
            :offset [:integer "First line to read (1-based)" :optional]
            :limit [:integer "Maximum number of lines" :optional]}}
  [{:keys [path offset limit]}]
  (let [f (resolve-path path)]
    (cond (not (fs/exists? f)) (fail (str "File not found: " f))
          (fs/directory? f)    (fail (str "Is a directory: " f)))
    (let [lines  (str/split-lines (slurp f))
          start  (max 1 (or offset 1))
          limit  (or limit read-default-limit)
          shown  (take limit (drop (dec start) lines))
          end    (+ start (count shown) -1)
          body   (->> shown
                      (map-indexed (fn [i l] (format "%6d\t%s" (+ start i) l)))
                      (str/join "\n"))]
      (cond
        (and (empty? shown) (seq lines))
        (fail (str "offset " start " is past the end of the file (" (count lines) " lines)"))

        (< end (count lines))
        (str (cap-head read-max-chars body) "\n[lines " start "-" end " of " (count lines)
             "; use offset=" (inc end) " to continue]")

        :else (cap-head read-max-chars body)))))

;; ---------------------------------------------------------------------------
;; write

(defn write
  "Create or overwrite a file with the given content. Parent directories are
  created."
  {:kind "edit"
   :title (fn [{:keys [path]}] (str "Write " path))
   :params {:path [:string "File path"]
            :content [:string "Full new file content"]}}
  [{:keys [path content]}]
  (when-not (string? content) (fail "content is required"))
  (let [f (resolve-path path)]
    (some-> (fs/parent f) fs/create-dirs)
    (spit f content)
    (str "Wrote " (count (.getBytes ^String content "UTF-8")) " bytes to " f)))

;; ---------------------------------------------------------------------------
;; edit

(defn- count-occurrences [^String s ^String sub]
  (loop [from 0 n 0]
    (let [i (.indexOf s sub from)]
      (if (neg? i) n (recur (+ i (count sub)) (inc n))))))

(defn edit
  "Replace one exact occurrence of old_text with new_text in a file. old_text
  must match exactly once (whitespace included); read the file first."
  {:kind "edit"
   :title (fn [{:keys [path]}] (str "Edit " path))
   :params {:path [:string "File path"]
            :old_text [:string "Exact text to replace; must be unique in the file"]
            :new_text [:string "Replacement text"]}}
  [{:keys [path old_text new_text]}]
  (when (or (not (string? old_text)) (empty? old_text)) (fail "old_text must be a non-empty string"))
  (when-not (string? new_text) (fail "new_text is required"))
  (let [f (resolve-path path)]
    (when-not (fs/regular-file? f) (fail (str "File not found: " f)))
    (let [text (slurp f)
          n    (count-occurrences text old_text)]
      (case n
        0 (fail (str "old_text not found in " f ". It must match the file exactly, including whitespace."))
        1 (let [i (str/index-of text old_text)]
            (spit f (str (subs text 0 i) new_text (subs text (+ i (count old_text)))))
            (str "Edited " f))
        (fail (str "old_text matches " n " times in " f ". Include more surrounding context so it matches exactly once."))))))

;; ---------------------------------------------------------------------------
;; bash

(defn bash
  "Run a bash command in the working directory. Returns combined stdout and
  stderr (the tail, if long). Non-zero exit is reported as an error."
  {:kind "execute"
   :title (fn [{:keys [command]}] command)
   :params {:command [:string "Command to run"]
            :timeout [:number "Timeout in seconds" :optional]}}
  [{:keys [command timeout]}]
  (when (str/blank? command) (fail "command is required"))
  (let [cancel     (:cancel (session/session))
        timeout-ms (* 1000 (or timeout bash-timeout))
        proc       (p/process ["bash" "-c" command] {:dir (session/cwd) :err :out})
        _          (.close ^java.io.OutputStream (:in proc)) ; no stdin: ours may be the ACP stream
        ;; Read output concurrently so a chatty process never blocks on a full pipe.
        out        (future (slurp (:out proc)))
        kill       #(p/destroy-tree proc)
        unregister (cancel/on-cancel cancel kill)
        deadline   (+ (System/currentTimeMillis) timeout-ms)
        result     (loop []
                     (let [r (deref proc 100 ::running)]
                       (cond (cancel/cancelled? cancel) :cancelled
                             (not= r ::running) :exited
                             (> (System/currentTimeMillis) deadline) (do (kill) :timeout)
                             :else (recur))))
        _          (unregister)
        output     (cap-tail bash-max-chars (str/trimr (deref out 5000 "")))]
    (case result
      :cancelled (fail (str/triml (str output "\n[cancelled]")))
      :timeout   (fail (str/triml (str output "\n[command timed out after " (/ timeout-ms 1000) "s]")))
      (let [code (:exit @proc)]
        (if (zero? code)
          (if (str/blank? output) "(no output)" output)
          (fail (str/triml (str output "\n[exit code " code "]"))))))))

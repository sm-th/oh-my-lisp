(ns oml.ext.core
  "The built-in tools and system prompt, as an ordinary extension.

  Nothing here is special: the core finds these functions by their metadata,
  exactly as it finds a user's. Redefine one to change it, `ns-unmap` it to
  remove it, or leave this namespace out of oml.init/extensions.

  A tool is a public fn of [ctx args] marked ^:oml/tool. Its docstring is
  the description sent to the model, :oml/params its parameters (see
  oml.custom/json-schema), :oml/kind its ACP tool kind and :oml/title a fn
  of args giving the UI title. ctx is {:cwd :cancel :on-event :session-id};
  args are the parsed arguments. It returns a string for the model and
  throws ex-info on failure; the loop turns that into an error result the
  model can read.

  A prompt section is a fn of [ctx] returning text (or nil), added as a var
  to oml.agent/system-prompt-functions, so redefining it with a plain defn
  changes the prompt of the next turn.

  Compare with pi: packages/coding-agent/src/core/tools/{read,write,edit,bash}.ts
  and core/system-prompt.ts"
  (:refer-clojure :exclude [read])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [oml.agent :as agent]
            [oml.cancel :as cancel]
            [oml.custom :refer [add-hook! defsetting]]))

;; ---------------------------------------------------------------------------
;; Settings

(defsetting read-max-chars
  "Cap on the characters `read` returns; the beginning is kept."
  50000)

(defsetting read-default-limit
  "Lines `read` returns when the model gives no limit."
  2000)

(defsetting bash-max-chars
  "Cap on the characters of `bash` output; the end is kept."
  50000)

(defsetting bash-timeout
  "Seconds a `bash` command may run when the model gives no timeout."
  120)

;; ---------------------------------------------------------------------------
;; System prompt

(defn identity-section
  "Who the agent is."
  [_ctx]
  "You are oml, a coding agent working in a user's project.")

(defn cwd-section
  "Where the agent works."
  [{:keys [cwd]}]
  (when cwd (str "The working directory is " cwd ".")))

(defn guidelines-section
  "How to use the built-in tools and how to answer."
  [_ctx]
  (str/join "\n"
            ["Use the tools to inspect and change files and to run commands:"
             "read before you edit; edit needs an exact, unique old_text; prefer edit over write for existing files."
             "Be concise. When the task is done, answer with a short summary and no tool call."]))

(add-hook! #'agent/system-prompt-functions #'identity-section)
(add-hook! #'agent/system-prompt-functions #'cwd-section)
(add-hook! #'agent/system-prompt-functions #'guidelines-section)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- fail [msg] (throw (ex-info msg {:tool-error true})))

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
  {:oml/tool true
   :oml/kind "read"
   :oml/title (fn [{:keys [path]}] (str "Read " path))
   :oml/params {:path [:string "File path, relative to the working directory or absolute"]
                :offset [:integer "First line to read (1-based)" :optional]
                :limit [:integer "Maximum number of lines" :optional]}}
  [{:keys [cwd]} {:keys [path offset limit]}]
  (let [f (agent/resolve-path cwd path)]
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
  {:oml/tool true
   :oml/kind "edit"
   :oml/title (fn [{:keys [path]}] (str "Write " path))
   :oml/params {:path [:string "File path"]
                :content [:string "Full new file content"]}}
  [{:keys [cwd]} {:keys [path content]}]
  (when-not (string? content) (fail "content is required"))
  (let [f (agent/resolve-path cwd path)]
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
  {:oml/tool true
   :oml/kind "edit"
   :oml/title (fn [{:keys [path]}] (str "Edit " path))
   :oml/params {:path [:string "File path"]
                :old_text [:string "Exact text to replace; must be unique in the file"]
                :new_text [:string "Replacement text"]}}
  [{:keys [cwd]} {:keys [path old_text new_text]}]
  (when (or (not (string? old_text)) (empty? old_text)) (fail "old_text must be a non-empty string"))
  (when-not (string? new_text) (fail "new_text is required"))
  (let [f (agent/resolve-path cwd path)]
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
  {:oml/tool true
   :oml/kind "execute"
   :oml/title (fn [{:keys [command]}] command)
   :oml/params {:command [:string "Command to run"]
                :timeout [:number "Timeout in seconds" :optional]}}
  [{:keys [cwd cancel]} {:keys [command timeout]}]
  (when (str/blank? command) (fail "command is required"))
  (let [timeout-ms (* 1000 (or timeout bash-timeout))
        proc       (p/process ["bash" "-c" command] {:dir cwd :err :out})
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

(ns oml.tools
  "Chapter 3: tools.

  A tool is data: a name, a description and a JSON-schema for its arguments
  (both sent to the model), an ACP `kind` and `title` for the UI, and an
  `execute` fn. `execute` receives a context {:cwd :cancel} and the parsed
  arguments, and returns a string for the model. Failures are thrown as
  ex-info; the agent loop turns them into error results the model can read.

  Compare with pi: packages/coding-agent/src/core/tools/{read,write,edit,bash}.ts"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [oml.cancel :as cancel]))

(def max-output-chars
  "Cap on any tool result, so one large file or noisy command cannot flood the
  context window."
  50000)

(defn- fail [msg] (throw (ex-info msg {:tool-error true})))

(defn resolve-path
  "Resolve `path` against the session cwd (absolute paths and ~ pass through)."
  [cwd path]
  (when (str/blank? path) (fail "path is required"))
  (let [path (if (str/starts-with? path "~")
               (str (fs/home) (subs path 1))
               path)]
    (str (fs/normalize (fs/absolutize (if (fs/absolute? path) path (fs/path cwd path)))))))

(defn- cap-head
  "Keep the beginning of `s`, noting what was dropped."
  [s]
  (if (<= (count s) max-output-chars)
    s
    (str (subs s 0 max-output-chars)
         "\n[output truncated: " (- (count s) max-output-chars) " more characters]")))

(defn- cap-tail
  "Keep the end of `s` (the useful part of command output)."
  [s]
  (if (<= (count s) max-output-chars)
    s
    (str "[output truncated: first " (- (count s) max-output-chars) " characters dropped]\n"
         (subs s (- (count s) max-output-chars)))))

;; ---------------------------------------------------------------------------
;; read

(defn- read-file [{:keys [cwd]} {:keys [path offset limit]}]
  (let [f (resolve-path cwd path)]
    (cond (not (fs/exists? f)) (fail (str "File not found: " f))
          (fs/directory? f)    (fail (str "Is a directory: " f)))
    (let [lines  (str/split-lines (slurp f))
          start  (max 1 (or offset 1))
          limit  (or limit 2000)
          shown  (take limit (drop (dec start) lines))
          end    (+ start (count shown) -1)
          body   (->> shown
                      (map-indexed (fn [i l] (format "%6d\t%s" (+ start i) l)))
                      (str/join "\n"))]
      (cond
        (and (empty? shown) (seq lines))
        (fail (str "offset " start " is past the end of the file (" (count lines) " lines)"))

        (< end (count lines))
        (str (cap-head body) "\n[lines " start "-" end " of " (count lines)
             "; use offset=" (inc end) " to continue]")

        :else (cap-head body)))))

(def read-tool
  {:name "read"
   :description "Read a text file. Output is line-numbered. Use offset (1-based line) and limit (number of lines) to page through large files."
   :parameters {:type "object"
                :properties {:path {:type "string" :description "File path, relative to the working directory or absolute"}
                             :offset {:type "integer" :description "First line to read (1-based)"}
                             :limit {:type "integer" :description "Maximum number of lines (default 2000)"}}
                :required ["path"]}
   :kind "read"
   :title (fn [{:keys [path]}] (str "Read " path))
   :execute read-file})

;; ---------------------------------------------------------------------------
;; write

(defn- write-file [{:keys [cwd]} {:keys [path content]}]
  (when-not (string? content) (fail "content is required"))
  (let [f (resolve-path cwd path)]
    (some-> (fs/parent f) fs/create-dirs)
    (spit f content)
    (str "Wrote " (count (.getBytes ^String content "UTF-8")) " bytes to " f)))

(def write-tool
  {:name "write"
   :description "Create or overwrite a file with the given content. Parent directories are created."
   :parameters {:type "object"
                :properties {:path {:type "string" :description "File path"}
                             :content {:type "string" :description "Full new file content"}}
                :required ["path" "content"]}
   :kind "edit"
   :title (fn [{:keys [path]}] (str "Write " path))
   :execute write-file})

;; ---------------------------------------------------------------------------
;; edit

(defn- count-occurrences [^String s ^String sub]
  (loop [from 0 n 0]
    (let [i (.indexOf s sub from)]
      (if (neg? i) n (recur (+ i (count sub)) (inc n))))))

(defn- edit-file [{:keys [cwd]} {:keys [path old_text new_text]}]
  (when (or (not (string? old_text)) (empty? old_text)) (fail "old_text must be a non-empty string"))
  (when-not (string? new_text) (fail "new_text is required"))
  (let [f (resolve-path cwd path)]
    (when-not (fs/regular-file? f) (fail (str "File not found: " f)))
    (let [text (slurp f)
          n    (count-occurrences text old_text)]
      (case n
        0 (fail (str "old_text not found in " f ". It must match the file exactly, including whitespace."))
        1 (let [i (str/index-of text old_text)]
            (spit f (str (subs text 0 i) new_text (subs text (+ i (count old_text)))))
            (str "Edited " f))
        (fail (str "old_text matches " n " times in " f ". Include more surrounding context so it matches exactly once."))))))

(def edit-tool
  {:name "edit"
   :description "Replace one exact occurrence of old_text with new_text in a file. old_text must match exactly once (whitespace included); read the file first."
   :parameters {:type "object"
                :properties {:path {:type "string" :description "File path"}
                             :old_text {:type "string" :description "Exact text to replace; must be unique in the file"}
                             :new_text {:type "string" :description "Replacement text"}}
                :required ["path" "old_text" "new_text"]}
   :kind "edit"
   :title (fn [{:keys [path]}] (str "Edit " path))
   :execute edit-file})

;; ---------------------------------------------------------------------------
;; bash

(defn- run-bash [{:keys [cwd cancel]} {:keys [command timeout]}]
  (when (str/blank? command) (fail "command is required"))
  (let [timeout-ms (* 1000 (or timeout 120))
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
        output     (cap-tail (str/trimr (deref out 5000 "")))]
    (case result
      :cancelled (fail (str/triml (str output "\n[cancelled]")))
      :timeout   (fail (str/triml (str output "\n[command timed out after " (/ timeout-ms 1000) "s]")))
      (let [code (:exit @proc)]
        (if (zero? code)
          (if (str/blank? output) "(no output)" output)
          (fail (str/triml (str output "\n[exit code " code "]"))))))))

(def bash-tool
  {:name "bash"
   :description "Run a bash command in the working directory. Returns combined stdout and stderr (the tail, if long). Non-zero exit is reported as an error."
   :parameters {:type "object"
                :properties {:command {:type "string" :description "Command to run"}
                             :timeout {:type "number" :description "Timeout in seconds (default 120)"}}
                :required ["command"]}
   :kind "execute"
   :title (fn [{:keys [command]}] command)
   :execute run-bash})

;; ---------------------------------------------------------------------------

(def default-tools [read-tool write-tool edit-tool bash-tool])

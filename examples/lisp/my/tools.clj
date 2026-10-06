(ns my.tools
  "Search tools as user code: ls, find and grep. Every public function with
  a docstring here is a tool once my.tools is in oml.agent/tool-namespaces
  (see examples/init.clj)."
  (:refer-clojure :exclude [find])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [oml.session :refer [cwd resolve-path]]))

(def max-lines
  "Cap on the lines ls, find and grep return."
  200)

(defn- capped [lines]
  (let [lines (map #(if (> (count %) 300) (str (subs % 0 300) "...") %) lines)
        more (- (count lines) max-lines)]
    (str (str/join "\n" (take max-lines lines))
         (when (pos? more) (str "\n[" more " more lines; narrow the search]")))))

(defn ls
  "List a directory. Subdirectories end with /."
  {:kind "read"
   :title (fn [{:keys [path]}] (str "List " (or path ".")))
   :params {:path [:string "Directory, default the working directory" :optional]}}
  [{:keys [path]}]
  (let [dir (resolve-path (or path "."))]
    (when-not (fs/directory? dir) (throw (ex-info (str "Not a directory: " dir) {})))
    (let [names (sort (for [f (fs/list-dir dir)] (str (fs/file-name f) (when (fs/directory? f) "/"))))]
      (if (empty? names) "(empty)" (capped names)))))

(defn find
  "Find files by glob pattern, e.g. **/*.clj (hidden files are skipped)."
  {:kind "search"
   :title (fn [{:keys [pattern]}] (str "Find " pattern))
   :params {:pattern [:string "Glob relative to path, e.g. **/*.clj"]
            :path [:string "Directory to search, default the working directory" :optional]}}
  [{:keys [pattern path]}]
  (let [dir (resolve-path (or path "."))
        ;; Java globs need a / for **, so **/*.clj alone would miss ./x.clj.
        patterns (cond-> [pattern] (str/starts-with? pattern "**/") (conj (subs pattern 3)))
        hits (sort (distinct (for [p patterns f (fs/glob dir p)] (str (fs/relativize dir f)))))]
    (if (empty? hits) "No files found." (capped hits))))

(defn grep
  "Search file contents for a regular expression. Returns file:line:text
  lines. Uses ripgrep when installed (respecting .gitignore), else grep -rn."
  {:kind "search"
   :title (fn [{:keys [pattern]}] (str "Grep " pattern))
   :params {:pattern [:string "Regular expression"]
            :path [:string "File or directory, default the working directory" :optional]
            :glob [:string "Only files whose name matches, e.g. *.clj" :optional]}}
  [{:keys [pattern path glob]}]
  (let [cmd (if (fs/which "rg")
              (cond-> ["rg" "-n" "--no-heading" "--color=never"] glob (into ["-g" glob]))
              (cond-> ["grep" "-rnE"] glob (conj (str "--include=" glob))))
        {:keys [exit out err]} @(p/process (into cmd ["-e" pattern "--" (or path ".")])
                                           {:dir (cwd) :in "" :out :string :err :string})]
    (case exit
      0 (capped (str/split-lines out))
      1 "No matches."
      (throw (ex-info (str "grep failed: " (str/trim err)) {})))))

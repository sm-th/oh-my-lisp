(ns build
  "Builds https://oml.sh: the markdown pages in site/pages and
  docs/CHAPTERS.md, rendered with nextjournal.markdown and hiccup into
  site/_site, plus site/assets and site/CNAME.

  Pages may contain directives, expanded before parsing:

    {{src-lines}}            total lines of src/**/*.clj
    {{lines src/oml/x.clj}}  lines of one file
    {{include path}}         a fenced code block with the file
    {{include path 3-17}}    ... with lines 3 to 17 only
    {{commands}}             table of the built-in slash commands, from src

  Every `oml.ns/name` mentioned in a page must resolve to a var in src, or
  the build fails."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nextjournal.markdown :as md]
            [org.httpkit.server :as hk]))

(def site-url "https://oml.sh")
(def repo-url "https://github.com/sm-th/oh-my-lisp")
(def out-dir "site/_site")

(def pages
  [{:slug "" :src "site/pages/index.md" :stamp ["oml.sh" "coding agent" "babashka"]
    :title "oml: a minimal coding agent you change while it runs"
    :headline ["A minimal coding agent " [:em "you change while it runs"]]
    :description "oml is a coding agent in about 1.5k lines of babashka. Every step is a function you redefine or advise, live over nREPL or /eval. ACP clients (Toad, Zed, Emacs) are the UI; any OpenAI-compatible endpoint is the model."}
   {:slug "getting-started" :src "site/pages/getting-started.md" :nav "start"
    :headline "Getting started"
    :description "Install oml with nix, point it at an OpenAI-compatible endpoint, and run it in Toad, Zed, Emacs agent-shell or print mode."}
   {:slug "customising" :src "site/pages/customising.md" :nav "customise"
    :headline "Customising"
    :description "One mechanism: named functions you redefine with defn, wrap with advise! or set with setq; tools and commands are functions in namespaces."}
   {:slug "primitives" :src "site/pages/primitives.md" :nav "primitives"
    :headline "Primitives"
    :description "What the core gives Lisp code: the current session and its transcript, say, complete, call-tool, request!, request-permission, and the extension points."}
   {:slug "recipes" :src "site/pages/recipes.md" :nav "recipes"
    :headline "Recipes"
    :description "Features as user code: project context, token usage, search tools, /model, a permission policy, and a sketch that replaces the loop."}
   {:slug "architecture" :src "site/pages/architecture.md" :nav "architecture"
    :headline "Architecture"
    :description "The namespaces of oml, how one turn flows from ACP to the model and back, what a session is, and the current limits."}
   {:slug "chapters" :src "docs/CHAPTERS.md" :nav "chapters"
    :headline "Chapters"
    :description "oml is built chapter by chapter; what is done and what is planned."}
   {:slug "404" :src "site/pages/404.md" :file "404.html"
    :headline "Not found"
    :description "This page does not exist."}])

;; ---------------------------------------------------------------------------
;; Directives

(defn- line-count [path] (count (str/split-lines (slurp path))))

(defn- src-lines []
  (reduce + (map (comp line-count str) (fs/glob "src" "**.clj"))))

(defn- include [path range]
  (let [lines (str/split-lines (slurp path))
        [a b] (if range (map parse-long (str/split range #"-")) [1 (count lines)])
        lang (case (fs/extension path) ("clj" "edn") "clojure" "nix" "nix" "yml" "yaml" "")]
    (str "```" lang "\n" (str/join "\n" (subvec lines (dec a) b)) "\n```")))

(defn- commands-table []
  (require 'oml.commands)
  (let [commands (requiring-resolve 'oml.agent/commands)
        summary (requiring-resolve 'oml.agent/summary)
        fn-name (requiring-resolve 'oml.agent/fn-name)]
    (str "| Command | Argument | What it does |\n|---|---|---|\n"
         (str/join "\n" (for [v (commands) :let [{:keys [doc hint]} (meta v)]]
                          (str "| `/" (fn-name v) "` | " (or hint "") " | " (summary doc) " |"))))))

(defn- expand [text]
  (str/replace text #"\{\{([a-z-]+)(?: ([^}]+))?\}\}"
               (fn [[_ directive arg]]
                 (let [[path range] (some-> arg (str/split #" "))]
                   (case directive
                     "src-lines" (str (src-lines))
                     "lines" (str (line-count path))
                     "include" (include path range)
                     "commands" (commands-table)
                     (throw (ex-info (str "Unknown directive {{" directive "}}") {})))))))

(defn- check-vars!
  "Fail when a page names an oml var that does not exist."
  [src text]
  (doseq [s (distinct (re-seq #"\boml\.[a-z-]+/[^\s`\"'()\[\]{},;]+" text))
          :when (not (str/starts-with? s "oml.sh/"))
          :let [sym (symbol (str/replace s #"[.:]$" ""))]]
    (when-not (try (requiring-resolve sym) (catch Exception _ nil))
      (throw (ex-info (str src ": no var " sym) {})))))

;; ---------------------------------------------------------------------------
;; Markdown

(defn- href
  "Links relative to a source file point at the file on GitHub."
  [src target]
  (if (re-find #"^(?:[a-z]+:|/|#)" target)
    target
    (let [[path anchor] (str/split target #"#" 2)
          file (str (fs/normalize (fs/path (or (fs/parent src) ".") path)))]
      (str repo-url (if (fs/directory? file) "/tree/main/" "/blob/main/") file
           (when anchor (str "#" anchor))))))

(defn- slug [text]
  (-> (str/lower-case text) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- renderers [src]
  (assoc md/default-hiccup-renderers
         :html-block (fn [_ node] (h/raw (md/node->text node)))
         :html-inline (fn [_ node] (h/raw (md/node->text node)))
         :heading (fn [ctx {:keys [heading-level] :as node}]
                    (md/into-hiccup [(keyword (str "h" heading-level)) {:id (slug (md/node->text node))}] ctx node))
         :link (fn [ctx node]
                 (md/into-hiccup [:a {:href (href src (get-in node [:attrs :href]))}] ctx node))))

(defn- without-title
  "Drop a leading `# Title` line; the layout sets the headline."
  [text]
  (str/replace-first text #"^# .*\n" ""))

(defn- render-markdown [src]
  (let [text (expand (without-title (slurp src)))]
    (check-vars! src text)
    (md/->hiccup (renderers src) text)))

;; ---------------------------------------------------------------------------
;; Layout

(defn- updated
  "Date of the last commit touching `src`; today when it has none yet."
  [src]
  (let [d (try (str/trim (:out (p/sh "git" "log" "-1" "--format=%cs" "--" src)))
               (catch Exception _ ""))]
    (if (str/blank? d) (str (java.time.LocalDate/now)) d)))

(defn- url [{:keys [slug]}] (str site-url "/" (when (seq slug) (str slug "/"))))

(defn- text-of [headline] (if (string? headline) headline (apply str (flatten (map #(if (vector? %) (last %) %) headline)))))

(defn- head [{:keys [headline title description] :as page}]
  (let [title (or title (str (text-of headline) " · oml"))
        image (str site-url "/assets/og.png")]
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title title]
     [:meta {:name "description" :content description}]
     [:link {:rel "canonical" :href (url page)}]
     [:meta {:property "og:type" :content "website"}]
     [:meta {:property "og:site_name" :content "oml"}]
     [:meta {:property "og:title" :content title}]
     [:meta {:property "og:description" :content description}]
     [:meta {:property "og:url" :content (url page)}]
     [:meta {:property "og:image" :content image}]
     [:meta {:property "og:image:width" :content "1200"}]
     [:meta {:property "og:image:height" :content "630"}]
     [:meta {:property "og:image:alt" :content "oml: a minimal coding agent you change while it runs"}]
     [:meta {:name "twitter:card" :content "summary_large_image"}]
     [:meta {:name "twitter:title" :content title}]
     [:meta {:name "twitter:description" :content description}]
     [:meta {:name "twitter:image" :content image}]
     [:meta {:name "color-scheme" :content "light dark"}]
     [:link {:rel "icon" :href "/assets/favicon.svg" :type "image/svg+xml"}]
     [:link {:rel "stylesheet" :href "/assets/tokens.css"}]
     [:link {:rel "stylesheet" :href "/assets/site.css"}]]))

(defn- masthead [current]
  [:header.mast
   [:a.name {:href "/"} [:img {:src "/assets/logo.svg" :alt "" :width 28 :height 28}] "oml"]
   [:nav
    (for [{:keys [slug nav]} pages :when nav]
      [:a (cond-> {:href (str "/" slug "/")} (= slug (:slug current)) (assoc :aria-current "page")) nav])
    [:a {:href repo-url} "github"]]])

(defn- page-html [{:keys [slug src stamp headline] :as page}]
  (str "<!doctype html>\n"
       (h/html
        [:html {:lang "en"}
         (head page)
         [:body {:class (if (= "" slug) "home" "doc")}
          (masthead page)
          [:main
           (into [:div.stamp] (map-indexed (fn [i s] [(if (zero? i) :b :span) s])
                                           (or stamp [(updated src) "docs"])))
           (into [:h1.hx] (concat (if (string? headline) [headline] headline)
                                  [[:i.k.blink {:aria-hidden "true"}]]))
           [:div.body (render-markdown src)]]
          [:footer
           [:span [:a {:href "/"} "oml.sh"]]
           [:span [:a {:href repo-url} "source"]]
           [:span "design " [:a {:href "https://github.com/sm-th/design"} "sm-th/design"]]]]])))

;; ---------------------------------------------------------------------------
;; Build and serve

(defn build!
  "Render every page into site/_site, with the assets and CNAME."
  []
  (fs/delete-tree out-dir)
  (fs/create-dirs out-dir)
  (fs/copy-tree "site/assets" (fs/path out-dir "assets"))
  (fs/copy "site/CNAME" (fs/path out-dir "CNAME"))
  (doseq [{:keys [slug file] :as page} pages
          :let [target (fs/path out-dir (or file (str (when (seq slug) (str slug "/")) "index.html")))]]
    (fs/create-dirs (fs/parent target))
    (spit (str target) (page-html page))
    (println "wrote" (str target)))
  (doseq [png ["og.png" "screenshot.png"]
          :when (not (fs/exists? (fs/path "site/assets" png)))]
    (println (str "note: site/assets/" png " is referenced but not there yet"))))

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "svg" "image/svg+xml" "png" "image/png"
   "woff2" "font/woff2" "otf" "font/otf" "txt" "text/plain; charset=utf-8"})

(defn serve!
  "Serve site/_site on http://localhost:`port` until interrupted."
  [port]
  (hk/run-server
   (fn [{:keys [uri]}]
     (let [f (fs/path out-dir (subs (java.net.URLDecoder/decode ^String uri "UTF-8") 1))
           f (if (fs/directory? f) (fs/path f "index.html") f)]
       (if (and (fs/regular-file? f) (not (str/includes? uri "..")))
         {:status 200 :headers {"content-type" (content-types (fs/extension f) "application/octet-stream")}
          :body (fs/file f)}
         {:status 404 :headers {"content-type" (content-types "html")}
          :body (fs/file out-dir "404.html")})))
   {:port port :ip "127.0.0.1"})
  (println (str "Serving " out-dir " on http://localhost:" port))
  @(promise))

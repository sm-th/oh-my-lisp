(ns oml.session
  "The current session: the context of everything the agent does.

  Sessions live in the `sessions` atom: id -> {:id :cwd :transcript
  :cancel :on-event ...}. :transcript is a vector of OpenAI-format messages
  without the system message; whatever user code appends or replaces is
  what the next model request sees. :cancel is the token of the running
  prompt (nil when idle). :on-event shows agent-loop events to the user;
  the frontend (ACP, print mode) provides it.

  `*session*` is the id of the current session. It is bound while a prompt
  runs (commands, /eval, tools and the loop included). Its root value is the
  most recently active session, so code evaluated over nREPL works on that
  one. Tools, commands and init code take no context argument: they call
  the functions here."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn log
  "Print `xs` to stderr (stdout may be the ACP stream)."
  [& xs]
  (binding [*out* *err*] (apply println "[oml]" xs)))

(def ^:dynamic *session*
  "Id of the current session; see the namespace doc. nil before any session."
  nil)

(defonce ^{:doc "Every session: id -> session map."} sessions (atom {}))

(defn activate!
  "Make session `id` the most recently active one (the root of *session*)."
  [id]
  (alter-var-root #'*session* (constantly id))
  id)

(defn create!
  "Create a session and make it the most recently active one. `opts` is
  {:cwd :on-event} plus whatever the frontend keeps there (e.g. :client).
  Returns its id."
  [{:keys [id] :as opts}]
  (let [id (or id (str "sess_" (random-uuid)))]
    (swap! sessions assoc id (merge {:on-event (fn [_])}
                                    (dissoc opts :id)
                                    {:id id :transcript [] :cancel nil}))
    (activate! id)))

(defmacro with-session
  "Run `body` with session `id` current (and most recently active)."
  [id & body]
  `(let [id# ~id]
     (activate! id#)
     (binding [*session* id#] ~@body)))

(defn session
  "The current session map (or the one with `id`), or nil."
  ([] (session *session*))
  ([id] (get @sessions id)))

(defn- current-id []
  (or (:id (session)) (throw (ex-info "No current session" {}))))

(defn cwd
  "The working directory of the current session (outside any session, the
  process directory)."
  []
  (or (:cwd (session)) (System/getProperty "user.dir")))

(defn resolve-path
  "Resolve `path` against the session cwd (absolute paths and ~ pass through)."
  [path]
  (when (str/blank? path) (throw (ex-info "path is required" {})))
  (let [path (if (str/starts-with? path "~") (str (fs/home) (subs path 1)) path)]
    (str (fs/normalize (fs/absolutize (if (fs/absolute? path) path (fs/path (cwd) path)))))))

(defn transcript
  "The messages of the current session, oldest first, without the system
  message."
  []
  (:transcript (session (current-id))))

(defn append-message!
  "Append `msgs` (e.g. {:role \"user\" :content \"...\"}) to the transcript
  of the current session, in one step; the next model request sees them.
  Returns the transcript."
  [& msgs]
  (doseq [m msgs]
    (when-not (and (map? m) (:role m))
      (throw (ex-info "A message is a map with :role" {:message m}))))
  (let [id (current-id)]
    (get-in (swap! sessions update-in [id :transcript] into msgs) [id :transcript])))

(defn set-transcript!
  "Replace the transcript of the current session with `messages` (e.g. a
  compacted one). Returns it."
  [messages]
  (let [id (current-id)]
    (get-in (swap! sessions assoc-in [id :transcript] (vec messages)) [id :transcript])))

(defn emit!
  "Send an agent-loop event (e.g. {:type :text-delta :text \"hi\"}) to the
  user of the current session. A no-op outside any session."
  [event]
  (when-let [f (:on-event (session))] (f event))
  nil)

(defn say
  "Show `texts` (concatenated) to the user of the current session as agent
  message text."
  [& texts]
  (emit! {:type :text-delta :text (apply str texts)}))

(defn on-session-start
  "Called when a session starts, with it current, after its project init
  file is loaded. Does nothing; redefine or advise! it. It runs on the ACP
  reader thread, so it cannot wait for the client (oml.acp/request!)."
  []
  nil)

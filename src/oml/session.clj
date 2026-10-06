(ns oml.session
  "The current session, for Lisp code.

  Sessions live in one place, the `sessions` atom: id -> session map
  {:id :cwd :transcript :cancel :on-event :client ...}. :transcript is an
  atom holding the OpenAI-format messages (without the system message); the
  agent loop appends to it in place, so whatever user code appends or
  replaces is what the next model request sees.

  `*session*` holds the id of the current session. It is bound while a
  prompt turn runs, while a slash command (/eval included) runs, and during
  tool calls and hooks. Its root value is the most recently active session
  (the last one created or prompted), so code evaluated over nREPL, or on a
  thread that has no binding, works on that one.

  The frontend (ACP, print mode) creates a session with an :on-event fn
  that shows agent-loop events to the user; `emit!` and `say` go through
  it.")

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
                                    {:id id :transcript (atom []) :cancel nil}))
    (activate! id)))

(defmacro with-session
  "Run `body` with session `id` current (and most recently active)."
  [id & body]
  `(let [id# ~id]
     (activate! id#)
     (binding [*session* id#] ~@body)))

(defn session
  "The current session map (or the one with `id`), or nil. It has :id,
  :cwd, :transcript (an atom; prefer `transcript`), :cancel (the token of
  the running prompt, or nil) and :on-event."
  ([] (session *session*))
  ([id] (get @sessions id)))

(defn- current! []
  (or (session) (throw (ex-info "No current session" {}))))

(defn transcript
  "The messages of the current session: OpenAI-format maps, oldest first,
  without the system message."
  []
  @(:transcript (current!)))

(defn append-message!
  "Append `msg` (e.g. {:role \"user\" :content \"...\"}) to the transcript of
  the current session; the next model request sees it. Returns the
  transcript."
  [msg]
  (when-not (and (map? msg) (:role msg))
    (throw (ex-info "A message is a map with :role" {:message msg})))
  (swap! (:transcript (current!)) conj msg))

(defn set-transcript!
  "Replace the transcript of the current session with `messages` (e.g. a
  compacted one). Returns it."
  [messages]
  (reset! (:transcript (current!)) (vec messages)))

(defn ctx
  "The tool/command context for the current session:
  {:cwd :cancel :on-event :session-id}. Outside any session, the process
  directory and no-op events."
  []
  (if-let [{:keys [id cwd cancel on-event]} (session)]
    {:cwd cwd :cancel cancel :on-event on-event :session-id id}
    {:cwd (System/getProperty "user.dir") :on-event (fn [_])}))

(defn emit!
  "Send an agent-loop event (e.g. {:type :text-delta :text \"hi\"}) to the
  user of the current session. A no-op outside any session."
  [event]
  (when-let [f (:on-event (session))] (f event))
  nil)

(defn say
  "Show `texts` (concatenated) to the user of the current session as agent
  message text, from a command, a hook, a tool or /eval."
  [& texts]
  (emit! {:type :text-delta :text (apply str texts)}))

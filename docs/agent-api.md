# Agent API

`oml.agent` drives one installed OMP process as an explicit session. It exposes
session and run lifecycle values while keeping OMP process and RPC mechanics in
`oml.agent.omp`.

OMP is the only supported harness. This API does not define a harness registry,
a plugin protocol, or a second scheduler.

## Session lifecycle

A `Session` owns one `omp --mode rpc` child process. `open` requires a working
directory and a native OMP session directory:

```clojure
(require '[oml.agent :as agent])

(def session
  (agent/open
   {:cwd "/path/to/workspace"
    :session-dir "/path/to/omp-sessions"
    :model "provider/model"
    :thinking :low
    :native-tools [:read :glob]}))

(agent/session? session)
;; => true

(agent/session-info session)
;; => {:session-id "..." :session-file "/path/to/omp-sessions/...jsonl"}
```

`:model` and `:thinking` are optional OMP launch arguments. oml does not select,
route, or fall back between models. `:command` can replace the default
`["omp"]` executable vector; it exists for packaging and deterministic protocol
tests.

`open` creates `:cwd` and `:session-dir` when necessary, launches OMP, waits for
its ready frame, negotiates RPC protocol 2, registers grant-backed eval when
configured, and reads the native session identity before returning.

Always close a session:

```clojure
(try
  ;; use session
  (finally
    (agent/close session)))
```

`close` first stops prompt admission. If a run is active, it asks OMP to abort
it and interrupts matching host-tool work. It then closes OMP stdin, continues
to drain stdout, and waits for the child to exit. A child that does not exit is
terminated and then forcibly terminated. A normal close returns:

```clojure
{:exit-code 0 :stderr ""}
```

A non-zero child exit is an error rather than a successful close.

## Native session resume

`session-info` exposes OMP's native stable session ID and session file. Save the
session file only according to the caller's own policy. Resume is an explicit
new child launch with OMP's `--resume` option:

```clojure
(def native-session-file
  (:session-file (agent/session-info session)))

(agent/close session)

(def resumed
  (agent/resume
   {:cwd "/path/to/workspace"
    :session-dir "/path/to/omp-sessions"
    :native-tools []}
   native-session-file))
```

oml does not copy, discover, retain, or prune OMP session files. It adds no
persistence policy of its own.

## Runs, send, and ask

`send` waits only until OMP accepts the prompt, then returns a `Run`. Acceptance
is not completion.

```clojure
(def run (agent/send session "Summarize the current workspace."))

(agent/run? run)
;; => true

(agent/result run)
;; => {:status :completed
;;     :text "..."
;;     :event {... terminal agent_end ...}
;;     :events [{...} ...]}
```

`result` blocks for OMP's terminal `agent_end`. It then obtains OMP's final
assistant text. `:status` is `:completed` or `:aborted`; `:event` is the
terminal event and `:events` contains every structured event observed for the
run.

`ask` combines `send` and `result`:

```clojure
(agent/ask session "State the next concrete action.")
;; => {:status :completed, :text "...", :event {...}, :events [...]}
```

Only one native run is active in a session. A prompt sent without an active-run
behavior while a run is active is rejected instead of being placed in an oml
queue.

## Events

`next-event` exposes the structured event maps emitted by OMP. With one
argument it blocks while the run is live. A timeout in milliseconds makes it
return `nil` if no event arrives in time:

```clojure
(loop []
  (when-let [event (agent/next-event run 1000)]
    (println (:type event))
    (recur)))
```

After completion, queued events can still be drained; `nil` means the queue is
empty at that observation point. `observed-events` returns a non-blocking
snapshot of all events seen so far.

The events are OMP event data, not an oml-normalized event protocol. Consumers
must handle event types they do not recognize.

## Native active-run queueing

oml delegates active-run messages to OMP. It does not implement a queue,
worker pool, retry loop, or ordering policy.

```clojure
(def run (agent/send session "Begin the requested analysis."))

(def same-run
  (agent/send session
              "After that, give the final conclusion."
              {:active :follow-up}))

(= run same-run)
;; => true

(agent/result same-run)
```

`:active :follow-up` sends OMP's `streamingBehavior: "followUp"`.
`:active :steer` sends `streamingBehavior: "steer"`. Both remain part of the
current native `Run`, and OMP owns their ordering and queue modes. The run ends
at OMP's terminal `agent_end`; oml does not promise an independent completion
result for each queued message.

Providing `:active` without a live run is an error.

## Cancellation

`cancel` sends OMP's native `abort` command for the run:

```clojure
(agent/cancel run)
;; => {:accepted true}

(agent/result run)
;; => {:status :aborted, ...}
```

The acknowledgement means OMP accepted the abort command. Completion still
comes from the run's terminal event. OMP can emit `host_tool_cancel`; oml uses
it to interrupt the matching JVM future.

Cancellation is cooperative. A caller-supplied evaluation that does not
respond to interruption may continue running. oml does not claim forced
cancellation of arbitrary JVM code.

## Native tool allowlist

`:native-tools` is fixed when the session opens:

```clojure
(agent/open
 {:cwd "/path/to/workspace"
  :session-dir "/path/to/omp-sessions"
  :native-tools [:read :write :edit :glob]})
```

A non-empty collection becomes one OMP `--tools` allowlist with exactly those
names. An empty or omitted collection launches with `--no-tools`. File tools
can therefore be enabled without enabling `bash`.

There is no runtime native-tool replacement API. Open a new session to change
the allowlist.

Native OMP tools execute with the authority of the OMP process and its host
environment. They are not confined by an oml grant. In particular, enabling
`bash` grants native shell execution.

## Grant-backed Clojure eval

There is no ambient full Clojure eval tool. Supplying a context from
`oml.grant/build` registers one OMP host tool named `clojure_eval`:

```clojure
(require '[oml.grant :as grant])

(def counter (atom 0))

(def restricted
  (grant/build
   {:vocab {'increment #(swap! counter inc)}
    :docs {'increment "Increment the granted counter."}
    :context {:purpose :example}}))

(def session
  (agent/open
   {:cwd "/path/to/workspace"
    :session-dir "/path/to/omp-sessions"
    :native-tools []
    :grant restricted}))
```

When OMP calls `clojure_eval`, oml evaluates the supplied source with
`oml.grant/eval-string` in that same live grant context. The printed value is
returned to OMP. Evaluation failures become host-tool errors.

The grant controls only `clojure_eval`. It does not narrow separately enabled
native OMP tools.

## Error model

Invalid arguments throw `IllegalArgumentException`. Runtime and protocol
failures throw `ExceptionInfo`. Its `ex-data` includes
`:component :oml.agent.omp` and a `:kind` callers can branch on.

Important kinds include:

- `:launch` for child-process startup failure;
- `:timeout` for a ready frame or command response timeout;
- `:unsupported-protocol` when OMP does not advertise RPC protocol 2;
- `:frame-too-large` for a physical or reassembled frame over its negotiated
  bound;
- `:protocol` for malformed JSON, broken chunk sequences, or invalid framing;
- `:command-failed` for an OMP failure response, preserving `:request`,
  `:response`, and OMP's optional `:code`;
- `:unexpected-exit` for a child that exits before `close`, preserving
  `:exit-code` and captured `:stderr`;
- `:exit` for a non-zero exit during `close`;
- `:active-run`, `:no-active-run`, and `:inactive-run` for lifecycle misuse;
- `:closed` when the session no longer accepts work.

Callers can handle the structured data without matching message text:

```clojure
(try
  (agent/result run)
  (catch clojure.lang.ExceptionInfo error
    (case (:kind (ex-data error))
      :unexpected-exit
      {:exit-code (:exit-code (ex-data error))
       :stderr (:stderr (ex-data error))}

      :command-failed
      {:code (:code (ex-data error))
       :response (:response (ex-data error))}

      (throw error))))
```

## Proven boundaries

The production API deliberately stops at the behavior proven against OMP:

- OMP is the sole harness.
- OMP owns sessions, active-run queueing, cancellation, events, and terminal
  results.
- Native tool selection is launch-time only.
- Grant-backed eval is opt-in; native tools retain host authority.
- Resume requires an explicit native OMP session file.
- Cancellation of arbitrary JVM evaluation is not guaranteed.
- No worktree management, sandbox, process confinement, persistence policy,
  model routing, application integration, or private scenario is included.
- CI exercises a deterministic fake OMP/RPC child. An installed-OMP smoke
  boundary is optional through `OML_OMP_SMOKE=1`, so CI requires no external
  model credentials.

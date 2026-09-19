(ns oml.agent
  "Sessions and runs for driving an OMP agent.

  A session owns one OMP child. A run is the native active run: prompts sent
  with `:active :follow-up` or `:active :steer` are queued by OMP and remain
  part of that run. Native tools are fixed when the session opens. Supplying a
  grant adds one host tool, `clojure_eval`, evaluated only in that grant."
  (:refer-clojure :exclude [send])
  (:require [oml.agent.omp :as omp]))

(defrecord Session [implementation])
(defrecord Run [implementation])

(declare result)

(defn session? [value]
  (instance? Session value))

(defn run? [value]
  (instance? Run value))

(defn open
  "Open an OMP session.

  Required options are `:cwd` and `:session-dir`. `:native-tools` is a
  collection of OMP native tool names and defaults to none. `:model` and
  `:thinking` are passed to OMP when present. `:grant` may be a context from
  `oml.grant/build`; without it no live eval tool is registered.

  `:command` may replace the default `[\"omp\"]` executable vector. It is
  primarily useful for packaging and deterministic protocol tests."
  [options]
  (->Session (omp/open options)))

(defn resume
  "Open a new child on the native OMP session identified by `session-file`.
  All other options have the same meaning as `open`."
  [options session-file]
  (when-not (and (string? session-file) (seq session-file))
    (throw (IllegalArgumentException. "session-file must be a non-empty string")))
  (open (assoc options :resume session-file)))

(defn session-info
  "Return OMP's stable `:session-id` and resumable `:session-file`."
  [^Session session]
  (omp/session-info (:implementation session)))

(defn send
  "Send a prompt and return its Run after OMP accepts it.

  While a run is active, set `:active` to `:follow-up` or `:steer`. OMP owns
  ordering and queue behavior; oml does not maintain a second queue. Such a
  send returns the current native Run. Sending without `:active` while a run
  is active is rejected."
  ([session message]
   (send session message {}))
  ([^Session session message options]
   (->Run (omp/send (:implementation session) message options))))

(defn ask
  "Send a prompt and block until its native run ends. Returns the same result
  map as `result`."
  ([session message]
   (ask session message {}))
  ([session message options]
   (result (send session message options))))

(defn next-event
  "Observe the next structured OMP event for `run`.

  With no timeout, blocks while the run is live. With `timeout-ms`, returns nil
  when no event arrives before the timeout. It also returns nil once the run is
  complete and its event queue has been drained."
  ([^Run run]
   (omp/next-event (:implementation run)))
  ([^Run run timeout-ms]
   (omp/next-event (:implementation run) timeout-ms)))

(defn observed-events
  "Return a snapshot of all structured events observed for `run`."
  [^Run run]
  (omp/observed-events (:implementation run)))

(defn result
  "Block for a terminal run result.

  The result contains `:status` (`:completed` or `:aborted`), final assistant
  `:text`, the terminal `:event`, and all `:events`. Protocol failures and
  unexpected child exits throw ExceptionInfo with structured ex-data."
  [^Run run]
  (omp/result (:implementation run)))

(defn cancel
  "Ask OMP to abort the active native run. Returns OMP's acknowledgement.
  OMP cancellation is cooperative; a caller-supplied eval may not stop."
  [^Run run]
  (omp/cancel (:implementation run)))

(defn close
  "Stop admission, abort an active run, close OMP stdin, and await child exit.
  Returns `{:exit-code n :stderr s}`. A non-zero exit throws ExceptionInfo."
  [^Session session]
  (omp/close (:implementation session)))

---
layout: docs.njk
title: "ACP connection — oh-my-lisp"
description: "Connecting to a local ACP agent through the official Java SDK."
---
# ACP connection

oml connects to a local coding agent through the [Agent Client Protocol](https://agentclientprotocol.com/)
(ACP) over stdio. Issue #35 implements the first slice of that lifecycle: launch a
configured agent command, complete the `initialize` handshake, expose the negotiated
capabilities, and close the connection safely.

## Public interface

The `oml.acp` namespace is a thin wrapper over the official ACP Java SDK
(`com.agentclientprotocol:acp-core:0.16.0`). It exposes seven functions:

- `(connect command args)` — launch a local agent and negotiate ACP v1.
- `(capabilities conn)` — return the negotiated capabilities, including `:protocol-version`.
- `(new-session conn cwd)` — create a session and return `{:session-id "..."}`.
- `(prompt conn session-id text)` — send a prompt and return `{:stop-reason ... :updates [...]}`.
- `(cancel conn session-id)` — cancel an in-flight turn via `session/cancel`.
- `(close-session conn session-id)` — close a session via `session/close` when supported.
- `(close conn)` — close the connection idempotently with a bounded wait.

`connect` accepts an executable `command`, a sequence of string `args`, and an optional
environment map. It returns a connection value on success. On failure it throws `ex-info`
tagged with `:oml/error`:

- `:acp/connection` — process or transport failure.
- `:acp/protocol` — protocol-version mismatch or JSON-RPC protocol error.
- `:acp/capability` — capability negotiation failure.
- `:acp/unknown` — other SDK failure.

## Sessions

`(new-session conn cwd)` creates an ACP session on an initialized connection and returns an immutable map `{:session-id "<id>"}`. `cwd` is an absolute-path string naming the agent's working directory. Failures raise `ex-info` tagged with `:oml/error` using the same categories above, with the original SDK `Throwable` attached as the exception cause.

## Prompts

`(prompt conn session-id text)` sends a text prompt on an existing session and completes the turn. It returns an immutable map `{:stop-reason <keyword> :updates [<update-map> ...]}`:

- `:stop-reason` is the agent's final stop reason as a keyword, for example `:end-turn`, `:max-tokens`, or `:refusal`.
- `:updates` is the ordered vector of `session/update` events received during the turn. Each is a map with `:type` (for example `:agent-message-chunk`) and, for text-bearing chunks, a `:text` string.

Failures raise `ex-info` tagged with `:oml/error` using the categories above, with the original SDK `Throwable` attached as the exception cause.

Requests use a generous per-request timeout (default 120 seconds), because a real turn runs model inference and tool calls. Pass `:request-timeout-ms` in the `connect` opts map to change it for long turns.

## Permissions

When the agent asks to perform an action it sends `session/request_permission`. Pass `:on-permission` in the optional `opts` map to `(connect command args env opts)` (env may be `nil`) to decide the outcome:

`(connect command args env {:on-permission (fn [req] ...)})`

The callback receives `{:session-id <string> :options [{:option-id <string> :name <string> :kind <keyword>} ...]}` and returns the decision: an option-id string, or `:allow`, `:reject`, or `:cancel`. `:allow` and `:reject` select the first option of that kind. When no `:on-permission` is configured, or the callback throws, the request is rejected safely.

## Cancellation

`(cancel conn session-id)` sends a fire-and-forget `session/cancel` notification and returns `nil`. Because `prompt` blocks the calling thread, run it on another thread (for example a `future`) and call `cancel` from elsewhere; the in-flight `prompt` then returns `{:stop-reason :cancelled :updates [...]}` once the agent ends the turn.

## Closing a session

`(close-session conn session-id)` closes one ACP session with `session/close` when the agent advertised the capability (`:close-session` in `capabilities`). It returns `nil`, or raises `ex-info` tagged `{:oml/error :acp/capability}` when the agent does not support close. This is distinct from `(close conn)`, which shuts down the whole connection.

## What the SDK owns

The wrapper intentionally does not re-implement ACP mechanics. The official SDK is
responsible for:

- UTF-8, newline-delimited JSON-RPC framing.
- Request/response correlation and request IDs.
- Stdio transport and agent subprocess protocol handling.
- Subprocess reaping, including bounded TERM/KILL shutdown.
- The `initialize` request/response exchange.

## What this stage excludes

The connection lifecycle currently implements connection, initialization, session creation, single-turn prompting, permission handling, turn cancellation, and capability-gated session close. It does not yet implement:

- Rich modeling of non-text `session/update` kinds (tool calls, plans) and non-text prompt content.
- Grant or authorization design beyond the permission callback.
- MCP injection, Lisp Eval exposure, or grant authorization.
- Agent discovery, catalog generation, or startup integration.
- Remote transports, providers, authentication, or session persistence.

See [ACP client architecture](/docs/acp-architecture/) for the full design and staged
delivery plan.

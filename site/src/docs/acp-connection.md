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
(`com.agentclientprotocol:acp-core:0.16.0`). It exposes four functions:

- `(connect command args)` — launch a local agent and negotiate ACP v1.
- `(capabilities conn)` — return the negotiated capabilities, including `:protocol-version`.
- `(new-session conn cwd)` — create a session and return `{:session-id "..."}`.
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

## What the SDK owns

The wrapper intentionally does not re-implement ACP mechanics. The official SDK is
responsible for:

- UTF-8, newline-delimited JSON-RPC framing.
- Request/response correlation and request IDs.
- Stdio transport and agent subprocess protocol handling.
- Subprocess reaping, including bounded TERM/KILL shutdown.
- The `initialize` request/response exchange.

## What this stage excludes

The connection lifecycle currently implements connection, initialization, and session creation. It does not yet implement:

- `session/prompt`, `session/update`, or the turn lifecycle.
- Permissions (`session/request_permission`), callbacks, or grants.
- Turn cancellation (`session/cancel`).
- Capability-gated `session/close`.
- MCP injection, Lisp Eval exposure, or grant authorization.
- Agent discovery, catalog generation, or startup integration.
- Remote transports, providers, authentication, or session persistence.

See [ACP client architecture](/docs/acp-architecture/) for the full design and staged
delivery plan.

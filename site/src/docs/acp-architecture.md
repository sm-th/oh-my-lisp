---
layout: docs.njk
title: "ACP client architecture — oh-my-lisp"
description: "Accepted design for the oh-my-lisp ACP v1 client."
navTitle: "ACP design"
order: 2
---
# ACP client architecture

<p class="status">
<strong>Status:</strong> Accepted design record. Stage 1a (local stdio connection
and v1 initialization) is implemented. Later Stage 1 children (sessions, prompt
turns, permissions, cancellation, close) are planned and tracked in GitHub issues.
</p>

## Decision

**oml** is a programmable Lisp [Agent Client Protocol](https://agentclientprotocol.com/)
(ACP) client and glue layer. It does not embed or recreate a coding-agent loop and it is
not coupled to a specific harness.

There are two distinct loops:

1. **Lisp application loop:** decides when work is needed, selects configured behavior,
   sends an ACP prompt, consumes updates and the final outcome, and continues the
   application.
2. **Coding-agent loop:** an external full coding agent owns model interaction, context
   management, tool selection and iteration, plus its native file, shell, git,
   pull-request, and skills capabilities.

ACP is the boundary between the loops. The Lisp loop invokes a turn; the agent decides
how to perform that turn.

## Responsibility split

### oml owns

- Lisp application behavior and user-facing integration.
- Explicit Lisp configuration and selection of a local agent command.
- Local subprocess lifetime and ACP v1 JSON-RPC over stdio, **carried by the official
  Apache-2.0 ACP Java SDK** (`com.agentclientprotocol:acp-core:0.16.0`). oml is a thin
  Clojure-facing wrapper over the SDK's synchronous stdio client and transport; the SDK
  owns JSON framing, UTF-8 handling, request/response correlation, stderr handling, and
  subprocess reaping, and oml verifies those behaviors rather than re-implementing them.
- Protocol-version and capability negotiation through the SDK's `initialize` handshake,
  exposed to later children as Clojure-facing values.
- Session creation, prompt submission, update delivery, cancellation, final stop-reason
  handling, and predictable shutdown.
- An instance-configured Lisp permission handler.

### The external agent and deployment own

- Installation and updates.
- Provider, model, authentication, credentials, and native product configuration.
- The model/tool loop and native files, shell, git, pull-request, skills, sandbox, and
  product permission behavior.

oml does not claim that ACP itself supplies those coding capabilities; it connects to an
agent that does.

## ACP v1 lifecycle

The first protocol implementation is local only:

1. Launch one configured agent process.
2. Exchange newline-delimited JSON-RPC over stdin/stdout, carried by the SDK's stdio
   transport rather than hand-written in oml.
3. Call `initialize` and retain the negotiated version and capabilities.
4. Create a session with `session/new`.
5. Keep `session/prompt` pending while delivering `session/update` notifications.
6. Complete the turn from the prompt response's stop reason.
7. Use `session/cancel` for turn cancellation and resolve pending permission requests as
   cancelled.
8. Call `session/close` only when advertised, then close stdin and reap the process.
9. Surface protocol errors, subprocess exit, and shutdown results predictably.

No client filesystem or terminal callbacks are advertised in v1. The selected coding
agent uses its own tools.

## Current scope: Stage 1a

Issue #35 delivers the first reviewed slice:

- `oml.acp/connect` launches a configured command, creates the SDK stdio transport and
  sync client, and completes the `initialize` handshake.
- `oml.acp/capabilities` returns the negotiated capabilities as a Clojure map, including
  `:protocol-version`.
- `oml.acp/close` closes the connection idempotently and with a bounded wait.
- SDK failures are translated into stable `ex-info` categories under `:oml/error`:
  `:acp/connection`, `:acp/protocol`, `:acp/capability`, and `:acp/unknown`.

This stage deliberately does **not** implement sessions, prompts, events, permissions,
cancellation, MCP, Lisp Eval, grants, discovery, remote transports, or provider/auth
configuration.

## Configuration, absence, and discovery

Configuration is executable Lisp, never slash commands.

A missing configured default agent is non-fatal: agent-independent Lisp use still starts.
Interactive startup may offer to configure a discovered agent through explicit Lisp;
headless use returns an actionable result without prompting.

Discovery uses a maintained in-code catalog of known ACP agents and known command names.
It checks `PATH` only for those commands and proposes reviewable Lisp configuration. It
does not scan arbitrary executables, install or download software, query a network
registry, or rewrite existing Lisp configuration.

## Permissions

ACP permission requests are routed to an instance-configured Lisp handler. An interactive
REPL may ask the user. Noninteractive use with no handler safely rejects. This callback
is a protocol policy hook, not a claim that every agent action is mediated by ACP.

## Deferred work

MCP injection, Lisp Eval exposure, and grant/authorization design are explicitly
deferred. They are not prerequisites for the local ACP lifecycle or catalog/startup
stages, and no implementation issue is open for them now.

## Exclusions

- OMP-specific control or adapter behavior.
- Custom or remote transports.
- Client filesystem or terminal callbacks.
- Agent installation, provider/authentication setup, or native configuration.
- Session persistence policy.
- Natural-language REPL routing or automatic agent selection.
- MCP, Lisp Eval, and grant authorization.

## Staged delivery

- **Stage 0** — establish and merge the documentation-site workflow and publish this
  design. (Merged.)
- **Stage 1** — local ACP v1 client lifecycle.
  - **1a** — connect and initialize v1 (#35, implemented).
  - **1b** — `session/new` and session lifecycle.
  - **1c** — `session/prompt`, updates, and stop reasons.
  - **1d** — permission handling.
  - **1e** — cancellation and graceful close.
- **Stage 2** — maintained catalog, Lisp configuration generation, and startup behavior;
  depends on Stage 1.

Every implementation change ships its relevant English documentation in the same pull
request, publishes it through the project site and navigation, and passes the site build
in GitHub Actions.

## Official ACP sources

- [ACP v1 overview](https://agentclientprotocol.com/protocol/v1/overview)
- [Architecture](https://agentclientprotocol.com/get-started/architecture)
- [Stdio transport and process ownership](https://agentclientprotocol.com/protocol/v1/transports)
- [Initialization and capabilities](https://agentclientprotocol.com/protocol/v1/initialization)
- [Session setup and close](https://agentclientprotocol.com/protocol/v1/session-setup)
- [Prompt turns, updates, stop reasons, and cancellation](https://agentclientprotocol.com/protocol/v1/prompt-turn)
- [Tool calls and permission requests](https://agentclientprotocol.com/protocol/v1/tool-calls#requesting-permission)
- [ACP v1 schema](https://agentclientprotocol.com/protocol/v1/schema)
- [Official ACP agent registry](https://agentclientprotocol.com/get-started/agents)

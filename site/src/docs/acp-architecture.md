---
layout: docs.njk
title: "ACP client architecture — oh-my-lisp"
description: "Accepted design for the oh-my-lisp ACP v1 client."
---
# ACP client architecture

<p class="status">
<strong>Status:</strong> Accepted design record. This page describes a planned
future direction, not shipped behavior. Implementation is staged in later issues.
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
- Local subprocess lifetime and ACP v1 JSON-RPC over stdio.
- Protocol-version and capability negotiation.
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
2. Exchange newline-delimited JSON-RPC over stdin/stdout.
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
  design. No coding pull request starts before Stage 0 is reviewed and merged.
- **Stage 1** — local ACP v1 client lifecycle.
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

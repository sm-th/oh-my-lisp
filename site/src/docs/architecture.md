---
layout: docs.njk
title: "Architecture"
description: "The accepted architecture of oml: a programmable Lisp image and ACP client."
navTitle: "Architecture"
order: 1
---
# Architecture

This document records oml's **accepted** architecture — the design the project is
building to, including decisions already shipped and decisions accepted but not yet
implemented. The long-term aspiration is on the [home page](/); the record of
what has actually shipped is in the [Changelog](/docs/changelog/). The authoritative
design record is issue [#30](https://github.com/sm-th/oh-my-lisp/issues/30).

<p class="status">
<strong>Status:</strong> v0.1.0 ships the local ACP v1 client. The persistent image,
the grant, the natural-language front, and agent discovery are accepted directions at
earlier stages of delivery, marked inline below.
</p>

## Shape: image, drivers, and agents

- **The image (body).** oml is a persistent Lisp image: its objects, state, and skills
  are meant to survive restarts. Everything that acts on the image does so through a
  single **eval surface** — one way to affect the image.
- **Drivers (minds).** A driver acts *through* the eval surface: a human at the REPL, a
  headless script, or an attached AI. The image persists and grows; drivers are
  swappable.
- **External agents.** oml also connects to **external coding agents** over the
  [Agent Client Protocol](https://agentclientprotocol.com/) (ACP). Such an agent is a
  separate process that runs its own model and tool loop; oml does not embed or recreate
  it.

## Two loops

oml is a glue layer between two distinct loops:

1. **Lisp application loop** — decides when work is needed, selects configured behavior,
   sends an ACP prompt, consumes the streamed updates and the final outcome, and
   continues the application.
2. **Coding-agent loop** — an external agent owns model interaction, context management,
   tool selection and iteration, and its native file, shell, git, pull-request, and
   skills capabilities.

**ACP is the boundary between the loops.** The Lisp loop invokes a turn; the agent
decides how to perform it. oml is not coupled to any specific agent product.

## Responsibility split

**oml owns:** Lisp application behavior and user-facing integration; explicit Lisp
configuration and selection of a local agent command; local subprocess lifetime and
ACP v1 JSON-RPC over stdio (carried by the official Apache-2.0 ACP Java SDK); protocol
and capability negotiation; session creation, prompt submission, update delivery,
cancellation, final stop-reason handling, and predictable shutdown; an
instance-configured Lisp permission handler.

**The external agent and deployment own:** installation and updates; provider, model,
authentication, credentials, and native product configuration; the model and tool loop
and the agent's native file, shell, git, pull-request, skills, sandbox, and permission
behavior.

oml does not claim that ACP itself supplies coding capabilities; it connects to an agent
that does.

## Core components

### Interpreter

A Lisp of the Clojure family. v0.1.0 runs on a full JVM via Clojure. A lightweight
babashka/SCI runtime is an accepted future possibility, not a current commitment.

### ACP client — shipped (v0.1.0)

`oml.acp` is a thin Clojure wrapper over the official
`com.agentclientprotocol:acp-core` SDK: connect and `initialize`, capability
negotiation, `session/new`, `session/prompt` with streamed `session/update` events and a
final stop reason, `session/request_permission` routed to a Lisp callback,
`session/cancel`, and capability-gated `session/close`. The SDK owns JSON-RPC framing,
request correlation, and subprocess lifecycle. See
[ACP connection](/docs/acp-connection/) and
[ACP client architecture](/docs/acp-architecture/).

### Persistent image — accepted; partial

Durable state that survives restarts: objects and their behavior saved and restored, so
a launch resumes where the previous one left off. The image *is* the memory.

### Grant — accepted; not yet implemented

A deny-by-default eval surface: a driver sees exactly the vocabulary it was granted — a
bounded set of verbs, never ambient authority. Two authorities coexist in one image: the
**owner**, with full reach, and an **attached driver**, confined to its granted
vocabulary. The grant governs the **eval surface** inside the image; it is distinct from
ACP permissions, which govern what an *external* agent may do during a turn. Grant and
authorization design is deferred (#30).

### Natural-language front — accepted; not yet implemented

The intended default way to drive oml is natural language: a request interpreted by the
attached mind, which acts in the image through the grant. Today the built-in REPL
evaluates Lisp entered directly; natural-language routing is not yet implemented.

### Skills — accepted

Successful workflows persisted as named, reusable capabilities. An image accumulates
skills over its lifetime, so the vocabulary the owner and drivers can invoke grows with
use.

### Extension seam

Capabilities are added to an image by import, declared in the image's configuration. The
core provides the seam; what is imported — tools, verbs, integrations, connected agents —
is the owner's choice.

## Configuration

Configuration is executable Lisp, never slash commands. An image reads its configuration
(identity, where it persists, its default grant, the capabilities and agents it imports)
and is specialized by running the binary against it. A missing configured default agent
is non-fatal: agent-independent Lisp use still starts.

## Staged delivery

- **Stage 0 (#27)** — documentation-site workflow and published design. Merged.
- **Stage 1 (#28)** — local ACP v1 client lifecycle. Merged; shipped in v0.1.0.
- **Stage 2 (#29)** — maintained agent catalog, Lisp configuration generation, and
  startup integration. Accepted; not started.

## Runtime notes

- The current build is JVM Clojure on a Nix foundation.
- The object model is plain Clojure — functions and maps — so behavior can remain
  portable if a second runtime is added later.

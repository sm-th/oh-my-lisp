---
layout: docs.njk
title: "Architecture"
description: "The accepted architecture of oml: a programmable Lisp image and ACP client."
navTitle: "Architecture"
order: 1
---
# Architecture

This document is the architectural view of oml: how a minimal kernel and the layers
built on it fit together. It is **accepted** architecture — decisions already shipped
and decisions accepted but not yet implemented, marked inline below. The kernel itself
is specified in full on the [Kernel](/docs/kernel/) page; its design record is issue
[#55](https://github.com/sm-th/oh-my-lisp/issues/55). The ACP client layer follows its
own design record, issue [#30](https://github.com/sm-th/oh-my-lisp/issues/30). The
[Changelog](/docs/changelog/) records what has actually shipped.

<p class="status">
<strong>Status:</strong> the kernel and the ACP client layer's v0.1.0 stage are shipped.
The grant, a natural-language client, and agent discovery are accepted directions at
earlier stages of delivery, marked inline below.
</p>

## Kernel and layers

oml is a **kernel** plus **layers**. The kernel is the minimal living-image substrate:
one shared, mutable eval surface, explicit-file persistence, configuration-as-program,
and a recovery REPL that survives a broken configuration. Everything else — reaching an
external agent, bounding the eval surface for a less-trusted driver, natural-language
routing, orchestration — is a layer: Lisp loaded onto the kernel through the same eval
surface everything else uses, never a kernel primitive itself.

The deletion test from #55 draws the line: removing any one layer — the ACP client, a
grant, a client — leaves the eval surface, persistence, and recovery intact. An
abstract seam for a capability is added only once a second concrete implementation of
it exists; a single adapter (ACP) does not justify one yet.

## The eval surface

Every actor that affects the image — a human at the REPL, loaded configuration, or an
external input routed in by a layer — does so the same way: evaluating Lisp forms in
the shared runtime namespace. There is no second privileged path in. The kernel's eval
surface is full-trust; a grant layer narrows it for a less-trusted driver without
changing the surface itself. See [Kernel](/docs/kernel/) for the exact input
classification and console examples.

## Layers on the kernel

### ACP client — bundled layer; shipped (v0.1.0)

`oml.acp` connects the image to an **external coding agent** over the
[Agent Client Protocol](https://agentclientprotocol.com/) (ACP): a separate process
that runs its own model and tool loop, which oml does not embed or recreate. It is a
thin Clojure wrapper over the official `com.agentclientprotocol:acp-core` SDK: connect
and `initialize`, capability negotiation, `session/new`, `session/prompt` with streamed
`session/update` events and a final stop reason, `session/request_permission` routed to
a Lisp callback, `session/cancel`, and capability-gated `session/close`. The SDK owns
JSON-RPC framing, request correlation, and subprocess lifecycle.

The client sits at the boundary of oml's **two-loop model**: a Lisp application loop
decides when work is needed, selects configured behavior, sends an ACP prompt, and
consumes the streamed updates and final outcome; a coding-agent loop — owned entirely
by the external agent — handles model interaction, context management, tool selection
and iteration, and its native file, shell, git, pull-request, and skills capabilities.
ACP is the boundary between the loops; oml is not coupled to any specific agent
product. See [ACP connection](/docs/acp-connection/) and
[ACP client architecture](/docs/acp-architecture/).

### Grant — accepted layer; not yet implemented

A deny-by-default eval surface for a less-trusted driver: it sees exactly the
vocabulary it was granted — a bounded set of verbs, never ambient authority. Two
authorities coexist in one image: the owner, with full reach through the kernel eval
surface, and an attached driver, confined to its granted vocabulary. A grant is
distinct from ACP permissions, which govern what an *external* agent may do during a
turn; grant and authorization design is deferred (#30).

### Natural-language client — accepted layer; not yet implemented

The intended default way to drive oml is natural language: a request interpreted by
the attached driver, which acts in the image through a grant. Today the built-in REPL
evaluates direct Lisp only — a line starting with `(` or `/eval` — and reports
anything else as un-evaluated text; natural-language routing is a client layer that
does not exist yet.

### Skills — accepted

A successful workflow saved through the kernel's explicit-file persistence becomes a
named, reusable capability. Skills are not a separate kernel primitive: they are what
persistence looks like once an owner or an attached driver starts naming what it
saves. An image accumulates skills over its lifetime, so the vocabulary invokable
through it grows with use.

### Extension seam

Layers are added to an image by import, declared in the image's configuration. The
kernel provides only the seam the configuration runs through; what is imported —
tools, verbs, integrations, connected agents — is the owner's choice.

## Configuration

Configuration is executable Lisp, never slash commands: the same
configuration-as-program mechanism the kernel uses to build a ready image at startup
(see [Kernel](/docs/kernel/)). A configuration can declare identity, where the image
persists, a default grant, and the layers and agents it imports. A missing configured
default agent is non-fatal: agent-independent Lisp use still starts.

## Staged delivery

The kernel — eval surface, explicit-file persistence, recovery boot,
configuration-as-program — is fully delivered; see [Kernel](/docs/kernel/) and its
design record (#55). The ACP client layer ships in stages:

- **Stage 0 (#27)** — documentation-site workflow and published design. Merged.
- **Stage 1 (#28)** — local ACP v1 client lifecycle. Merged; shipped in v0.1.0.
- **Stage 2 (#29)** — maintained agent catalog, Lisp configuration generation, and
  startup integration. Accepted; not started.

## Runtime notes

- The current build is JVM Clojure on a Nix foundation.
- The object model is plain Clojure — functions and maps — so behavior can remain
  portable if a second runtime is added later.

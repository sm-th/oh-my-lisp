---
layout: docs.njk
title: "Architecture — oh-my-lisp"
description: "The current JVM foundation and intended core shape of oml."
---
# Architecture

oml is a programmable Lisp runtime. This document describes the current foundation
and the intended core shape of the project.

<p class="status">
<strong>Status:</strong> The first implementation stage uses JVM Clojure and a
Nix-based build. Babashka/SCI support and ACP agent integration are accepted future
directions, not shipped behavior.
</p>

## Shape: body and mind

- **The body** is oml itself: the persistent Lisp image, its grant, and its skills.
- **The mind** is a driver that acts *through* the image — by default an attached AI,
  and equally a human at the REPL, a headless script, or any other client of the same
  surface. The mind is attached out of the box and is swappable; the body is what
  persists and grows.

Everything that acts on oml — human or AI — does so through a single **eval surface**.
That uniformity is deliberate: there is one way to affect the image, and it is governed
by the grant.

## Core components

### 1. Interpreter

A Lisp of the Clojure family. The first version runs on a full JVM via Clojure.
A lightweight babashka/SCI runtime is an accepted future possibility, not a current
delivery commitment.

### 2. Persistent image

Durable state that survives restarts. Objects and their behavior are saved and restored,
so a launch resumes where the previous one left off. The image *is* the memory.

### 3. Grant

A deny-by-default eval surface. A driver sees exactly the vocabulary it was granted — a
bounded set of verbs, never ambient authority. Two authorities coexist in one image:

- the **owner**, with full reach into the image;
- an **attached agent**, confined to its granted vocabulary.

Adding or removing a capability is an edit to the grant, not prompt engineering.

### 4. Natural-language front

The default way to drive oml is natural language: a request is interpreted by the
attached mind, which acts in the image through the grant. Explicit Lisp can be entered
directly, for precision, bypassing the mind.

### 5. Skills

Successful workflows are persisted as named, reusable capabilities. An image accumulates
skills over its lifetime, so the vocabulary the owner and the agent can invoke grows with
use. Skills are how the image gets better at understanding and serving its owner.

### 6. Extension seam

Capabilities are added to an image by import, declared in the image's configuration. The
core provides the seam; what is imported — tools, verbs, integrations — is the owner's
choice. Connectivity to other images is one such importable capability; it is not part of
the core.

## Configuration

An image reads its configuration from `~/.oml`: its identity, where it persists, its
default grant, and the capabilities it imports. Running the binary against a given
configuration is how an image is specialized for a purpose.

## Runtime notes

- The current build is JVM Clojure on a Nix foundation.
- The object model is plain Clojure — functions and maps — so behavior can remain
  portable if a second runtime is added later.

## Future direction

The accepted ACP client design describes how an external coding agent can be connected
through the Agent Client Protocol. See [ACP client architecture](/docs/acp-architecture/).

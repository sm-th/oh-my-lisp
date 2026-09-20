---
layout: docs.njk
title: "Changelog"
description: "Notable changes to the oh-my-lisp project."
navTitle: "Changelog"
order: 4
---
# Changelog

This page records notable changes to oh-my-lisp. It is a high-level summary, not a
duplicate of git history.

## 0.1.0 — 2026-09-19

First tagged release: a complete local ACP v1 client over the official ACP Java SDK.

- Implement Stage 1f ACP session close (#47).
  - Add `oml.acp/close-session`, sending `session/close` when the agent advertised the capability and raising `:acp/capability` otherwise.
  - Extend the fake ACP subprocess and behavior tests for supported and unsupported close.
  - Document session close on the [ACP connection](/docs/acp-connection/) page and update this changelog.

- Implement Stage 1e ACP turn cancellation (#45).
  - Add `oml.acp/cancel`, sending a `session/cancel` notification so an in-flight `prompt` (on another thread) returns with `:stop-reason :cancelled`.
  - Extend the fake ACP subprocess and behavior tests to cancel an in-flight turn.
  - Document cancellation on the [ACP connection](/docs/acp-connection/) page and update this changelog.

- Implement Stage 1d ACP permission handling (#43).
  - Route `session/request_permission` to an optional `:on-permission` Lisp callback passed to `connect`, defaulting to a safe reject when unconfigured or when the callback throws.
  - Make the per-request timeout configurable via `connect` opts (`:request-timeout-ms`, default 120000) so real prompt turns are not cut off at 10 seconds.
  - Extend the fake ACP subprocess and behavior tests to exercise a client-handled permission request (allow, default reject, and callback error).
  - Document permissions on the [ACP connection](/docs/acp-connection/) page and update this changelog.

- Implement Stage 1c ACP prompt turn (#41).
  - Add `oml.acp/prompt`, sending `session/prompt` and returning `{:stop-reason ... :updates [...]}` with the turn's ordered `session/update` events.
  - Capture streamed `session/update` events per connection via a registered SDK consumer.
  - Extend the fake ACP subprocess and behavior tests to cover a streamed turn, a non-`end_turn` stop reason, and an agent-reported failure.
  - Document `prompt` on the [ACP connection](/docs/acp-connection/) page and update this changelog.

- Implement Stage 1b ACP session creation (#39).
  - Add `oml.acp/new-session`, sending `session/new` through the SDK and returning `{:session-id "..."}`.
  - Extend the fake ACP subprocess and behavior tests to cover session-creation success and agent-reported failure.
  - Document `new-session` on the [ACP connection](/docs/acp-connection/) page and update this changelog.

- Implement Stage 1a ACP local stdio connection (#35).
  - Add `oml.acp/connect`, `oml.acp/capabilities`, and `oml.acp/close` as a thin
    wrapper over the official ACP Java SDK (`com.agentclientprotocol:acp-core:0.16.0`).
  - Translate SDK failures into stable `ex-info` categories under `:oml/error`.
  - Add behavior tests with a real fake ACP subprocess covering successful initialize,
    protocol-version mismatch, malformed frame, unexpected exit, double close, and
    bounded shutdown of a hanging agent.
  - Add the SDK and its runtime transitive dependencies to `deps.edn` and the closed
    Nix dependency set.
  - Publish the [ACP connection](/docs/acp-connection/) page and update the ACP
    architecture status, site navigation, and this changelog.

- Polish the documentation site's visual design and navigation.
  - Introduce an explicit, accessible palette and spacing/type tokens in `site/src/style.css`.
  - Add current-page navigation markers, focus-visible states, and clearer navbar grouping.
  - Improve content width, heading rhythm, link contrast, and narrow-screen wrapping.

- Establish the documentation-site workflow and change-tracking policy.
  - Add a documentation source structure under `site/src/docs/`.
  - Publish the accepted ACP client architecture as a design/future-direction page.
  - Add a documentation landing page and site navigation.
  - Record the same-PR documentation rule in contributor guidance.
  - Run the site build in GitHub Actions for documentation and code pull requests.

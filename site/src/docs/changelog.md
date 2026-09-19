---
layout: docs.njk
title: "Changelog — oh-my-lisp"
description: "Notable changes to the oh-my-lisp project."
---
# Changelog

This page records notable changes to oh-my-lisp. It is a high-level summary, not a
duplicate of git history.

## Unreleased

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

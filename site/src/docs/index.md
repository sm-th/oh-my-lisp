---
layout: docs-index.njk
title: "Documentation — oh-my-lisp"
description: "Public design documents for the oh-my-lisp project."
---
# Documentation

These pages record the public design and direction of **oh-my-lisp**.
They are versioned with the repository and published through the project site.

- [Vision](/docs/vision/) — what oml is and why it matters.
- [Architecture](/docs/architecture/) — the current JVM foundation and intended core shape.
- [ACP client architecture](/docs/acp-architecture/) — accepted design for the local ACP v1 client.
- [ACP connection](/docs/acp-connection/) — the local ACP v1 client: connect, sessions, prompts with streamed updates, permissions, cancellation, and close.
- [Changelog](/docs/changelog/) — a lightweight record of notable changes.

## Try v0.1.0

oml is a local ACP v1 client. Run it straight from GitHub and drive any ACP stdio agent (for example `omp acp`):

```bash
nix run github:sm-th/oh-my-lisp
```

```clojure
(require '[oml.acp :as acp])
(def c (acp/connect "omp" ["acp"] nil {:on-permission (fn [_] :allow)}))
(def s (:session-id (acp/new-session c "/path/to/project")))
(acp/prompt c s "list the files here")
(acp/close c)
```

See [ACP connection](/docs/acp-connection/) for the full API.

Implementation issues and day-to-day work are tracked on
[GitHub](https://github.com/sm-th/oh-my-lisp).

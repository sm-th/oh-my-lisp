---
layout: docs.njk
title: "Documentation policy — oh-my-lisp"
description: "How oh-my-lisp keeps implementation changes and documentation in sync."
---
# Documentation policy

After Stage 0, every behavior, interface, or architecture change must update the
relevant English documentation in the same pull request. Documentation is not a
follow-up task.

## Same-pull-request rule

A pull request that changes observable behavior must also:

- Add or update the English documentation that explains the change.
- Publish that documentation through the generated project site.
- Link the new or updated page from site navigation so it is reachable.
- Pass the site build in GitHub Actions.

This applies to user-facing behavior, configuration surfaces, grants, APIs, and any
accepted architecture change.

## Change record

Notable changes are recorded in a lightweight `Unreleased` section of the
[changelog](/docs/changelog/). The changelog is a high-level summary, not a duplicate of
commit history. When a release is tagged, the `Unreleased` section is given a version
heading; until then, it accumulates the changes on the main branch.

## Scope

- Public product and design docs live under `site/src/docs/` and are published by the
  Eleventy site build.
- Internal repository guidance, such as issue and pull-request procedures, is not
  surfaced as product documentation.
- All public content is in English and is sanitized: no local paths, session identifiers
  or transcripts, credentials, provider or model values, telemetry, or private commands.

## Verification

Repository checks, including the site build and markdown linting, run only in GitHub
Actions. A broken site build or invalid markup fails the pull request.

---
name: Implementation task
about: A self-contained, executable change with clear acceptance criteria
title: ""
labels: []
---

<!--
An issue must be executable by someone with no access to prior chat or session
context. Rely only on durable artifacts: repository files, linked issues, and
official specifications. Fill every section below and delete these comments.
-->

## Goal

<!-- The value this delivers and why. Link the design or authority issue or spec. -->

## Execution context (read first)

This issue specifies *what* to build; the repository's durable guidance specifies
*how* to work here and is not restated below. Read `AGENTS.md` (repository map and
canonical checks), `CONTRIBUTING.md` (contribution sequence and CI-only verification
— do not run validation locally; inspect GitHub Actions checks after pushing),
`docs/agents/issue-tracker.md` (issue, pull-request, and branch/worktree procedure;
one pull request closes this issue and references `Closes #<this issue>`),
`CODING_STANDARDS.md`, and `CONTEXT.md`.

<!-- List the affected files or surfaces and the .github/workflows/ci.yml jobs that validate the change. -->

## Public API / contract

<!-- Signatures, types, semantics, and error behavior a consumer observes. Omit only if there is no external contract. -->

## Scope

<!-- The concrete work to do. -->

## Acceptance criteria

<!-- A checklist that doubles as the Definition of Done; every item observable and testable. -->

- [ ] ...

## Non-goals

<!-- Explicit boundaries so the change stays a focused vertical slice. -->

# Issue tracker workflow

GitHub issues are planning and specification units in this repository. Start with an open issue whose dependencies are satisfied; create or clarify an issue before changing repository behavior without one.

## Resolve the specification

Read sources in this order:

1. The current issue body, comments that record owner decisions, and acceptance criteria define the requested change.
2. Current code, tests, and executable configuration define behavior and commands that already exist.
3. `CONTEXT.md` defines canonical vocabulary and responsibility boundaries.
4. `README.md`, `site/src/docs/vision.md`, and `site/src/docs/architecture.md` describe product direction; they do not override the sources above.

When those sources disagree, follow the higher source and keep the pull request limited to its approved scope. Record a material scope decision on every affected issue or the pull request so reviewers can evaluate the same specification.

## Write a self-contained issue

Create new issues from the templates in `.github/ISSUE_TEMPLATE/`. An issue must be executable by someone with no access to prior chat or session context, so it relies only on durable artifacts: repository files, linked issues, and official specifications. Never reference chat transcripts, session state, or other ephemeral context.

Reuse the **Implementation task** template skeleton and fill every section:

- **Goal** — the value delivered and the design or authority issue it serves.
- **Execution context (read first)** — links to the durable guidance above plus the affected files and the `.github/workflows/ci.yml` jobs that validate the change; do not restate that guidance.
- **Public API / contract** — the signatures, types, semantics, and error behavior a consumer observes.
- **Scope** — the concrete work.
- **Acceptance criteria** — a checklist that doubles as the Definition of Done, with every item observable and testable.
- **Non-goals** — the explicit boundaries that keep the change a focused vertical slice.

Use the **Design record** template for discussion-only architecture decisions.

## Deliver a focused pull request

One issue may be split across multiple focused pull requests, and one focused pull request may combine multiple issues, only when the owner records that decision. Otherwise, one pull request normally closes one issue.

1. Define the issues and acceptance criteria the pull request will deliver. Confirm its dependencies and any owner-recorded decision to split or combine work.
2. Create a dedicated branch and worktree from the pull request's required base. Use them only for that pull request.
3. Implement every acceptance criterion assigned to the pull request while following `CODING_STANDARDS.md` and existing patterns in the affected code.
4. Commit only the approved scope, push its branch, and open the pull request against the required base.
5. Link every affected issue, summarize the observable change, and use `Closes #N` only for each issue that merging the pull request should close.
6. Review every GitHub Actions check and investigate failures in its job log. Report check results as required by `CONTRIBUTING.md`.
7. Leave the pull request open for review. Apply the owner-authorization gate in `CONTRIBUTING.md`.

A dependency branch remains the pull request base until its dependency lands; then retarget as directed by the owner. Stop after review unless the owner authorizes the next integration or publication action under `CONTRIBUTING.md`.

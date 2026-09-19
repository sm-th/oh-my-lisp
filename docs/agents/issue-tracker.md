# Issue tracker workflow

GitHub issues are the unit of work in this repository. Start with an open issue whose dependencies are satisfied; create or clarify an issue before changing repository behavior without one.

## Resolve the specification

Read sources in this order:

1. The current issue body, comments that record owner decisions, and acceptance criteria define the requested change.
2. Current code, tests, and executable configuration define behavior and commands that already exist.
3. `CONTEXT.md` defines canonical vocabulary and responsibility boundaries.
4. `README.md`, `docs/vision.md`, and `docs/architecture.md` describe product direction; they do not override the sources above.

When those sources disagree, follow the higher source and keep the pull request limited to the issue. Record a material scope decision on the issue so reviewers can evaluate the same specification.

## Deliver one issue

1. Create a dedicated branch and worktree from the issue's required base. Use them only for that issue.
2. Implement every acceptance criterion while following `CODING_STANDARDS.md` and existing patterns in the affected code.
3. Commit only the issue's changes, push its branch, and open one pull request against the required base.
4. Link the issue, summarize the observable change, and use `Closes #N` only when merging that pull request should close the issue.
5. Review every GitHub Actions check and investigate failures in its job log. Report check results as required by `CONTRIBUTING.md`.
6. Leave the pull request open for review. Apply the owner-authorization gate in `CONTRIBUTING.md`.

A dependency branch remains the pull request base until its dependency lands; then retarget as directed by the owner. Stop after review unless the owner authorizes the next integration or publication action under `CONTRIBUTING.md`.

# Contributing

Repository work starts from a GitHub issue. The issue and its acceptance criteria define the change; current code, tests, and configuration define existing behavior. Treat `README.md`, `docs/vision.md`, and `docs/architecture.md` as direction rather than implemented specification.

Follow the repository issue and pull-request procedure in `docs/agents/issue-tracker.md`. Use one branch, one worktree, and one pull request for each issue so its diff and verification stay focused.

## Verification

Repository tests, checks, and builds run only in GitHub Actions. Do not run validation commands locally. Keep behavior changes covered by a focused CI check; when an issue needs proof that no workflow provides, add that coverage to GitHub Actions within the issue's scope.

After pushing, inspect every check on the pull request and open the job logs for failures. Report the check names and outcomes in the pull request. A change is ready for review when its acceptance criteria are met and all required checks pass.

The workflow and configuration files are the command sources of truth: `.github/workflows/ci.yml`, `deps.edn`, and `site/package.json`. Run the checkout-local program with `bin/oml`; its optional init-file behavior is defined by `src/oml/core.clj`.

The repository owner controls integration and publication. Merge a pull request, create a tag, or publish a release only after the owner gives explicit permission for that action.

See `CODING_STANDARDS.md` for code conventions and `CONTEXT.md` for project terminology.

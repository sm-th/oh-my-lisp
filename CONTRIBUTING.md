# Contributing

Repository work starts from a GitHub issue. The issue and its acceptance criteria define the change; current code, tests, and configuration define existing behavior. Treat `README.md`, `docs/vision.md`, and `docs/architecture.md` as direction rather than implemented specification.

Follow the repository issue and pull-request procedure in `docs/agents/issue-tracker.md`. An issue is a planning and specification unit; a pull request is a focused review and integration unit; a branch and worktree isolate a concurrent pull request. One pull request normally closes one issue, but splitting an issue across multiple pull requests or combining multiple issues in one pull request requires an explicit owner-recorded decision.

## Verification

Repository tests, checks, and builds run only in GitHub Actions. Do not run validation commands locally. Keep behavior changes covered by a focused flake check; when an issue needs proof the flake does not provide, add that coverage to `flake.nix` within the issue's scope so CI runs it.

After pushing, inspect every check on the pull request and open the job logs for failures. Report the check names and outcomes in the pull request. A change is ready for review when its acceptance criteria are met and all required checks pass.

`.github/workflows/ci.yml` defines required CI jobs. `flake.nix` defines the JVM production build and tests run by the `nix flake check` job; `site/package.json` and `.markdownlint-cli2.jsonc` define the other checked surfaces. Run the checkout-local program with `bin/oml`; its optional init-file behavior is defined by `src/oml/core.clj`.

## Documentation and change tracking

Every pull request that changes behavior, configuration, or a public API must add or
update the relevant English documentation in the same pull request. Documentation is
not a separate follow-up task and does not need per-commit sign-off.

Relevant documentation is published through the generated project site (`site/`),
linked from site navigation, and covered by the site build in GitHub Actions. If a
change introduces a new public concept, surface, or workflow, add a short site page
and link it from navigation.

Keep examples and screenshots in public docs generic and free of local paths, session
identifiers, credentials, provider or model values, telemetry, and private commands.

The repository owner controls integration and publication. Merge a pull request, create a tag, or publish a release only after the owner gives explicit permission for that action.

See `CODING_STANDARDS.md` for code conventions and `CONTEXT.md` for project terminology.

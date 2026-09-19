# Repository guidance

Start with the current GitHub issue and its acceptance criteria, then inspect the code, tests, and configuration that implement the affected surface. Those sources outrank the aspirational product descriptions in `README.md`, `docs/vision.md`, and `docs/architecture.md`.

Use these canonical references instead of restating them:

- `CONTEXT.md` defines project language and responsibility boundaries.
- `docs/agents/issue-tracker.md` defines issue and pull-request work.
- `CONTRIBUTING.md` defines the contribution sequence and CI-only verification policy.
- `CODING_STANDARDS.md` defines conventions evidenced by the current repository.

## Repository map

- `src/oml/`: JVM Clojure runtime, built-in REPL, and entry point.
- `test/oml/`: tests corresponding to the runtime namespaces.
- `bin/oml`: checkout-local launcher.
- `deps.edn`: Clojure dependencies, entry point, and test aliases.
- `site/`: Eleventy site and its package scripts.
- `.github/workflows/`: required CI and Pages workflows.
- `docs/`: product direction and agent-facing repository guidance.

Treat `deps.edn`, `site/package.json`, and `.github/workflows/ci.yml` as the canonical definitions of executable commands. Follow `CONTRIBUTING.md` for when and where they run.

# Coding standards

These are the conventions present in the JVM foundation. An issue may deliberately change them; aspirational product documents may not.

## Clojure layout and names

- Put production namespaces under `src/oml/` and matching tests under `test/oml/`.
- Map file names to namespaces in the established form: `src/oml/kernel.clj` is `oml.kernel`; `test/oml/kernel_test.clj` is `oml.kernel-test`.
- Declare namespace dependencies with `:require` aliases and Java imports with `:import`. Use qualified calls across namespaces.
- Mark implementation-only functions with `defn-` and implementation-only values with `^:private`.
- Give public entry points and behavior-bearing functions docstrings that state observable inputs, results, and failure behavior.

## Runtime boundaries

- Keep the kernel on JVM Clojure and keep its runtime dependency set limited to Clojure unless an accepted issue changes that boundary.
- Keep shared evaluation behavior in `oml.kernel`, REPL classification and presentation in `oml.repl`, and process startup in `oml.core`.
- Represent failures that need machine-readable context with `ex-info`, an `:oml/error` category, relevant context data, and the original cause.
- Keep process termination at the `-main` boundary; callable functions return data or throw documented exceptions.

## Tests

- Use `clojure.test` with behavior-named `deftest` forms and assertions on observable results or error data.
- Keep each test namespace aligned with its production namespace.
- Make filesystem fixtures temporary and self-cleaning, following the existing `deleteOnExit` pattern.

Verification commands and contribution policy live in `CONTRIBUTING.md`. Project terms and component boundaries live in `CONTEXT.md`.

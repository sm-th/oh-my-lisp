# oh-my-lisp

**`oml`** — *a Lisp for AI*.

`oml` is a persistent Lisp environment: a runtime whose definitions, data, and
accumulated state are defined, run, saved, and rewritten in place, and survive
restarts. A human or an attached agent drives it through the same eval surface,
extends it, and grows it over time — the environment stays yours, not a
disposable session. Reaching an AI model or agent, bounding what it may touch,
and every interface on top are layers built in `oml` itself, not the core.

## What's here today

`oml` is a minimal kernel — the eval surface, explicit-file persistence, and a
recovery REPL — with an ACP client bundled as the first layer on top of it.

- **Eval surface.** One shared, mutable runtime namespace. The built-in REPL
  classifies each line of input deterministically: a line starting with `(` is
  evaluated as Lisp, a line starting with `/eval` is evaluated as Lisp using
  the rest of the line as the code, a blank line does nothing, and anything
  else is reported as plain, un-evaluated text. Lisp is evaluated exactly as
  written — no interpretation or rewriting by a model sits between input and
  `eval`.
- **Explicit-file persistence.** Nothing survives a restart unless it was
  explicitly saved. `evaluate-file` reads and evaluates a Lisp file in the
  shared namespace; `save-forms`/`save-and-evaluate` write definitions to a
  file that a later `evaluate-file` restores; `save-data`/`load-data`
  round-trip a plain value through a readable EDN file. Configuration is the
  same mechanism: an optional init file is evaluated once at startup and
  decides what the image does next.
- **Recovery boot.** With no init file, `oml` starts the plain Lisp REPL
  directly. With one, `oml` evaluates it; if it fails to load, the failure is
  reported and the plain REPL starts anyway, so a human or agent can repair
  the image from within it rather than the process aborting.
- **ACP client — a layer, not the kernel.** `oml.acp` is a thin Clojure
  wrapper over the official ACP Java SDK: connect and negotiate capabilities,
  open a session, send a prompt and consume streamed updates, route
  permission requests to a Lisp callback, cancel a turn, and close a session.
  It is one way to reach an external coding agent; removing it leaves the
  eval surface, persistence, and recovery intact.

```console
$ bin/oml
oml> (def greeting "hi")
#'oml.user/greeting
oml> (oml.kernel/save-forms "greeting.clj" '[(def greeting "hi")])
"greeting.clj"
oml> hello there
oml: not Lisp: nothing was evaluated. Lisp is evaluated when it starts with `(` or is marked with `/eval`
oml> /eval (+ 1 2)
3
```

Natural-language routing, a bounded grant for a less-trusted driver, and
orchestration on top of the ACP client are accepted directions for future
layers, not shipped kernel behavior; see [Architecture](https://oml.sh/docs/architecture/)
for what is shipped and what is still accepted-but-not-implemented.

## Documents

- [Vision](https://oml.sh/) — what oml is and why it matters (the home page).
- [Architecture](https://oml.sh/docs/architecture/) — the current JVM foundation and
  intended core shape.
- [ACP client architecture](https://oml.sh/docs/acp-architecture/) — accepted design
  for the local ACP v1 client.
- [Changelog](https://oml.sh/docs/changelog/) — a lightweight record of notable
  changes.

## Status

Early, and now runnable. **v0.2.0** adds the living-image kernel — explicit-file
persistence, recovery boot, and the Lisp-only REPL — on top of the eval surface,
with the local ACP v1 client (connect, sessions, prompts with streamed updates,
permissions, cancellation, and capability-gated close) bundled as the first
layer, shipped in v0.1.0. Design first;
work happens in issues and pull requests.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

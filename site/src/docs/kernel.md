---
layout: docs.njk
title: "Kernel — oh-my-lisp"
description: "The living-image kernel: the eval surface, explicit-file persistence, recovery boot, and the kernel/layer boundary."
navTitle: "Kernel"
order: 1.5
---
# Kernel

The kernel is the minimal living-image substrate: the eval surface, explicit-file
persistence, and a recovery REPL that survives a broken configuration. Everything
else — reaching an external agent, bounding the eval surface for a less-trusted
actor, orchestration — is a layer expressed in Lisp on top of the kernel, not part
of it. The design record is issue
[#55](https://github.com/sm-th/oh-my-lisp/issues/55).

## The eval surface

`oml` keeps one shared, mutable runtime namespace. Configuration loaded at
startup is evaluated into it, and so is every later direct eval, so anything
defined earlier stays visible to everything that acts through the same surface
afterward.

Lisp is evaluated exactly as written: there is no interpretation or rewriting of
input before it reaches `eval`. The built-in REPL classifies each line of input
deterministically:

- a blank line does nothing;
- a line starting with `(` is evaluated as Lisp;
- a line starting with `/eval` (standing alone or followed by whitespace) is
  evaluated as Lisp, using the rest of the line as the code;
- anything else is reported as plain, un-evaluated text — routing that text
  elsewhere (natural language or otherwise) is a layer's job, not the kernel's.

```console
oml> (def counter 0)
#'oml.user/counter
oml> (def counter (inc counter))
1
oml> hello there
oml: not Lisp: nothing was evaluated. Lisp is evaluated when it starts with `(` or is marked with `/eval`
```

## Explicit-file persistence

Everything defined live in the image is ephemeral: nothing survives a restart
unless it was explicitly saved to a file. There is no automatic journal or heap
snapshot of the running image.

- **Evaluate a file.** Reads and evaluates every form in a file, in order, in the
  shared runtime — the same operation used to load configuration at startup and
  to restore anything saved earlier.
- **Save definitions.** Writes a sequence of Lisp forms (for example quoted `def`
  forms) to a file, one printed form per line, so a later evaluate-file call can
  restore them.
- **Save and evaluate.** Saves a sequence of forms to a file and evaluates the
  saved file in one step, so a definition becomes both live and durable.
- **Save and load data.** Round-trips a plain value through a readable EDN file,
  for state that is data rather than code — separate from the Lisp forms saved
  above.

```console
oml> (oml.kernel/save-forms "config.clj" '[(def greeting "hi")])
"config.clj"
oml> (oml.kernel/evaluate-file "config.clj")
#'oml.user/greeting
oml> (oml.kernel/save-and-evaluate "config.clj" '[(def greeting "hi") (def count 1)])
1
oml> (oml.kernel/save-data "state.edn" {:visits 3})
"state.edn"
oml> (oml.kernel/load-data "state.edn")
{:visits 3}
```

A definition made only with `def` at the REPL — never passed to `save-forms` or
`save-and-evaluate` — is gone the next time the process starts; a value saved
with `save-data` and restored with `load-data` is not, and neither read requires
the file's original writer to still be running.

Configuration-as-program follows the same mechanism: at startup, `oml` evaluates
one configuration file (if given) with `evaluate-file`, so a configuration is
just Lisp that runs once to build a ready image — the same operation, and the
same file format, used to restore anything else saved during a session.

## Recovery boot

`oml` boots the plain Lisp REPL — the recovery floor — whenever there is no
configuration to run or that configuration does not take over:

- **No configuration.** With no init-file argument, `oml` starts the built-in
  REPL directly.
- **Valid configuration.** With an init-file argument, `oml` evaluates that file
  once. The configuration decides what happens next — including starting the
  REPL itself, or a service, or nothing at all — and boot does not start the
  REPL on its own for it.
- **Broken configuration.** If the configuration fails to load or evaluate, `oml`
  reports the failure and its cause, then starts the plain REPL anyway, so the
  image stays reachable for repair rather than aborting.
- **`--no-init`.** Skips loading any configuration — even when a path is also
  given — and starts the plain REPL directly, the same as no arguments at all.

```console
$ oml
oml> (+ 1 2)
3

$ oml broken-config.clj
oml: failed to load init file broken-config.clj
RuntimeException: Unable to resolve symbol: bogus in this context
oml> (+ 1 2)
3

$ oml --no-init config.clj
oml> (+ 1 2)
3
```

## Kernel/layer boundary

The kernel is deliberately small: the eval surface, explicit-file persistence,
configuration-as-program, and the recovery REPL. Reaching an external coding
agent over the Agent Client Protocol, bounding the eval surface with a grant for
a less-trusted actor, natural-language routing, and orchestration are all layers
— Lisp loaded onto the kernel through the same eval surface everything else
uses — and are never part of the kernel itself. See
[Architecture](/docs/architecture/) and
[ACP client architecture](/docs/acp-architecture/) for the layers built on top of
this kernel.

# oh-my-lisp

**`oml`** — *oml is my lisp*.

A Lisp image you run and inhabit. This repository currently contains the JVM
kernel: a Clojure runtime with a built-in REPL, driven by executable init
configuration. The [vision](docs/vision.md) and [architecture](docs/architecture.md)
documents describe the direction — persistent image, grant, skills — that later
issues build toward; none of that is implemented yet.

## Run it

From a repository checkout, with Clojure's CLI tools and a JVM installed:

```console
$ bin/oml
oml> (+ 1 2)
3
oml> /eval (* 6 7)
42
oml> hello
oml: unhandled natural-language input: nothing was evaluated. Lisp is evaluated when it starts with `(` or when it is marked with `/eval`
```

`bin/oml` starts the built-in REPL directly; `clojure -M:oml` does the same
from the project root.

## Input rules

Input is classified line by line, deterministically:

- a line starting with `(` is direct Lisp — evaluated in the shared runtime
  namespace, and the result is printed;
- a line marked `/eval <code>` is also direct Lisp through the same eval
  surface;
- blank lines are no-ops;
- anything else — ordinary text — is not evaluated. There is no
  natural-language routing yet, so it is reported as unhandled input instead.

Evaluation errors are reported with their full cause chain, and the REPL keeps
running.

## Init file

The first argument, when given, is an init file — executable Clojure, not a
declarative config:

```sh
bin/oml ~/.oml/init.clj
```

Its forms are evaluated once, in order, before the REPL starts, into the same
runtime namespace the REPL uses, so anything the init file defines stays
visible to every later direct eval. Ordinary non-daemon JVM threads the init
file starts keep the image alive after stdin reaches EOF. Omitting the init
file is fine; a supplied file that is missing or fails to load aborts startup
with a visible error and a non-zero exit.

Init extends the running image — it never replaces the built-in REPL.

## Tests

```sh
clojure -M:test                       # all tests
clojure -M:test -n oml.kernel-test    # one namespace
```

## Status

Early. The JVM kernel is in; the documents above remain the direction. Work
happens in issues and pull requests.

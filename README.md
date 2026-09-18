# oh-my-lisp

**`oml`** — *oh my lisp*.

> A personal, persistent, self-evolving Lisp you drive in natural language — and,
> when you want precision, in Lisp directly. An agent lives in the image, acts
> through a bounded grant, and the image grows by keeping the workflows that worked.

oml is to Lisp agents what an agent CLI is to chat: you run it, you talk to it, and
it becomes yours over time.

## Run

With Nix:

```text
nix run github:sm-th/oh-my-lisp
```

Or with [babashka](https://babashka.org) from a checkout:

```text
bb -m oml.main
```

Point it at an OpenAI-compatible endpoint and talk to it:

```text
export OPENAI_API_KEY=…
export OPENAI_BASE_URL=https://…/v1
oml
```

```text
$ oml
oml — talk to me. (Ctrl-D to exit)

you> …
oml> …
```

Credentials can also live under `:openai` in `~/.oml/config.edn`.

## Documents

- [Vision](docs/vision.md) — what oml is and why it matters.
- [Architecture](docs/architecture.md) — the core: interpreter, image, grant,
  natural-language front, skills, extension seam.

## Status

Early. The design is in the documents above. Work happens in issues and pull requests.

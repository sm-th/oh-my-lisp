# oh-my-lisp

**`oml`** — *oh my lisp*.

> A personal, persistent, self-evolving Lisp you drive in natural language — and,
> when you want precision, in Lisp directly. An agent lives in the image, acts
> through a bounded grant, and the image grows by keeping the workflows that worked.

oml is to Lisp agents what an agent CLI is to chat: you run it, you talk to it, and
it becomes yours over time.

```text
$ oml
oml 0.1 · image restored — 42 objects, 17 skills
talk to me. (drop to Lisp with a leading form)

you> summarize project X and save it as a skill "weekly-x"
oml> done — saved skill weekly-x; next time just say "weekly X".

you> (skills)
=> (:recall :note :weekly-x …)
```

## Documents

- [Vision](docs/vision.md) — what oml is and why it matters.
- [Architecture](docs/architecture.md) — the core: interpreter, image, grant,
  natural-language front, skills, extension seam.

## Status

Early. Design first — see the documents above. Work happens in issues and pull requests.

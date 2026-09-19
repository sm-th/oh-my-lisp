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

- [Vision](https://oml.sh/docs/vision/) — what oml is and why it matters.
- [Architecture](https://oml.sh/docs/architecture/) — the current JVM foundation and
  intended core shape.
- [ACP client architecture](https://oml.sh/docs/acp-architecture/) — accepted design
  for the local ACP v1 client.
- [Changelog](https://oml.sh/docs/changelog/) — a lightweight record of notable
  changes.

## Status

Early, and now runnable. The **v0.1.0** release delivers a local ACP v1 client —
connect, sessions, prompts with streamed updates, permissions, cancellation, and
capability-gated close — over the official ACP Java SDK. Design first; work happens
in issues and pull requests.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

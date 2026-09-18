# oh-my-lisp

**`oml`** — *oml is my lisp*.

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

## Run (v0.0.1)

The first release is a launcher: it starts your configured agent harness (the
"mind") with the model from `~/.oml/config.edn`, and passes any extra arguments
through. It needs [babashka](https://babashka.org).

```text
./oml              # launch your configured harness (or onboard, if none set)
./oml onboard      # configure oml conversationally
./oml --help
./oml --print-cmd  # show the resolved command without launching
```

There is **no default harness**. On first run, if none is configured, oml **onboards
you in natural language** using its own OpenAI-compatible model and writes the config
for you:

```text
$ export OPENAI_API_KEY=…  OPENAI_BASE_URL=https://…/v1
$ ./oml
oml onboarding — tell me which harness and model you want (or ask for help).
you> use omp with the default model
oml: wrote ~/.oml/config.edn → {:harness :omp, :model "auto"}
```

The config is plain EDN:

```clojure
{:harness :omp        ; :omp (oh-my-pi) | :pi (omp pi) | :fx (Vercel fx)
 :model "auto"        ; "auto" = harness default, or e.g. "anthropic/claude-opus-4-8"
 :args []}            ; extra args always passed to the harness
```

oml tells the harness where its own config lives, so once running you can also
**reconfigure oml in natural language** — ask it to "use opus as your model" and the
harness edits `~/.oml/config.edn`. Changes take effect on the next launch.

## Documents

- [Vision](docs/vision.md) — what oml is and why it matters.
- [Architecture](docs/architecture.md) — the core: interpreter, image, grant,
  natural-language front, skills, extension seam.

## Status

Early. `v0.0.1` is a launcher with conversational onboarding; the design is in the
documents above. Work happens in issues and pull requests.

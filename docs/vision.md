# Vision

## What oml is

> **oml** (*oh my lisp*) — a personal, persistent, self-evolving Lisp you drive
> in natural language, and, when you want precision, in Lisp directly. An agent lives
> in the image, acts through a bounded grant, and the image grows by keeping the
> workflows that worked.

## Why it matters

- **Natural language first.** You talk to it. Without that, it is a REPL for hackers;
  with it, it is something a person can actually use. This is the difference between a
  toolkit and a companion.
- **A body that remembers.** A bare language model forgets. oml is a persistent image:
  its objects, state, and learned skills survive restarts. The work you did yesterday
  is still here today.
- **It grows.** Every request is fulfilled by composing and running Lisp in the image.
  When a workflow works, it is kept as a named skill. The longer you live with an image,
  the richer its vocabulary — and the better it understands what you mean.
- **Safe to inhabit.** The agent acts through a deny-by-default grant: a bounded
  vocabulary, not ambient power. That makes it safe to hand a capable but untrusted mind
  the keys to a live environment.
- **Lisp underneath.** Precision when you want it, and a substrate where behavior is
  data — so the image can extend and rewrite itself.

## The loop

```text
natural language
   → the agent composes and runs Lisp in the image
      → if it works, it is saved as a skill
         → next time that intent is a known capability,
            and your language is understood more richly
```

This compounding loop is the point. A plain assistant cannot do it — it has no
persistent body in which a vocabulary can accumulate. oml does.

## Personal and extensible

oml is *yours*. It starts small and gains capabilities by import — you add what you
need, when you need it. Its identity is the personal, growing Lisp; everything else is
something you bring in.

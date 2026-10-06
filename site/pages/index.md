<p class="lead">oml is a coding agent in Clojure: its own agent loop in {{src-lines}} lines of babashka. Every step it takes is a named function, and you change any of them while it runs.</p>

<div class="cta"><a class="btn" href="/getting-started/">Get started</a><a href="https://github.com/sm-th/oh-my-lisp">github.com/sm-th/oh-my-lisp</a></div>

<figure>
<img src="/assets/screenshot.png" alt="oml running in Toad: tool cards, a /eval that changes the system prompt, and the next answer in pirate voice">
<figcaption>oml in Toad: tool calls as cards, then one /eval changes the system prompt for the next answer.</figcaption>
</figure>

<figure>
<video controls playsinline preload="metadata"><source src="https://files.andysmith.ai/2026/10/07/oml-demo-video/oml-demo.mp4" type="video/mp4"><a href="https://files.andysmith.ai/2026/10/07/oml-demo-video/oml-demo.mp4">Watch the demo video</a></video>
<figcaption>Demo video. <a href="https://files.andysmith.ai/2026/10/07/oml-demo-video/oml-demo.mp4">Open it directly</a> if it does not play here.</figcaption>
</figure>

## What it is

- **Its own agent loop, small enough to read.** Stream a Chat Completions call, run the tool calls, append the results, repeat. Built-in tools: `read`, `write`, `edit`, `bash`.
- **Everything is a function.** Each step of the loop, each tool and each slash command is a named function the core calls through its var. Redefine it with `defn`, wrap it with `advise!`, set a setting with `setq`: from an init file, from `/eval`, or from an nREPL connected to the running agent. The next call uses it.
- **ACP is the UI.** oml speaks the [Agent Client Protocol](https://agentclientprotocol.com) over stdio, so Toad, Zed and Emacs agent-shell are its interface.
- **Any OpenAI-compatible endpoint.** A base URL, a key and a model id; responses stream over SSE.

## Quick start

With [nix](https://nixos.org) (flakes enabled):

```sh
git clone https://github.com/sm-th/oh-my-lisp oml
cd oml
cp .env.example .env      # set OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL
nix develop               # or: direnv allow
oml-toad ~/some/project   # Toad as the UI, oml as the agent
```

Then, in the session, try `/tools`, `/doc oml.agent/run`, or change the agent while it runs:

```clojure
/eval (oml.custom/advise! #'oml.agent/system-prompt :pirate
        (fn [system-prompt] (str (system-prompt) "\n\nAnswer like a pirate.")))
```

## Read on

- [Getting started](/getting-started/): install, endpoint, Toad, Zed, Emacs, print mode, tests.
- [Customising](/customising/): `defn`, `advise!`, `setq`; tools and commands as functions; init files; nREPL.
- [Primitives](/primitives/): the session, its transcript, `say`, `complete`, `call-tool`, `request!`.
- [Recipes](/recipes/): project context, usage, search tools, `/model`, permissions, a different loop.
- [Architecture](/architecture/): the namespaces, one turn from ACP to the model and back, limits.
- [Chapters](/chapters/): what is done and what is planned.

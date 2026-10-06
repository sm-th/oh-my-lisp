# oml

A minimal coding agent in Clojure (babashka) that you change while it runs.
Built chapter by chapter to understand how coding agents work from the
inside, and compared at each step with
[pi](https://github.com/earendil-works/pi).

- Its own agent loop in about 1.5k lines; tools `read`, `write`, `edit`, `bash`.
- Every step, tool and slash command is a named function: redefine it with
  `defn` or wrap it with `advise!`, from an init file, `/eval`, or an nREPL
  connected to the running agent.
- It speaks the [Agent Client Protocol](https://agentclientprotocol.com), so
  Toad, Zed and Emacs agent-shell are its UI.
- Any OpenAI-compatible Chat Completions endpoint.

Demo video: <https://files.andysmith.ai/2026/10/07/oml-demo-video/oml-demo.mp4>

Documentation: **<https://oml.sh>**

## Quick start

With [nix](https://nixos.org) (flakes enabled):

```sh
git clone https://github.com/sm-th/oh-my-lisp oml
cd oml
cp .env.example .env      # set OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL
nix develop               # or: direnv allow
oml-toad ~/some/project   # Toad as the UI, oml as the agent
```

Without nix, with [babashka](https://babashka.org) 1.13.219 or newer:

```sh
bb acp                    # ACP agent on stdio; or bin/oml-acp from anywhere
bb prompt "text"          # one prompt in the current directory
bb test                   # offline tests
bb site                   # build the website into site/_site
```

See [Getting started](https://oml.sh/getting-started/) for Zed and Emacs,
[Customising](https://oml.sh/customising/) for the one extension mechanism,
and [docs/CHAPTERS.md](docs/CHAPTERS.md) for what is done and planned.

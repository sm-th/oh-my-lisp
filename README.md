# oml

A minimal coding-agent harness in Clojure (babashka), built chapter by chapter
to understand how coding agents work from the inside, and compared at each
step with [pi](https://github.com/earendil-works/pi) (`~/pi/packages`).

- It talks to models through any OpenAI-compatible **Chat Completions**
  endpoint (OpenRouter by default), streaming over SSE.
- It speaks the [Agent Client Protocol](https://agentclientprotocol.com) (ACP)
  as an **agent** over stdio, so any ACP client (Toad, Zed, Emacs
  agent-shell) is its UI.
- Tools: `read`, `write`, `edit`, `bash`.

See [docs/CHAPTERS.md](docs/CHAPTERS.md) for the plan and status.

## Layout

| File | Chapter | What it does |
|---|---|---|
| `src/oml/llm.clj` | 1 | One streaming `POST /chat/completions`; SSE parsing; folds text and tool-call deltas into one assistant message |
| `src/oml/agent.clj` | 2 | The loop: call the model, run tool calls, append results, repeat |
| `src/oml/tools.clj` | 3 | `read`, `write`, `edit`, `bash` as data + an execute fn |
| `src/oml/cancel.clj` | 6 | Cancellation token shared by the HTTP stream, the loop and `bash` |
| `src/oml/acp.clj` | 7 | ACP agent: JSON-RPC over stdio, `session/update` streaming |
| `src/oml/print_mode.clj` | - | One prompt, output to stdout, no UI |
| `test/oml/fake_llm.clj` | - | In-process fake OpenAI server replaying scripted SSE |

## Running

Requires [babashka](https://babashka.org) (tested with 1.13.219).

```sh
bb acp                 # ACP agent on stdin/stdout (logs go to stderr)
bb prompt "text"       # one prompt in the current directory, streamed to stdout
bb test                # tests; offline, against the fake server
```

`bb` finds `bb.edn` in the current directory. From another directory use
`bb --config ~/sm-th/oml/bb.edn prompt "..."`, or `bin/oml-acp` for ACP (it
locates its own `bb.edn`).

### Environment

| Variable | Default | Meaning |
|---|---|---|
| `OML_BASE_URL` | `https://openrouter.ai/api/v1` | OpenAI-compatible base URL (`/chat/completions` is appended) |
| `OML_API_KEY` | falls back to `OPENROUTER_API_KEY`, then `OPENAI_API_KEY` | Bearer token |
| `OML_MODEL` | none, required when prompting | Model id, e.g. `openai/gpt-4o-mini` on OpenRouter |

A missing `OML_MODEL` is reported on the first prompt (as an error in print
mode, as a JSON-RPC error in ACP mode), not at startup.

## How to test it by hand

### a) Print mode with an OpenRouter key

```sh
export OPENROUTER_API_KEY=sk-or-...
export OML_MODEL=openai/gpt-4o-mini
cd /some/scratch/project
bb --config ~/sm-th/oml/bb.edn prompt "List the files here, then create hello.txt containing hi"
```

Text streams as it arrives; each tool call prints as `> name: title`
followed by `ok:` or `failed:` and the first line of the result. There is no
permission prompt yet (chapter 6): `bash` and `write` run immediately, so use
a scratch directory.

### b) Toad as the ACP client

Toad ([github.com/batrachianai/toad](https://github.com/batrachianai/toad))
installs with uv:

```sh
uv tool install -U batrachian-toad --python 3.14
```

`toad acp COMMAND [PATH]` runs any command as an ACP agent (see `toad acp
--help`; implemented in `src/toad/cli.py`). Toad starts the command through
a shell with its own environment, in the project directory, so export the
variables first:

```sh
export OPENROUTER_API_KEY=sk-or-...
export OML_MODEL=openai/gpt-4o-mini
toad acp ~/sm-th/oml/bin/oml-acp ~/some/project
```

### c) Zed

From Zed's [External Agents docs](https://zed.dev/docs/ai/external-agents#custom-agents),
add a custom agent to `settings.json` (Agent Settings -> External Agents ->
Add Agent -> Add Custom Agent opens it):

```json
{
  "agent_servers": {
    "oml": {
      "type": "custom",
      "command": "/Users/you/sm-th/oml/bin/oml-acp",
      "args": [],
      "env": {
        "OML_MODEL": "openai/gpt-4o-mini",
        "OPENROUTER_API_KEY": "sk-or-..."
      }
    }
  }
}
```

Start a thread with `oml` from the Agent Panel. `dev: open acp logs` in the
command palette shows the messages.

### d) Emacs agent-shell

[agent-shell](https://github.com/xenodium/agent-shell) (MELPA) builds agents
from `agent-shell-make-agent-config` with a `:client-maker` that calls
`acp-make-client` from [acp.el](https://github.com/xenodium/acp.el):

```elisp
(defun oml-agent-shell ()
  "Start an agent-shell running oml."
  (interactive)
  (agent-shell-start
   :config (agent-shell-make-agent-config
            :identifier 'oml
            :mode-line-name "oml"
            :buffer-name "oml"
            :shell-prompt "oml> "
            :shell-prompt-regexp "oml> "
            :client-maker
            (lambda (buffer)
              (acp-make-client
               :command (expand-file-name "~/sm-th/oml/bin/oml-acp")
               :environment-variables '("OML_MODEL=openai/gpt-4o-mini")
               :context-buffer buffer)))))
```

`:environment-variables` is appended to Emacs's `process-environment`, so an
`OPENROUTER_API_KEY` already set in Emacs is passed through.

### What the ACP surface supports

`initialize` (protocol version 1, no auth, text prompts only),
`session/new`, `session/prompt` with `session/update` notifications
(`agent_message_chunk`, `tool_call`, `tool_call_update`) and stop reasons
`end_turn`, `cancelled`, `max_turn_requests`, and the `session/cancel`
notification. Sessions live in memory only. `session/request_permission`,
`session/load` and client-side `fs/*` / `terminal/*` are later chapters.

## History

The earlier continuation-interpreter prototype (suspend/resume of an EDN
machine state) is preserved at tag `proto/continuations`; chapter 11 returns
to it.

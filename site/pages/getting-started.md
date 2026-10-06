<p class="lead">oml needs <a href="https://babashka.org">babashka</a> (1.13.219 or newer) and an OpenAI-compatible endpoint. The nix dev shell brings babashka and Toad.</p>

## Install

```sh
git clone https://github.com/sm-th/oh-my-lisp oml
cd oml
cp .env.example .env   # optional, gitignored; loaded on shell entry
nix develop            # or, with direnv: direnv allow  (.envrc says: use flake)
```

`flake.nix` provides babashka, Python 3.14, uv and Toad. Toad (`batrachian-toad==0.6.20`) is not in nixpkgs; the `toad` wrapper runs that pinned version through `uvx` on the nix Python, so the first run downloads it from PyPI and later runs use uv's cache. The shell adds these scripts:

| Script | Runs |
|---|---|
| `oml-test` | `bb test` |
| `oml-prompt "text"` | `bb prompt` in the current directory |
| `oml-acp` | `bin/oml-acp`, the ACP agent on stdio |
| `oml-toad [dir]` | `toad acp bin/oml-acp <dir or $PWD>` |

They find the repo through `OML_HOME`, which the shell sets to the directory you entered it from, so enter it from the repo root.

Without nix, install babashka and use the tasks directly:

```sh
bb acp                 # ACP agent on stdin/stdout (logs go to stderr)
bb prompt "text"       # one prompt in the current directory, streamed to stdout
bb test                # tests; offline, against a fake server
```

`bb` finds `bb.edn` in the current directory. From elsewhere use `bb --config /path/to/oml/bb.edn prompt "..."`, or `bin/oml-acp` for ACP (it locates its own `bb.edn`).

## The model endpoint

| Variable | Default | Meaning |
|---|---|---|
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL; `/chat/completions` is appended |
| `OPENAI_API_KEY` | none | Bearer token |
| `OPENAI_MODEL` | none, required when prompting | Model id, e.g. `gpt-4o-mini` |

These are only the defaults of the settings `oml.llm/base-url`, `oml.llm/api-key` and `oml.llm/model`, which an init file can set instead. A missing model is reported on the first prompt (as an error in print mode, as a JSON-RPC error over ACP), not at startup.

`XDG_CONFIG_HOME` (default `~/.config`) locates the init file and load path, `XDG_STATE_HOME` (default `~/.local/state`) the nREPL port file.

## Toad

[Toad](https://github.com/batrachianai/toad)'s `toad acp COMMAND PATH` runs any command as an ACP agent. It starts the command through a shell in the project directory, inheriting the dev shell environment (`.env` included):

```sh
oml-toad ~/some/project
```

## Zed

Add a custom agent to `settings.json` ([Zed's external agents docs](https://zed.dev/docs/ai/external-agents#custom-agents); Agent Settings → External Agents → Add Agent → Add Custom Agent opens it):

```json
{
  "agent_servers": {
    "oml": {
      "type": "custom",
      "command": "/path/to/oml/bin/oml-acp",
      "args": [],
      "env": {
        "OPENAI_BASE_URL": "https://api.openai.com/v1",
        "OPENAI_API_KEY": "sk-...",
        "OPENAI_MODEL": "gpt-4o-mini"
      }
    }
  }
}
```

Start a thread with `oml` from the Agent Panel. `dev: open acp logs` in the command palette shows the messages.

## Emacs agent-shell

[agent-shell](https://github.com/xenodium/agent-shell) (MELPA) builds agents from `agent-shell-make-agent-config` with a `:client-maker` that calls `acp-make-client` from [acp.el](https://github.com/xenodium/acp.el):

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
               :command (expand-file-name "/path/to/oml/bin/oml-acp")
               :environment-variables '("OPENAI_MODEL=gpt-4o-mini")
               :context-buffer buffer)))))
```

`:environment-variables` is appended to Emacs's `process-environment`, so an `OPENAI_API_KEY` and `OPENAI_BASE_URL` already set in Emacs are passed through.

## Print mode

One prompt, output to stdout, no UI:

```sh
cd /some/scratch/project
oml-prompt "List the files here, then create hello.txt containing hi"
```

Init files load as in ACP mode, and `oml-prompt "/doc oml.agent/run"` runs a command. Text streams as it arrives; each tool call prints as `> name: title` followed by `ok:` or `failed:` and the first line of the result. There is no client to ask for permission: without a policy `bash` and `write` run immediately, so use a scratch directory (with the [permissions recipe](/recipes/#permissions) they are blocked instead).

## Tests

```sh
bb test    # or oml-test in the dev shell
```

The suite is offline: an in-process fake OpenAI server replays scripted SSE (`test/oml/fake_llm.clj`), and a scripted ACP client drives the agent end to end and answers its requests (`test/oml/acp_client.clj`). The recipes in `examples/` run end to end too.

# oml

A minimal coding-agent harness in Clojure (babashka), built chapter by chapter
to understand how coding agents work from the inside, and compared at each
step with [pi](https://github.com/earendil-works/pi) (`~/pi/packages`).

- It talks to models through any OpenAI-compatible **Chat Completions**
  endpoint, streaming over SSE.
- It speaks the [Agent Client Protocol](https://agentclientprotocol.com) (ACP)
  as an **agent** over stdio, so any ACP client (Toad, Zed, Emacs
  agent-shell) is its UI.
- Tools: `read`, `write`, `edit`, `bash`.
- Everything is customisable the way Emacs is: named functions you can
  redefine, documented settings, hook variables, advice, an init file and a
  live REPL into the running agent. See [Customising oml](#customising-oml).

See [docs/CHAPTERS.md](docs/CHAPTERS.md) for the plan and status.

## Layout

| File | Chapter | What it does |
|---|---|---|
| `src/oml/llm.clj` | 1 | One streaming `POST /chat/completions`; SSE parsing; folds text and tool-call deltas into one assistant message |
| `src/oml/agent.clj` | 2, 4 | The loop as named steps: build the request, call the model, run tool calls, decide whether to go on; hook variables |
| `src/oml/ext/core.clj` | 3, 4 | Built-in `read`, `write`, `edit`, `bash` tools and system prompt sections, as an ordinary extension |
| `src/oml/custom.clj` | 4 | `defsetting`/`setq`, hooks, advice, discovery of tools and commands by metadata |
| `src/oml/init.clj` | 4 | Init files and load path |
| `src/oml/repl.clj` | 4 | nREPL server into the running agent |
| `src/oml/ext/help.clj` | 4 | `/describe`, `/apropos`, `/eval`, `/reload`, `/settings`, `/tools`, `/commands`, `/hooks` |
| `src/oml/cancel.clj` | 7 | Cancellation token shared by the HTTP stream, the loop and `bash` |
| `src/oml/acp.clj` | 8 | ACP agent: JSON-RPC over stdio, one multimethod per method, `session/update` streaming, slash commands |
| `src/oml/print_mode.clj` | - | One prompt, output to stdout, no UI |
| `test/oml/fake_llm.clj` | - | In-process fake OpenAI server replaying scripted SSE |
| `examples/init.clj` | 4 | An example init file |

## Running

Requires [babashka](https://babashka.org) (tested with 1.13.219 and newer).
The nix dev shell below provides it.

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
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL (`/chat/completions` is appended); e.g. `https://openrouter.ai/api/v1` |
| `OPENAI_API_KEY` | none | Bearer token |
| `OPENAI_MODEL` | none, required when prompting | Model id, e.g. `gpt-4o-mini`, or `openai/gpt-4o-mini` on OpenRouter |

These are only the defaults of the settings `oml.llm/base-url`,
`oml.llm/api-key` and `oml.llm/model`, which an init file can set instead.
A missing model is reported on the first prompt (as an error in print mode,
as a JSON-RPC error in ACP mode), not at startup.

`XDG_CONFIG_HOME` (default `~/.config`) locates the init file and load path,
`XDG_STATE_HOME` (default `~/.local/state`) the nREPL port file.

## Customising oml

Customisation is the core of oml, done the way Emacs does it: there is no
plugin or registration API. Behaviour lives in named functions and
documented variables in ordinary namespaces. The core calls them through
their vars, so whatever you redefine, wrap or add, from an init file, a
slash command or a REPL connected to the running agent, is used from the
next call on.

| Emacs | oml | Example |
|---|---|---|
| `defun`; redefine any function | `defn`; the loop calls named steps (`oml.agent/build-request`, `call-model`, `run-tool-call`, `stop-reason`, ...) through their vars | `(in-ns 'oml.ext.core) (defn guidelines-section [_] "Be terse.")` |
| `defcustom` | `defsetting` (a var with a docstring and `:oml/setting` metadata) | `(defsetting max-turns "Model calls per prompt." 30)` |
| `setq`, `customize-set-variable` | `setq` macro, or plain `alter-var-root` | `(setq oml.llm/model "gpt-4o-mini" oml.agent/max-turns 50)` |
| hooks, `add-hook`, `remove-hook` | vars holding a vector of functions; `add-hook!`, `remove-hook!` | `(add-hook! #'oml.agent/before-tool-functions #'refuse-rm-rf)` |
| `advice-add :around`, `advice-remove` | `advise!`, `unadvise!` (by key; same key replaces) | `(advise! #'oml.agent/call-model :log (fn [f ctx req] (f ctx req)))` |
| `(interactive)`, `M-x command` | `^:oml/command` fn of `[ctx input]`, typed as `/name` | `(defn today "Show the date." {:oml/command true} [_ _] (str (java.time.LocalDate/now)))` |
| (none: Emacs has no model) | `^:oml/tool` fn of `[ctx args]`; docstring and `:oml/params` are what the model sees | see `examples/init.clj` |
| `fmakunbound` | `ns-unmap` | `(ns-unmap 'oml.ext.core 'bash)` removes the `bash` tool |
| `describe-function`, `describe-variable` (`C-h f`, `C-h v`) | `/describe <symbol>` | `/describe oml.agent/max-turns` |
| `apropos` | `/apropos <regex>` | `/apropos tool` |
| `M-:` (`eval-expression`) | `/eval <forms>` | `/eval (count (oml.custom/tools))` |
| `init.el` | `~/.config/oml/init.clj`, then `<project>/.oml/init.clj` | `examples/init.clj` |
| `load-path` | `~/.config/oml/lisp/` and `<project>/.oml/lisp/` on the classpath | `(require 'my.notes)` loads `lisp/my/notes.clj` |
| re-evaluating `init.el` | `/reload` | |
| `M-x ielm`, a live Lisp image | an nREPL server in the agent process | `cider-connect-clj` |

Also: `/settings`, `/tools`, `/commands` and `/hooks` list what is there.

### Functions and settings

Every step of a turn is a public, documented function: `oml.agent/system-prompt`,
`collect-tools`, `build-request`, `call-model`, `model-turn`,
`run-tool-call`, `execute-tool`, `tool-result-message`, `stop-reason`,
`parse-prompt`, `run-command`; `oml.acp/render-event` turns loop events into
ACP updates, and each ACP method is a method of the `oml.acp/handle`
multimethod (`defmethod` adds one). Redefine any of them with `defn` in its
namespace. Settings are vars made with `defsetting`; like Emacs' `defvar`,
re-loading their namespace keeps the value you set. The ones that exist:
`oml.llm/base-url`, `api-key`, `model` (defaults from `OPENAI_*`),
`oml.agent/max-turns`, `enabled-tools` (nil = all), `oml.ext.core/bash-timeout`,
`bash-max-chars`, `read-max-chars`, `read-default-limit`,
`oml.init/load-project-init?`, `extensions`, `oml.repl/nrepl?`,
`nrepl-host`, `nrepl-port`.

### Hooks

| Hook | Called as | Returns |
|---|---|---|
| `oml.agent/system-prompt-functions` | `(f ctx)` | text of one system prompt section, or nil |
| `oml.agent/before-model-functions` | `(f request ctx)` | a replacement `{:messages :tools}`, or nil |
| `oml.agent/after-model-functions` | `(f response request ctx)` | a replacement `{:message :cancelled?}`, or nil |
| `oml.agent/before-tool-functions` | `(f call ctx)`, call `{:id :name :args}` | a rewritten call, nil, or the call with `:block "reason"` (the model sees the reason) |
| `oml.agent/after-tool-functions` | `(f result call ctx)`, result `{:content :error?}` | a replacement result, or nil |
| `oml.agent/session-start-hook` | `(f {:session-id :cwd})` | ignored |

Add vars (`#'my-fn`), not anonymous functions: a var stays late-bound when
you redefine the function, and adding it again on `/reload` is a no-op.

### Tools and commands are discovered, not registered

The harness scans the public vars of all loaded namespaces for metadata.
A tool is a function of `[ctx args]` (ctx is `{:cwd :cancel :on-event
:session-id}`) returning a string, or throwing `ex-info` for an error the
model should see:

```clojure
(defn word-count
  "Count the words in a text file."            ; the description the model reads
  {:oml/tool true
   :oml/kind "read"                             ; ACP tool kind, for the UI
   :oml/title (fn [{:keys [path]}] (str "Count words in " path))
   :oml/params {:path [:string "File path"]     ; compact JSON schema; a map with
                :max [:integer "Cap" :optional]}} ; :type is used as is
  [{:keys [cwd]} {:keys [path]}]
  (str (count (re-seq #"\S+" (slurp (oml.agent/resolve-path cwd path)))) " words"))
```

A command is a function of `[ctx input]` with `{:oml/command true}` (and
optionally `:oml/hint`); its output, printed or returned, is shown as an
agent message, and the commands are advertised to the client. The metadata
is part of the definition, like Emacs' `(interactive)`: redefining a tool
with a `defn` that lacks `:oml/tool` makes it an ordinary function. To
change only behaviour, use `advise!`, which keeps the metadata. The
built-in tools, prompt sections and commands (`oml.ext.core`,
`oml.ext.help`) use exactly this mechanism; `ns-unmap` one, or drop the
namespace from `oml.init/extensions` in the user init file.

### Init files and load path

At startup oml adds `$XDG_CONFIG_HOME/oml/lisp` to the classpath and loads
`$XDG_CONFIG_HOME/oml/init.clj` (default `~/.config/oml/`), then requires
the `extensions`. When a session starts it adds `<cwd>/.oml/lisp` and loads
`<cwd>/.oml/init.clj`. Init files are evaluated in the `user` namespace;
`/reload` loads both again. An error in an init file is logged to stderr
and shown in the session as a warning; the agent keeps working.

A project init file runs arbitrary code with your permissions as soon as a
client opens that directory. If you open projects you do not trust, put
`(setq oml.init/load-project-init? false)` in your user init file.

See [`examples/init.clj`](examples/init.clj): it overrides a prompt
section, adds a tool and a command, blocks `rm -rf` with a hook, logs model
calls with advice, changes settings and requires a module from the load
path ([`examples/lisp/my/notes.clj`](examples/lisp/my/notes.clj)).

### A live REPL into the agent

In ACP mode oml starts an nREPL server on `127.0.0.1` and a free port
(settings `oml.repl/nrepl?`, `nrepl-host`, `nrepl-port`), logs
`nREPL on 127.0.0.1:<port>` to stderr and writes the port to
`$XDG_STATE_HOME/oml/nrepl-port` (default `~/.local/state/oml/nrepl-port`).
It evaluates in the same image that serves the client: a `defn` there
changes the next prompt.

- CIDER: `M-x cider-connect-clj`, host `127.0.0.1`, port from the file.
  Or:

  ```elisp
  (defun oml-connect ()
    "Connect CIDER to the running oml agent."
    (interactive)
    (cider-connect-clj
     (list :host "127.0.0.1"
           :port (string-trim (with-temp-buffer
                                (insert-file-contents "~/.local/state/oml/nrepl-port")
                                (buffer-string))))))
  ```

- Calva: "Connect to a running REPL server, not in your project", project
  type babashka, `127.0.0.1:<port>`.
- Any other nREPL client (Conjure, `rep`, ...): host `127.0.0.1`, the port
  in the file.

Anyone who can connect can run code as you, which is why it binds to
loopback only; set `oml.repl/nrepl?` to false to turn it off.

## How to test it by hand

### Environment: nix dev shell

`flake.nix` provides babashka, Python 3.14, uv and Toad. Toad
(`batrachian-toad==0.6.20`) is not in nixpkgs; the `toad` wrapper runs that
pinned version through `uvx` on the nix Python, so the first run downloads it
from PyPI and later runs use uv's cache. Keys go in an optional, gitignored
`.env`, loaded on shell entry:

```sh
cp .env.example .env   # set OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL
nix develop            # or: direnv allow  (.envrc: use flake)
```

| Script | Runs |
|---|---|
| `oml-test` | `bb test` |
| `oml-prompt "text"` | `bb prompt` in the current directory |
| `oml-acp` | `bin/oml-acp` (ACP agent on stdio) |
| `oml-toad [dir]` | `toad acp bin/oml-acp <dir or $PWD>` |

Scripts find the repo through `OML_HOME`, which the shell sets to the
directory you entered it from, so enter it from the repo root.

### a) Print mode

```sh
cd /some/scratch/project
oml-prompt "List the files here, then create hello.txt containing hi"
```

Init files load as in ACP mode, and `oml-prompt "/describe oml.agent/run"`
runs a command. Text streams as it arrives; each tool call prints as `> name: title`
followed by `ok:` or `failed:` and the first line of the result. There is no
permission prompt yet (chapter 7): `bash` and `write` run immediately, so use
a scratch directory.

### b) Toad as the ACP client

[Toad](https://github.com/batrachianai/toad)'s `toad acp COMMAND PATH` runs
any command as an ACP agent. It starts the command through a shell in the
project directory, inheriting the dev shell environment (`.env` included):

```sh
oml-toad ~/some/project
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
      "command": "/absolute/path/to/oml/bin/oml-acp",
      "args": [],
      "env": {
        "OPENAI_BASE_URL": "https://openrouter.ai/api/v1",
        "OPENAI_API_KEY": "sk-...",
        "OPENAI_MODEL": "openai/gpt-4o-mini"
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
               :environment-variables '("OPENAI_MODEL=openai/gpt-4o-mini")
               :context-buffer buffer)))))
```

`:environment-variables` is appended to Emacs's `process-environment`, so an
`OPENAI_API_KEY` / `OPENAI_BASE_URL` already set in Emacs are passed through.

### What the ACP surface supports

`initialize` (protocol version 1, no auth, text prompts only),
`session/new`, `session/prompt` with `session/update` notifications
(`agent_message_chunk`, `tool_call`, `tool_call_update`,
`available_commands_update`) and stop reasons `end_turn`, `cancelled`,
`max_turn_requests`, and the `session/cancel` notification. A prompt that
starts with `/name` of a known command runs the command instead of calling
the model; an unknown `/x` goes to the model as text. Sessions live in memory only. `session/request_permission`,
`session/load` and client-side `fs/*` / `terminal/*` are later chapters.

## History

The earlier continuation-interpreter prototype (suspend/resume of an EDN
machine state) is preserved at tag `proto/continuations`; chapter 11 returns
to it.

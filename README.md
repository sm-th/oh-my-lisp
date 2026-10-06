# oml

A minimal coding-agent harness in Clojure (babashka), built chapter by chapter
to understand how coding agents work from the inside, and compared at each
step with [pi](https://github.com/earendil-works/pi) (`~/pi/packages`).

- It talks to models through any OpenAI-compatible **Chat Completions**
  endpoint, streaming over SSE.
- It speaks the [Agent Client Protocol](https://agentclientprotocol.com) (ACP)
  as an **agent** over stdio, so any ACP client (Toad, Zed, Emacs
  agent-shell) is its UI.
- Tools: `read`, `write`, `edit`, `bash`. More (search tools, a
  permission policy, project context, usage, model switch) are user code in
  [`examples/`](#recipes).
- One extension mechanism: plain functions in namespaces. Change behaviour
  by redefining a function (`defn`) or wrapping it (`advise!`), from an
  init file or a live REPL into the running agent. See
  [Customising oml](#customising-oml).

See [docs/CHAPTERS.md](docs/CHAPTERS.md) for the plan and status.

## Layout

| File | Chapter | What it does |
|---|---|---|
| `src/oml/llm.clj` | 1 | One streaming `POST /chat/completions`; SSE parsing; folds text and tool-call deltas into one assistant message |
| `src/oml/agent.clj` | 2, 4 | The loop as named steps; tools and commands as the functions of `tool-namespaces` and `command-namespaces`; `call-tool`, `complete` |
| `src/oml/tools.clj` | 3 | Built-in `read`, `write`, `edit`, `bash` tools |
| `src/oml/commands.clj` | 4 | `/doc`, `/apropos`, `/source` (on `clojure.repl`), `/eval`, `/reload`, `/settings`, `/tools`, `/commands` |
| `src/oml/custom.clj` | 4 | `setq`, `advise!`, `unadvise!` |
| `src/oml/session.clj` | 4 | The current session (`*session*`), its transcript, `say`, `on-session-start` |
| `src/oml/init.clj` | 4 | Init files and load path |
| `src/oml/repl.clj` | 4 | nREPL server into the running agent |
| `src/oml/cancel.clj` | 7 | Cancellation token shared by the HTTP stream, the loop and `bash` |
| `src/oml/acp.clj` | 8 | ACP agent: JSON-RPC over stdio, one multimethod per method, `session/update` rendering, `request!` and `request-permission` to the client |
| `src/oml/print_mode.clj` | - | One prompt, output to stdout, no UI |
| `test/oml/fake_llm.clj` | - | In-process fake OpenAI server replaying scripted SSE |
| `test/oml/acp_client.clj` | - | Scripted ACP client for end-to-end tests (answers the agent's requests) |
| `examples/init.clj`, `examples/lisp/my/` | 4 | An example init file and recipes (see [Recipes](#recipes)) |

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

oml takes Emacs' principle, not its API: a live image where everything is
a named function, plus introspection. There is no plugin, hook or
registration API. The core calls every step through its var, so whatever
you redefine or wrap, from an init file, `/eval` or a REPL connected to
the running agent, is used from the next call on. Clojure already has
most of what Emacs adds on top:

| Emacs | oml |
|---|---|
| `defun`; redefine any function | `defn`, e.g. `(in-ns 'oml.agent) (defn system-prompt [] "Be terse.")` |
| `defcustom`, `defvar` | `def` with a docstring: `(def max-turns "Model calls per prompt." 30)` |
| `setq` | `oml.custom/setq` (`alter-var-root`): `(setq oml.llm/model "gpt-4o-mini")` |
| hooks, `advice-add :around` | `advise!`, `unadvise!` on any function var (by key; same key replaces) |
| `(interactive)`, `M-x` | a documented public function in a `command-namespaces` namespace, typed as `/name` |
| (no model in Emacs) | a documented public function in a `tool-namespaces` namespace |
| `fmakunbound` | `ns-unmap`: `(ns-unmap 'oml.tools 'bash)` removes the `bash` tool |
| `C-h f`, `C-h v`, `apropos`, `find-function` | `/doc`, `/apropos`, `/source` (on `clojure.repl`) |
| `M-:` | `/eval <forms>` |
| `init.el`, `load-path` | `~/.config/oml/init.clj` and `lisp/`, then `<project>/.oml/init.clj` and `.oml/lisp/` |
| re-evaluating `init.el` | `/reload` |
| `M-x ielm`, a live image | an nREPL server in the agent process |

Also: `/settings`, `/tools` and `/commands` list what is there.

### Extension points

Every step is a public, documented function; these are the ones meant to
be redefined or wrapped. Each takes and returns plain data.

| Function | What it does |
|---|---|
| `oml.agent/system-prompt` | `[]` the system prompt, built for every request; wrap it to add a section |
| `oml.agent/tools`, `commands` | `[]` the tool and command vars, from the namespaces |
| `oml.agent/build-request` | `[messages]` the request: system prompt, transcript, tool specs |
| `oml.agent/call-model` | `[request]` one model call; returns `{:message :usage :cancelled?}`; wrap it to rewrite requests or account usage |
| `oml.agent/run-tool-call` | `[call]` one OpenAI tool call: show it, `execute-tool`, show the result |
| `oml.agent/execute-tool` | `[{:id :name :args}]` run the tool; wrap it for a permission policy (the client already shows the call) or to rewrite results |
| `oml.agent/tool-result-message` | `[call result]` the `tool` message for the transcript |
| `oml.agent/stop-reason` | `[{:turn :response}]` nil to go on, or `:end-turn`, `:cancelled`, `:max-turns` |
| `oml.agent/parse-prompt`, `run-command`, `prompt` | how the user's text becomes a command or a turn |
| `oml.session/on-session-start` | `[]` called when a session starts; does nothing |
| `oml.llm/on-chunk` | `[chunk]` called with every raw SSE chunk; does nothing |
| `oml.acp/render-event` | `[event]` the ACP update for a loop event; `oml.acp/handle` is a multimethod per ACP method |

Settings are documented vars: `oml.llm/base-url`, `api-key` (`^:secret`,
never shown), `model` (defaults from `OPENAI_*`), `send-reasoning?`;
`oml.agent/max-turns`, `tool-namespaces`, `command-namespaces`;
`oml.tools/bash-timeout`, `bash-max-chars`, `read-max-chars`,
`read-default-limit`; `oml.init/load-project-init?`; `oml.repl/nrepl?`,
`nrepl-host`, `nrepl-port`. `/settings` lists every documented var holding
data in oml and user code.

### Tools and commands are functions in namespaces

`oml.agent/tool-namespaces` (default `[oml.tools]`) and
`command-namespaces` (default `[oml.commands]`) name namespaces; every
public function with a docstring in them is a tool or a command, named
after the function (on a clash the later namespace wins). Add yours by
defining functions in your own namespace and listing it, or by defining
them in `oml.tools` directly; use `defn-` for helpers.

A tool takes the argument map the model sent and returns a string, or
throws `ex-info` with a message the model should read. Context comes from
`oml.session`: `(cwd)`, `(resolve-path p)`, `(session)` (its `:cancel`
token), `(transcript)`, `(say ...)`.

```clojure
(ns my.tools (:require [oml.session :refer [resolve-path]]))

(defn word-count
  "Count the words in a text file."                     ; what the model reads
  {:kind "read"                                         ; optional: ACP kind,
   :title (fn [{:keys [path]}] (str "Count " path))     ; UI title,
   :params {:path [:string "File path"]                 ; types, descriptions
            :limit [:integer "Stop counting here" :optional]}}
  [{:keys [path limit]}]
  (str (count (re-seq #"\S+" (slurp (resolve-path path)))) " words"))
```

```clojure
(setq oml.agent/tool-namespaces '[oml.tools my.tools])
```

The parameters come from the destructuring: each `:keys` entry is a
property, a required string unless `:params` says otherwise
(`[type description & flags]`; `:optional`, or an `:or` default, makes it
optional). The model sees:

```json
{"name": "word-count", "description": "Count the words in a text file.",
 "parameters": {"type": "object",
                "properties": {"path": {"type": "string", "description": "File path"},
                               "limit": {"type": "integer", "description": "Stop counting here"}},
                "required": ["path"]}}
```

The attr-map is optional: a plain `defn` redefinition keeps the function a
tool (with string parameters), `ns-unmap` removes it, and dropping the
namespace from the vector removes all of them.

A command takes the text after `/name`; what it prints, then what it
returns, is shown as an agent message. An optional `:hint` in its
attr-map is shown by the client. The command list is advertised to the
client after `session/new` and after every command.

```clojure
(defn today "Show today's date." [_] (str (java.time.LocalDate/now)))
```

### Init files and load path

At startup oml loads the tool and command namespaces, adds
`$XDG_CONFIG_HOME/oml/lisp` to the classpath and loads
`$XDG_CONFIG_HOME/oml/init.clj` (default `~/.config/oml/`). When a session
starts it adds `<cwd>/.oml/lisp` and loads `<cwd>/.oml/init.clj`, then
calls `oml.session/on-session-start`. Init files are evaluated in the
`user` namespace; `/reload` loads both again, so they should be safe to
repeat (`setq`, `defn` and keyed `advise!` are). An error in an init file
is logged to stderr and shown in the session as a warning; the agent keeps
working.

A project init file runs arbitrary code with your permissions as soon as a
client opens that directory. If you open projects you do not trust, put
`(setq oml.init/load-project-init? false)` in your user init file.

See [`examples/init.clj`](examples/init.clj): it sets settings, requires
modules from the load path, lists them in the tool and command namespaces,
redefines a step with `defn` and logs model calls with `advise!`.

### Primitives

What the core gives Lisp code, for what an init file could not do on its
own. Everything else (see [Recipes](#recipes)) is built on these.

**The current session** (`oml.session`). `*session*` is the id of the
current session: bound while a prompt runs (commands, `/eval`, tools and
the loop included). Its root value is the most recently active session, so
nREPL evaluations work on that one.

```clojure
(oml.session/session)        ; => {:id "sess_..." :cwd "/project" :cancel <token or nil> ...}
(oml.session/transcript)     ; => [{:role "user" :content "..."} {:role "assistant" ...} ...]
(oml.session/append-message! {:role "user" :content "Remember: tabs."})  ; the next request sees it
(oml.session/set-transcript! compacted)                                 ; replace it (compaction)
(oml.session/say "Indexing...")  ; agent_message_chunk to the client
```

The transcript holds OpenAI-format messages without the system message
(which is rebuilt every request). The loop appends each assistant message
together with its tool results.

**Call a tool as the agent** (`oml.agent/call-tool`). Runs through
`run-tool-call`, like a model tool call: the client shows a `tool_call`
card and its `tool_call_update`, and advice on `execute-tool` applies.
Returns `{:content :error?}`. With `:record? true` the call is appended to
the transcript as the model would have made it, so the model sees it next
turn:

```clojure
(oml.agent/call-tool 'ls {:path "."} :record? true)   ; a symbol, a name or a var
;; appends
{:role "assistant" :content nil
 :tool_calls [{:id "call_..." :type "function"
               :function {:name "ls" :arguments "{\"path\":\".\"}"}}]}
{:role "tool" :tool_call_id "call_..." :content "AGENTS.md\nsrc/"}
```

**One-shot model call** (`oml.agent/complete`): the configured model, no
tools, nothing shown to the user; returns the text. It goes through
`call-model`, so its advice applies. Cancelling the prompt cancels it.

```clojure
(oml.agent/complete "Name this session in three words." :system "Be terse.")
(oml.agent/complete (oml.session/transcript) :model "small/model")
```

**Requests to the client** (`oml.acp`). `(request! method params)` sends a
JSON-RPC request to the ACP client of the current session and blocks until
it answers; it throws on an error response, when the client goes away, or
when the prompt is cancelled. `request-permission` is ACP
`session/request_permission` on top of it; the core has no permission
policy, it only asks.

```clojure
(oml.acp/request-permission {:toolCallId id :rawInput args})  ; default options: allow_once,
;; => {:outcome "selected" :optionId "allow_always" :kind "allow_always"}  ; allow_always, reject_once,
;;    or {:outcome "cancelled"}                                         ; reject_always
```

Responses are read by the stdin reader thread, so `request!` refuses to run
on it (in `on-session-start`, which runs there); prompts, commands, tools
and nREPL are fine.

**Reasoning.** `reasoning_content` or `reasoning` deltas are shown as
`agent_thought_chunk` (loop event `:thought-delta`) and kept out of the
transcript unless `oml.llm/send-reasoning?` is true. `oml.llm/on-chunk`
sees every raw chunk for anything else a provider sends.

### Recipes

Each is a short module in [`examples/lisp/my/`](examples/lisp/my/),
loaded by [`examples/init.clj`](examples/init.clj) and run end to end by
`test/oml/examples_test.clj`:

| Module | What | Built on |
|---|---|---|
| `my.context` | `AGENTS.md` (or `CLAUDE.md`) from the session cwd in the system prompt | `advise!` on `system-prompt` |
| `my.usage` | token usage per session | `advise!` on `call-model` |
| `my.tools` | `ls`, `find`, `grep` tools (`rg` when on PATH, else `grep -rn`), output capped | `tool-namespaces` |
| `my.commands` | `/model` shows or switches the model, `/usage`, `/ls-here` runs `ls` as the agent | `command-namespaces`, `setq`, `call-tool` |
| `my.permissions` | ask before `write`, `edit`, `bash`; reject blocks and the model sees why; "always" remembered per tool for the session | `advise!` on `execute-tool`, `request-permission` |

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

Init files load as in ACP mode, and `oml-prompt "/doc oml.agent/run"`
runs a command. Text streams as it arrives; each tool call prints as `> name: title`
followed by `ok:` or `failed:` and the first line of the result. There is no
client to ask for permission: without a policy `bash` and `write` run
immediately, so use a scratch directory (with the `my.permissions` recipe
they are blocked instead).

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
(`agent_message_chunk`, `agent_thought_chunk`, `tool_call`,
`tool_call_update`, `available_commands_update`) and stop reasons
`end_turn`, `cancelled`, `max_turn_requests`, and the `session/cancel`
notification. A prompt that starts with `/name` of a known command runs the
command instead of calling the model (and the command list is advertised
again); an unknown `/x` goes to the model as
text. Agent-to-client requests: `session/request_permission` (and any
other method through `oml.acp/request!`). Sessions live in memory only.
`session/load` and client-side `fs/*` / `terminal/*` are later chapters.

## History

The earlier continuation-interpreter prototype (suspend/resume of an EDN
machine state) is preserved at tag `proto/continuations`; chapter 11 returns
to it.

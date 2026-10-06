<p class="lead">What the core gives Lisp code, for what an init file could not do on its own. Everything in <a href="/recipes/">Recipes</a> is built on these.</p>

## The current session

`oml.session`. `*session*` is the id of the current session: bound while a prompt runs (commands, `/eval`, tools and the loop included). Its root value is the most recently active session, so nREPL evaluations work on that one. Tools, commands and init code take no context argument: they call these functions.

```clojure
(oml.session/session)        ; => {:id "sess_..." :cwd "/project" :cancel <token or nil> ...}
(oml.session/cwd)            ; => "/project"
(oml.session/resolve-path "src/a.clj")  ; against the session cwd
(oml.session/transcript)     ; => [{:role "user" :content "..."} {:role "assistant" ...} ...]
(oml.session/append-message! {:role "user" :content "Remember: tabs."})  ; the next request sees it
(oml.session/set-transcript! compacted)                                 ; replace it (compaction)
(oml.session/say "Indexing...")  ; agent_message_chunk to the client
```

The transcript holds OpenAI-format messages without the system message, which is rebuilt for every request. The loop appends each assistant message together with its tool results, so code appending messages meanwhile never splits a `tool_calls` message from its `tool` messages.

`oml.session/on-session-start` is called with no arguments when a session starts, with it current, after its project init file is loaded. It does nothing; redefine or advise it.

## Call a tool as the agent

`oml.agent/call-tool` runs a tool through `run-tool-call`, like a model tool call: the client shows a `tool_call` card and its `tool_call_update`, and advice on `execute-tool` applies. Returns `{:content :error?}`. With `:record? true` the call is appended to the transcript as the model would have made it, so the model sees it next turn:

```clojure
(oml.agent/call-tool 'ls {:path "."} :record? true)   ; a symbol, a name or a var
;; appends
{:role "assistant" :content nil
 :tool_calls [{:id "call_..." :type "function"
               :function {:name "ls" :arguments "{\"path\":\".\"}"}}]}
{:role "tool" :tool_call_id "call_..." :content "AGENTS.md\nsrc/"}
```

## One-shot model call

`oml.agent/complete`: the configured model, no tools, nothing shown to the user; returns the text. `prompt` is a string (one user message) or a vector of messages. It goes through `call-model`, so its advice applies. Cancelling the prompt cancels it, and it throws.

```clojure
(oml.agent/complete "Name this session in three words." :system "Be terse.")
(oml.agent/complete (oml.session/transcript) :model "small/model")
```

## Requests to the client

`oml.acp/request!` sends a JSON-RPC request to the ACP client of the current session and blocks until it answers; it throws on an error response, when the client goes away, or when the prompt is cancelled. `oml.acp/request-permission` is ACP `session/request_permission` on top of it, with `oml.acp/permission-options` as the default options. The core has no permission policy; it only asks.

```clojure
(oml.acp/request-permission {:toolCallId id :rawInput args})
;; => {:outcome "selected" :optionId "allow_always" :kind "allow_always"}
;;    or {:outcome "cancelled"}
;; default options: allow_once, allow_always, reject_once, reject_always
```

Responses are read by the stdin reader thread, so `request!` refuses to run on it (in `on-session-start`, which runs there); prompts, commands, tools and nREPL are fine.

## Reasoning and raw chunks

`reasoning_content` or `reasoning` deltas are shown as `agent_thought_chunk` (loop event `:thought-delta`) and kept out of the transcript unless `oml.llm/send-reasoning?` is true. `oml.llm/on-chunk` is called with every parsed SSE chunk as it arrives and does nothing; redefine or advise it for anything else a provider sends.

## Extension points

Every step is a public, documented function; these are the ones meant to be redefined or wrapped. Each takes and returns plain data.

| Function | What it does |
|---|---|
| `oml.agent/system-prompt` | `[]` the system prompt, built for every request; wrap it to add a section |
| `oml.agent/tools`, `oml.agent/commands` | `[]` the tool and command vars, from the namespaces |
| `oml.agent/build-request` | `[messages]` the request: system prompt, transcript, tool specs |
| `oml.agent/call-model` | `[request]` one model call; returns `{:message :usage :cancelled?}`; wrap it to rewrite requests or account usage |
| `oml.agent/run-tool-call` | `[call]` one OpenAI tool call: show it, `execute-tool`, show the result |
| `oml.agent/execute-tool` | `[{:id :name :args}]` run the tool; wrap it for a permission policy (the client already shows the call) or to rewrite results |
| `oml.agent/tool-result-message` | `[call result]` the `tool` message for the transcript |
| `oml.agent/stop-reason` | `[{:turn :response}]` nil to go on, or `:end-turn`, `:cancelled`, `:max-turns` |
| `oml.agent/run` | `[]` the loop itself, on the current transcript; returns the stop reason |
| `oml.agent/parse-prompt`, `oml.agent/run-command`, `oml.agent/prompt` | how the user's text becomes a command or a turn |
| `oml.session/on-session-start` | `[]` called when a session starts; does nothing |
| `oml.llm/on-chunk` | `[chunk]` called with every raw SSE chunk; does nothing |
| `oml.acp/render-event` | `[event]` the ACP update for a loop event |
| `oml.acp/handle` | a multimethod per ACP method; `defmethod` adds one |

## Settings

Documented vars holding data; `/settings` lists them with their values, including those of user code.

| Setting | Default |
|---|---|
| `oml.llm/base-url`, `oml.llm/api-key`, `oml.llm/model` | from `OPENAI_BASE_URL` (else `https://api.openai.com/v1`), `OPENAI_API_KEY`, `OPENAI_MODEL`; the key is `^:secret` and never shown |
| `oml.llm/send-reasoning?` | `false` |
| `oml.agent/max-turns` | `30` model calls per prompt |
| `oml.agent/tool-namespaces`, `oml.agent/command-namespaces` | `[oml.tools]`, `[oml.commands]` |
| `oml.tools/bash-timeout`, `oml.tools/bash-max-chars` | `120` seconds, `50000` characters (the tail is kept) |
| `oml.tools/read-max-chars`, `oml.tools/read-default-limit` | `50000` characters, `2000` lines |
| `oml.init/load-project-init?` | `true` |
| `oml.repl/nrepl?`, `oml.repl/nrepl-host`, `oml.repl/nrepl-port` | `true`, `"127.0.0.1"`, `0` (a free port) |

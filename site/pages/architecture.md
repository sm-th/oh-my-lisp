<p class="lead">{{src-lines}} lines of Clojure in eleven namespaces, run by babashka. No agent framework: the libraries babashka bundles, and the loop.</p>

## Module map

Line counts are taken when this page is built.

| Namespace | Lines | What it does |
|---|---|---|
| `oml.llm` | {{lines src/oml/llm.clj}} | One streaming `POST /chat/completions`; SSE parsing; folds text, reasoning and tool-call deltas into one assistant message |
| `oml.agent` | {{lines src/oml/agent.clj}} | The loop as named steps; tools and commands as the functions of `tool-namespaces` and `command-namespaces`; `call-tool`, `complete` |
| `oml.tools` | {{lines src/oml/tools.clj}} | Built-in `read`, `write`, `edit`, `bash` tools |
| `oml.commands` | {{lines src/oml/commands.clj}} | `/doc`, `/apropos`, `/source` (on `clojure.repl`), `/eval`, `/reload`, `/settings`, `/tools`, `/commands` |
| `oml.custom` | {{lines src/oml/custom.clj}} | `setq`, `advise!`, `unadvise!` |
| `oml.session` | {{lines src/oml/session.clj}} | Sessions, the current one (`*session*`), its transcript, `say`, `on-session-start` |
| `oml.init` | {{lines src/oml/init.clj}} | Init files and load path |
| `oml.repl` | {{lines src/oml/repl.clj}} | nREPL server into the running agent |
| `oml.cancel` | {{lines src/oml/cancel.clj}} | Cancellation token shared by the HTTP stream, the loop and `bash` |
| `oml.acp` | {{lines src/oml/acp.clj}} | ACP agent: JSON-RPC over stdio, one multimethod per method, `session/update` rendering, `request!` and `request-permission` to the client |
| `oml.print-mode` | {{lines src/oml/print_mode.clj}} | One prompt, output to stdout, no UI |

The tests add an in-process fake OpenAI server replaying scripted SSE (`test/oml/fake_llm.clj`) and a scripted ACP client that answers the agent's requests (`test/oml/acp_client.clj`).

## One turn

The client sends `session/prompt`. It runs on its own thread, so the stdin reader stays free for `session/cancel` and for the client's answers to `request!`.

```text
oml.acp/handle "session/prompt"     new cancel token; *session* bound
  oml.agent/prompt                  the user's text
    parse-prompt                    "/name ..." of a known command?
    run-command                     yes: run it, show its output; done
    append-message!                 no: the user message, then
    run                             the loop, until stop-reason says stop:
      build-request                 system-prompt + transcript + tool specs
      call-model                    oml.llm/stream-chat over SSE
        session/emit!               deltas -> oml.acp/render-event -> session/update
      run-tool-call   (each call)   tool_call card, later tool_call_update
        execute-tool                the tool function, or advice around it
      tool-result-message           the tool message for the transcript
      append-message!               assistant message + its tool results
      stop-reason                   nil | :end-turn | :cancelled | :max-turns
```

The response carries the stop reason: `end_turn`, `cancelled` or `max_turn_requests`. Every name in the tree is a var the caller goes through, so each step can be redefined or advised.

## Sessions

A session is a map in the `oml.session/sessions` atom, one per ACP `session/new`: `{:id :cwd :transcript :cancel :on-event ...}`. `:transcript` is a vector of OpenAI-format messages without the system message; whatever user code appends or replaces is what the next request sees. `:cancel` is the token of the running prompt, nil when idle. `:on-event` shows loop events to the user; the frontend (ACP or print mode) provides it.

Sessions live in memory only. They are not persisted, and `session/load` is not implemented; that is [chapter 5](/chapters/).

## Limits

- ACP surface: `initialize` (protocol version 1, no auth, text prompts only), `session/new`, `session/prompt` with `agent_message_chunk`, `agent_thought_chunk`, `tool_call`, `tool_call_update` and `available_commands_update`, and `session/cancel`. Agent to client: `session/request_permission`, and any other method through `oml.acp/request!`.
- Not yet: `session/load`, the client-side `fs/*` and `terminal/*` methods, images and audio in prompts.
- One prompt at a time per session; a second one is rejected while the first runs.
- No permission policy in the core: tools run unless user code (the [permissions recipe](/recipes/#permissions)) blocks them.
- No compaction yet; a long session grows until the endpoint rejects it.
- A project's `.oml/init.clj` runs arbitrary code when a client opens that directory (`oml.init/load-project-init?` turns it off), and the nREPL server runs code for anyone who can reach it on loopback (`oml.repl/nrepl?` turns it off).

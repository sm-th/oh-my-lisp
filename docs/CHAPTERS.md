# Chapters

The harness is built one component at a time. Each chapter names the
[pi](https://github.com/earendil-works/pi) code to read alongside it (paths
relative to its `packages/` directory). Customisation is
the core of the project, so it comes right after the first working loop,
and every later chapter is built as plain functions that can be redefined
or wrapped.

| # | Chapter | Status |
|---|---|---|
| 1 | Model call: streaming Chat Completions, SSE | done |
| 2 | Agent loop | done |
| 3 | Tools: read, write, edit, bash | done |
| 4 | Customisation the Lisp way: named functions, tools and commands by namespace, `init.clj`, live REPL | done |
| 5 | Sessions: persisting the transcript, resume, fork | planned |
| 6 | Context: system prompt assembly, compaction | planned |
| 7 | Control: cancel, steer, follow-up queue, permissions | cancel, permission primitive done |
| 8 | UI: ACP agent mode; own TUI later (charm.clj), optional | ACP done (basic) |
| 9 | Agent with only `eval` in a SCI sandbox | planned |
| 10 | Self-modification under owner-approved goals and invariants | planned |
| 11 | Durability: survive crash/sleep and resume | planned |
| 12 | Presentations: output as typed objects | planned |

## Primitives in the core, the rest in user code

The core only grows primitives: what code in `init.clj` could not do
because the core does not expose it. These exist now
([Primitives](https://oml.sh/primitives/)): the current session and its transcript (`oml.session`),
`call-tool`, `say`, `complete`, agent-to-client `request!` and
`request-permission`, the reasoning stream (`agent_thought_chunk`) and
`oml.llm/on-chunk`, called with every raw chunk.

Moved to user code, as recipes in `examples/lisp/my/` with end-to-end
tests (`test/oml/examples_test.clj`):

- project context (`AGENTS.md` / `CLAUDE.md` in the system prompt; part of chapter 6)
- token usage per session and `/usage`
- search tools `ls`, `find`, `grep`
- model switch `/model`
- the permission policy (part of chapter 7); the core only asks

Still core, later: session persistence and `session/load` (chapter 5),
the follow-up and steering queues (chapter 7), and compaction (chapter 6),
which will use `complete` and `set-transcript!`.

## 1. Model call (streaming, SSE) - done

`src/oml/llm.clj`. One `POST {base}/chat/completions` with `stream: true`;
`data:` lines are folded into one assistant message (text deltas, tool-call
argument fragments keyed by `index`), with `:text-delta`, `:tool-call` and
`:done` events. Cancelling closes the response stream.

Compare with in pi: `ai/src/api/openai-completions.ts` (same API), `ai/src/utils/event-stream.ts` (event stream type).

## 2. Agent loop - done

`src/oml/agent.clj`. Call the model; run every tool call; append one `tool`
message per call; repeat until a reply has no tool calls, the turn is
cancelled, or `max-turns` (30) model calls have been made. The model client
is a parameter, so tests drive the loop with a scripted fake.

Compare with in pi: `agent/src/agent-loop.ts` (the loop), `agent/src/agent.ts` (state and queues around it).

## 3. Tools: read, write, edit, bash - done

`src/oml/tools.clj`. A tool is a documented function of the argument map;
its name, docstring and destructuring (plus optional types in the attr-map)
are what the model sees. `edit` demands a unique exact match; `bash` merges
stdout/stderr, keeps the tail, and kills the process tree on timeout or
cancel. Errors go back to the model as tool results.

Compare with in pi: `coding-agent/src/core/tools/read.ts`, `write.ts`, `edit.ts`, `bash.ts`, `truncate.ts`.

## 4. Customisation the Lisp way - done

One mechanism: named functions the core calls through their vars. Change
behaviour by redefining one (`defn`) or wrapping it (`advise!`, keyed and
removable, `src/oml/custom.clj`); there are no hook variables and no
registration API. The loop is split into documented steps
(`src/oml/agent.clj`); each ACP method is a multimethod. Settings are
documented vars, set with `setq`. Tools and slash commands are the
documented public functions of the namespaces in `tool-namespaces` and
`command-namespaces` (`src/oml/tools.clj`, `src/oml/commands.clj`); a tool's
JSON schema comes from its argument destructuring. Context comes from one
dynamic var, `oml.session/*session*`. Introspection reuses `clojure.repl`
(`/doc`, `/apropos`, `/source`), plus `/eval` and `/reload`. Configuration
is a program: `~/.config/oml/init.clj`, then `<project>/.oml/init.clj`,
with `lisp/` dirs on the classpath; an nREPL server gives a live REPL into
the running agent; commands are advertised to the ACP client
(`available_commands_update`). See [Customising](https://oml.sh/customising/).

Compare with in pi: `coding-agent/src/core/extensions/` (loader, runner, types: the registry approach this chapter deliberately avoids), `coding-agent/src/core/slash-commands.ts`.

## 5. Sessions: persisting transcript, resume, fork

Write the transcript as it grows, reload it (ACP `session/load`), branch from
any earlier message.

Compare with in pi: `coding-agent/src/core/session-manager.ts` (append-only JSONL tree with forks).

## 6. Context: system prompt assembly, compaction

Build the system prompt from project files (AGENTS.md etc.) and tool list;
summarise old turns when the context window fills up. Project files are a
recipe, advice on `system-prompt` (`examples/lisp/my/context.clj`); compaction remains, on top of
`complete` and `oml.session/set-transcript!`.

Compare with in pi: `coding-agent/src/core/system-prompt.ts`, `coding-agent/src/core/compaction/compaction.ts`.

## 7. Control: cancel, steer, follow-up queue, permissions

Done: `session/cancel` aborts the HTTP stream and kills a running `bash`
(`src/oml/cancel.clj`). `oml.acp/request-permission` asks the client
(ACP `session/request_permission`); the policy that asks before `write`,
`edit` and `bash` is user code, advice on `execute-tool`
(`examples/lisp/my/permissions.clj`). Next:
steering messages injected mid-turn and a follow-up queue.

Compare with in pi: `agent/src/agent.ts` (steering and follow-up queues), `coding-agent/examples/extensions/permission-gate.ts` (permissions as an extension).

## 8. UI: ACP agent mode; own TUI later

Done (basic): `src/oml/acp.clj`, `initialize`, `session/new`,
`session/prompt` with `agent_message_chunk` / `agent_thought_chunk` /
`tool_call` / `tool_call_update`, `session/cancel`, and agent-to-client
requests (`request!`, `session/request_permission`). Optional later: a TUI with charm.clj.

Compare with in pi: `coding-agent/src/modes/rpc/rpc-mode.ts` (pi's own stdio JSON protocol), `coding-agent/src/modes/interactive/interactive-mode.ts` and `tui/src` (its TUI).

## 9. Agent with only `eval` in a SCI sandbox

Replace the fixed tools with a single `eval` tool running Clojure in SCI,
where only a granted vocabulary of functions is visible.

Compare with in pi: `codemode/` (model-written JavaScript in a QuickJS sandbox whose only capability is calling injected tools).

## 10. Self-modification under owner-approved goals and invariants

The agent may change its own code by redefining functions (the same
mechanism as chapter 4), but only within executable goals and invariants the
owner approved; every change is a revision that can be rolled back, as in
Jiti and Autolith (both Common Lisp).

Compare with in pi: `coding-agent/src/core/extensions/jiti-loader.ts` (hot-loading TypeScript extensions; unrelated to the Common Lisp project also called Jiti), `coding-agent/examples/extensions/reload-runtime.ts`.

## 11. Durability: survive crash/sleep and resume

Commit each step before acting on it so a turn can resume after a crash or a
laptop sleep.

Compare with in pi: `durable/` (pi-durable: turns and tool calls committed to storage before they are shown, resume after process death).

## 12. Presentations: output as typed objects

Tool and agent output as typed objects the UI can present and act on
(Genera/CLIM presentations), rather than plain text.

Compare with in pi: `coding-agent/src/modes/interactive/components/` (per-message/tool renderers), `coding-agent/examples/extensions/message-renderer.ts`.

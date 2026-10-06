# Chapters

The harness is built one component at a time. Each chapter names the pi code
to read alongside it (paths relative to `~/pi/packages`).

| # | Chapter | Status |
|---|---|---|
| 1 | Model call: streaming Chat Completions, SSE | done |
| 2 | Agent loop | done |
| 3 | Tools: read, write, edit, bash | done |
| 4 | Sessions: persisting the transcript, resume, fork | planned |
| 5 | Context: system prompt assembly, compaction | planned |
| 6 | Control: cancel, steer, follow-up queue, permissions | cancel done (basic) |
| 7 | UI: ACP agent mode; own TUI later (charm.clj), optional | ACP done (basic) |
| 8 | Extensions and hooks | planned |
| 9 | Agent with only `eval` in a SCI sandbox | planned |
| 10 | Self-modification under owner-approved goals and invariants | planned |
| 11 | Durability: survive crash/sleep and resume | planned |
| 12 | Presentations: output as typed objects | planned |

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

`src/oml/tools.clj`. Tools are data (name, description, JSON schema, ACP kind,
title fn) plus `execute`. `edit` demands a unique exact match; `bash` merges
stdout/stderr, keeps the tail, and kills the process tree on timeout or
cancel. Errors go back to the model as tool results.

Compare with in pi: `coding-agent/src/core/tools/read.ts`, `write.ts`, `edit.ts`, `bash.ts`, `truncate.ts`.

## 4. Sessions: persisting transcript, resume, fork

Write the transcript as it grows, reload it (ACP `session/load`), branch from
any earlier message.

Compare with in pi: `coding-agent/src/core/session-manager.ts` (append-only JSONL tree with forks).

## 5. Context: system prompt assembly, compaction

Build the system prompt from project files (AGENTS.md etc.) and tool list;
summarise old turns when the context window fills up.

Compare with in pi: `coding-agent/src/core/system-prompt.ts`, `coding-agent/src/core/compaction/compaction.ts`.

## 6. Control: cancel, steer, follow-up queue, permissions

Done: `session/cancel` aborts the HTTP stream and kills a running `bash`
(`src/oml/cancel.clj`). Next: steering messages injected mid-turn, a
follow-up queue, and ACP `session/request_permission` before `write`, `edit`
and `bash`.

Compare with in pi: `agent/src/agent.ts` (steering and follow-up queues), `coding-agent/examples/extensions/permission-gate.ts` (permissions as an extension).

## 7. UI: ACP agent mode; own TUI later

Done (basic): `src/oml/acp.clj`, `initialize`, `session/new`,
`session/prompt` with `agent_message_chunk` / `tool_call` /
`tool_call_update`, `session/cancel`. Optional later: a TUI with charm.clj.

Compare with in pi: `coding-agent/src/modes/rpc/rpc-mode.ts` (pi's own stdio JSON protocol), `coding-agent/src/modes/interactive/interactive-mode.ts` and `tui/src` (its TUI).

## 8. Extensions and hooks

Genera-style commands: one typed definition used by the human (slash
command), the agent (tool) and the UI (menu), plus hooks around tool calls
and turns.

Compare with in pi: `coding-agent/src/core/extensions/` (loader, runner, types), `coding-agent/src/core/slash-commands.ts`.

## 9. Agent with only `eval` in a SCI sandbox

Replace the fixed tools with a single `eval` tool running Clojure in SCI,
where only a granted vocabulary of functions is visible.

Compare with in pi: `codemode/` (model-written JavaScript in a QuickJS sandbox whose only capability is calling injected tools).

## 10. Self-modification under owner-approved goals and invariants

The agent may change its own code (Jiti-style hot reload), but only within
executable goals and invariants the owner approved; every change is a
revision that can be rolled back.

Compare with in pi: `coding-agent/src/core/extensions/jiti-loader.ts` (hot-loading TypeScript extensions), `coding-agent/examples/extensions/reload-runtime.ts`.

## 11. Durability: survive crash/sleep and resume

Commit each step before acting on it so a turn can resume after a crash or a
laptop sleep. The continuation-interpreter prototype is tagged
`proto/continuations`.

Compare with in pi: `durable/` (pi-durable: turns and tool calls committed to storage before they are shown, resume after process death).

## 12. Presentations: output as typed objects

Tool and agent output as typed objects the UI can present and act on
(Genera/CLIM presentations), rather than plain text.

Compare with in pi: `coding-agent/src/modes/interactive/components/` (per-message/tool renderers), `coding-agent/examples/extensions/message-renderer.ts`.

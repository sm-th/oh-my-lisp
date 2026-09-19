# oml

oml is a programmable Lisp runtime with a built-in REPL. Executable configuration extends that runtime and may start additional concurrent services; deployment and accessible resources come from the host environment.

## Language

**oml**:
A programmable Lisp runtime whose behavior is defined by loaded Lisp code. It is not an agent harness, deployment system, or multi-agent orchestrator.
_Avoid_: agent, agent network, workspace manager

**Kernel**:
The minimal oml runtime: Lisp evaluation, executable configuration loading, grants, and the default REPL. Agent harnesses, communication channels, repository workflows, and fleet management are not kernel responsibilities.
_Avoid_: full distribution, standard library

**Supported runtime**:
JVM Clojure is the only runtime in the first-version roadmap. Babashka support is deferred and carries no compatibility commitment.
_Avoid_: dual-runtime support

**Direct eval**:
Lisp input is evaluated as written, without interpretation or rewriting by a language model.
_Avoid_: agent-mediated eval

**ACP**:
The [Agent Client Protocol](https://agentclientprotocol.com/): the local stdio JSON-RPC boundary between oml and an external coding agent. oml is a thin client over the official ACP Java SDK; the SDK owns framing, request IDs, process mechanics, and the initialize handshake.
_Avoid_: custom ACP implementation, wire-level re-implementation

**Glue layer**:
The part of oml that decides when work is needed, selects configured behavior, sends an ACP prompt, consumes updates and the final outcome, and continues the Lisp application. It does not embed or recreate the coding-agent loop.
_Avoid_: agent harness, agent wrapper

**Two-loop model**:

1. **Lisp application loop:** decides when work is needed, selects configured behavior, sends an ACP prompt, consumes updates and the final outcome, and continues the application.
2. **Coding-agent loop:** an external full coding agent owns model interaction, context management, tool selection and iteration, plus its native file, shell, git, pull-request, and skills capabilities.

ACP is the boundary between the loops. The Lisp loop invokes a turn; the agent decides how to perform that turn.
_Avoid_: single loop, built-in agent loop

**Agent harness**:
An external coding agent invoked by oml through ACP. The harness is not part of oml; oml only owns the local subprocess lifetime and the ACP client handshake.
_Avoid_: OMP, built-in agent, harness registry

**ACP client**:
The thin Clojure wrapper over the official ACP Java SDK (`com.agentclientprotocol:acp-core`). It connects to a configured local agent command, negotiates v1, exposes negotiated capabilities, and closes the connection idempotently.
_Avoid_: custom transport, custom JSON-RPC

**Bundled agent library**:
The non-kernel library shipped with oml that exposes the ACP client and related glue-layer helpers. It does not embed agent model logic or native tool implementations.
_Avoid_: kernel primitive, OMP adapter

**External library**:
An optional user-selected library, resolved through standard Clojure dependencies and loaded with `require`, that adds application-specific behavior.
_Avoid_: kernel module, oml plugin

**Lisp input**:
In the default REPL, input beginning with `(` or explicitly marked `/eval`; it is sent to direct eval rather than natural-language handling. Custom configuration may define other input behavior.
_Avoid_: inferred code

**Input channel**:
An external source of requests consumed by behavior defined in configuration.
_Avoid_: built-in transport

**Configuration**:
Executable Lisp code loaded to extend an oml alongside its built-in REPL, including input handling, agent invocation, grants, and long-running services.
_Avoid_: settings file, replacement main program

**Grant**:
The authority exposed through an eval surface. Configuration can define separate grants for the user and an agent; by default the user has full authority and the agent is restricted. Grant design for agent-mediated eval is deferred and not a prerequisite for the ACP lifecycle.
_Avoid_: prompt instruction, native tool policy

**oml instance**:
One running oml process together with the agent harnesses it invokes. Isolation between instances and coordination of a fleet belong to deployment and configuration, not to oml.
_Avoid_: sandbox, fleet manager

**Agent session**:
An explicit Lisp value referring to an ACP session owned by an external agent. Configuration code owns its lifecycle and decides which requests share it.
_Avoid_: global current session, hidden conversation

**Session key**:
A stable key chosen or derived by configuration code to associate an external conversation with an agent session. Persistence and translation to a harness-native session identifier are not oml core policy.
_Avoid_: display name

**Agent run**:
An explicit Lisp value representing one ACP prompt turn in an agent session. Configuration code decides whether to await, observe, cancel, or run it alongside invocations in other sessions.
_Avoid_: implicit background job

## Explicit exclusions

The ACP client stage covered by issue #35 intentionally excludes:

- `session/new`, `session/prompt`, `session/update` events, and the full turn lifecycle.
- Permissions (`session/request_permission`), permission callbacks, and grants.
- Turn cancellation (`session/cancel`) and pending-permission rejections.
- `session/close` capability-gated close.
- MCP injection, Lisp Eval exposure, and grant authorization.
- Agent discovery, catalog/config generation, and startup/REPL integration.
- Remote transports, providers, authentication, session persistence.

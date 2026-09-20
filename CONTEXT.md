# oml

oml is a living Lisp image: a persistent runtime whose definitions, data, and
accumulated state are defined, run, saved, and rewritten in place, and survive
restarts. Everything beyond the minimal kernel — reaching agents, bounding the
eval surface, orchestration — is a layer expressed in Lisp on top of it.

## Language

**oml**:
A living Lisp image driven in natural language or Lisp: a persistent runtime
whose behavior — and the behavior it accumulates — is defined, run, saved, and
rewritten in place.
_Avoid_: personal tool, agent, orchestrator, framework

### Kernel

**Kernel**:
The minimal living-image substrate through which everything else is expressed:
the eval surface, file-based persistence, a recovery REPL that survives a broken
configuration, and configuration-as-program loading. Reaching an agent and
bounding the eval surface are layers, not the kernel.
_Avoid_: standard library, orchestrator, full distribution

**Living image**:
A running oml process whose definitions, data, and accumulated state survive
restarts and can be inspected and rewritten while it runs.
_Avoid_: session, REPL process, program instance

**Eval surface**:
The single way any actor — a human, loaded configuration, or an external input —
affects the image: evaluating Lisp forms in the shared, mutable runtime
namespace.
_Avoid_: interpreter, sandbox

**Direct eval**:
Lisp evaluated as written, without interpretation or rewriting by a language
model.
_Avoid_: agent-mediated eval

**Persistence**:
Durability by explicit files: definitions and data are saved as readable Lisp or
EDN and re-evaluated at startup. Nothing survives a restart unless it was
explicitly saved.
_Avoid_: heap dump, automatic journal, snapshot of the running image

**Recovery mode**:
The base REPL the kernel brings up before configuration loads and keeps
available when configuration or loaded code fails, so a human or agent can
repair the image from within it.
_Avoid_: safe mode, crash handler

**Configuration**:
Executable Lisp loaded at startup to build a ready image; importable or otherwise
evaluated. It extends the image and never replaces the built-in REPL.
_Avoid_: settings file, replacement main program

**Supported runtime**:
JVM Clojure.
_Avoid_: dual-runtime support, babashka

**Lisp input**:
In the default REPL, input beginning with `(` or explicitly marked `/eval`; it is
sent to direct eval rather than natural-language handling.
_Avoid_: inferred code

**oml instance**:
One running oml process. Isolation between instances and coordination of several
belong to deployment and layers, not to the kernel.
_Avoid_: sandbox, fleet manager

### Layers

**Layer**:
Behavior expressed as Lisp loaded onto the kernel — reaching agents, grants,
orchestration, catalogs, applications, domain skills. A layer is not part of the
kernel and is never named by it.
_Avoid_: plugin, kernel module, core feature

**Grant**:
A layer that bounds the eval surface for a less-trusted actor: a deny-by-default
vocabulary exposing exactly what that actor may call. The kernel eval surface is
full-trust; a grant applies only where eval is exposed to an agent or external
input.
_Avoid_: prompt instruction, kernel primitive, native tool policy

**ACP**:
The [Agent Client Protocol](https://agentclientprotocol.com/): the local stdio
JSON-RPC boundary between oml and an external coding agent. Used by the
agent-reach layer, not the kernel.
_Avoid_: custom ACP implementation, wire-level re-implementation

**ACP client**:
The thin Clojure wrapper over the official ACP Java SDK
(`com.agentclientprotocol:acp-core`), shipped as a bundled layer. It connects to
a configured local agent command, negotiates v1, exposes negotiated
capabilities, and closes the connection idempotently.
_Avoid_: kernel primitive, custom transport

**Agent harness**:
An external coding agent invoked through ACP. The harness is not part of oml; the
ACP-client layer owns only the local subprocess lifetime and handshake.
_Avoid_: OMP, built-in agent, harness registry

**Bundled agent library**:
The layer shipped in the oml repository that exposes the ACP client and related
glue helpers. It embeds no agent model logic or native tool implementations.
_Avoid_: kernel primitive, OMP adapter

**Glue layer**:
The layer that decides when work is needed, selects configured behavior, sends an
ACP prompt, consumes updates and the final outcome, and continues the Lisp
application. It does not embed or recreate the coding-agent loop.
_Avoid_: agent harness, agent wrapper

**Two-loop model**:
The split between the Lisp application loop (invokes a turn) and the external
coding-agent loop (performs the turn, owning model interaction, context, tools,
and its native capabilities). ACP is the boundary between them.
_Avoid_: single loop, built-in agent loop

**Agent session**:
An explicit Lisp value referring to an ACP session owned by an external agent.
Layer code owns its lifecycle and decides which requests share it.
_Avoid_: global current session, hidden conversation

**Session key**:
A stable key chosen by layer code to associate an external conversation with an
agent session.
_Avoid_: display name

**Agent run**:
An explicit Lisp value representing one ACP prompt turn in an agent session.
Layer code decides whether to await, observe, cancel, or run it alongside others.
_Avoid_: implicit background job

**Input channel**:
An external source of requests consumed by behavior defined in a layer.
_Avoid_: built-in transport

**External library**:
An optional user-selected library, resolved through standard Clojure
dependencies and loaded with `require`, that adds application-specific behavior.
_Avoid_: kernel module, oml plugin

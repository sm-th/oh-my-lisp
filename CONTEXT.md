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

**Agent harness**:
An external agent runner invoked by oml. OMP is the sole harness supported in the first version; the concept is not defined as OMP-specific so another harness can be added later.
_Avoid_: oml, built-in agent

**Bundled agent library**:
The non-kernel library shipped with oml that exposes the agent API and the OMP adapter in the first version.
_Avoid_: kernel primitive, harness registry

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
The authority exposed through an eval surface. Configuration can define separate grants for the user and an agent; by default the user has full authority and the agent is restricted.
_Avoid_: prompt instruction

**Native tool policy**:
The subset of an agent harness's own tools made available for one invocation. An enabled tool carries the ambient authority of the oml instance's host environment.
_Avoid_: all-or-nothing tools, sandbox

**Default agent profile**:
The fallback authority for an agent invocation: native read, search, and edit tools plus restricted eval introspection, without an agent-controlled shell or network tool.
_Avoid_: full harness defaults

**oml instance**:
One running oml process together with the agent harnesses it invokes. Isolation between instances and coordination of a fleet belong to deployment and configuration, not to oml.
_Avoid_: sandbox, fleet manager

**Agent session**:
An explicit Lisp value referring to a conversation owned by an agent harness. Configuration code owns its lifecycle and decides which requests share it.
_Avoid_: global current session, hidden conversation

**Session key**:
A stable key chosen or derived by configuration code to associate an external conversation with an agent session. Persistence and translation to a harness-native session identifier are not oml core policy.
_Avoid_: display name

**Agent run**:
An explicit Lisp value representing one invocation in an agent session. Configuration code decides whether to await, observe, cancel, or run it alongside invocations in other sessions.
_Avoid_: implicit background job

**Agent queue**:
The ordering mechanism owned by an agent harness session. Messages sent while work is active are delegated to the harness's native queue when available, rather than scheduled by oml.
_Avoid_: oml work queue

<p class="lead">oml takes Emacs' principle, not its API: a live image where everything is a named function, plus introspection. There is no plugin, hook or registration API.</p>

## One mechanism

The core calls every step through its var. Whatever you redefine or wrap, from an init file, `/eval` or a REPL connected to the running agent, is used from the next call on.

- **Redefine** a function with `defn`:

  ```clojure
  (in-ns 'oml.agent)
  (defn system-prompt [] "Be terse.")
  ```

- **Wrap** it with `oml.custom/advise!`. The advice is called as `(f original & args)`. It is named by a key: advising again with the same key replaces it instead of stacking, so reloading code that advises is safe. `unadvise!` removes it; redefining the function with `defn` drops its advice.

  ```clojure
  (require '[oml.custom :refer [advise! unadvise!]])

  (advise! #'oml.agent/call-model ::log
           (fn [call-model request]
             (binding [*out* *err*]
               (println "model call:" (count (:messages request)) "messages"))
             (call-model request)))

  (unadvise! #'oml.agent/call-model ::log)
  ```

- **Set** a setting with `oml.custom/setq` (`alter-var-root` by name). Settings are documented vars:

  ```clojure
  (setq oml.agent/max-turns 50
        oml.llm/model "gpt-4o-mini")
  ```

- **Remove** a tool or command with `ns-unmap`: `(ns-unmap 'oml.tools 'bash)` removes the `bash` tool.

Which functions are meant to be redefined or wrapped, and every setting, is listed under [Primitives](/primitives/#extension-points).

## Tools and commands are functions in namespaces

`oml.agent/tool-namespaces` (default `[oml.tools]`) and `oml.agent/command-namespaces` (default `[oml.commands]`) name namespaces; every public function with a docstring in them is a tool or a command, named after the function (on a clash the later namespace wins). Add yours by defining functions in your own namespace and listing it, or by defining them in `oml.tools` directly; use `defn-` for helpers.

### Tools

A tool takes the argument map the model sent and returns a string, or throws `ex-info` with a message the model should read. Context comes from `oml.session`: `(cwd)`, `(resolve-path p)`, `(session)` (with its `:cancel` token), `(transcript)`, `(say ...)`.

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

The parameters come from the destructuring: each `:keys` entry is a property, a required string unless `:params` says otherwise (`[type description & flags]`; `:optional`, or an `:or` default, makes it optional). The model sees:

```json
{"name": "word-count", "description": "Count the words in a text file.",
 "parameters": {"type": "object",
                "properties": {"path": {"type": "string", "description": "File path"},
                               "limit": {"type": "integer", "description": "Stop counting here"}},
                "required": ["path"]}}
```

The attr-map is optional: a plain `defn` redefinition keeps the function a tool (with string parameters), `ns-unmap` removes it, and dropping the namespace from the vector removes all of them.

### Commands

A command takes the text after `/name`; what it prints, then what it returns, is shown as an agent message. The first sentence of its docstring and an optional `:hint` in its attr-map are advertised to the client, after `session/new` and after every command. A prompt that starts with `/name` of a known command runs the command instead of calling the model; an unknown `/x` goes to the model as text.

```clojure
(defn today "Show today's date." [_] (str (java.time.LocalDate/now)))
```

### Built-in commands

Introspection on top of `clojure.repl`, plus `/eval` and `/reload`:

{{commands}}

## Init files and load path

Configuration is a program, like `init.el`:

| When | Load path (on the classpath) | Init file |
|---|---|---|
| startup | `$XDG_CONFIG_HOME/oml/lisp/` | `$XDG_CONFIG_HOME/oml/init.clj` |
| a session starts | `<cwd>/.oml/lisp/` | `<cwd>/.oml/init.clj` |

`XDG_CONFIG_HOME` defaults to `~/.config`. At startup oml first loads the tool and command namespaces, so init code can redefine what is in them. When a session starts it loads the project files, then calls `oml.session/on-session-start`. Init files are evaluated in the `user` namespace; `/reload` loads both again, so they should be safe to repeat (`setq`, `defn` and keyed `advise!` are). An error in an init file is logged to stderr and shown in the session as a warning; the agent keeps working.

A project init file runs arbitrary code with your permissions as soon as a client opens that directory. If you open projects you do not trust, put `(setq oml.init/load-project-init? false)` in your user init file.

[`examples/init.clj`](https://github.com/sm-th/oh-my-lisp/blob/main/examples/init.clj) sets settings, requires modules from the load path, lists them in the tool and command namespaces, redefines a step with `defn` and logs model calls with `advise!`:

{{include examples/init.clj 9-47}}

## A live REPL into the agent

In ACP mode oml starts an nREPL server on `127.0.0.1` and a free port (settings `oml.repl/nrepl?`, `oml.repl/nrepl-host`, `oml.repl/nrepl-port`), logs `nREPL on 127.0.0.1:<port>` to stderr and writes the port to `$XDG_STATE_HOME/oml/nrepl-port` (default `~/.local/state/oml/nrepl-port`). It evaluates in the same image that serves the client: a `defn` there changes the next prompt.

- CIDER: `M-x cider-connect-clj`, host `127.0.0.1`, port from the file. Or:

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

- Calva: "Connect to a running REPL server, not in your project", project type babashka, `127.0.0.1:<port>`.
- Any other nREPL client (Conjure, `rep`, ...): host `127.0.0.1`, the port in the file.

`oml.session/*session*` is bound while a prompt runs; its root value is the most recently active session, so REPL evaluations work on that one. Anyone who can connect can run code as you, which is why it binds to loopback only; set `oml.repl/nrepl?` to false to turn it off.

## If you know Emacs

Clojure already has most of what Emacs adds on top:

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

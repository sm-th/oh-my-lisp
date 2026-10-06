<p class="lead">Features other agents build into their core are user code here: short modules on top of the <a href="/primitives/">primitives</a>.</p>

Each module lives in [`examples/lisp/my/`](https://github.com/sm-th/oh-my-lisp/tree/main/examples/lisp/my), is loaded by [`examples/init.clj`](https://github.com/sm-th/oh-my-lisp/blob/main/examples/init.clj), and runs end to end in `test/oml/examples_test.clj`. To use them, copy `examples/init.clj` to `~/.config/oml/init.clj` and `examples/lisp/` to `~/.config/oml/lisp/`.

| Module | What | Built on |
|---|---|---|
| `my.context` | `AGENTS.md` (or `CLAUDE.md`) from the session cwd in the system prompt | `advise!` on `system-prompt` |
| `my.usage` | token usage per session | `advise!` on `call-model` |
| `my.tools` | `ls`, `find`, `grep` tools (`rg` when on PATH, else `grep -rn`), output capped | `tool-namespaces` |
| `my.commands` | `/model` shows or switches the model, `/usage`, `/ls-here` runs `ls` as the agent | `command-namespaces`, `setq`, `call-tool` |
| `my.permissions` | ask before `write`, `edit`, `bash`; a rejection blocks the call and the model sees why; "always" is remembered per tool for the session | `advise!` on `execute-tool`, `request-permission` |

## Project context

Advice on `oml.agent/system-prompt` appends the project's instructions, read from the session's directory on every request:

{{include examples/lisp/my/context.clj 9-18}}

## Token usage

Advice on `oml.agent/call-model` adds up the usage the endpoint reports, per session:

{{include examples/lisp/my/usage.clj 8-17}}

## Search tools

`ls`, `find` and `grep` are documented public functions of `my.tools`; listing the namespace in `oml.agent/tool-namespaces` makes them tools. `ls`, for example:

{{include examples/lisp/my/tools.clj 21-30}}

## Commands

`/model` switches the model with `setq`; `/ls-here` calls a tool as the agent, so the client shows the call and the model sees it on the next turn:

{{include examples/lisp/my/commands.clj 12-33}}

## Permissions

Advice on `oml.agent/execute-tool` asks the client before `write`, `edit` and `bash`. The client already shows the call; a rejection becomes an error result the model reads. The answer comes from `oml.acp/request-permission`:

{{include examples/lisp/my/permissions.clj 11-37}}

## Replacing the loop

`oml.agent/prompt` calls `oml.agent/run`, the loop, through its var, so a different control flow is one `advise!` (or `defn`) away. This **sketch** wraps the built-in loop in plan → execute → review: one tool-less call writes a plan, the loop carries it out, another call reviews the result and sends the loop back with what is missing, at most twice. It is not in `examples/` and not covered by the test suite; it has only been run against the tests' scripted fake model.

```clojure
(ns my.graph
  "Sketch: plan -> execute -> review around the built-in loop."
  (:require [clojure.string :as str]
            [oml.agent :as agent]
            [oml.custom :refer [advise!]]
            [oml.session :as session]))

(def max-reviews "Review rounds before giving up." 2)

(defn- ask [instruction]
  (agent/complete (session/transcript) :system instruction))

(advise! #'agent/run ::plan-execute-review
  (fn [run]
    (let [plan (ask "Write a short numbered plan for the user's last request. No code.")]
      (session/say "Plan:\n" plan "\n\n")
      (session/append-message! {:role "assistant" :content plan}
                               {:role "user" :content "Carry out the plan."})
      (loop [round 0]
        (let [reason (run)]
          (if (or (not= :end-turn reason) (= round max-reviews))
            reason
            (let [review (ask "Review the work above against the plan. Answer DONE if it is complete, else list what is missing.")]
              (if (str/starts-with? (str/trim review) "DONE")
                reason
                (do (session/say "\n\nReview:\n" review "\n\n")
                    (session/append-message! {:role "user" :content (str "Not done yet:\n" review)})
                    (recur (inc round)))))))))))
```

Each node is an ordinary function call, so a node can use another model (`complete` takes `:model`) or be a graph of its own. `(unadvise! #'oml.agent/run :my.graph/plan-execute-review)` restores the plain loop.

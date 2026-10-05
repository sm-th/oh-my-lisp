# oml

A babashka-based Lisp agent runtime where **a running process is data**.

A process is ordinary Clojure source, interpreted by a small-step machine.
The machine's entire state, including the call stack (`:kont`), is EDN. A
process can stop at an await point (`ask`, `llm`, `tool`), be written to a
file, and be continued by a later OS process.

## Usage

```sh
bb demo start            # run until the first `ask`, save .oml/state.edn, exit
bb demo answer yes       # load, resume with "yes", run to next `ask` or done
bb demo show             # pretty-print the saved state
bb demo boundary         # show the boundary-rule error
bb test
```

The demo process (`oml.demo/program`):

```clojure
(let [plan ["step-1" "step-2" "step-3"]]
  (loop [ps plan acc []]
    (if (empty? ps)
      (do (println "done" acc) acc)
      (let [answer (ask "proceed with?" (first ps))]
        (recur (rest ps) (conj acc [(first ps) answer]))))))
```

## API

`oml.machine` is pure:

- `(start form)` returns the initial state.
- `(step state)` performs one transition.
- `(run state)` / `(run state {:max-steps n})` steps until `:suspended`,
  `:done` or `:error`.
- `(resume state value)` makes `value` the result of the pending effect and
  runs on.

`oml.store` handles persistence: `(save! path state)` and `(load path)`.
`save!` refuses to write a state that does not read back equal.

## Design

- **Source.** Forms are macroexpanded (`oml.expand`) down to the special
  forms `quote if do let* loop* recur fn* throw`. Other special forms
  (`def`, `try`, `new`, `.`, `letfn*`, ...) are rejected. `doseq` and
  `dotimes` are replaced by process-language macros that expand to `loop*`,
  because the clojure.core versions expand to chunked-seq interop. `for` is
  unsupported because it expands to `(new clojure.lang.LazySeq ...)`.
- **State.** `{:status :control :kont :await :value :error :steps}`. `:kont`
  is a vector of frame maps (`:if`, `:do`, `:let`, `:collect`,
  `:recur-target`), innermost frame last.
- **Closures** are `{:oml/closure true :name :arities [{:params :rest :body}] :env}`.
- **Vocabulary is deny-by-default** (`oml.vocab`). A symbol that is not a local
  resolves only to a whitelisted native (`{:oml/native inc}`) or effect
  (`{:oml/effect ask}`). Anything else is an `Unknown symbol` error. A forged
  `{:oml/native slurp}` is also checked against the whitelist when it is applied.
- **Effects** (`ask`, `llm`, `tool`) set `:status :suspended` and
  `:await {:op ask :args [...]}`.
- **Boundary rule.** A native runs on the host stack within one step, so it
  cannot be suspended. Interpreted functions passed to natives (`map`,
  `reduce`, `sort-by`, ...) are run synchronously in a nested machine. If one
  reaches an effect, the call fails:

  ```
  (map (fn [x] (ask "?" x)) [1 2])
  ;; => Boundary rule: native `map` called an interpreted function that tried
  ;;    to suspend on effect `ask`. Natives run on the host stack and cannot be
  ;;    suspended; use loop/recur in the process instead.
  ```

  Native results are fully realized so no lazy seq that holds a host fn can
  leak into the state. Only top-level arguments are adapted: an interpreted fn
  nested inside a data structure is passed to the native as a plain map.

## Known limitations

- Each closure captures its whole environment, and EDN has no sharing, so
  saved states repeat captured envs.
- `try`/`catch`, `letfn`, `def` and interop are not supported.
- babashka's destructuring expansion inlines host fn objects (`seq`, `first`,
  `next`, `seq?`). `oml.expand` maps them back to `clojure.core/*` symbols.

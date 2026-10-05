(ns oml.vocab
  "The process vocabulary. Deny-by-default: a symbol that is not a local
  binding resolves only through these whitelists.

  - `natives`: pure-ish leaf functions executed by the host in one step.
  - `effects`: operations the interpreter never executes itself; applying one
    suspends the machine (an await point).

  State refers to natives and effects by symbol (`{:oml/native inc}`,
  `{:oml/effect ask}`), never by host fn object, so it round-trips through EDN.")

(defn- bounded-range
  "`range` without the infinite 0-arity: native results are fully realized."
  ([end] (range end))
  ([start end] (range start end))
  ([start end step] (range start end step)))

(def natives
  {'+ +, '- -, '* *, '/ /, 'mod mod, 'rem rem, 'quot quot, 'inc inc, 'dec dec
   'min min, 'max max, 'abs abs
   '= =, 'not= not=, '< <, '> >, '<= <=, '>= >=, 'compare compare
   'not not, 'nil? nil?, 'some? some?, 'zero? zero?, 'pos? pos?, 'neg? neg?
   'even? even?, 'odd? odd?, 'true? true?, 'false? false?, 'boolean boolean
   'number? number?, 'string? string?, 'keyword? keyword?, 'symbol? symbol?
   'vector? vector?, 'map? map?, 'set? set?, 'seq? seq?, 'coll? coll?
   'sequential? sequential?
   'str str, 'subs subs, 'format format, 'keyword keyword, 'symbol symbol, 'name name
   'list list, 'vector vector, 'hash-map hash-map, 'hash-set hash-set, 'vec vec, 'set set
   'conj conj, 'cons cons, 'count count, 'first first, 'second second, 'last last
   'rest rest, 'next next, 'nth nth, 'nthnext nthnext, 'seq seq, 'empty? empty?
   'empty empty, 'not-empty not-empty, 'get get, 'get-in get-in, 'contains? contains?
   'assoc assoc, 'assoc-in assoc-in, 'dissoc dissoc, 'update update, 'merge merge
   'keys keys, 'vals vals, 'concat concat, 'into into, 'reverse reverse
   'take take, 'drop drop, 'range bounded-range, 'sort sort, 'sort-by sort-by
   'distinct distinct, 'frequencies frequencies, 'group-by group-by
   'map map, 'mapv mapv, 'filter filter, 'filterv filterv, 'remove remove
   'reduce reduce, 'some some, 'every? every?, 'apply apply, 'identity identity
   'println println, 'prn prn, 'print print, 'pr-str pr-str
   ;; emitted by map destructuring
   'seq-to-map-for-destructuring seq-to-map-for-destructuring})

(def effects
  "Effect ops. Applying one is an await point; the host supplies the result
  via `oml.machine/resume`."
  '#{ask llm tool})

(defn- core-name
  "Unqualified symbol for `sym` if it is unqualified or `clojure.core/`
  qualified (macroexpansion qualifies core references); otherwise nil."
  [sym]
  (when (contains? #{nil "clojure.core"} (namespace sym))
    (symbol (name sym))))

(defn resolve-global
  "Resolve a non-local symbol to a data reference, or nil if not allowed."
  [sym]
  (when-let [n (core-name sym)]
    (cond
      (contains? natives n) {:oml/native n}
      (contains? effects n) {:oml/effect n})))

(defn native-fn
  "Host fn for a whitelisted native symbol, or nil."
  [sym]
  (get natives sym))

(ns oml.expand
  "Macroexpansion of process source into the core special forms the machine
  understands.

  This is `clojure.walk/macroexpand-all` with one twist: `doseq` and
  `dotimes` are replaced by process-language versions that expand to plain
  `loop*`. The clojure.core versions expand to chunked-seq interop
  (`chunk-first`, `chunked-seq?`, `unchecked-inc`, `int`, ...), which the
  deny-by-default vocabulary does not (and should not) expose. `for` is not
  supported: it expands to `(new clojure.lang.LazySeq ...)`, which the machine
  rejects as an unsupported special form.

  SCI quirk: babashka's `let`/`fn`/`loop` destructuring inlines host fn
  objects (seq, first, next, seq?) into the expansion instead of symbols.
  Those are mapped back to `clojure.core/<name>` symbols so the program stays
  data and still resolves through the whitelist.")

(def ^:private core-fn->symbol
  (delay (into {} (for [[sym v] (ns-publics 'clojure.core)
                        :let [f @v]
                        :when (fn? f)]
                    [f (symbol "clojure.core" (name sym))]))))

(defn- fn->symbol [f]
  (or (get @core-fn->symbol f)
      (throw (ex-info (str "Macroexpansion produced a host function object: " f) {}))))

(defn- expand-doseq
  "(doseq [x xs, y ys] body...) — binding pairs only, no :when/:let/:while."
  [[_ bindings & body]]
  (if (empty? bindings)
    `(do ~@body nil)
    (let [[x coll & more] bindings
          s (gensym "seq__")]
      (when (keyword? x)
        (throw (ex-info (str "doseq modifier " x " is not supported in processes")
                        {:form bindings})))
      `(loop* [~s (seq ~coll)]
         (if ~s
           (let [~x (first ~s)]
             (doseq ~(vec more) ~@body)
             (recur (next ~s)))
           nil)))))

(defn- expand-dotimes [[_ [i n] & body]]
  (let [limit (gensym "n__")]
    `(let* [~limit ~n]
       (loop* [~i 0]
         (if (< ~i ~limit)
           (do ~@body (recur (inc ~i)))
           nil)))))

(def ^:private process-macros
  {'doseq expand-doseq
   'dotimes expand-dotimes})

(defn- process-macro [head]
  (when (and (symbol? head) (contains? #{nil "clojure.core"} (namespace head)))
    (get process-macros (symbol (name head)))))

(defn expand
  "Fully macroexpand `form`, leaving only special forms and calls."
  [form]
  (cond
    (and (seq? form) (seq form))
    (let [head (first form)]
      (cond
        (= 'quote head) form
        (process-macro head) (expand ((process-macro head) form))
        :else (let [expanded (if (symbol? head) (macroexpand-1 form) form)]
                (if (identical? expanded form)
                  (apply list (map expand form))
                  (expand expanded)))))

    (vector? form) (mapv expand form)
    (map? form) (into {} (map (fn [[k v]] [(expand k) (expand v)])) form)
    (set? form) (into #{} (map expand) form)
    (fn? form) (fn->symbol form)
    :else form))

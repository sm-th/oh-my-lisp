(ns oml.custom
  "Changing a running oml. Everything the core does is a named function it
  calls through its var, and every setting is a documented var, so plain
  Clojure is the extension mechanism: `defn` replaces a function, `def` or
  `setq` sets a value, `ns-unmap` removes a tool or command. This
  namespace adds the two conveniences Clojure lacks:

  - setq: set vars by name, like Emacs' setq
  - advise!/unadvise!: wrap a function var, removable by key, like Emacs'
    advice-add :around

  Compare with pi: packages/coding-agent/src/core/extensions/ (a registry
  API; this is the alternative).")

(defmacro setq
  "Set vars by name (alter-var-root), like Emacs' setq:

    (setq oml.agent/max-turns 10
          oml.llm/model \"gpt-4o-mini\")"
  [& pairs]
  (assert (even? (count pairs)) "setq takes symbol/value pairs")
  `(do ~@(for [[sym v] (partition 2 pairs)]
           `(alter-var-root (var ~sym) (constantly ~v)))))

(defn- install! [v original advices]
  (alter-var-root v (constantly (reduce (fn [g [_ a]] (fn [& args] (apply a g args)))
                                        original advices)))
  (if (seq advices)
    (alter-meta! v assoc ::advice {:original original :advices advices})
    (alter-meta! v dissoc ::advice)))

(defn advise!
  "Wrap the function in var `v` with `f`, called as (f original & args).
  `key` names the advice: advising again with the same key replaces it
  instead of stacking, so reloading code that advises is safe. The newest
  advice is outermost. Redefining the function with defn drops its advice."
  [v key f]
  (let [{:keys [original advices]} (or (::advice (meta v)) {:original @v :advices []})
        advices (if (some #(= key (first %)) advices)
                  (mapv (fn [[k a]] [k (if (= k key) f a)]) advices)
                  (conj advices [key f]))]
    (install! v original advices)
    v))

(defn unadvise!
  "Remove the advice named `key` from var `v`; without a key, all of it."
  ([v] (unadvise! v nil))
  ([v key]
   (when-let [{:keys [original advices]} (::advice (meta v))]
     (install! v original (if key (vec (remove #(= key (first %)) advices)) [])))
   v))

(defn advice-keys
  "Keys of the advice on var `v`, innermost first."
  [v]
  (mapv first (:advices (::advice (meta v)))))

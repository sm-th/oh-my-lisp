(ns oml.machine
  "A small-step interpreter whose whole state is EDN data.

  State shape:

    {:status  :running | :suspended | :done | :error
     :control {:mode :eval :expr form :env {sym value}}   ; evaluate form
            | {:mode :return :value v}                    ; deliver v to :kont
            | {:mode :await}                              ; waiting for resume
     :kont    [frame ...]   ; the call stack, innermost frame last
     :await   {:op ask :args [...]}                       ; when :suspended
     :value   v                                           ; when :done
     :error   {:message str :rule kw ...}                 ; when :error
     :steps   n}

  Values are plain data. Closures are `{:oml/closure true ...}` maps, natives
  and effects are `{:oml/native sym}` / `{:oml/effect sym}` references, so a
  state can be printed, saved, read back and continued.

  Supported special forms (after macroexpansion): quote, if, do, let*,
  loop*, recur, fn*, throw. Everything else that is a special form is
  rejected; everything else is a call."
  (:require [clojure.walk :as walk]
            [oml.expand :as expand]
            [oml.vocab :as vocab]))

(def default-max-steps 1000000)

(def ^:private unsupported-special-forms
  '#{def var set! try catch finally new . letfn* case* monitor-enter monitor-exit
     import* deftype* reify* clojure.core/import*})

(defn closure? [v] (and (map? v) (true? (:oml/closure v))))
(defn native? [v] (and (map? v) (contains? v :oml/native)))
(defn effect? [v] (and (map? v) (contains? v :oml/effect)))

(declare run apply-fn)

;; ---------------------------------------------------------------------------
;; State transitions

(defn- eval-in [state expr env] (assoc state :control {:mode :eval :expr expr :env env}))
(defn- return [state v] (assoc state :control {:mode :return :value v}))
(defn- push [state frame] (update state :kont conj frame))

(defn- fail
  ([state message] (fail state message nil))
  ([state message extra]
   (assoc state :status :error :error (merge {:message message} extra))))

(defn- body-form [forms]
  (if (next forms) (cons 'do forms) (first forms)))

(defn- fresh-state []
  {:status :running :kont [] :steps 0})

;; ---------------------------------------------------------------------------
;; Closures

(defn- parse-arity [[params & body]]
  (let [[fixed [_ rest-param]] (split-with #(not= '& %) params)]
    {:params (vec fixed) :rest rest-param :body (body-form body)}))

(defn- make-closure [[_ & more] env]
  (let [[fname more] (if (symbol? (first more)) [(first more) (rest more)] [nil more])
        arities (if (vector? (first more)) [more] more)]
    {:oml/closure true :name fname :arities (mapv parse-arity arities) :env env}))

(defn- apply-closure [state {:keys [arities env name] :as f} args]
  (let [n (count args)
        arity (or (first (filter #(and (nil? (:rest %)) (= n (count (:params %)))) arities))
                  (first (filter #(and (:rest %) (>= n (count (:params %)))) arities)))]
    (if-not arity
      (fail state (str "Wrong number of args (" n ") passed to " (or name "fn")) {:rule :arity})
      (let [{:keys [params rest body]} arity
            base (cond-> env name (assoc name f))
            env' (cond-> (merge base (zipmap params args))
                   rest (assoc rest (seq (drop (count params) args))))]
        (-> state
            (push {:op :recur-target :params params :rest rest :body body :env base})
            (eval-in body env'))))))

;; ---------------------------------------------------------------------------
;; Natives and the boundary rule
;;
;; A native runs on the host stack inside a single step, so it cannot be
;; suspended. Interpreted functions passed to a native are run synchronously
;; in a nested machine; if one reaches an effect, the call fails.

(defn- boundary-message [native-sym await]
  (str "Boundary rule: native `" native-sym "` called an interpreted function that "
       "tried to suspend on effect `" (:op await) "`. Natives run on the host stack "
       "and cannot be suspended; use loop/recur in the process instead."))

(defn- run-nested [native-sym f args]
  (let [s (run (apply-fn (fresh-state) f (vec args)))]
    (case (:status s)
      :done (:value s)
      :suspended (throw (ex-info (boundary-message native-sym (:await s)) {:oml/rule :boundary}))
      (throw (ex-info (get-in s [:error :message])
                      {:oml/rule (get-in s [:error :rule])})))))

(defn- host-arg [native-sym v]
  (cond
    (native? v) (or (vocab/native-fn (:oml/native v))
                    (throw (ex-info (str "Unknown native: " (:oml/native v)) {:oml/rule :unknown-symbol})))
    (or (closure? v) (effect? v)) (fn [& xs] (run-nested native-sym v xs))
    :else v))

(defn- realize
  "Force lazy results so no host fn (or pending work) leaks into the state."
  [v]
  (if (coll? v) (walk/postwalk identity v) v))

(defn- apply-native [state {sym :oml/native} args]
  (if-let [host (vocab/native-fn sym)]
    (try
      (return state (realize (apply host (mapv #(host-arg sym %) args))))
      (catch Exception e
        (fail state (ex-message e) (when-let [rule (:oml/rule (ex-data e))] {:rule rule}))))
    (fail state (str "Unknown native: " sym) {:rule :unknown-symbol})))

;; ---------------------------------------------------------------------------
;; Application

(defn apply-fn
  "Apply callable value `f` to `args` on top of `state`."
  [state f args]
  (cond
    (closure? f) (apply-closure state f args)
    (native? f) (apply-native state f args)
    (effect? f) (if (contains? vocab/effects (:oml/effect f))
                  (assoc state :status :suspended
                         :control {:mode :await}
                         :await {:op (:oml/effect f) :args (vec args)})
                  (fail state (str "Unknown effect: " (:oml/effect f)) {:rule :unknown-symbol}))
    (or (keyword? f) (map? f) (set? f) (vector? f))
    (try (return state (apply f args))
         (catch Exception e (fail state (ex-message e))))
    :else (fail state (str "Not callable: " (pr-str f)) {:rule :not-callable})))

(defn- do-recur [state vals]
  (let [{:keys [op params rest body env]} (peek (:kont state))
        n (count params)]
    (cond
      (not= :recur-target op)
      (fail state "recur must be in tail position of loop or fn" {:rule :recur})

      (not= (count vals) (if rest (inc n) n))
      (fail state (str "recur expects " (if rest (inc n) n) " args, got " (count vals)) {:rule :arity})

      :else
      (eval-in state body (cond-> (merge env (zipmap params vals))
                            rest (assoc rest (nth vals n)))))))

(defn- finish-collect [state kind vals]
  (case kind
    :vector (return state vals)
    :map (return state (into {} (map vec) (partition 2 vals)))
    :set (return state (set vals))
    :call (apply-fn state (first vals) (subvec vals 1))
    :recur (do-recur state vals)
    :throw (fail state (str "Uncaught throw: " (pr-str (first vals)))
                 {:rule :throw :thrown (first vals)})))

;; ---------------------------------------------------------------------------
;; Eval / continue

(defn- collect
  "Evaluate `exprs` left to right, then finish according to `kind`."
  [state kind exprs env]
  (if (empty? exprs)
    (finish-collect state kind [])
    (-> state
        (push {:op :collect :kind kind :done [] :todo (vec (rest exprs)) :env env})
        (eval-in (first exprs) env))))

(defn- eval-do [state forms env]
  (cond
    (empty? forms) (return state nil)
    (empty? (rest forms)) (eval-in state (first forms) env)
    :else (-> state
              (push {:op :do :todo (vec (rest forms)) :env env})
              (eval-in (first forms) env))))

(defn- bind-next
  "Evaluate remaining let*/loop* binding `pairs`, then `body`. For loop*,
  `loop-params` is the vector of loop symbols and a recur target is pushed."
  [state pairs body env loop-params]
  (if-let [[sym expr] (first pairs)]
    (-> state
        (push {:op :let :sym sym :todo (vec (rest pairs)) :body body :env env :loop loop-params})
        (eval-in expr env))
    (cond-> state
      loop-params (push {:op :recur-target :params loop-params :rest nil :body body :env env})
      true (eval-in body env))))

(defn- lookup [state sym env]
  (if (and (nil? (namespace sym)) (contains? env sym))
    (return state (get env sym))
    (if-let [ref (vocab/resolve-global sym)]
      (return state ref)
      (fail state (str "Unknown symbol: " sym
                       " (not a local, whitelisted native, or effect)")
            {:rule :unknown-symbol :symbol sym}))))

(defn- eval-list [state [head & args :as expr] env]
  (case head
    quote (return state (first args))
    if (-> state
           (push {:op :if :then (second args) :else (nth args 2 nil) :env env})
           (eval-in (first args) env))
    do (eval-do state args env)
    let* (bind-next state (mapv vec (partition 2 (first args))) (body-form (rest args)) env nil)
    loop* (let [bindings (first args)]
            (bind-next state (mapv vec (partition 2 bindings)) (body-form (rest args)) env
                       (vec (take-nth 2 bindings))))
    fn* (return state (make-closure expr env))
    recur (collect state :recur args env)
    throw (collect state :throw args env)
    (if (contains? unsupported-special-forms head)
      (fail state (str "Unsupported special form: " head) {:rule :unsupported-form})
      (collect state :call expr env))))

(defn- eval-form [state expr env]
  (cond
    (symbol? expr) (lookup state expr env)
    (and (seq? expr) (seq expr)) (eval-list state expr env)
    (vector? expr) (collect state :vector expr env)
    (map? expr) (collect state :map (vec (mapcat identity expr)) env)
    (set? expr) (collect state :set (vec expr) env)
    :else (return state expr)))

(defn- continue [state v]
  (let [kont (:kont state)]
    (if (empty? kont)
      (-> state (assoc :status :done :value v) (dissoc :control))
      (let [{:keys [op env todo] :as frame} (peek kont)
            state (assoc state :kont (pop kont))]
        (case op
          :if (eval-in state (if v (:then frame) (:else frame)) env)
          :do (eval-do state todo env)
          :let (bind-next state todo (:body frame) (assoc env (:sym frame) v) (:loop frame))
          :collect (let [done (conj (:done frame) v)]
                     (if (seq todo)
                       (-> state
                           (push (assoc frame :done done :todo (subvec todo 1)))
                           (eval-in (first todo) env))
                       (finish-collect state (:kind frame) done)))
          :recur-target (return state v))))))

;; ---------------------------------------------------------------------------
;; Public API

(defn start
  "Initial state for process source `form` (any Clojure form)."
  [form]
  (let [state (fresh-state)]
    (try
      (eval-in state (expand/expand form) {})
      (catch Exception e
        (fail state (str "Macroexpansion failed: " (ex-message e)) {:rule :expand})))))

(defn step
  "One transition. Non-running states are returned unchanged."
  [state]
  (if (not= :running (:status state))
    state
    (let [{:keys [mode expr env value]} (:control state)
          state (update state :steps inc)]
      (case mode
        :eval (eval-form state expr env)
        :return (continue state value)))))

(defn run
  "Step until the state is no longer :running, or fail after `:max-steps`."
  ([state] (run state {}))
  ([state {:keys [max-steps] :or {max-steps default-max-steps}}]
   (loop [s state n 0]
     (cond
       (not= :running (:status s)) s
       (>= n max-steps) (fail s (str "Step limit exceeded (" max-steps ")") {:rule :step-limit})
       :else (recur (step s) (inc n))))))

(defn resume
  "Plug `value` in as the result of the pending effect and run on."
  ([state value] (resume state value {}))
  ([state value opts]
   (when (not= :suspended (:status state))
     (throw (ex-info "resume: state is not suspended" {:status (:status state)})))
   (-> state
       (assoc :status :running :control {:mode :return :value value})
       (dissoc :await)
       (run opts))))

(ns oml.cancel
  "A cancellation token shared by the model client, the agent loop and tools.

  Cancelling runs every registered hook once (close an HTTP stream, kill a
  process). Code that blocks registers a hook; code that loops polls
  `cancelled?`. Compare with pi's use of AbortSignal.")

(defn token [] (atom {:cancelled? false :hooks {}}))

(defn cancelled? [t] (boolean (and t (:cancelled? @t))))

(defn cancel!
  "Mark the token cancelled and run its hooks. Idempotent."
  [t]
  (let [[old _] (swap-vals! t assoc :cancelled? true :hooks {})]
    (when-not (:cancelled? old)
      (doseq [f (vals (:hooks old))]
        (try (f) (catch Exception _))))))

(defn on-cancel
  "Run `f` when `t` is cancelled (immediately if it already is). Returns a
  zero-arg fn that unregisters the hook. A nil token never cancels."
  [t f]
  (if (nil? t)
    (fn [])
    (let [k (Object.)
          s (swap! t (fn [s] (if (:cancelled? s) s (assoc-in s [:hooks k] f))))]
      (when-not (contains? (:hooks s) k) (f))
      (fn [] (swap! t update :hooks dissoc k)))))

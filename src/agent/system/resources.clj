(ns agent.system.resources
  "Rollback for partially acquired resources. Successful scopes transfer ownership.")

(defn with-rollback
  "Call f with (own! resource close!). On failure close in reverse order,
   preserving the original exception and attaching cleanup failures as suppressed.
   Register mutable services before starting them, so partial starts are covered."
  [f]
  (let [cleanups (atom [])]
    (try
      (f (fn [resource close!]
           (swap! cleanups conj #(close! resource))
           resource))
      (catch Throwable error
        (doseq [cleanup (rseq @cleanups)]
          (try (cleanup)
               (catch Throwable cleanup-error
                 (when-not (identical? error cleanup-error)
                   (.addSuppressed error cleanup-error)))))
        (throw error)))))

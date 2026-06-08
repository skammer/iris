(ns agent.runtime.tokens
  "Small deterministic token estimator for runtime budgeting.")

(defn estimate
  [value]
  (let [text (cond
               (string? value) value
               (nil? value) ""
               :else (pr-str value))]
    (max 1 (long (Math/ceil (/ (count text) 4.0))))))

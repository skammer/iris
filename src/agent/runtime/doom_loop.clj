(ns agent.runtime.doom-loop
  "Per-run repeated tool-call guard."
  (:require
   [agent.security :as security]
   [clojure.string :as str]))

(def default-config
  {:enabled? true
   :threshold 3
   :window-size 16
   :sequence-threshold 3
   :sequence-window-size 24
   :max-sequence-length 8})

(defn normalize-config
  [config]
  (merge default-config (or config {})))

(defn enabled?
  [config]
  (true? (:enabled? (normalize-config config))))

(defn new-state []
  {:recent []
   :recent-steps []})

(declare canonical-value)

(defn- canonical-map [m]
  [:map (->> m
             (map (fn [[k v]]
                    [(canonical-value k)
                     (canonical-value v)]))
             (sort-by pr-str)
             vec)])

(defn- canonical-set [s]
  [:set (->> s
             (map canonical-value)
             (sort-by pr-str)
             vec)])

(defn- canonical-value [value]
  (cond
    (map? value) (canonical-map value)
    (set? value) (canonical-set value)
    (sequential? value) [:seq (mapv canonical-value value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    :else value))

(defn canonical-input [input]
  (pr-str (canonical-value input)))

(defn normalize-tool-name [tool-name]
  (cond
    (keyword? tool-name) (name tool-name)
    (string? tool-name) (str/trim tool-name)
    :else (str tool-name)))

(defn fingerprint
  [{:keys [tool-name input]}]
  (security/sha256-hex (pr-str [(normalize-tool-name tool-name)
                                (canonical-input input)])))

(defn tool-calls
  [step]
  (->> (:directives step)
       (keep (fn [directive]
               (when (= :tool-call (some-> (:type directive) keyword))
                 (let [{:keys [tool-name input]} (:payload directive)
                       call {:tool-name (normalize-tool-name tool-name)
                             :input input}]
                   (assoc call
                          :fingerprint (fingerprint call)
                          :canonical-input (canonical-input input))))))))

(defn record-call
  [state config call]
  (let [{:keys [threshold window-size]} (normalize-config config)
        recent (conj (vec (:recent state)) call)
        recent* (vec (take-last window-size recent))
        count* (count (filter #(= (:fingerprint call) (:fingerprint %)) recent*))
        state* (assoc state :recent recent*)]
    {:state state*
     :detected? (>= count* threshold)
     :count count*
     :detection :identical-call
     :call call}))

(defn- step-signature [step]
  (->> (tool-calls step)
       (map :fingerprint)
       sort
       vec))

(defn- repeated-tail [steps threshold max-length]
  (let [step-count (count steps)
        max-period (min max-length (quot step-count threshold))]
    (some (fn [period]
            (let [pattern (subvec steps (- step-count period))
                  repeated (vec (mapcat identity (repeat threshold pattern)))
                  tail (subvec steps (- step-count (count repeated)))]
              (when (= repeated tail)
                {:sequence-length period
                 :sequence pattern})))
          (range 1 (inc max-period)))))

(defn- record-step [state config step]
  (let [{:keys [sequence-threshold sequence-window-size max-sequence-length]}
        (normalize-config config)
        signature (step-signature step)]
    (if (empty? signature)
      {:state state :detected? false}
      (let [recent (conj (vec (:recent-steps state)) signature)
            recent* (vec (take-last sequence-window-size recent))
            repeated (repeated-tail recent* sequence-threshold max-sequence-length)]
        (cond-> {:state (assoc state :recent-steps recent*)
                 :detected? (boolean repeated)
                 :detection :repeated-sequence
                 :count (when repeated sequence-threshold)}
          repeated (merge repeated))))))

(defn check-step
  [state config step]
  (if-not (enabled? config)
    {:state state :detected? false}
    (let [call-check (reduce (fn [{:keys [state detected?] :as acc} call]
                               (if detected?
                                 acc
                                 (record-call state config call)))
                             {:state state :detected? false}
                             (tool-calls step))]
      (if (:detected? call-check)
        call-check
        (record-step (:state call-check) config step)))))

(ns agent.runtime.doom-loop
  "Per-run repeated tool-call guard."
  (:require
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(def default-config
  {:enabled? true
   :threshold 3
   :window-size 16
   :action :stop})

(defn normalize-config
  [config]
  (merge default-config (or config {})))

(defn enabled?
  [config]
  (true? (:enabled? (normalize-config config))))

(defn new-state []
  {:recent []})

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

(defn- sha-256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn normalize-tool-name [tool-name]
  (cond
    (keyword? tool-name) (name tool-name)
    (string? tool-name) (str/trim tool-name)
    :else (str tool-name)))

(defn fingerprint
  [{:keys [tool-name input]}]
  (sha-256 (pr-str [(normalize-tool-name tool-name)
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
     :call call}))

(defn check-step
  [state config step]
  (if-not (enabled? config)
    {:state state :detected? false}
    (reduce (fn [{:keys [state detected?] :as acc} call]
              (if detected?
                acc
                (record-call state config call)))
            {:state state :detected? false}
            (tool-calls step))))

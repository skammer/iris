(ns agent.memory.vault.embeddings
  "Batched vault embeddings and coverage diagnostics."
  (:require
   [agent.llm.core :as llm]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str]
   [agent.memory.vault.format :as note-format]))

(def ^:private default-embedding-surfaces #{:vault-notes :vault-chunks})

(def ^:private default-embedding-batch-size 16)

(defn- embedding-enabled? [memory-service]
  (if (true? (get-in memory-service [:config :embeddings :enabled?]))
    true
    false))

(defn- keyword-set [values fallback]
  (let [values* (if (seq values) values fallback)]
    (->> values*
         (map #(if (keyword? %) % (keyword (str %))))
         set)))

(defn- embedding-surfaces [memory-service]
  (keyword-set (get-in memory-service [:config :embeddings :surfaces])
               default-embedding-surfaces))

(defn- embedding-batch-size [memory-service]
  (let [value (get-in memory-service [:config :embeddings :batch-size])]
    (if (and (integer? value) (pos? value))
      value
      default-embedding-batch-size)))

(defn- approved-note? [note]
  (= "approved" (:iris-status note)))

(defn- approved-notes [notes]
  (filter approved-note? notes))

(defn- approved-chunks [notes]
  (mapcat (fn [note]
            (for [chunk (:chunks note)]
              (assoc chunk
                     :path (:path note)
                     :note-id (:id note)
                     :updated-at (:updated-at note))))
          (approved-notes notes)))

(defn- note-embedding-text [note]
  (str/join "\n"
            (remove str/blank?
                    [(:title note)
                     (:description note)
                     (str/join " " (:tags note))
                     (:body note)])))

(defn- embedding-vector? [value]
  (and (sequential? value)
       (every? number? value)))

(defn- normalize-embedding-response [input-count response]
  (cond
    (and (= 1 input-count) (embedding-vector? response))
    [(vec response)]

    (sequential? response)
    (mapv vec response)

    :else
    []))

(defn- embed-batches [provider texts opts batch-size]
  (->> texts
       (partition-all batch-size)
       (mapcat (fn [batch]
                 (normalize-embedding-response
                  (count batch)
                  (llm/embed provider (vec batch) opts))))
       vec))

(defn ^:no-doc build-vault-embeddings [memory-service notes]
  (let [surfaces (embedding-surfaces memory-service)
        provider (:embedding-provider memory-service)]
    (cond
      (not (embedding-enabled? memory-service))
      {:memory-embeddings []
       :vault-chunk-embeddings []
       :embedding-errors []}

      (nil? provider)
      {:memory-embeddings []
       :vault-chunk-embeddings []
       :embedding-errors [(note-format/audit-issue :embedding-error nil "embedding provider is not configured")]}

      :else
      (try
        (let [now (str (java.time.Instant/now))
              model (:embedding-model memory-service)
              opts (cond-> {}
                     model (assoc :model model))
              batch-size (embedding-batch-size memory-service)
              note-items (if (contains? surfaces :vault-notes)
                           (->> (approved-notes notes)
                                (remove #(str/blank? (note-embedding-text %)))
                                vec)
                           [])
              chunk-items (if (contains? surfaces :vault-chunks)
                            (vec (approved-chunks notes))
                            [])
              note-vectors (embed-batches provider
                                          (mapv note-embedding-text note-items)
                                          opts
                                          batch-size)
              chunk-vectors (embed-batches provider
                                           (mapv :text chunk-items)
                                           opts
                                           batch-size)]
          (when (not= (count note-items) (count note-vectors))
            (throw (ex-info "embedding provider returned unexpected note embedding count"
                            {:type :embedding-count-mismatch
                             :expected (count note-items)
                             :actual (count note-vectors)})))
          (when (not= (count chunk-items) (count chunk-vectors))
            (throw (ex-info "embedding provider returned unexpected chunk embedding count"
                            {:type :embedding-count-mismatch
                             :expected (count chunk-items)
                             :actual (count chunk-vectors)})))
          {:memory-embeddings
           (mapv (fn [note embedding]
                   {:id (str "vault_note:" (or (:id note) (:path note)))
                    :surface_id (:path note)
                    :content_hash (:body-hash note)
                    :model model
                    :embedding embedding
                    :updated_at now})
                 note-items
                 note-vectors)
           :vault-chunk-embeddings
           (mapv (fn [chunk embedding]
                   {:chunk_id (:chunk-id chunk)
                    :content_hash (:content-hash chunk)
                    :model model
                    :embedding embedding
                    :updated_at now})
                 chunk-items
                 chunk-vectors)
           :embedding-errors []})
        (catch Exception e
          {:memory-embeddings []
           :vault-chunk-embeddings []
           :embedding-errors [(note-format/audit-issue :embedding-error nil
                                           (or (.getMessage e) "embedding failed")
                                           (ex-data e))]})))))

(defn- stale-embedding-report [memory-service notes]
  (let [current-chunks (into {}
                             (map (juxt :chunk-id :content-hash))
                             (approved-chunks notes))
        current-notes (into {}
                            (map (juxt :path :body-hash))
                            (approved-notes notes))
        store (:store memory-service)]
    {:stale-embeddings
     (->> (sqlite/list-vault-chunk-embeddings store {:limit 10000})
          (keep (fn [{:keys [chunk-id content-hash]}]
                  (let [current-hash (get current-chunks chunk-id)]
                    (when (not= current-hash content-hash)
                      {:chunk-id chunk-id
                       :content-hash content-hash
                       :current-content-hash current-hash}))))
          vec)
     :stale-note-embeddings
     (->> (sqlite/list-memory-embeddings store {:surface "vault_note"
                                                :limit 10000})
          (keep (fn [{:keys [surface-id content-hash]}]
                  (let [current-hash (get current-notes surface-id)]
                    (when (not= current-hash content-hash)
                      {:surface-id surface-id
                       :content-hash content-hash
                       :current-content-hash current-hash}))))
          vec)}))

(defn ^:no-doc embedding-report [memory-service notes embedding-result]
  (if (embedding-enabled? memory-service)
    (let [surfaces (embedding-surfaces memory-service)
          desired-chunks (if (contains? surfaces :vault-chunks)
                           (vec (approved-chunks notes))
                           [])
          desired-notes (if (contains? surfaces :vault-notes)
                          (vec (approved-notes notes))
                          [])
          generated-chunk-ids (set (map :chunk_id (:vault-chunk-embeddings embedding-result)))
          generated-note-paths (set (map :surface_id (:memory-embeddings embedding-result)))
          stale (stale-embedding-report memory-service notes)]
      {:embedding-audit {:enabled true}
       :missing-embeddings (->> desired-chunks
                                (remove #(contains? generated-chunk-ids (:chunk-id %)))
                                (mapv #(select-keys % [:chunk-id :content-hash])))
       :missing-note-embeddings (->> desired-notes
                                     (remove #(contains? generated-note-paths (:path %)))
                                     (mapv #(select-keys % [:path :id :body-hash])))
       :stale-embeddings (:stale-embeddings stale)
       :stale-note-embeddings (:stale-note-embeddings stale)
       :embedding-errors (:embedding-errors embedding-result)})
    {:embedding-audit {:enabled false}
     :missing-embeddings []
     :missing-note-embeddings []
     :stale-embeddings []
     :stale-note-embeddings []
     :embedding-errors []}))

(ns agent.memory.recall
  "Unified memory recall records. SQLite search stays an implementation detail."
  (:require
   [agent.memory.core :as memory]
   [cheshire.core :as json]))

(defn- event-text [event]
  (json/generate-string (:payload event)))

(defn- result-text [surface item]
  (case surface
    :message (:content item)
    :event (event-text item)
    :vault_chunk (:text item)
    ""))

(defn- result-id [surface item]
  (or (:id item)
      (case surface
        :message (:message-id item)
        :event (:event-id item)
        :vault_chunk (:chunk-id item)
        nil)))

(defn- result-type [surface item]
  (case surface
    :message (:role item)
    :event (:event-type item)
    :vault_chunk (:type item)
    surface))

(defn- result-scope [surface item]
  (case surface
    :message {:type :session :id (:session-id item)}
    :event (if (= :session (:entity-type item))
             {:type :session :id (:entity-id item)}
             {:type (or (:entity-type item) :event)
              :id (:entity-id item)})
    :vault_chunk {:type (:iris-scope item)}
    nil))

(defn- result-source [surface item]
  (case surface
    :message {:message-id (:id item)
              :session-id (:session-id item)}
    :event {:event-id (:id item)
            :entity-type (:entity-type item)
            :entity-id (:entity-id item)}
    :vault_chunk {:note-id (:note-id item)
                  :path (:path item)
                  :chunk-id (:chunk-id item)
                  :heading (:heading item)
                  :block-id (:block-id item)
                  :origins (:origins item)}
    {}))

(defn- recall-reason [{:keys [score-breakdown]}]
  (let [{:keys [exact lexical confidence]} score-breakdown]
    (cond
      (= 1.0 exact) :exact-match
      (pos? (double (or lexical 0.0))) :lexical-overlap
      (pos? (double (or confidence 0.0))) :confidence
      :else :ranked-search)))

(defn- ranked->result [{:keys [surface item score] :as ranked}]
  {:surface surface
   :type (result-type surface item)
   :id (result-id surface item)
   :scope (result-scope surface item)
   :status :current
   :text (result-text surface item)
   :score score
   :source (result-source surface item)
   :reason (recall-reason ranked)
   :tags []})

(defn- vault->result [item]
  {:surface :vault_chunk
   :type (result-type :vault_chunk item)
   :id (:chunk-id item)
   :scope (result-scope :vault_chunk item)
   :status (keyword (or (:iris-status item) "approved"))
   :text (:text item)
   :score 1.0
   :source (result-source :vault_chunk item)
   :reason :fts-match
   :tags (:tags item)})

(defn recall
  "Return compact normalized recall records for chat/API/tool callers."
  ([memory-service query] (recall memory-service query {}))
  ([memory-service query opts]
   (let [search (memory/search-memory memory-service query opts)
         vault (memory/search-vault memory-service query opts)
         results (vec (concat (map ranked->result (:ranked search))
                              (map vault->result vault)))]
     {:query query
      :limit (:limit opts)
      :results results
      :surface-counts {:messages (count (:messages search))
                       :events (count (:events search))
                       :vault-chunks (count vault)}})))

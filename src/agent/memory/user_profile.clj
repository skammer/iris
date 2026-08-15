(ns agent.memory.user-profile
  "Bounded, LLM-assisted maintenance of the learned USER.md profile section."
  (:require
   [agent.llm.core :as llm]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.file AtomicMoveNotSupportedException Files StandardCopyOption)
   (java.util UUID)
   (java.util.regex Pattern)))

(def default-config
  {:enabled true
   :min-confidence 0.9
   :max-facts 24
   :max-operations 5
   :max-transcript-chars 20000
   :max-user-md-chars 8000})

(def ^:private start-marker "<!-- iris:user-profile:start -->")
(def ^:private end-marker "<!-- iris:user-profile:end -->")
(def ^:private max-fact-chars 180)

(defn create-service
  [{:keys [config config-dir model on-update provider store]}]
  {:config (merge default-config config)
   :config-dir config-dir
   :model model
   :on-update on-update
   :provider provider
   :store store})

(defn enabled? [service]
  (and service
       (:provider service)
       (not (false? (get-in service [:config :enabled])))))

(defn- profile-path [service]
  (io/file (:config-dir service) "USER.md"))

(defn- normalize-fact [value]
  (-> (or value "")
      str
      (str/replace #"^\s*[-*]\s+" "")
      (str/replace #"\s+" " ")
      str/trim
      (util/truncate max-fact-chars #(str " [truncated " % " chars]"))))

(defn- fact-key [value]
  (-> value normalize-fact str/lower-case))

(defn- sensitive-fact? [value]
  (boolean
   (re-find #"(?i)(api[-_ ]?key|access[-_ ]?token|password|passwd|secret|credential|private[-_ ]?key|bearer\s+|sk-[a-z0-9])"
            (or value ""))))

(defn- managed-body [content]
  (let [start (.indexOf ^String content start-marker)
        end (.indexOf ^String content end-marker)]
    (when (and (not (neg? start)) (> end start))
      (subs content (+ start (count start-marker)) end))))

(defn managed-facts [content]
  (->> (str/split-lines (or (managed-body (or content "")) ""))
       (keep (fn [line]
               (when-let [[_ fact] (re-matches #"\s*[-*]\s+(.+)" line)]
                 (not-empty (normalize-fact fact)))))
       vec))

(defn- profile-section [facts]
  (str start-marker
       "\n## Learned user profile\n"
       (str/join "\n" (map #(str "- " %) facts))
       "\n" end-marker))

(defn- replace-managed-section [content facts]
  (let [content* (or content "# USER\n")
        pattern (re-pattern
                 (str "(?s)" (Pattern/quote start-marker)
                      ".*?" (Pattern/quote end-marker)))
        section (when (seq facts) (profile-section facts))
        updated (if (re-find pattern content*)
                  (str/replace content* pattern (or section ""))
                  (if section
                    (str (str/trimr content*) "\n\n" section "\n")
                    content*))]
    (str (str/trimr updated) "\n")))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties
   {:operations
    {:type "array"
     :items
     {:type "object"
      :additionalProperties false
      :properties
      {:operation {:type "string" :enum ["upsert" "delete"]}
       :old {:type ["string" "null"]}
       :value {:type "string"}
       :confidence {:type "number"}
       :evidence {:type "string"}}
      :required ["operation" "old" "value" "confidence" "evidence"]}}}
   :required ["operations"]})

(defn- parse-operations [content]
  (let [value (cond
                (map? content) content
                (str/blank? (or content "")) {}
                :else (json/parse-string content true))]
    (vec (filter map? (:operations value)))))

(defn- existing-index [facts]
  (into {} (map (juxt fact-key identity)) facts))

(defn- apply-operation [facts operation min-confidence]
  (let [confidence (double (or (:confidence operation) 0.0))
        op (:operation operation)
        old (normalize-fact (:old operation))
        value (normalize-fact (:value operation))
        index (existing-index facts)
        old-key (fact-key old)
        value-key (fact-key value)]
    (cond
      (< confidence min-confidence) facts
      (or (sensitive-fact? old) (sensitive-fact? value)) facts

      (= "delete" op)
      (if-let [existing (get index old-key)]
        (vec (remove #(= existing %) facts))
        facts)

      (not= "upsert" op) facts
      (str/blank? value) facts

      (not (str/blank? old))
      (if-let [existing (get index old-key)]
        (mapv #(if (= existing %) value %) facts)
        facts)

      (contains? index value-key) facts
      :else (conj facts value))))

(defn- apply-operations [facts operations cfg]
  (let [min-confidence (double (:min-confidence cfg))
        max-operations (long (:max-operations cfg))
        max-facts (long (:max-facts cfg))]
    (->> (take max-operations operations)
         (reduce #(apply-operation %1 %2 min-confidence) (vec facts))
         (reduce (fn [{:keys [seen out] :as acc} fact]
                   (let [key (fact-key fact)]
                     (if (or (str/blank? key) (contains? seen key))
                       acc
                       {:seen (conj seen key) :out (conj out fact)})))
                 {:seen #{} :out []})
         :out
         (take max-facts)
         vec)))

(defn- atomic-write! [file content]
  (let [parent (.getParentFile file)
        temp (io/file parent (str ".USER.md." (UUID/randomUUID) ".tmp"))]
    (.mkdirs parent)
    (try
      (spit temp content)
      (try
        (Files/move (.toPath temp)
                    (.toPath file)
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move (.toPath temp)
                      (.toPath file)
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (when (.exists temp)
          (io/delete-file temp true))))))

(defn learn-from-transcript!
  [service {:keys [session-id transcript]}]
  (if-not (enabled? service)
    {:status :disabled :updated? false}
    (let [cfg (:config service)
          file (profile-path service)
          current (if (.isFile file) (slurp file) "# USER\n")
          facts (managed-facts current)
          response (llm/invoke
                    (:provider service)
                    {:model (:model service)
                     :session-id session-id
                     :temperature 0.0
                     :messages
                     [{:role "system"
                       :content (prompts/load-prompt "user-profile-extraction")}
                      {:role "user"
                       :content (json/generate-string
                                 {:current_user_md
                                  (util/truncate current (:max-user-md-chars cfg)
                                                 #(str "\n[USER.md truncated " % " chars]"))
                                  :managed_facts facts
                                  :transcript
                                  (util/truncate transcript (:max-transcript-chars cfg)
                                                 #(str "\n[transcript truncated " % " chars]"))})}]
                     :structured-output {:name "user_profile_updates"
                                         :strict? true
                                         :schema (extraction-schema)}})
          operations (parse-operations (:content response))
          facts* (apply-operations facts operations cfg)
          updated? (not= facts facts*)]
      (when updated?
        (atomic-write! file (replace-managed-section current facts*))
        (when-let [on-update (:on-update service)]
          (on-update))
        (when-let [store (:store service)]
          (sqlite/log-event!
           store
           {:event-type :memory.user_profile.updated
            :entity-type :session
            :entity-id session-id
            :payload {:operation-count (count operations)
                      :fact-count (count facts*)}})))
      {:status (if updated? :updated :unchanged)
       :updated? updated?
       :operation-count (count operations)
       :fact-count (count facts*)
       :path (.getAbsolutePath file)})))

(defn learn-session!
  [service session-id limit]
  (let [limit* (min 200 (max 1 (long (or limit 80))))
        messages (->> (sqlite/list-messages (:store service) session-id)
                      (filter #(contains? #{"user" "assistant"} (:role %)))
                      (take-last limit*))
        transcript (str/join "\n\n"
                             (map #(str "[" (:id %) "] " (:role %) ": " (:content %))
                                  messages))]
    (if (seq messages)
      (learn-from-transcript! service {:session-id session-id :transcript transcript})
      {:status :empty :updated? false :fact-count 0})))

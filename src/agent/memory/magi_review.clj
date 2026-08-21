(ns agent.memory.magi-review
  "MAGI review and optional promotion of candidate Vault Notes."
  (:require
   [agent.magi.core :as magi]
   [agent.memory.core :as memory]
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [agent.skills :as skills]
   [agent.util :as util]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def review-event-type :memory.vault.magi_evaluated)
(def update-review-event-type :memory.vault.update_magi_evaluated)

(def ^:private review-body-chars 5000)
(def ^:private review-update-body-chars 2000)
(def ^:private review-evidence-chars 1600)
(def ^:private review-diff-chars 3500)
(def ^:private review-origin-sample 4)

(defn- normalize-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/replace value #"_" "-")))
    :else value))

(defn- positive-long [value fallback maximum]
  (long (min maximum
             (if (and (integer? value) (pos? value)) value fallback))))

(defn config [system]
  (magi/memory-promotion-config (:magi-service system)))

(defn mode [system]
  (magi/memory-promotion-mode (:magi-service system)))

(defn enabled? [system]
  (magi/memory-promotion-enabled? (:magi-service system)))

(defn auto? [system]
  (and (enabled? system) (= :auto (mode system))))

(defn review-applies? [system]
  (and (enabled? system) (contains? #{:auto :manual} (mode system))))

(defn- configured-scopes [system]
  (set (map normalize-keyword (:scopes (config system)))))

(defn scope-allowed? [system scope]
  (let [configured (configured-scopes system)
        scope* (normalize-keyword scope)]
    (or (contains? configured :all)
        (contains? configured scope*))))

(defn- candidate-notes [system]
  (sqlite/list-vault-notes (:store system) {:status "candidate" :limit 1000}))

(defn- pending-updates [system]
  (sqlite/list-memory-note-updates (:store system) {:status "pending" :limit 1000}))

(defn- note-by-path [system path]
  (some #(when (= path (:path %)) %) (candidate-notes system)))

(defn latest-review [system note]
  (first
   (sqlite/list-events (:store system)
                       {:event-type review-event-type
                        :entity-type :vault_note
                        :entity-id (or (:id note) (:path note))
                        :limit 1})))

(defn latest-update-review [system update]
  (first
   (sqlite/list-events (:store system)
                       {:event-type update-review-event-type
                        :entity-type :memory_note_update
                        :entity-id (:id update)
                        :limit 1})))

(defn- bounded [value limit]
  (util/truncate (or value "") limit #(str " [truncated " % " chars]")))

(defn- compact-origins [origins]
  {:count (count origins)
   :types (->> origins
               (map #(bounded (or (:type %) "unknown") 80))
               frequencies
               (sort-by (comp - val))
               (take 8)
               (into {}))
   :sample (mapv #(select-keys % [:type :session-id :session_id
                                  :message-id :message_id :message-id-start :message_id_start
                                  :message-id-end :message_id_end :message-count :message_count
                                  :event-id :event_id :event-id-start :event_id_start
                                  :event-id-end :event_id_end :event-count :event_count
                                  :request-id :request_id :vault-path :vault_path])
                 (take review-origin-sample origins))})

(defn- evidence-excerpt [content]
  (let [marker "## Evidence"
        idx (str/index-of (or content "") marker)]
    (when idx
      (bounded (subs content idx) review-evidence-chars))))

(defn- compact-note [note content]
  (let [values (vault/note-change-values content)]
    {:id (:id note)
     :path (bounded (:path note) 1000)
     :type (bounded (:type note) 80)
     :title (bounded (:title note) 300)
     :description (bounded (:description note) 800)
     :tags (mapv #(bounded % 100) (take 12 (:tags note)))
     :scope (:iris-scope note)
     :confidence (:iris-confidence note)
     :content-hash (:body-hash note)
     :revision (:revision note)
     :body (bounded (:body values) review-body-chars)
     :evidence (evidence-excerpt content)
     :origins (compact-origins (:origins note))}))

(defn- request-for [note content]
  {:kind :yes-no
   :domain :memory-promotion
   :expected-response :permit
   :file-review? true
   :question "Should Iris promote this candidate Vault Note to approved memory?"
   :context {:note (compact-note note content)}})

(defn- update-request-for [update current-content]
  {:kind :yes-no
   :domain :memory-promotion
   :expected-response :permit
   :file-review? true
   :question "Should Iris apply this reviewed memory or skill operation?"
   :context {:operation (:operation update)
             :proposal-id (:id update)
             :target-id (:target-id update)
             :target-path (:target-path update)
             :base-revision (:base-revision update)
             :proposed-revision (:proposed-revision update)
             :change-fields (->> (:changes update)
                                 keys
                                 (map #(if (keyword? %) (name %) (str %)))
                                 sort
                                 (take 12)
                                 vec)
             :diff (bounded (:diff update) review-diff-chars)
             :evidence (bounded (pr-str (:evidence update)) review-evidence-chars)
             :current-body (bounded (:body (vault/note-change-values current-content))
                                    review-update-body-chars)
             :proposed-body (bounded (:body (vault/note-change-values (:proposed-content update)))
                                     review-update-body-chars)}})

(defn- proposal-content [system proposal]
  (if (= "skill-update" (:operation proposal))
    (slurp (:target-path proposal))
    (:content (memory/read-vault-file (:memory-service system) (:target-path proposal)))))

(defn apply-proposal! [system proposal-id reason]
  (let [proposal (or (memory/get-memory-note-update (:memory-service system) proposal-id)
                     (throw (ex-info "Memory proposal not found"
                                     {:type :not-found :proposal-id proposal-id})))]
    (if (= "skill-update" (:operation proposal))
      (let [before (slurp (:target-path proposal))
            _ (skills/update-proposed-skill!
               (:skills-registry system)
               (:target-id proposal)
               (:base-revision proposal)
               (:proposed-content proposal))]
        (try
          (memory/decide-memory-note-update! (:memory-service system)
                                             proposal-id :applied :yes reason)
          (catch Exception e
            (skills/update-proposed-skill!
             (:skills-registry system)
             (:target-id proposal)
             (:proposed-revision proposal)
             before)
            (throw e))))
      (memory/apply-memory-note-proposal! (:memory-service system) proposal-id reason))))

(defn- emit! [system event]
  (if-let [event-sink (:event-sink system)]
    (event-sink event)
    (sqlite/log-event! (:store system) event)))

(defn- current-candidate! [system path]
  (or (note-by-path system path)
      (throw (ex-info "Vault Note is no longer a candidate"
                      {:type :vault-note-not-candidate
                      :path path}))))

(defn apply-candidate!
  "Approve a memory candidate. Skill candidates become active only here."
  [system path scope]
  (let [note (current-candidate! system path)]
    (if (= "skill" (str/lower-case (or (:type note) "")))
      (let [content (:content (memory/read-vault-file (:memory-service system) path))
            skill-source (:body (vault/note-change-values content))
            installed (skills/install-proposed-skill! (:skills-registry system) skill-source)]
        (try
          (let [promoted (memory/promote-vault-note! (:memory-service system) path {:scope scope})
                _ (memory/update-vault-note-iris! (:memory-service system)
                                                  (:path promoted) {:status :superseded})
                archived (memory/move-vault-note! (:memory-service system)
                                                  (:path promoted) "archive")]
            (assoc archived :skill (select-keys installed [:name :description :path])))
          (catch Exception e
            (skills/uninstall-proposed-skill! (:skills-registry system) (:path installed))
            (throw e))))
      (memory/promote-vault-note! (:memory-service system) path {:scope scope}))))

(defn review-note!
  [system path {:keys [apply? source] :or {apply? false source :advice}}]
  (when-not (enabled? system)
    (throw (ex-info "MAGI memory promotion is disabled"
                    {:type :magi-memory-promotion-disabled})))
  (let [note (current-candidate! system path)]
    (when-not (scope-allowed? system (:iris-scope note))
      (throw (ex-info "Vault Note scope is outside MAGI promotion scope"
                      {:type :magi-memory-scope-disabled
                       :scope (:iris-scope note)})))
    (let [content (:content (memory/read-vault-file (:memory-service system) path))
          request (request-for note content)
          start (System/nanoTime)
          result (magi/decide (:magi-service system) request)
          duration-ms (long (/ (- (System/nanoTime) start) 1000000))
          decision (normalize-keyword (:decision result))
          should-apply? (and apply?
                             (review-applies? system)
                             (= :yes decision))
          applied (when should-apply?
                    (apply-candidate! system path (:iris-scope note)))
          rejected (when (and apply? (= :no decision))
                     (memory/update-vault-note-iris!
                      (:memory-service system) path {:status :rejected}))
          payload {:source (name source)
                   :note (select-keys note [:id :path :type :title :description
                                            :tags :iris-scope :iris-confidence
                                            :origins :body-hash])
                   :input request
                   :content-hash (:body-hash note)
                   :revision (:revision note)
                   :decision decision
                   :reason (:reason result)
                   :judge (select-keys result [:decision :reason])
                   :filter (:filter result)
                   :agents (:agents result)
                   :providers (:providers result)
                   :duration-ms duration-ms
                   :applied (boolean applied)
                   :status (cond applied "approved" rejected "rejected" :else "candidate")}]
      (emit! system {:event-type review-event-type
                     :entity-type :vault_note
                     :entity-id (or (:id note) (:path note))
                     :request-id (str "magi-memory-" (or (:id note) "note") "-" (util/now-str))
                     :payload payload})
      payload)))

(defn review-update!
  [system update-id {:keys [apply? source] :or {apply? false source :advice}}]
  (when-not (enabled? system)
    (throw (ex-info "MAGI memory promotion is disabled"
                    {:type :magi-memory-promotion-disabled})))
  (let [update (or (memory/get-memory-note-update (:memory-service system) update-id)
                   (throw (ex-info "Memory update proposal not found"
                                   {:type :not-found :update-id update-id})))]
    (when-not (= "pending" (:status update))
      (throw (ex-info "Memory update proposal is not pending"
                      {:type :memory-update-not-pending
                       :update-id update-id
                       :status (:status update)})))
    (let [target (sqlite/get-vault-note-by-id (:store system) (:target-id update))
          scope (or (get-in update [:changes :scope]) (:iris-scope target) "global")]
      (when-not (scope-allowed? system scope)
        (throw (ex-info "Vault Note scope is outside MAGI promotion scope"
                        {:type :magi-memory-scope-disabled :scope scope})))
      (let [content (proposal-content system update)
            request (update-request-for update content)
            start (System/nanoTime)
            result (magi/decide (:magi-service system) request)
            duration-ms (long (/ (- (System/nanoTime) start) 1000000))
            decision (normalize-keyword (:decision result))
            should-apply? (and apply?
                               (review-applies? system)
                               (= :yes decision))
            applied (when should-apply?
                      (apply-proposal! system update-id (:reason result)))
            rejected (when (and apply? (= :no decision))
                       (memory/decide-memory-note-update!
                        (:memory-service system) update-id :rejected :no (:reason result)))
            payload {:source (name source)
                     :update (dissoc update :proposed-content)
                     :input request
                     :content-hash (:proposed-revision update)
                     :decision decision
                     :reason (:reason result)
                     :judge (select-keys result [:decision :reason])
                     :filter (:filter result)
                     :agents (:agents result)
                     :providers (:providers result)
                     :duration-ms duration-ms
                     :applied (= "applied" (:status applied))
                     :status (or (:status applied) (:status rejected) (:status update))}]
        (emit! system {:event-type update-review-event-type
                       :entity-type :memory_note_update
                       :entity-id update-id
                       :request-id (str "magi-memory-update-" update-id "-" (util/now-str))
                       :payload payload})
        payload))))

(defn- same-content-review? [note event]
  (= (:revision note) (get-in event [:payload :revision])))

(defn- retry-after-error? [system event]
  (let [decision (normalize-keyword (get-in event [:payload :decision]))
        cooldown (positive-long (:failure-cooldown-minutes (config system)) 15 1440)]
    (and (= :error decision)
         (try
           (.isAfter (Instant/now)
                     (.plusSeconds (Instant/parse (:created-at event)) (* 60 cooldown)))
           (catch Exception _ true)))))

(defn- pending-review? [system note]
  (if-let [event (latest-review system note)]
    (or (= "advice" (get-in event [:payload :source]))
        (not (same-content-review? note event))
        (retry-after-error? system event))
    true))

(defn- pending-update-review? [system update]
  (if-let [event (latest-update-review system update)]
    (or (= "advice" (get-in event [:payload :source]))
        (not= (:proposed-revision update) (get-in event [:payload :content-hash]))
        (retry-after-error? system event))
    true))

(defn run-once! [system]
  (if-not (auto? system)
    {:processed 0 :skipped :disabled}
    (let [limit (positive-long (:max-candidates (config system)) 10 100)
          runnable-notes (->> (candidate-notes system)
                              (filter #(scope-allowed? system (:iris-scope %)))
                              (filter #(pending-review? system %))
                              (map #(vector :note %)))
          runnable-updates (->> (pending-updates system)
                                (filter #(pending-update-review? system %))
                                (map #(vector :update %)))
          runnable (take limit (concat runnable-notes runnable-updates))
          results (mapv (fn [[kind item]]
                          (try
                            (case kind
                              :note (review-note! system (:path item) {:apply? true :source :auto})
                              :update (review-update! system (:id item) {:apply? true :source :auto}))
                            (catch Exception e
                              (let [payload {:source "auto"
                                             :kind (name kind)
                                             :note (when (= kind :note)
                                                     (select-keys item
                                                                  [:id :path :title :iris-scope
                                                                   :body-hash :revision]))
                                             :update (when (= kind :update)
                                                       (dissoc item :proposed-content))
                                             :content-hash (if (= kind :note)
                                                             (:revision item)
                                                             (:proposed-revision item))
                                             :decision :error
                                             :reason (.getMessage e)
                                             :applied false}]
                                (emit! system {:event-type (if (= kind :note)
                                                            review-event-type
                                                            update-review-event-type)
                                               :entity-type (if (= kind :note)
                                                              :vault_note
                                                              :memory_note_update)
                                               :entity-id (if (= kind :note)
                                                            (or (:id item) (:path item))
                                                            (:id item))
                                               :payload payload})
                                payload))))
                        runnable)]
      {:processed (count results)
       :approved (count (filter :applied results))
       :pending-updates (count (pending-updates system))
       :approved-inbox (count (memory/approved-inbox-notes (:memory-service system)))
       :results results})))

(defn create-service [system-ref]
  {:system-ref system-ref
   :running? (atom false)
   :stop? (atom false)
   :last-approved-inbox (atom ::unknown)
   :worker (atom nil)})

(defn running? [service]
  (boolean (and service @(:running? service))))

(defn- sleep-until-stop! [stop? millis]
  (let [deadline (+ (System/currentTimeMillis) (long millis))]
    (loop []
      (when (and (not @stop?) (< (System/currentTimeMillis) deadline))
        (Thread/sleep (max 1 (min 1000 (- deadline (System/currentTimeMillis)))))
        (recur)))))

(defn report-approved-inbox-drift! [service system]
  (let [notes (memory/approved-inbox-notes (:memory-service system))
        current (set (map #(or (:id %) (:path %)) notes))
        previous @(:last-approved-inbox service)]
    (when (not= previous current)
      (reset! (:last-approved-inbox service) current)
      (cond
        (seq current)
        (emit! system {:event-type :memory.vault.approved_inbox_detected
                       :entity-type :system
                       :entity-id "memory"
                       :payload {:count (count notes)
                                 :notes (mapv #(select-keys % [:id :path :type :title :iris-scope])
                                              (take 50 notes))}})

        (and (not= ::unknown previous) (seq previous))
        (emit! system {:event-type :memory.vault.approved_inbox_cleared
                       :entity-type :system
                       :entity-id "memory"
                       :payload {:previous-count (count previous)}})))))

(defn start! [service]
  (when (and service (not @(:running? service)))
    (when-let [system @(:system-ref service)]
      (when (auto? system)
        (reset! (:stop? service) false)
        (reset! (:running? service) true)
        (reset! (:worker service)
                (future
                  (try
                    (while (not @(:stop? service))
                      (when-let [system* @(:system-ref service)]
                        (try
                          (run-once! system*)
                          (report-approved-inbox-drift! service system*)
                          (catch Exception e
                            (emit! system* {:event-type :memory.vault.magi_worker_failed
                                            :entity-type :system
                                            :entity-id "memory"
                                            :payload {:message (.getMessage e)}})))
                        (sleep-until-stop!
                         (:stop? service)
                         (* 1000 (positive-long (:poll-interval-seconds (config system*))
                                                60
                                                3600)))))
                    (finally
                      (reset! (:running? service) false))))))))
  service)

(defn stop! [service]
  (when service
    (reset! (:stop? service) true)
    (when-let [worker @(:worker service)]
      (future-cancel worker))
    (reset! (:worker service) nil)
    (reset! (:running? service) false))
  {:stopped (boolean service)})

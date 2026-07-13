(ns agent.memory.magi-review
  "MAGI review and optional promotion of candidate Vault Notes."
  (:require
   [agent.magi.core :as magi]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.util :as util]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def review-event-type :memory.vault.magi_evaluated)

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

(defn- note-by-path [system path]
  (some #(when (= path (:path %)) %) (candidate-notes system)))

(defn latest-review [system note]
  (first
   (sqlite/list-events (:store system)
                       {:event-type review-event-type
                        :entity-type :vault_note
                        :entity-id (or (:id note) (:path note))
                        :limit 1})))

(defn- request-for [note content]
  {:kind :yes-no
   :domain :memory-promotion
   :expected-response :permit
   :question "Should Iris promote this candidate Vault Note to approved memory?"
   :context {:note-id (:id note)
             :path (:path note)
             :type (:type note)
             :title (:title note)
             :description (:description note)
             :tags (:tags note)
             :scope (:iris-scope note)
             :confidence (:iris-confidence note)
             :origins (:origins note)
             :content-hash (:body-hash note)
             :markdown content}})

(defn- emit! [system event]
  (if-let [event-sink (:event-sink system)]
    (event-sink event)
    (sqlite/log-event! (:store system) event)))

(defn- current-candidate! [system path]
  (or (note-by-path system path)
      (throw (ex-info "Vault Note is no longer a candidate"
                      {:type :vault-note-not-candidate
                       :path path}))))

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
                    (current-candidate! system path)
                    (memory/update-vault-note-iris!
                     (:memory-service system)
                     path
                     {:status "approved"
                      :scope (:iris-scope note)}))
          payload {:source (name source)
                   :note (select-keys note [:id :path :type :title :description
                                            :tags :iris-scope :iris-confidence
                                            :origins :body-hash])
                   :input request
                   :content-hash (:body-hash note)
                   :decision decision
                   :reason (:reason result)
                   :judge (select-keys result [:decision :reason])
                   :filter (:filter result)
                   :agents (:agents result)
                   :providers (:providers result)
                   :duration-ms duration-ms
                   :applied (boolean applied)}]
      (emit! system {:event-type review-event-type
                     :entity-type :vault_note
                     :entity-id (or (:id note) (:path note))
                     :request-id (str "magi-memory-" (or (:id note) "note") "-" (util/now-str))
                     :payload payload})
      payload)))

(defn- same-content-review? [note event]
  (= (:body-hash note) (get-in event [:payload :content-hash])))

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

(defn run-once! [system]
  (if-not (auto? system)
    {:processed 0 :skipped :disabled}
    (let [limit (positive-long (:max-candidates (config system)) 10 100)
          runnable (->> (candidate-notes system)
                        (filter #(scope-allowed? system (:iris-scope %)))
                        (filter #(pending-review? system %))
                        (take limit))
          results (mapv (fn [note]
                          (try
                            (review-note! system (:path note) {:apply? true :source :auto})
                            (catch Exception e
                              (let [payload {:source "auto"
                                             :note (select-keys note [:id :path :title :iris-scope :body-hash])
                                             :content-hash (:body-hash note)
                                             :decision :error
                                             :reason (.getMessage e)
                                             :applied false}]
                                (emit! system {:event-type review-event-type
                                               :entity-type :vault_note
                                               :entity-id (or (:id note) (:path note))
                                               :payload payload})
                                payload))))
                        runnable)]
      {:processed (count results)
       :approved (count (filter :applied results))
       :results results})))

(defn create-service [system-ref]
  {:system-ref system-ref
   :running? (atom false)
   :stop? (atom false)
   :worker (atom nil)})

(defn running? [service]
  (boolean (and service @(:running? service))))

(defn- sleep-until-stop! [stop? millis]
  (let [deadline (+ (System/currentTimeMillis) (long millis))]
    (loop []
      (when (and (not @stop?) (< (System/currentTimeMillis) deadline))
        (Thread/sleep (max 1 (min 1000 (- deadline (System/currentTimeMillis)))))
        (recur)))))

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

(ns agent.api.handlers.ui.memory
  "Memory review, search and vault operation handlers."
  (:require
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.memory.core :as memory]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.memory.recall :as memory-recall]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.ui.memory :as ui-memory]
   [clojure.string :as str]
   [agent.api.handlers.ui.params :as params]))

(defn memory-workspace [system request]
  (let [limit (params/request-ui-limit request 20 100)
        {:keys [tab note_id]} (-> request :parameters :query)]
    (responses/html-response
     200
     (ui/memory-workspace-fragment system nil {:limit limit
                                               :tab tab
                                               :note-id note_id}))))

(defn memory-vault-detail [system _request note-id]
  (let [store (get-in system [:memory-service :store])
        note (sqlite/get-vault-note-by-id store note-id)
        review (first (sqlite/list-events store
                                          {:event-type memory-magi-review/review-event-type
                                           :entity-type :vault_note
                                           :entity-id note-id
                                           :limit 1}))]
    (responses/html-response 200 (ui-memory/vault-note-detail-fragment note review))))

(defn memory-update-detail [system _request update-id]
  (responses/html-response
   200
   (ui-memory/memory-update-detail-fragment
    (sqlite/get-memory-note-update (get-in system [:memory-service :store]) update-id))))

(defn memory-search [system request]
  (let [{:keys [query]} (h/read-form-body request)]
    (responses/html-response 200
                             (ui/memory-search-results-fragment
                              (memory-recall/recall (:memory-service system) query)))))

(defn- parse-int-form [value]
  (when-not (str/blank? (str value))
    (Integer/parseInt (str value))))

(defn- form-scope [{:keys [scope_type scope_id]}]
  (when-not (str/blank? (str scope_type))
    (cond-> {:type scope_type}
      (not (str/blank? (str scope_id))) (assoc :id scope_id))))

(defn- memory-tool-input [{:keys [action query old_text new_text
                                  expected_revision]
                           :as body}]
  (let [action* (keyword (str/lower-case (str action)))
        scratchpad-replace? (= :scratchpad-replace action*)]
    (cond-> {:action action}
      (not (str/blank? (str query))) (assoc :query query)
      (parse-int-form (:limit body)) (assoc :limit (parse-int-form (:limit body)))
      (form-scope body) (assoc :scope (form-scope body))
      (or (not (str/blank? (str old_text))) scratchpad-replace?)
      (assoc :old-text (or old_text ""))
      (or (not (str/blank? (str new_text))) scratchpad-replace?)
      (assoc :new-text (or new_text ""))
      (not (str/blank? (str expected_revision))) (assoc :expected-revision expected_revision))))

(defn- memory-tool-target [{:keys [action] :as input}]
  (case (keyword (str/lower-case (str action)))
	    :recall [:memory_recall (select-keys input [:query :limit :scope])]
	    :vault-search [:vault_search (select-keys input [:query :limit])]
	    :scratchpad-read [:scratchpad_read (select-keys input [:scope])]
	    :scratchpad-search [:scratchpad_search (select-keys input [:query :scope])]
	    :scratchpad-replace [:scratchpad_replace (select-keys input [:old-text :new-text :expected-revision :scope])]))

(defn- memory-search-source-json [system {:keys [action query limit scope]}]
  (when (and (= :recall (keyword (str/lower-case (str action))))
             (not (str/blank? (str query))))
    (memory-recall/recall (:memory-service system)
                          query
                          (cond-> {}
                            limit (assoc :limit limit)
                            scope (assoc :scope scope)))))

(defn memory-tool-run [system request]
  (try
    (let [body (h/read-form-body request)
          input (memory-tool-input body)
          [tool-name tool-input] (memory-tool-target input)
          result (tools/execute-tool (:tool-registry system)
                                     tool-name
                                     tool-input
                                     (assoc
                                      (tools-h/execution-context system :ui tool-name tool-input
                                                                 {:user "ui-memory"})
                                      :permissions #{:memory-read :memory-write}))]
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? true
         :input input
         :result result
         :source-json (memory-search-source-json system input)})))
    (catch Exception e
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? false
         :error (.getMessage e)
         :details (ex-data e)})))))

(defn- memory-reset-response
  ([system surface reset!]
   (memory-reset-response system surface reset! {}))
  ([system surface reset! opts]
   (try
     (responses/html-response
      200
      (ui/memory-workspace-fragment system
                                    {:ok? true
                                     :surface surface
                                     :result (reset!)}
                                    opts))
     (catch Exception e
      (responses/html-response
       200
       (ui/memory-workspace-fragment system
                                     {:ok? false
                                      :surface surface
                                      :error (.getMessage e)
                                      :details (ex-data e)}
                                     opts))))))

(defn memory-vault-reindex [system _request]
  (memory-reset-response system
                         "Vault reindex"
                         #(memory/reindex-vault! (:memory-service system))))

(defn memory-vault-status [system request]
  (let [body (h/read-form-body request)]
    (memory-reset-response system
                           "Vault note"
                           #(if (= "approved" (:status body))
                              (memory-magi-review/apply-candidate!
                               system (:path body) (:scope body))
                              (memory/update-vault-note-iris!
                               (:memory-service system)
                               (:path body)
                               (select-keys body [:scope :status])))
                           {:tab :approvals :limit 20})))

(defn memory-vault-magi [system request]
  (let [{:keys [path action]} (h/read-form-body request)
        review? (= "review" action)]
    (memory-reset-response system
                           (if review? "MAGI review" "MAGI advice")
                           #(memory-magi-review/review-note!
                             system
                             path
                             {:apply? review?
                              :source (if review? :manual :advice)})
                           {:tab :approvals :limit 20})))

(defn memory-vault-magi-update [system request]
  (let [{:keys [update_id action]} (h/read-form-body request)
        review? (= "review" action)]
    (memory-reset-response system
                           (if review? "MAGI update review" "MAGI update advice")
                           #(memory-magi-review/review-update!
                             system
                             update_id
                             {:apply? review?
                              :source (if review? :manual :advice)})
                           {:tab :approvals :limit 20})))

(defn memory-proposal-decision [system request]
  (let [{:keys [proposal_id decision]} (h/read-form-body request)]
    (memory-reset-response
     system
     "Memory proposal"
     #(case decision
        "apply" (memory-magi-review/apply-proposal! system proposal_id "Operator approved")
        "reject" (memory/decide-memory-note-update!
                  (:memory-service system) proposal_id :rejected :no "Operator rejected"))
     {:tab :approvals :limit 20})))

(ns agent.tools.approvals
  "Persisted approval flow for sensitive tool executions."
  (:require
   [agent.magi.core :as magi]
   [agent.persistence.sqlite :as sqlite]
   [agent.security :as security]
   [agent.tools.core :as tools]
   [clojure.set :as set]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn approval-required?
  [tool-name _input]
  (case tool-name
    :shell true
    :homeassistant (= :call_service (some-> (:action _input) keyword))
    :cronjob (not (contains? #{:list :get :history :preview}
                             (some-> (:action _input) keyword)))
    (:fs_write :fs_create :fs_replace :fs_delete :fs_mkdir) true
    false))

(defn granted-permissions
  [tool-name _input]
  (case tool-name
    :shell #{:shell-exec}
    :homeassistant #{:homeassistant}
    :cronjob (if (contains? #{:list :get :history :preview}
                            (some-> (:action _input) keyword))
               #{:cron-read}
               #{:cron-read :cron-manage})
    (:fs_write :fs_create :fs_replace :fs_delete :fs_mkdir) #{:filesystem-write}
    (:fs_read :fs_list) #{:filesystem-read}
    #{}))

(defn input-hash [input]
  (security/sha256-hex (security/canonical-json input)))

(defn default-expires-at
  "ISO instant when a newly minted approval expires, per
   [:tools :approvals :ttl-seconds] (default 900)."
  [system]
  (str (.plusSeconds (Instant/now)
                     (long (get-in system [:config :tools :approvals :ttl-seconds] 900)))))

(defn expires-at
  [ttl-seconds]
  (str (.plusSeconds (Instant/now) (long (or ttl-seconds 900)))))

(defn- expired? [expires-at]
  (when (seq expires-at)
    (.isAfter (Instant/now) (Instant/parse expires-at))))

(defn create-request!
  [store {:keys [tool-name input requested-permissions requested-by reason expires-at]}]
  (sqlite/create-tool-approval! store
                                {:tool-name tool-name
                                 :input input
                                 :input-hash (input-hash input)
                                 :requested-permissions (or requested-permissions
                                                            (granted-permissions tool-name input))
                                 :requested-by requested-by
                                 :reason reason
                                 :expires-at expires-at}))

(defn- emit! [event-sink event]
  (when event-sink
    (event-sink event)))

(defn log-requested!
  [event-sink approval]
  (emit! event-sink
         {:event-type :tool.approval.requested
          :entity-type :tool_approval
          :entity-id (:id approval)
          :payload {:tool-name (:tool-name approval)
                    :requested-by (:requested-by approval)
                    :requested-permissions (mapv name (:requested-permissions approval))
                    :expires-at (:expires-at approval)}}))

(defn log-decision!
  [event-sink approval status actor reason]
  (emit! event-sink
         {:event-type (keyword (str "tool.approval." (name status)))
          :entity-type :tool_approval
          :entity-id (:id approval)
          :payload {:tool-name (:tool-name approval)
                    :actor actor
                    :decision status
                    :reason reason}}))

(defn- log-magi-evaluated!
  [event-sink approval result duration-ms]
  (emit! event-sink
         {:event-type :tool.approval.magi_evaluated
          :entity-type :tool_approval
          :entity-id (:id approval)
          :payload {:tool-name (:tool-name approval)
                    :input (:input approval)
                    :decision (:decision result)
                    :reason (:reason result)
                    :judge {:decision (:decision result)
                            :reason (:reason result)}
                    :filter (:filter result)
                    :agents (:agents result)
                    :providers (:providers result)
                    :duration-ms duration-ms}}))

(defn list-requests
  ([store] (list-requests store {}))
  ([store opts]
   (sqlite/list-tool-approvals store opts)))

(defn request-expired?
  [approval]
  (boolean (expired? (:expires-at approval))))

(defn effective-status
  [approval]
  (if (and (#{"pending" "approved"} (:status approval))
           (request-expired? approval))
    "expired"
    (:status approval)))

(defn review-required?
  [approval]
  (= "pending" (effective-status approval)))

(defn runnable?
  [approval]
  (= "approved" (effective-status approval)))

(defn list-review-requests
  ([store] (list-review-requests store {}))
  ([store {:keys [limit] :or {limit 100}}]
   (->> (list-requests store {:status "pending" :limit 1000})
        (filter review-required?)
        (take limit)
        vec)))

(defn list-review-records
  "Pending review requests first, then recent history, bounded by limit."
  ([store] (list-review-records store {}))
  ([store {:keys [limit] :or {limit 50}}]
   (let [pending (list-review-requests store {:limit limit})
         pending-ids (set (map :id pending))]
     (->> (list-requests store {:limit limit})
          (remove #(contains? pending-ids (:id %)))
          (concat pending)
          (take limit)
          vec))))

(defn get-request
  [store approval-id]
  (sqlite/get-tool-approval store approval-id))

(defn- clean-reason [reason]
  (some-> reason str str/trim not-empty))

(defn approve!
  [store approval-id actor reason]
  (when-let [approval (get-request store approval-id)]
    (when (request-expired? approval)
      (throw (tools/tool-error :approval-expired
                               "Approval request is expired"
                               {:approval-id approval-id
                                :expires-at (:expires-at approval)}))))
  (sqlite/decide-tool-approval! store approval-id :approved actor (clean-reason reason)))

(defn deny!
  [store approval-id actor reason]
  (sqlite/decide-tool-approval! store approval-id :denied actor (clean-reason reason)))

(defn- approval-reason [decision result]
  (let [reason (some-> (:reason result) str str/trim not-empty)]
    (case decision
      :yes (str "magi: yes" (when reason (str " - " reason)))
      :no (str "magi: no" (when reason (str " - " reason)))
      :conditional (str "magi: conditional - human review required: "
                        (or reason "specified condition"))
      :info (str "magi: info" (when reason (str " - " reason)))
      :error (str "magi: error" (when reason (str " - " reason)))
      (str "magi: " (name decision)))))

(defn- magi-action [magi-service result]
  (let [decision (:decision result)]
    (case (magi/mode magi-service)
      :assistive :pending
      :auto-approve
      (case decision
        :yes :approve
        :no :deny
        :conditional :pending
        (:info :error) (case (magi/fallback magi-service)
                         :deny :deny
                         :human :pending
                         :pending))
      :pending)))

(defn- maybe-decision-status [action]
  (case action
    :approve :approved
    :deny :denied
    nil))

(defn evaluate-magi-for-approval!
  [store {:keys [magi-service event-sink]} approval tool-description input context]
  (if-not (and magi-service
               (magi/approval-applicable? magi-service tool-description))
    approval
    (let [start (System/nanoTime)
          result (magi/decide magi-service
                              (magi/approval-question approval tool-description input context))
          duration-ms (long (/ (- (System/nanoTime) start) 1000000))
          action (magi-action magi-service result)
          decision (maybe-decision-status action)
          reason (approval-reason (:decision result) result)]
      (log-magi-evaluated! event-sink approval result duration-ms)
      (case action
        :approve
        (let [updated (approve! store (:id approval) "magi" reason)]
          (log-decision! event-sink updated decision "magi" reason)
          updated)

        :deny
        (let [updated (deny! store (:id approval) "magi" reason)]
          (log-decision! event-sink updated decision "magi" reason)
          updated)

        approval))))

(defn request-with-magi!
  [store opts request tool-description context]
  (let [approval (create-request! store request)]
    (log-requested! (:event-sink opts) approval)
    (evaluate-magi-for-approval! store opts approval tool-description (:input request) context)))

(defn valid-approval?
  ([approval tool-name input]
   (valid-approval? approval tool-name input {}))
  ([approval tool-name input context]
   (and approval
        (= "approved" (:status approval))
        (= (name tool-name) (:tool-name approval))
        (= (input-hash input)
           (or (:input-hash approval)
               (input-hash (:input approval))))
        (not (expired? (:expires-at approval)))
        (let [requested-by (:requested-by approval)
              user (:user context)]
          (or (str/blank? requested-by)
              (str/blank? user)
              (= requested-by user))))))

(defn- approval-permissions [approval]
  (set (or (seq (:requested-permissions approval))
           (granted-permissions (keyword (:tool-name approval)) (:input approval)))))

(defn- approval-invalid
  [message details]
  (tools/tool-error :approval-invalid message details))

(defn validate-approved-request!
  [approval approval-id tool-name input context]
  (when-not approval
    (throw (tools/tool-error :approval-not-found
                             "Approval request not found"
                             {:approval-id approval-id})))
  (when-not (= "approved" (:status approval))
    (throw (tools/tool-error :approval-not-approved
                             "Approval request is not approved"
                             (cond-> {:approval-id approval-id
                                      :status (:status approval)}
                               (:decision-reason approval)
                               (assoc :reason (:decision-reason approval))))))
  (when (expired? (:expires-at approval))
    (throw (tools/tool-error :approval-expired
                             "Approval request is expired"
                             {:approval-id approval-id
                              :expires-at (:expires-at approval)})))
  (when-not (= (name tool-name) (:tool-name approval))
    (throw (approval-invalid
            "Approval request does not match tool"
            {:approval-id approval-id
             :expected-tool (:tool-name approval)
             :actual-tool (name tool-name)})))
  (when-not (= (input-hash input)
               (or (:input-hash approval)
                   (input-hash (:input approval))))
    (throw (approval-invalid
            "Approval request does not match input"
            {:approval-id approval-id})))
  (let [requested-by (:requested-by approval)
        user (:user context)]
    (when-not (or (str/blank? requested-by)
                  (str/blank? user)
                  (= requested-by user))
      (throw (tools/tool-error :approval-forbidden
                               "Approval request belongs to another requester"
                               {:approval-id approval-id
                                :requested-by requested-by
                                :user user}))))
  approval)

(defn resolve-approved-request
  [store approval-id]
  (let [approval (get-request store approval-id)]
    (when-not approval
      (throw (tools/tool-error :approval-not-found
                               "Approval request not found"
                               {:approval-id approval-id})))
    (when-not (= "approved" (:status approval))
      (throw (tools/tool-error :approval-not-approved
                               "Approval request is not approved"
                               (cond-> {:approval-id approval-id
                                        :status (:status approval)}
                                 (:decision-reason approval)
                                 (assoc :reason (:decision-reason approval))))))
    (when (expired? (:expires-at approval))
      (throw (tools/tool-error :approval-expired
                               "Approval request is expired"
                               {:approval-id approval-id
                                :expires-at (:expires-at approval)})))
    {:tool-name (keyword (:tool-name approval))
     :input (:input approval)
     :permissions (approval-permissions approval)
     :approval approval}))

(defn resolve-valid-request
  [store approval-id tool-name input context]
  (let [approval (validate-approved-request!
                  (get-request store approval-id)
                  approval-id
                  tool-name
                  input
                  context)]
    {:tool-name (keyword (:tool-name approval))
     :input (:input approval)
     :permissions (approval-permissions approval)
     :approval approval}))

(defn- default-approval-reason [tool-name input]
  (or (some-> (or (:purpose input) (get input "purpose") (:reason input) (get input "reason"))
              str
              str/trim
              not-empty)
      (str "Agent requested " (name tool-name))))

(defn- approval-block
  ([reason] (approval-block reason nil))
  ([reason approval-id]
   (cond-> {:block true
            :reason reason}
     approval-id (assoc :approval-id approval-id))))

(defn- denied-block [reason]
  {:block true
   :type :tool-blocked
   :reason reason})

(defn create-policy-hook
  [store-or-opts]
  (let [{:keys [store magi-service event-sink approval-ttl-seconds]}
        (if (map? store-or-opts)
          store-or-opts
          {:store store-or-opts})]
    (fn [{:keys [tool input context]}]
      (let [tool-name (:name tool)]
        ;; `enforce-approval!` only calls this hook for inputs the tool itself
        ;; marked sensitive. Keep legacy action rules for tools whose public
        ;; metadata is intentionally read-oriented, and honor the generic
        ;; approval contract for new tools such as cronjob.
        (when (or (approval-required? tool-name input)
                  (:approval-sensitive? tool))
          (let [approval-id (:approval-id context)
                approval (when approval-id (get-request store approval-id))]
            (if (valid-approval? approval tool-name input context)
              {:allow true
               :approval-id (:id approval)
               :reason (:decision-reason approval)}
              (if (and magi-service
                       (= :auto-approve (magi/mode magi-service))
                       (magi/approval-applicable? magi-service tool))
                (let [created (request-with-magi!
                               store
                               {:magi-service magi-service
                                :event-sink event-sink}
                               {:tool-name tool-name
                                :input input
                                :requested-permissions
                                (set/union (set (:required-permissions tool))
                                           (granted-permissions tool-name input))
                                :requested-by (or (:user context) "tool")
                                :reason (default-approval-reason tool-name input)
                                :expires-at (expires-at approval-ttl-seconds)}
                               tool
                               context)]
                  (case (:status created)
                    "approved" {:allow true
                                :approval-id (:id created)
                                :reason (:decision-reason created)}
                    "denied" (denied-block (:decision-reason created))
                    (approval-block (str "Sensitive tool requires approved request approval_id=" (:id created))
                                    (:id created))))
                (approval-block "Sensitive tool requires approved request")))))))))

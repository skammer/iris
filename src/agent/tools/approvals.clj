(ns agent.tools.approvals
  "Persisted approval flow for sensitive tool executions."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.security :as security]
   [agent.tools.core :as tools]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn approval-required?
  [tool-name _input]
  (case tool-name
    :shell true
    (:fs_write :fs_create :fs_replace :fs_delete :fs_mkdir) true
    false))

(defn granted-permissions
  [tool-name _input]
  (case tool-name
    :shell #{:shell-exec}
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

(defn- expired? [expires-at]
  (when (seq expires-at)
    (.isAfter (Instant/now) (Instant/parse expires-at))))

(defn create-request!
  [store {:keys [tool-name input requested-by reason expires-at]}]
  (sqlite/create-tool-approval! store
                                {:tool-name tool-name
                                 :input input
                                 :input-hash (input-hash input)
                                 :requested-permissions (granted-permissions tool-name input)
                                 :requested-by requested-by
                                 :reason reason
                                 :expires-at expires-at}))

(defn list-requests
  ([store] (list-requests store {}))
  ([store opts]
   (sqlite/list-tool-approvals store opts)))

(defn get-request
  [store approval-id]
  (sqlite/get-tool-approval store approval-id))

(defn approve!
  [store approval-id actor reason]
  (sqlite/decide-tool-approval! store approval-id :approved actor reason))

(defn deny!
  [store approval-id actor reason]
  (sqlite/decide-tool-approval! store approval-id :denied actor reason))

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
                             {:approval-id approval-id
                              :status (:status approval)})))
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
                               {:approval-id approval-id
                                :status (:status approval)})))
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

(defn create-policy-hook
  [store]
  (fn [{:keys [tool input context]}]
    (let [tool-name (:name tool)]
      (when (approval-required? tool-name input)
        (let [approval-id (:approval-id context)
              approval (when approval-id (get-request store approval-id))]
          (if (valid-approval? approval tool-name input context)
            {:allow true}
            {:block true
             :reason "Sensitive tool requires approved request"}))))))

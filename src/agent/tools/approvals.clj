(ns agent.tools.approvals
  "Persisted approval flow for sensitive tool executions."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn approval-required?
  [tool-name input]
  (case tool-name
    :shell true
    :fs (contains? #{:write :delete :mkdir}
                   (cond
                     (keyword? (:action input)) (:action input)
                     (string? (:action input)) (keyword (str/lower-case (:action input)))
                     :else nil))
    false))

(defn granted-permissions
  [tool-name input]
  (case tool-name
    :shell #{:shell-exec}
    :fs (case (cond
                (keyword? (:action input)) (:action input)
                (string? (:action input)) (keyword (str/lower-case (:action input)))
                :else nil)
          (:write :delete :mkdir) #{:filesystem-write}
          #{:filesystem-read})
    #{}))

(defn create-request!
  [store {:keys [tool-name input requested-by reason]}]
  (sqlite/create-tool-approval! store
                                {:tool-name tool-name
                                 :input input
                                 :requested-by requested-by
                                 :reason reason}))

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

(defn- canonicalize-input [input]
  (cond
    (map? input)
    (->> input
         (remove (fn [[k v]]
                   (or (nil? v)
                       (and (= k :argv) (vector? v)))))
         (map (fn [[k v]]
                [k (cond
                     (keyword? v) (name v)
                     (vector? v) (mapv #(if (keyword? %) (name %) %) v)
                     :else v)]))
         (into (sorted-map)))
    :else input))

(defn valid-approval?
  [approval tool-name input]
  (and approval
       (= "approved" (:status approval))
       (= (name tool-name) (:tool-name approval))
       (= (canonicalize-input input)
          (canonicalize-input (:input approval)))))

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
    {:tool-name (keyword (:tool-name approval))
     :input (:input approval)
     :permissions (granted-permissions (keyword (:tool-name approval)) (:input approval))
     :approval approval}))

(defn create-policy-hook
  [store]
  (fn [{:keys [tool input context]}]
    (let [tool-name (:name tool)]
      (when (approval-required? tool-name input)
        (let [approval-id (:approval-id context)
              approval (when approval-id (get-request store approval-id))]
          (when-not (valid-approval? approval tool-name input)
            {:block true
             :reason "Sensitive tool requires approved request"}))))))

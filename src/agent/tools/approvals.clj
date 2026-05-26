(ns agent.tools.approvals
  "Persisted approval flow for sensitive tool executions."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)
   (java.time Instant)))

(defn approval-required?
  [tool-name input]
  (case tool-name
    :shell true
    :fs (contains? #{:write :create :replace :delete :mkdir}
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
          (:write :create :replace :delete :mkdir) #{:filesystem-write}
          #{:filesystem-read})
    #{}))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(declare canonicalize-input)

(defn input-hash [input]
  (sha256-hex (json/generate-string (canonicalize-input input) {:canonical true})))

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

(defn- canonicalize-input [input]
  (cond
    (map? input)
    (->> input
         (remove (fn [[k v]]
                   (nil? v)))
         (map (fn [[k v]]
                [k (cond
                     (keyword? v) (name v)
                     (vector? v) (mapv #(if (keyword? %) (name %) %) v)
                     :else v)]))
         (into (sorted-map)))
    :else input))

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
          (if (valid-approval? approval tool-name input context)
            {:allow true}
            {:block true
             :reason "Sensitive tool requires approved request"}))))))

(ns agent.tools.common.magi
  (:require
   [agent.magi.core :as magi]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- normalize-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/replace value #"_" "-")))
    :else value))

(defn create-magi-tool [magi-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :magi
     "Evaluate a question through MAGI oversight. Read-only; cannot approve or deny existing approval rows."
     :category :system
     :required-permissions #{:magi-evaluate}
     :input-schema [:map {:closed true}
                    [:question :string]
                    [:kind {:optional true} [:maybe [:or
                                                     [:enum :yes-no :info]
                                                     [:enum "yes-no" "info"]]]]
                    [:context {:optional true} [:maybe :any]]
                    [:expected-response {:optional true} [:maybe [:or
                                                                  [:enum :permit :classify :opine]
                                                                  [:enum "permit" "classify" "opine"]]]]
                    [:domain {:optional true} [:maybe [:or
                                                       [:enum :tool-approval :memory-promotion :policy :other]
                                                       [:enum "tool-approval" "memory-promotion" "policy" "other"]]]]]
     :operation :read
     :approval-sensitive? false
     :activates-tools? false
     :source :builtin)
    :validate-fn
    (fn [input]
      (when (str/blank? (:question input))
        (throw (tools/validation-error "question must be a non-blank string" {:input input})))
      (-> input
          (update :kind normalize-keyword)
          (update :expected-response normalize-keyword)
          (update :domain normalize-keyword)))
    :execute-fn
    (fn [input context]
      (ensure-permission! context :magi-evaluate)
      (assoc (magi/decide magi-service
                          {:question (:question input)
                           :kind (or (:kind input) :yes-no)
                           :context (:context input)
                           :expected-response (or (:expected-response input) :permit)
                           :domain (or (:domain input) :policy)})
             :mode :tool))}))

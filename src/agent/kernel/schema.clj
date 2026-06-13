(ns agent.kernel.schema
  "Malli directive contract."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(def directive-types
  #{:await :complete :tool-call})

(def current-step-schema-version "agent.step.v1")

(def await-payload-schema
  [:map {:closed true}
   [:reason {:optional true} [:maybe :string]]
   [:agent-id {:optional true} [:maybe :string]]
   [:timeout-ms {:optional true} [:int {:min 1}]]])

(def tool-call-payload-schema
  [:map {:closed true}
   [:tool-name [:or :string :keyword]]
   [:input :any]
   [:context {:optional true} [:map-of :any :any]]])

(def complete-payload-schema
  [:map {:closed true}
   [:result :any]])

(def directive-schema
  [:multi {:dispatch :type}
   [:await [:map {:closed true}
            [:type [:= :await]]
            [:payload await-payload-schema]]]
   [:tool-call [:map {:closed true}
                [:type [:= :tool-call]]
                [:payload tool-call-payload-schema]]]
   [:complete [:map {:closed true}
               [:type [:= :complete]]
               [:payload complete-payload-schema]]]])

(def receipt-schema
  [:map {:closed true}
   [:directive [:or :keyword :string]]
   [:status [:or :keyword :string]]
   [:reason {:optional true} [:maybe :string]]
   [:tool-name {:optional true} [:or :keyword :string]]
   [:tool-call-id {:optional true} [:maybe :string]]
   [:error-type {:optional true} [:maybe [:or :keyword :string]]]
   [:input {:optional true} :any]
   [:result {:optional true} :any]])

(def step-schema
  [:map {:closed true}
   [:schema-version :string]
   [:state {:optional true} [:map-of :any :any]]
   [:directives [:vector directive-schema]]
   [:receipts {:optional true} [:vector receipt-schema]]])

(defn normalize-directive
  [directive]
  (cond-> directive
    (string? (:type directive)) (update :type keyword)))

(defn normalize-step
  [step]
  (-> step
      (update :schema-version #(or % current-step-schema-version))
      (update :directives #(mapv normalize-directive (or % [])))
      (update :receipts #(vec (or % [])))
      (update :state #(or % {}))))

(defn validate!
  [schema value label]
  (if (m/validate schema value)
    value
    (throw (ex-info (str label " failed schema validation")
                    {:type :validation-failed
                     :schema label
                     :errors (me/humanize (m/explain schema value))
                     :value value}))))

(defn validate-directive!
  [directive]
  (validate! directive-schema (normalize-directive directive) :directive))

(defn validate-step!
  [step]
  (validate! step-schema (normalize-step step) :planner-step))

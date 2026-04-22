(ns agent.kernel.schema
  "Malli directive contract and planner JSON Schema."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as json-schema]))

(def directive-types
  #{:spawn-worker :send-message :await :complete :tool-call :state-patch})

(def current-step-schema-version "agent.step.v1")

(def spawn-worker-payload-schema
  [:map {:closed true}
   [:task :any]
   [:name {:optional true} [:maybe :string]]
   [:role {:optional true} [:maybe :string]]
   [:capability-bundle {:optional true} :any]
   [:memory-scopes {:optional true} [:vector :any]]
   [:budgets {:optional true} :any]
   [:system-prompt {:optional true} [:maybe :string]]
   [:parent-id {:optional true} [:maybe :string]]])

(def await-payload-schema
  [:map {:closed true}
   [:reason {:optional true} [:maybe :string]]
   [:agent-id {:optional true} [:maybe :string]]
   [:timeout-ms {:optional true} [:int {:min 1}]]])

(def tool-call-payload-schema
  [:map {:closed true}
   [:tool-name [:or :string :keyword]]
   [:input :any]
   [:context {:optional true} :any]])

(def send-message-payload-schema
  [:map {:closed true}
   [:agent-id {:optional true} [:maybe :string]]
   [:message [:map
              [:content :string]
              [:role {:optional true} [:maybe :string]]]]])

(def state-patch-payload-schema
  [:map {:closed true}
   [:patch [:map-of :any :any]]])

(def complete-payload-schema
  [:map {:closed true}
   [:result :any]])

(def directive-schema
  [:multi {:dispatch :type}
   [:spawn-worker [:map {:closed true}
                   [:type [:= :spawn-worker]]
                   [:payload spawn-worker-payload-schema]]]
   [:await [:map {:closed true}
            [:type [:= :await]]
            [:payload await-payload-schema]]]
   [:tool-call [:map {:closed true}
                [:type [:= :tool-call]]
                [:payload tool-call-payload-schema]]]
   [:send-message [:map {:closed true}
                   [:type [:= :send-message]]
                   [:payload send-message-payload-schema]]]
   [:state-patch [:map {:closed true}
                  [:type [:= :state-patch]]
                  [:payload state-patch-payload-schema]]]
   [:complete [:map {:closed true}
               [:type [:= :complete]]
               [:payload complete-payload-schema]]]])

(def receipt-schema
  [:map
   [:directive [:or :keyword :string]]
   [:status [:or :keyword :string]]])

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

(defn directive-json-schema []
  (json-schema/transform directive-schema))

(defn planner-json-schema []
  (json-schema/transform step-schema))

(ns agent.runtime.schema
  "Canonical runtime message and event contracts."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   (java.time Instant)))

(def message-block-types
  #{:text :thinking :image :tool-call :tool-result :custom})

(def runtime-event-types
  #{:agent-start
    :agent-end
    :turn-start
    :turn-end
    :message-start
    :message-update
    :message-end
    :nudge-injected
    :guardrail-blocked
    :tool-execution-start
    :tool-execution-update
    :tool-execution-end})

(defn- now-str [] (str (Instant/now)))

(defn- normalize-token [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (-> value
                                 str/trim
                                 str/lower-case
                                 (str/replace #"_" "-")))
    :else value))

(defn normalize-block-type [value]
  (case (normalize-token value)
    :toolcall :tool-call
    :tool-call :tool-call
    :tool-result :tool-result
    :toolresult :tool-result
    :text :text
    :thinking :thinking
    :image :image
    :custom :custom
    (normalize-token value)))

(def text-block-schema
  [:map {:closed true}
   [:type [:= :text]]
   [:text :string]
   [:annotations {:optional true} :any]])

(def thinking-block-schema
  [:map {:closed true}
   [:type [:= :thinking]]
   [:text :string]
   [:signature {:optional true} [:maybe :string]]])

(def image-source-schema
  [:map {:closed true}
   [:type [:enum :url :base64 :file]]
   [:value :string]
   [:media-type {:optional true} [:maybe :string]]])

(def image-block-schema
  [:map {:closed true}
   [:type [:= :image]]
   [:source image-source-schema]
   [:alt {:optional true} [:maybe :string]]])

(def tool-call-block-schema
  [:map {:closed true}
   [:type [:= :tool-call]]
   [:id {:optional true} [:maybe :string]]
   [:name [:or :keyword :string]]
   [:arguments {:optional true} [:map-of :any :any]]
   [:raw {:optional true} :any]])

(def tool-result-block-schema
  [:map {:closed true}
   [:type [:= :tool-result]]
   [:tool-call-id :string]
   [:name {:optional true} [:maybe [:or :keyword :string]]]
   [:status {:optional true} [:maybe [:or :keyword :string]]]
   [:content :any]
   [:raw {:optional true} :any]])

(def custom-block-schema
  [:map {:closed true}
   [:type [:= :custom]]
   [:kind [:or :keyword :string]]
   [:data :any]])

(def message-block-schema
  [:multi {:dispatch :type}
   [:text text-block-schema]
   [:thinking thinking-block-schema]
   [:image image-block-schema]
   [:tool-call tool-call-block-schema]
   [:tool-result tool-result-block-schema]
   [:custom custom-block-schema]])

(def assistant-turn-schema
  [:map {:closed true}
   [:provider [:maybe [:or :keyword :string]]]
   [:model [:maybe :string]]
   [:response-model [:maybe :string]]
   [:response-id [:maybe :string]]
   [:content [:vector message-block-schema]]
   [:usage [:maybe [:map-of :any :any]]]
   [:stop-reason [:maybe [:or :keyword :string]]]
   [:error [:maybe :any]]
   [:timestamp :string]])

(defn- event-schema [event-type]
  [:map {:closed true}
   [:event-type [:= event-type]]
   [:entity-type {:optional true} [:maybe [:or :keyword :string]]]
   [:entity-id {:optional true} [:maybe :string]]
   [:request-id {:optional true} [:maybe :string]]
   [:timestamp :string]
   [:payload {:optional true} :any]])

(def runtime-event-schema
  (into [:multi {:dispatch :event-type}]
        (map (fn [event-type] [event-type (event-schema event-type)])
             runtime-event-types)))

(defn validate!
  [schema value label]
  (if (m/validate schema value)
    value
    (throw (ex-info (str label " failed schema validation")
                    {:type :validation-failed
                     :schema label
                     :errors (me/humanize (m/explain schema value))
                     :value value}))))

(defn validate-message-block! [block]
  (validate! message-block-schema block :message-block))

(defn validate-assistant-turn! [turn]
  (validate! assistant-turn-schema turn :assistant-turn))

(defn validate-runtime-event! [event]
  (validate! runtime-event-schema event :runtime-event))

(defn normalize-block
  [block]
  (let [block* (cond
                 (string? block) {:type :text :text block}
                 (map? block) (update block :type normalize-block-type)
                 :else {:type :custom :kind :value :data block})]
    (case (:type block*)
      :text (-> {:type :text
                 :text (str (or (:text block*) (:content block*) ""))}
                (cond-> (contains? block* :annotations)
                  (assoc :annotations (:annotations block*))))
      :thinking (-> {:type :thinking
                     :text (str (or (:text block*)
                                    (:thinking block*)
                                    (:content block*)
                                    ""))}
                    (cond-> (contains? block* :signature)
                      (assoc :signature (:signature block*))))
      :image (-> {:type :image
                  :source (update (:source block*) :type normalize-token)}
                 (cond-> (contains? block* :alt)
                   (assoc :alt (:alt block*))))
      :tool-call (cond-> {:type :tool-call
                          :name (or (:name block*) (:tool-name block*) (:tool_name block*))
                          :arguments (or (:arguments block*) (:input block*) (:args block*) {})}
                   (contains? block* :id) (assoc :id (:id block*))
                   (contains? block* :raw) (assoc :raw (:raw block*)))
      :tool-result (cond-> {:type :tool-result
                            :tool-call-id (or (:tool-call-id block*) (:tool_call_id block*))
                            :content (:content block*)}
                     (contains? block* :name) (assoc :name (:name block*))
                     (contains? block* :status) (assoc :status (:status block*))
                     (contains? block* :raw) (assoc :raw (:raw block*)))
      :custom {:type :custom
               :kind (or (:kind block*) :unknown)
               :data (or (:data block*) (dissoc block* :type :kind))})))

(defn normalize-content
  [content]
  (mapv (comp validate-message-block! normalize-block)
        (cond
          (nil? content) []
          (vector? content) content
          (sequential? content) (vec content)
          :else [content])))

(def legacy-event-type-map
  {"chat.started" :agent-start
   "chat.memory.recalled" :message-update
   "chat.delta" :message-update
   "chat.planner.step" :turn-end
   "chat.tool.approval_required" :tool-execution-update
   "chat.fallback_completion" :message-start
   "chat.completed" :agent-end
   "chat.cancelled" :agent-end
   "chat.failed" :agent-end
   "chat.error" :agent-end
   "message.appended" :message-end
   "completion.completed" :message-end
   "tool.execution.requested" :tool-execution-start
   "tool.execution.blocked" :tool-execution-end
   "tool.execution.succeeded" :tool-execution-end
   "tool.execution.failed" :tool-execution-end})

(defn legacy-event-type->canonical [event-type]
  (get legacy-event-type-map
       (cond
         (keyword? event-type) (name event-type)
         (string? event-type) event-type
         :else (str event-type))))

(defn legacy-event->canonical
  [{:keys [event-type entity-type entity-id request-id payload created-at] :as event}]
  (when-let [canonical-type (legacy-event-type->canonical event-type)]
    (validate-runtime-event!
     {:event-type canonical-type
      :entity-type entity-type
      :entity-id entity-id
      :request-id request-id
      :timestamp (or created-at (:timestamp event) (now-str))
      :payload (assoc (if (map? payload) payload {:value payload})
                      :legacy-event-type (cond
                                           (keyword? event-type) (name event-type)
                                           (string? event-type) event-type
                                           :else (str event-type)))})))

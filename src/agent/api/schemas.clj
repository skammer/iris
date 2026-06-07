(ns agent.api.schemas
  "Reusable malli schemas for route :parameters declarations."
  (:require
   [clojure.string :as str]))

(def NonBlankString
  "Non-blank string. Mirrors the prior ensure-string! check, which rejected
   whitespace-only values."
  [:and :string [:fn {:error/message "must be a non-blank string"}
                 (complement str/blank?)]])

(def StringVec
  [:vector :string])

(def MemoryScope
  [:map
   [:type {:optional true} :string]
   [:id {:optional true} :string]])

(def TrustPolicy
  [:map
   [:message_types {:optional true} StringVec]
   [:routes {:optional true} StringVec]
   [:required_capabilities {:optional true} StringVec]])

(def TrustPolicies
  [:map-of :string TrustPolicy])

(def ChatRole
  [:enum "system" "user" "assistant" "tool"])

(defn- chat-content-block-valid? [block]
  (case (:type block)
    "text" (string? (:text block))
    "image_url" (map? (:image_url block))
    "input_audio" (map? (:input_audio block))
    "file" (map? (:file block))
    true))

(def ChatContentBlock
  [:and
   [:map
    [:type NonBlankString]
    [:text {:optional true} :string]
    [:source {:optional true} :map]
    [:image_url {:optional true} :map]
    [:input_audio {:optional true} :map]
    [:file {:optional true} :map]
    [:alt {:optional true} [:maybe :string]]
    [:detail {:optional true} :any]
    [:filename {:optional true} [:maybe :string]]]
   [:fn {:error/message "known content block type requires matching payload"}
    chat-content-block-valid?]])

(def ChatContent
  [:or NonBlankString
   [:vector {:min 1} ChatContentBlock]])

(defn- chat-message-valid? [{:keys [role content tool_calls]}]
  (case role
    "assistant" (or (some? content) (seq tool_calls))
    "system" (some? content)
    "user" (some? content)
    "tool" (some? content)
    false))

(def ChatMessage
  [:and
   [:map
    [:role ChatRole]
    [:content {:optional true} [:maybe ChatContent]]
    [:name {:optional true} :string]
    [:tool_calls {:optional true} [:vector :map]]
    [:tool_call_id {:optional true} :string]]
   [:fn {:error/message "message content required unless assistant has tool_calls"}
    chat-message-valid?]])

(def Directive
  [:map
   [:type :string]
   [:payload {:optional true} :map]])

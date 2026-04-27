(ns agent.api.schemas
  "Reusable malli schemas for route :parameters declarations."
  (:require
   [clojure.string :as str]))

(def NonBlankString
  "Non-blank string. Mirrors the prior ensure-string! check, which rejected
   whitespace-only values."
  [:and :string [:fn {:error/message "must be a non-blank string"}
                 (complement str/blank?)]])

(def NonBlankStringVec
  [:vector NonBlankString])

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

(def ChatMessage
  [:map
   [:role ChatRole]
   [:content NonBlankString]])

(def Directive
  [:map
   [:type :string]
   [:payload {:optional true} :map]])

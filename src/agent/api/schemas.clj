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

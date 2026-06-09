(ns agent.runtime.calls
  "Shared accessors for planner/provider tool-call shapes."
  (:require
   [cheshire.core :as json]))

(def fs-mutation-tools
  #{:fs_write :fs_create :fs_replace :fs_delete :fs_mkdir})

(defn tool-name-of
  "Tool name of a call/receipt map, as a keyword."
  [call]
  (some-> (or (:name call) (:tool-name call) (get-in call [:function :name]))
          keyword))

(defn call-id
  [idx call]
  (str (or (:id call) (:tool-call-id call) (:tool_call_id call) (str "tool-call-" idx))))

(defn call-input
  "Input map of a tool call. Provider-shaped calls carry JSON-string
   arguments; `malformed` is returned when that JSON does not parse."
  ([call] (call-input call {}))
  ([call malformed]
   (or (:input call)
       (:arguments call)
       (:args call)
       (when-let [arguments (get-in call [:function :arguments])]
         (if (string? arguments)
           (try
             (json/parse-string arguments true)
             (catch Exception _
               malformed))
           arguments))
       {})))

(ns agent.api.routes.chat
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private chat-completions-body
  [:map
   [:messages {:optional true} [:vector {:min 1} schemas/ChatMessage]]
   [:prompt {:optional true} :string]
   [:session_id {:optional true} :string]
   [:stream {:optional true} :boolean]])

(def ^:private chat-stop-body
  [:map [:session_id schemas/NonBlankString]])

(def routes
  [["/v1/chat/completions" {:post {:handler/id :chat-completions
                                   :parameters {:body chat-completions-body}}}]
   ["/v1/chat/stop" {:post {:handler/id :chat-stop
                            :parameters {:body chat-stop-body}}}]])

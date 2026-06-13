(ns agent.chat
  "Public chat facade. Exposes chat service lifecycle, per-session state,
   cancellation, streaming state, and queued turn execution while hiding the
   queue/turn/service split from callers."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.chat.queue :as queue]
   [agent.chat.service :as service]))

(def stopped-content service/stopped-content)

(defn create-service []
  (service/create-service))

(defn stop! [chat-service]
  (service/stop! chat-service))

(defn health-check [chat-service]
  (service/health-check chat-service))

(defn session-state [system session-id]
  (service/session-state system session-id))

(defn cancel-session! [system session-id]
  (queue/cancel-session! system session-id))

(defn streaming-state [system session-id]
  (service/streaming-state system session-id))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system opts]
  (queue/run! system opts))

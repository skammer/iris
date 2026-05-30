(ns agent.chat
  "First-class session chat loop public facade."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.chat.loop-control :as loop-control]
   [agent.chat.queue :as queue]
   [agent.chat.service :as service]))

(def stopped-content service/stopped-content)

(defn create-service []
  (service/create-service))

(defn stop! [chat-service]
  (service/stop! chat-service))

(defn reload! [chat-service]
  (service/reload! chat-service))

(defn health-check [chat-service]
  (service/health-check chat-service))

(defn active-run [system session-id]
  (service/active-run system session-id))

(defn active? [system session-id]
  (service/active? system session-id))

(defn session-state [system session-id]
  (service/session-state system session-id))

(defn cancel-session! [system session-id]
  (service/cancel-session! system session-id))

(defn streaming-content [system session-id]
  (service/streaming-content system session-id))

(defn loop-command! [system session-id text]
  (loop-control/loop-command! system session-id text queue/run!))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system opts]
  (queue/run! system opts))

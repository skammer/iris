(ns agent.api.handlers.channel-adapters
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.channels.core :as channel-adapters]))

(defn list-adapters [system _request]
  (responses/json-response 200
                           {:data (mapv ser/channel-adapter->response
                                        (channel-adapters/list-adapters (:channel-adapter-registry system)))}))

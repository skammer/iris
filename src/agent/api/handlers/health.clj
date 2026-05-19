(ns agent.api.handlers.health
  (:require
   [agent.api.responses :as responses]
   [agent.channels.core :as channel-adapters]
   [agent.config :as config]
   [agent.llm.core :as llm-core]
   [agent.llm.registry :as llm-registry]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.skills :as skills]
   [agent.telemetry :as telemetry]
   [agent.tools.core :as tools]))

(defn handle [system _request]
  (responses/json-response 200
                           {:ok true
                            :llm (llm-core/health-check (:llm-provider system))
                            :llm-registry {:active-provider (:active-provider (:llm-registry system))
                                           :providers (count (llm-registry/list-providers (:llm-registry system)))}
                            :storage (sqlite/health-check (:store system))
                            :tools (tools/registry-health (:tool-registry system))
                            :skills (skills/registry-health (:skills-registry system))
                            :telemetry (telemetry/health-check (:telemetry system))
                            :memory (memory/health-check (:memory-service system))
                            :channel-adapters (channel-adapters/registry-health (:channel-adapter-registry system))
                            :orchestrator (orchestrator/health-check (:orchestrator system))
                            :provider (config/active-provider-key (get-in system [:config :llm]))}))

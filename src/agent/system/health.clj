(ns agent.system.health
  "Component health wrappers and aggregate system health."
  (:require
   [agent.broker.core :as broker]
   [agent.api.streaming :as streaming]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.health :as health]
   [agent.llm.core :as llm-core]
   [agent.logging :as logging]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [agent.runtime.trace :as runtime-trace]
   [agent.skills :as skills]
   [agent.telemetry :as telemetry]
   [agent.tools.core :as tools]))

(defn with-component-health
  [registry component f]
  (try
    (let [result (f)]
      (health/mark-ok! registry component)
      result)
    (catch Exception e
      (health/mark-error! registry component e)
      (throw e))))

(defn- unhealthy-message
  [component result]
  (or (:error result)
      (:message result)
      (get-in result [:details :error])
      (get-in result [:details :message])
      (str component " unhealthy")))

(defn checked-component-health
  [registry component f]
  (try
    (let [result (f)]
      (if (false? (:healthy result))
        (health/mark-error! registry component (unhealthy-message component result))
        (health/mark-ok! registry component))
      result)
    (catch Exception e
      (health/mark-error! registry component e)
      {:healthy false
       :details {:error (.getMessage e)}})))

(defn health-check
  [system]
  (let [registry (:health-registry system)]
    {:llm (checked-component-health registry :llm-provider
            #(llm-core/health-check (:llm-provider system)))
     :llm-registry {:active-provider (:active-provider (:llm-registry system))
                    :provider-count (count (:providers (:llm-registry system)))
                    :providers (count (:providers (:llm-registry system)))}
     :storage (checked-component-health registry :sqlite
                #(sqlite/health-check (:store system)))
     :logging (logging/health-check)
     :broker (checked-component-health registry :broker
               #(broker/health-check (:broker system)))
     :tools (checked-component-health registry :tools
              #(tools/registry-health (:tool-registry system)))
     :skills (skills/registry-health (:skills-registry system))
     :memory (checked-component-health registry :memory
               #(memory/health-check (:memory-service system)))
     :telemetry (checked-component-health registry :telemetry
                  #(telemetry/health-check (:telemetry system)))
     :trace (runtime-trace/health-check (:trace system))
     :runtime (checked-component-health registry :runtime
                #(runtime/runtime-health (:runtime-service system)))
     :sse {:healthy true
           :metrics (streaming/metrics)}
     :chat (checked-component-health registry :chat
             #(chat/health-check (:chat-service system)))
     :channel-adapters (checked-component-health registry :channel-adapters
                         #(channel-adapters/registry-health (:channel-adapter-registry system)))
     :orchestrator (orchestrator/health-check (:orchestrator system))
     :provider (config/active-provider-key (get-in system [:config :llm]))
     :health-snapshot (health/snapshot registry)}))

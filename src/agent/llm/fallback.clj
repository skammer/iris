(ns agent.llm.fallback
  "Ordered fallback for generation only; embeddings retain their original space."
  (:require [agent.llm.core :as llm]))

(defn- invoke-chain [candidates request]
  (loop [[candidate & remaining] candidates
         attempts []]
    (let [{:keys [provider model client]} candidate
          emitted? (atom false)
          request* (if (seq attempts)
                     (-> request
                         (dissoc :api :extra-body :cache-control :cache_control)
                         (assoc :model model)
                         (update :opts #(-> %
                                            (dissoc :api :extra-body :cache-control :cache_control)
                                            (assoc :model model))))
                     request)
          guard (fn [callback]
                  (fn [delta]
                    (reset! emitted? true)
                    (callback delta)))
          request* (reduce
                    (fn [r k]
                      (if-let [callback (or (get r k) (get-in r [:opts k]))]
                        (-> r (assoc k (guard callback))
                            (assoc-in [:opts k] (guard callback)))
                        r))
                    request* [:on-content-delta :on-thinking-delta])
          result (try
                   {:turn (llm/invoke client request*)}
                   (catch Exception e {:error e}))
          attempt {:provider provider
                   :model (if (seq attempts) model (or (:model request) model))}
          attempts* (conj attempts (cond-> attempt
                                   (:error result)
                                   (assoc :status :failed
                                          :error-type (or (:type (ex-data (:error result)))
                                                          (.getName (class (:error result)))))))]
      (if-let [error (:error result)]
        (if (and (seq remaining) (not @emitted?) (llm/retryable-exception? error))
          (recur remaining attempts*)
          (throw (ex-info (.getMessage error)
                          (assoc (ex-data error) :llm-attempts attempts*) error)))
        (assoc (:turn result)
               :provider provider :model (:model attempt)
               :llm-attempts (conj attempts (assoc attempt :status :succeeded)))))))

(defrecord FallbackProvider [candidates]
  llm/ILLMProvider
  (complete [this messages opts] (llm/complete-via-invoke this messages opts))
  (stream [this messages opts] (llm/stream-via-invoke this messages opts))
  (embed [_ text opts] (llm/embed (:client (first candidates)) text opts))
  (list-models [_] (llm/list-models (:client (first candidates))))
  (get-capabilities [_ model] (llm/get-capabilities (:client (first candidates)) model))
  (estimate-cost [_ messages model]
    (llm/estimate-cost (:client (first candidates)) messages model))
  llm/ILLMProviderInvoke
  (invoke [_ request] (invoke-chain candidates request))
  (generate [this messages opts] (llm/invoke this (assoc opts :messages messages)))
  llm/ILLMProviderWithHealth
  (health-check [_] (llm/health-check (:client (first candidates)))))

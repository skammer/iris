(ns agent.core
  (:require
   [agent.llm :as llm]
   [agent.kg-integration :as kg]
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]))

(defonce llm-provider
  (delay
    (try
      (llm/create-openai-provider {})
      (catch Exception e
        (println "Warning: Could not create OpenAI provider:" (.getMessage e))
        nil))))

(def agent-response
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:prompt "Channel to receive user prompt"}
                   :outs {:response "Agent response message"
                          :text "Text for knowledge extraction"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input msg]
                  (println "Received prompt:" msg)
                  (if-let [provider @llm-provider]
                    (let [resp (llm/simple-completion provider msg)]
                      ;; Store interaction in knowledge graph
                      (async/go
                        (kg/store-interaction msg resp))
                      [state {:response [resp]
                              :text [msg]}])
                    [state {:response ["LLM provider not available"]
                            :text [msg]}]))}))

(def knowledge-enhancer
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:text "Text to enhance with knowledge"
                          :query "Query for relevant knowledge"}
                   :outs {:enhanced "Response enhanced with knowledge"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input inputs]
                  (let [text (first (:text inputs))
                        query (first (:query inputs))]
                    (println "Enhancing with knowledge, text:" (subs text 0 (min 30 (count text))) "...")
                    ;; Query knowledge graph for relevant information
                    (let [relevant-knowledge (kg/query-relevant-knowledge text)]
                      (if (seq relevant-knowledge)
                        (let [enhanced (str "Based on my knowledge: " 
                                            (pr-str relevant-knowledge)
                                            "\n\nResponse: " text)]
                          [state {:enhanced [enhanced]}])
                        [state {:enhanced [text]}]))))}))

(def printer
  (flow/map->step
   {:describe   (fn []
                  {:ins {:in "Channel to receive messages"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input response]
                  (let [message (first response)]
                    (println "Message received:" (subs message 0 (min 100 (count message)))))
                  [state nil])}))

(defn build-flow-spec
  [{:keys []}]
  {:procs {:agent     {:args {} :proc (flow/process #'agent-response)}
           :enhancer  {:args {} :proc (flow/process #'knowledge-enhancer)}
           :notifier  {:args {} :proc (flow/process #'printer)}}
   :conns [[[:agent :response] [:notifier :in]]
           [[:agent :text] [:enhancer :text]]
           [[:enhancer :enhanced] [:notifier :in]]]})

(defn start-session!
  [{:keys []}]
  (let [flow-spec (build-flow-spec {})]
    (flow/create-flow flow-spec)))

(comment
  (def fw (start-session! {}))

  (def chs (flow/start fw))
  (flow/resume fw)

  @(flow/inject fw [:agent :prompt] ["Tell me about Clojure"])

  (async/poll! (:report-chan chs))
  (async/poll! (:error-chan chs)))
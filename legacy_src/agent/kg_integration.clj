(ns agent.kg-integration
  "Knowledge graph integration with flow-based agent architecture."
  (:require
   [agent.knowledge-graph :as kg]
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]))

(defonce knowledge-graph
  (delay
    (kg/create-in-memory-graph {:name "agent-knowledge-base"})))

(def knowledge-extractor
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:text "Text to extract knowledge from"}
                   :outs {:facts "Extracted facts as triples"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input text]
                  (println "Extracting knowledge from:" (subs text 0 (min 50 (count text))) "...")
                  ;; Simple extraction - in production would use LLM or NLP
                  (let [facts (extract-simple-facts text)]
                    (when (seq facts)
                      (doseq [[s p o] facts]
                        (kg/store-triple @knowledge-graph s p o)))
                    [state {:facts facts}]))}))

(defn extract-simple-facts
  "Simple fact extraction for demonstration.
  In production, this would use LLM or NLP techniques."
  [text]
  (let [lower-text (str/lower-case text)]
    (cond-> []
      (re-find #"(?i)clojure" lower-text)
      (conj [:clojure :mentioned-in text])
      
      (re-find #"(?i)agent" lower-text)
      (conj [:agent :mentioned-in text])
      
      (re-find #"(?i)knowledge.*graph" lower-text)
      (conj [:knowledge-graph :mentioned-in text])
      
      (re-find #"(?i)ai" lower-text)
      (conj [:artificial-intelligence :mentioned-in text]))))

(def knowledge-query
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:query "Query pattern or topic"}
                   :outs {:results "Query results from knowledge graph"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input query]
                  (println "Querying knowledge graph for:" query)
                  (let [results (if (string? query)
                                  ;; Simple keyword search
                                  (kg/find-entities @knowledge-graph (keyword query))
                                  ;; Pattern query
                                  (kg/query @knowledge-graph query))]
                    [state {:results results}]))}))

(def knowledge-reasoner
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:context "Context for reasoning"}
                   :outs {:inferences "New inferences from knowledge graph"}})

    :init       (fn [args] args)

    :transition (fn [state _transition] state)

    :transform  (fn [state _input context]
                  (println "Reasoning with context:" context)
                  (let [inferences (apply-basic-reasoning @knowledge-graph context)]
                    [state {:inferences inferences}]))}))

(defn apply-basic-reasoning
  "Apply basic reasoning rules to the knowledge graph."
  [kg context]
  (let [rules (kg/basic-inference-rules)]
    (kg/infer kg rules)
    ;; Return summary of what was inferred
    {:rules-applied (count rules)
     :context context}))

(defn build-knowledge-flow-spec
  "Build a flow specification for knowledge graph operations."
  [{:keys []}]
  {:procs {:extractor {:args {} :proc (flow/process #'knowledge-extractor)}
           :querier   {:args {} :proc (flow/process #'knowledge-query)}
           :reasoner  {:args {} :proc (flow/process #'knowledge-reasoner)}}
   :conns [[[:extractor :facts] [:reasoner :context]]
           [[:querier :results] [:reasoner :context]]]})

(defn start-knowledge-session!
  "Start a knowledge graph processing session."
  [{:keys []}]
  (let [flow-spec (build-knowledge-flow-spec {})]
    (flow/create-flow flow-spec)))

(defn store-interaction
  "Store an interaction in the knowledge graph."
  [prompt response]
  (let [interaction-id (keyword (str "interaction-" (System/currentTimeMillis)))]
    (kg/add-entity @knowledge-graph interaction-id :interaction
                   {:prompt prompt
                    :response response
                    :timestamp (System/currentTimeMillis)})))

(defn query-relevant-knowledge
  "Query knowledge graph for information relevant to text."
  [text]
  (let [keywords (extract-keywords text)]
    (mapcat
     (fn [kw]
       (try
         (kg/find-entities @knowledge-graph kw)
         (catch Exception e
           (println "Error querying for keyword" kw ":" (.getMessage e))
           [])))
     keywords)))

(defn extract-keywords
  "Extract potential keywords from text."
  [text]
  (->> (str/split text #"\\s+")
       (filter #(> (count %) 3))
       (map str/lower-case)
       (map keyword)
       (take 5)))
  
  (def kg-chs (flow/start kg-flow))
  (flow/resume kg-flow)
  
  ;; Extract knowledge from text
  @(flow/inject kg-flow [:extractor :text] 
                ["Clojure is a functional programming language used for building AI agents."])
  
  ;; Query the knowledge graph
  @(flow/inject kg-flow [:querier :query] ["clojure"])
  
  ;; Apply reasoning
  @(flow/inject kg-flow [:reasoner :context] 
                ["Current context: programming languages and AI"])
  
  ;; Check results
  (async/poll! (:report-chan kg-chs))
  (async/poll! (:error-chan kg-chs))
  
  ;; Direct access to knowledge graph
  (def kg @knowledge-graph)
  (kg/store-triple kg :python :type :programming-language)
  (kg/store-triple kg :python :paradigm :multi-paradigm)
  (kg/query kg '[:find ?lang ?paradigm
                 :where [?lang :type :programming-language]
                        [?lang :paradigm ?paradigm]])
  )
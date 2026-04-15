(ns agent.multi-head
  "Multi-head decision making system for AI agents.
  Inspired by 'terminal dogma' from Evangelion and 'personality cores' from Portal.
  Enables collegial decision making with split responsibilities."
  (:require
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]
   [clojure.set :as set]
   [clojure.string :as str]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg])
  (:refer-clojure :exclude [resolve]))

(defprotocol IDecisionHead
  "Protocol for individual decision heads (personality cores)."
  (evaluate [this context options]
    "Evaluate options from this head's perspective.")
  
  (specialty [this]
    "Return the specialty/domain of this head.")
  
  (confidence [this evaluation]
    "Calculate confidence level for an evaluation.")
  
  (explain [this evaluation]
    "Provide explanation for the evaluation."))

(defrecord DecisionHead [id name specialty llm-provider knowledge-graph]
  IDecisionHead
  (evaluate [_ context options]
    (let [prompt (format "As %s (specializing in %s), evaluate these options:\n\nContext: %s\n\nOptions:\n%s\n\nProvide your evaluation in JSON format with keys: 'choice', 'reasoning', 'confidence' (0-1), 'risks', 'benefits'."
                         name specialty context
                         (str/join "\n" (map-indexed #(format "%d. %s" (inc %1) %2) options)))]
      (try
        (let [response (llm/simple-completion llm-provider prompt)
              parsed (parse-evaluation-response response)]
          (assoc parsed :head-id id :head-name name))
        (catch Exception e
          {:head-id id :head-name name :choice nil :error (.getMessage e)}))))
  
  (specialty [_] specialty)
  
  (confidence [_ evaluation]
    (or (:confidence evaluation) 0.5))
  
  (explain [_ evaluation]
    (str name " (" specialty "): " (:reasoning evaluation))))

(defn parse-evaluation-response
  "Parse LLM response into evaluation structure."
  [response]
  (try
    (let [json-str (-> response
                       (str/replace #"```json\n?" "")
                       (str/replace #"```" ""))
          ;; Simple JSON parsing - in production use cheshire
          data (clojure.edn/read-string json-str)]
      {:choice (:choice data)
       :reasoning (:reasoning data)
       :confidence (:confidence data)
       :risks (:risks data)
       :benefits (:benefits data)})
    (catch Exception e
      {:choice nil
       :reasoning response
       :confidence 0.5
       :risks []
       :benefits []})))

(defprotocol IDecisionOrchestrator
  "Protocol for orchestrating multiple decision heads."
  (add-head [this head]
    "Add a decision head to the orchestrator.")
  
  (remove-head [this head-id]
    "Remove a decision head.")
  
  (list-heads [this]
    "List all decision heads.")
  
  (make-decision [this context options]
    "Make a decision using all heads.")
  
  (resolve-conflict [this evaluations]
    "Resolve conflicts between head evaluations.")
  
  (consensus-level [this evaluations]
    "Calculate consensus level among evaluations."))

(defrecord DecisionOrchestrator [heads knowledge-graph]
  IDecisionOrchestrator
  (add-head [this head]
    (assoc this :heads (conj heads head)))
  
  (remove-head [this head-id]
    (assoc this :heads (remove #(= (:id %) head-id) heads)))
  
  (list-heads [_]
    (map #(select-keys % [:id :name :specialty]) heads))
  
  (make-decision [_ context options]
    (let [evaluations (map #(evaluate % context options) heads)
          ;; Store evaluations in knowledge graph
          _ (store-evaluations knowledge-graph context options evaluations)
          ;; Resolve conflicts
          decision (resolve-conflict evaluations)]
      {:decision decision
       :evaluations evaluations
       :consensus (consensus-level evaluations)
       :context context}))
  
  (resolve-conflict [_ evaluations]
    (let [valid-evals (filter :choice evaluations)
          grouped (group-by :choice valid-evals)
          counts (into {} (map (fn [[k v]] [k (count v)]) grouped))
          max-count (apply max (vals counts))
          top-choices (keys (filter #(= (val %) max-count) counts))]
      
      (cond
        ;; Unanimous decision
        (= 1 (count top-choices))
        (first top-choices)
        
        ;; Tie - use weighted confidence
        :else
        (let [weighted (reduce
                        (fn [acc eval]
                          (update acc (:choice eval) (fnil + 0) (:confidence eval)))
                        {}
                        valid-evals)
              max-weight (apply max (vals weighted))
              best-choices (keys (filter #(= (val %) max-weight) weighted))]
          (if (= 1 (count best-choices))
            (first best-choices)
            ;; Still tie - choose first
            (first best-choices))))))
  
  (consensus-level [_ evaluations]
    (let [valid-evals (filter :choice evaluations)
          choices (map :choice valid-evals)
          unique-choices (set choices)]
      (if (empty? valid-evals)
        0.0
        (/ 1 (count unique-choices))))))

(defn store-evaluations
  "Store decision evaluations in knowledge graph."
  [kg context options evaluations]
  (let [decision-id (keyword (str "decision-" (System/currentTimeMillis)))]
    ;; Store decision context
    (kg/add-entity kg decision-id :decision
                   {:context context
                    :options options
                    :timestamp (System/currentTimeMillis)})
    
    ;; Store each evaluation
    (doseq [eval evaluations
            :when (:choice eval)]
      (let [eval-id (keyword (str "eval-" (:head-id eval) "-" (System/currentTimeMillis)))]
        (kg/add-entity kg eval-id :evaluation
                       {:decision decision-id
                        :head-id (:head-id eval)
                        :head-name (:head-name eval)
                        :choice (:choice eval)
                        :confidence (:confidence eval)
                        :reasoning (:reasoning eval)})))))

(defn create-standard-heads
  "Create a standard set of decision heads."
  [llm-provider knowledge-graph]
  [(->DecisionHead :analytical "Analytical" "logic and analysis" llm-provider knowledge-graph)
   (->DecisionHead :creative "Creative" "innovation and possibilities" llm-provider knowledge-graph)
   (->DecisionHead :practical "Practical" "feasibility and implementation" llm-provider knowledge-graph)
   (->DecisionHead :ethical "Ethical" "ethics and values" llm-provider knowledge-graph)
   (->DecisionHead :strategic "Strategic" "long-term planning" llm-provider knowledge-graph)])

(defn create-orchestrator
  "Create a decision orchestrator with standard heads."
  [llm-provider knowledge-graph]
  (let [heads (create-standard-heads llm-provider knowledge-graph)]
    (->DecisionOrchestrator heads knowledge-graph)))

;; Flow integration
(def multi-head-decider
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:context "Decision context"
                          :options "Available options"}
                   :outs {:decision "Final decision"
                          :evaluations "All head evaluations"
                          :consensus "Consensus level"}})

    :init       (fn [args]
                  (let [llm-provider (delay (llm/create-openai-provider {}))
                        kg (delay (kg/create-in-memory-graph {:name "decision-history"}))]
                    {:orchestrator (create-orchestrator @llm-provider @kg)}))

    :transition (fn [state _transition] state)

    :transform  (fn [state _input inputs]
                  (let [context (first (:context inputs))
                        options (first (:options inputs))
                        orchestrator (:orchestrator state)
                        result (make-decision orchestrator context options)]
                    [state result]))}))

(comment
  ;; Example usage
  (def llm-provider (llm/create-openai-provider {}))
  (def kg (kg/create-in-memory-graph {:name "test-decisions"}))
  
  ;; Create orchestrator
  (def orchestrator (create-orchestrator llm-provider kg))
  
  ;; List heads
  (list-heads orchestrator)
  
  ;; Make a decision
  (def context "We need to choose a programming language for a new AI agent project.")
  (def options ["Clojure - functional, great for concurrency"
                "Python - extensive AI libraries"
                "Rust - performance and safety"
                "TypeScript - web integration"])
  
  (def decision (make-decision orchestrator context options))
  
  ;; Inspect results
  (:decision decision)
  (:consensus decision)
  
  ;; View evaluations
  (doseq [eval (:evaluations decision)]
    (println (explain (first (filter #(= (:id %) (:head-id eval)) (:heads orchestrator))) eval)))
  
  ;; Flow integration example
  (def flow-spec {:procs {:decider {:args {} :proc (flow/process #'multi-head-decider)}}})
  (def fw (flow/create-flow flow-spec))
  (def chs (flow/start fw))
  (flow/resume fw)
  
  @(flow/inject fw [:decider :context] [context])
  @(flow/inject fw [:decider :options] [options])
  
  (async/poll! (:report-chan chs))
  )

(defprotocol IDecisionHead
  "Protocol for individual decision heads (personality cores)."
  (evaluate [this context options]
    "Evaluate options from this head's perspective.")
  
  (specialty [this]
    "Return the specialty/domain of this head.")
  
  (confidence [this evaluation]
    "Calculate confidence level for an evaluation.")
  
  (explain [this evaluation]
    "Provide explanation for the evaluation."))

(defrecord DecisionHead [id name specialty llm-provider knowledge-graph]
  IDecisionHead
  (evaluate [_ context options]
    (let [prompt (format "As %s (specializing in %s), evaluate these options:\n\nContext: %s\n\nOptions:\n%s\n\nProvide your evaluation in JSON format with keys: 'choice', 'reasoning', 'confidence' (0-1), 'risks', 'benefits'."
                         name specialty context
                         (clojure.string/join "\n" (map-indexed #(format "%d. %s" (inc %1) %2) options)))]
      (try
        (let [response (llm/simple-completion llm-provider prompt)
              parsed (parse-evaluation-response response)]
          (assoc parsed :head-id id :head-name name))
        (catch Exception e
          {:head-id id :head-name name :choice nil :error (.getMessage e)}))))
  
  (specialty [_] specialty)
  
  (confidence [_ evaluation]
    (or (:confidence evaluation) 0.5))
  
  (explain [_ evaluation]
    (str name " (" specialty "): " (:reasoning evaluation))))

(defn parse-evaluation-response
  "Parse LLM response into evaluation structure."
  [response]
  (try
    (let [json-str (-> response
                       (clojure.string/replace #"```json\n?" "")
                       (clojure.string/replace #"```" ""))
          data (clojure.data.json/read-str json-str :key-fn keyword)]
      {:choice (:choice data)
       :reasoning (:reasoning data)
       :confidence (:confidence data)
       :risks (:risks data)
       :benefits (:benefits data)})
    (catch Exception e
      {:choice nil
       :reasoning response
       :confidence 0.5
       :risks []
       :benefits []})))

(defprotocol IDecisionOrchestrator
  "Protocol for orchestrating multiple decision heads."
  (add-head [this head]
    "Add a decision head to the orchestrator.")
  
  (remove-head [this head-id]
    "Remove a decision head.")
  
  (list-heads [this]
    "List all decision heads.")
  
  (make-decision [this context options]
    "Make a decision using all heads.")
  
  (resolve-conflict [this evaluations]
    "Resolve conflicts between head evaluations.")
  
  (consensus-level [this evaluations]
    "Calculate consensus level among evaluations."))

(defrecord DecisionOrchestrator [heads knowledge-graph]
  IDecisionOrchestrator
  (add-head [this head]
    (assoc this :heads (conj heads head)))
  
  (remove-head [this head-id]
    (assoc this :heads (remove #(= (:id %) head-id) heads)))
  
  (list-heads [_]
    (map #(select-keys % [:id :name :specialty]) heads))
  
  (make-decision [_ context options]
    (let [evaluations (map #(evaluate % context options) heads)
          ;; Store evaluations in knowledge graph
          _ (store-evaluations knowledge-graph context options evaluations)
          ;; Resolve conflicts
          decision (resolve-conflict evaluations)]
      {:decision decision
       :evaluations evaluations
       :consensus (consensus-level evaluations)
       :context context}))
  
  (resolve-conflict [_ evaluations]
    (let [valid-evals (filter :choice evaluations)
          grouped (group-by :choice valid-evals)
          counts (into {} (map (fn [[k v]] [k (count v)]) grouped))
          max-count (apply max (vals counts))
          top-choices (keys (filter #(= (val %) max-count) counts))]
      
      (cond
        ;; Unanimous decision
        (= 1 (count top-choices))
        (first top-choices)
        
        ;; Tie - use weighted confidence
        :else
        (let [weighted (reduce
                        (fn [acc eval]
                          (update acc (:choice eval) (fnil + 0) (:confidence eval)))
                        {}
                        valid-evals)
              max-weight (apply max (vals weighted))
              best-choices (keys (filter #(= (val %) max-weight) weighted))]
          (if (= 1 (count best-choices))
            (first best-choices)
            ;; Still tie - choose first
            (first best-choices))))))
  
  (consensus-level [_ evaluations]
    (let [valid-evals (filter :choice evaluations)
          choices (map :choice valid-evals)
          unique-choices (set choices)]
      (if (empty? valid-evals)
        0.0
        (/ 1 (count unique-choices))))))

(defn store-evaluations
  "Store decision evaluations in knowledge graph."
  [kg context options evaluations]
  (let [decision-id (keyword (str "decision-" (System/currentTimeMillis)))]
    ;; Store decision context
    (kg/add-entity kg decision-id :decision
                   {:context context
                    :options options
                    :timestamp (System/currentTimeMillis)})
    
    ;; Store each evaluation
    (doseq [eval evaluations
            :when (:choice eval)]
      (let [eval-id (keyword (str "eval-" (:head-id eval) "-" (System/currentTimeMillis)))]
        (kg/add-entity kg eval-id :evaluation
                       {:decision decision-id
                        :head-id (:head-id eval)
                        :head-name (:head-name eval)
                        :choice (:choice eval)
                        :confidence (:confidence eval)
                        :reasoning (:reasoning eval)})))))

(defn create-standard-heads
  "Create a standard set of decision heads."
  [llm-provider knowledge-graph]
  [(->DecisionHead :analytical "Analytical" "logic and analysis" llm-provider knowledge-graph)
   (->DecisionHead :creative "Creative" "innovation and possibilities" llm-provider knowledge-graph)
   (->DecisionHead :practical "Practical" "feasibility and implementation" llm-provider knowledge-graph)
   (->DecisionHead :ethical "Ethical" "ethics and values" llm-provider knowledge-graph)
   (->DecisionHead :strategic "Strategic" "long-term planning" llm-provider knowledge-graph)])

(defn create-orchestrator
  "Create a decision orchestrator with standard heads."
  [llm-provider knowledge-graph]
  (let [heads (create-standard-heads llm-provider knowledge-graph)]
    (->DecisionOrchestrator heads knowledge-graph)))

;; Flow integration
(def multi-head-decider
  (flow/map->step
   {:describe   (fn []
                  {:ins  {:context "Decision context"
                          :options "Available options"}
                   :outs {:decision "Final decision"
                          :evaluations "All head evaluations"
                          :consensus "Consensus level"}})

    :init       (fn [args]
                  (let [llm-provider (delay (llm/create-openai-provider {}))
                        kg (delay (kg/create-in-memory-graph {:name "decision-history"}))]
                    {:orchestrator (create-orchestrator @llm-provider @kg)}))

    :transition (fn [state _transition] state)

    :transform  (fn [state _input inputs]
                  (let [context (first (:context inputs))
                        options (first (:options inputs))
                        orchestrator (:orchestrator state)
                        result (make-decision orchestrator context options)]
                    [state result]))}))

(comment
  ;; Example usage
  (def llm-provider (llm/create-openai-provider {}))
  (def kg (kg/create-in-memory-graph {:name "test-decisions"}))
  
  ;; Create orchestrator
  (def orchestrator (create-orchestrator llm-provider kg))
  
  ;; List heads
  (list-heads orchestrator)
  
  ;; Make a decision
  (def context "We need to choose a programming language for a new AI agent project.")
  (def options ["Clojure - functional, great for concurrency"
                "Python - extensive AI libraries"
                "Rust - performance and safety"
                "TypeScript - web integration"])
  
  (def decision (make-decision orchestrator context options))
  
  ;; Inspect results
  (:decision decision)
  (:consensus decision)
  
  ;; View evaluations
  (doseq [eval (:evaluations decision)]
    (println (explain (first (filter #(= (:id %) (:head-id eval)) (:heads orchestrator))) eval)))
  
  ;; Flow integration example
  (def flow-spec {:procs {:decider {:args {} :proc (flow/process #'multi-head-decider)}}})
  (def fw (flow/create-flow flow-spec))
  (def chs (flow/start fw))
  (flow/resume fw)
  
  @(flow/inject fw [:decider :context] [context])
  @(flow/inject fw [:decider :options] [options])
  
  (async/poll! (:report-chan chs))
  )
(ns agent.example-multi-head
  (:require
   [agent.multi-head :as mh]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg]
   [clojure.pprint :refer [pprint]]))

;; Mock LLM provider for testing without API keys
(def mock-llm-provider
  (reify llm/ILLMProvider
    (complete [_ messages _]
      (let [prompt (-> messages first :content)]
        (cond
          (re-find #"Analytical" prompt)
          "{\"choice\": \"Clojure\", \"reasoning\": \"Logical choice for concurrent systems\", \"confidence\": 0.8, \"risks\": [\"Smaller ecosystem\"], \"benefits\": [\"Concurrency\", \"Immutability\"]}"
          
          (re-find #"Creative" prompt)
          "{\"choice\": \"Python\", \"reasoning\": \"Most creative possibilities with AI libraries\", \"confidence\": 0.7, \"risks\": [\"Performance\"], \"benefits\": [\"Rich ecosystem\", \"Rapid prototyping\"]}"
          
          (re-find #"Practical" prompt)
          "{\"choice\": \"TypeScript\", \"reasoning\": \"Practical for web integration and team collaboration\", \"confidence\": 0.9, \"risks\": [\"Type system complexity\"], \"benefits\": [\"Web native\", \"Large community\"]}"
          
          (re-find #"Ethical" prompt)
          "{\"choice\": \"Rust\", \"reasoning\": \"Memory safety prevents security vulnerabilities\", \"confidence\": 0.85, \"risks\": [\"Learning curve\"], \"benefits\": [\"Safety\", \"Performance\"]}"
          
          (re-find #"Strategic" prompt)
          "{\"choice\": \"Clojure\", \"reasoning\": \"Long-term maintainability and functional paradigm\", \"confidence\": 0.75, \"risks\": [\"Niche language\"], \"benefits\": [\"Stability\", \"Expressiveness\"]}"
          
          :else
          "{\"choice\": \"Unknown\", \"reasoning\": \"Cannot evaluate\", \"confidence\": 0.5, \"risks\": [], \"benefits\": []}")))
    
    (stream [_ messages _]
      (let [ch (async/chan)]
        (async/go
          (async/>! ch "Mock streaming response")
          (async/close! ch))
        ch))
    
    (embed [_ text _]
      (take 5 (repeat 0.1)))))

;; Example 1: Basic multi-head decision making
(defn demo-basic-decision []
  (println "=== Basic Multi-Head Decision Demo ===")
  
  (let [kg (kg/create-in-memory-graph {:name "demo-decisions"})
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    ;; List decision heads
    (println "\n1. Decision Heads:")
    (pprint (mh/list-heads orchestrator))
    
    ;; Define decision context
    (def context "Choosing a technology stack for a new startup's backend API.")
    (def options ["Node.js with Express - fast development, JavaScript ecosystem"
                  "Go with Gin - performance, concurrency, simplicity"
                  "Python with FastAPI - data science integration, async support"
                  "Java with Spring Boot - enterprise features, stability"])
    
    ;; Make decision
    (println "\n2. Making decision...")
    (println "Context:" context)
    (println "Options:" (count options))
    
    (let [result (mh/make-decision orchestrator context options)]
      (println "\n3. Decision Result:")
      (println "Final choice:" (:decision result))
      (println "Consensus level:" (:consensus result))
      
      (println "\n4. Individual Evaluations:")
      (doseq [eval (:evaluations result)]
        (println "\n" (:head-name eval) ":")
        (println "  Choice:" (:choice eval))
        (println "  Confidence:" (:confidence eval))
        (println "  Reasoning:" (:reasoning eval))))))

;; Example 2: Conflict resolution
(defn demo-conflict-resolution []
  (println "\n=== Conflict Resolution Demo ===")
  
  (let [kg (kg/create-in-memory-graph {:name "conflict-demo"})
        ;; Create custom heads with conflicting opinions
        heads [(mh/->DecisionHead :optimist "Optimist" "seeing opportunities" mock-llm-provider kg)
               (mh/->DecisionHead :pessimist "Pessimist" "identifying risks" mock-llm-provider kg)
               (mh/->DecisionHead :realist "Realist" "balanced perspective" mock-llm-provider kg)]
        orchestrator (assoc (mh/->DecisionOrchestrator [] kg) :heads heads)]
    
    ;; Simulate conflicting evaluations
    (println "\nSimulating conflicting evaluations...")
    
    ;; Test conflict resolution
    (let [evaluations [{:head-id :optimist :choice "Yes" :confidence 0.9}
                       {:head-id :pessimist :choice "No" :confidence 0.8}
                       {:head-id :realist :choice "Maybe" :confidence 0.7}]
          resolved (mh/resolve-conflict orchestrator evaluations)]
      (println "Conflicting evaluations:" (count evaluations) "different choices")
      (println "Resolved decision:" resolved)
      (println "Consensus level:" (mh/consensus-level orchestrator evaluations)))))

;; Example 3: Flow integration
(defn demo-flow-integration []
  (println "\n=== Flow Integration Demo ===")
  
  ;; Create a simple flow with multi-head decider
  (let [flow-spec {:procs {:decider {:args {} :proc (flow/process #'mh/multi-head-decider)}}}
        fw (flow/create-flow flow-spec)
        chs (flow/start fw)]
    
    (flow/resume fw)
    
    ;; Inject decision request
    (println "Injecting decision request into flow...")
    (let [context "Should we implement a new feature now or wait?"
          options ["Implement now - capture market opportunity"
                   "Wait - gather more user feedback"
                   "Prototype first - test with small group"]]
      
      @(flow/inject fw [:decider :context] [context])
      @(flow/inject fw [:decider :options] [options])
      
      ;; Check for results
      (println "Checking flow results...")
      (let [report (async/poll! (:report-chan chs))
            error (async/poll! (:error-chan chs))]
        (when report (println "Report:" report))
        (when error (println "Error:" error))))))

;; Example 4: Decision history tracking
(defn demo-decision-history []
  (println "\n=== Decision History Tracking Demo ===")
  
  (let [kg (kg/create-in-memory-graph {:name "history-tracking"})
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    ;; Make multiple decisions
    (println "Making series of decisions...")
    
    (doseq [i (range 3)]
      (let [context (format "Decision #%d: Team meeting scheduling" (inc i))
            options ["Morning - fresh minds"
                     "Afternoon - after lunch"
                     "Evening - flexible timing"]]
        (mh/make-decision orchestrator context options)
        (Thread/sleep 100)))  ; Small delay for async operations
    
    ;; Query decision history from knowledge graph
    (println "\nQuerying decision history...")
    (let [decisions (kg/find-entities kg :decision)]
      (println "Total decisions recorded:" (count decisions))
      
      (when (seq decisions)
        (println "\nRecent decisions:")
        (doseq [decision (take 3 decisions)]
          (let [facts (kg/get-facts kg decision)]
            (println "\nDecision:" decision)
            (doseq [[prop value] facts]
              (when (= prop :context)
                (println "  Context:" (subs value 0 (min 50 (count value))) "...")))))))))

;; Run all demos
(defn -main [& args]
  (println "Starting Multi-Head Decision Making Demos...")
  
  (demo-basic-decision)
  (demo-conflict-resolution)
  (demo-flow-integration)
  (demo-decision-history)
  
  (println "\n=== All demos completed ==="))

(comment
  ;; Run in REPL
  (demo-basic-decision)
  (demo-conflict-resolution)
  (demo-flow-integration)
  (demo-decision-history)
  
  ;; Create custom decision scenario
  (def custom-kg (kg/create-in-memory-graph {:name "custom-scenario"}))
  (def custom-orchestrator (mh/create-orchestrator mock-llm-provider custom-kg))
  
  ;; Add custom head
  (def security-head (mh/->DecisionHead :security "Security Expert" "security and privacy" mock-llm-provider custom-kg))
  (def orchestrator-with-security (mh/add-head custom-orchestrator security-head))
  
  ;; Make security-focused decision
  (def security-context "Choosing authentication method for user API")
  (def security-options ["JWT tokens - stateless, scalable"
                         "Session cookies - traditional, secure"
                         "OAuth 2.0 - third-party integration"
                         "API keys - simple, but less secure"])
  
  (def security-decision (mh/make-decision orchestrator-with-security security-context security-options))
  
  ;; Analyze results
  (:decision security-decision)
  (:consensus security-decision)
  
  ;; View all evaluations
  (doseq [eval (:evaluations security-decision)]
    (println (:head-name eval) "chose" (:choice eval) "with confidence" (:confidence eval)))
  )
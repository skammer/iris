(ns agent.end-to-end-tests
  "End-to-end tests simulating real user scenarios.
  Tests complete agent workflows from user input to final response."
  (:require
   [clojure.test :refer :all]
   [agent.test-framework :as tf]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg]
   [agent.multi-head :as mh]
   [agent.kg-integration :as kgi]
   [clojure.string :as str]
   [clojure.core.async :as async]))

(use-fixtures :each (tf/with-test-agent-fixture))

;; ============================================================================
;; Scenario 1: Technical Decision Making
;; ============================================================================

(deftest ^:scenario test-technology-selection-scenario
  (testing "Complete technology selection workflow"
    (println "\n=== Scenario: Technology Selection ===")
    
    (let [llm-provider (tf/create-mock-llm-provider
                        {"analytical prompt" {:text "{\"choice\": \"Clojure\", \"reasoning\": \"Logical for concurrent systems\", \"confidence\": 0.8}"}
                         "creative prompt" {:text "{\"choice\": \"Python\", \"reasoning\": \"Creative AI possibilities\", \"confidence\": 0.7}"}
                         "practical prompt" {:text "{\"choice\": \"TypeScript\", \"reasoning\": \"Practical for web teams\", \"confidence\": 0.9}"}
                         "ethical prompt" {:text "{\"choice\": \"Rust\", \"reasoning\": \"Memory safety\", \"confidence\": 0.85}"}
                         "strategic prompt" {:text "{\"choice\": \"Clojure\", \"reasoning\": \"Long-term stability\", \"confidence\": 0.75}"}})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      ;; User query
      (let [user-query "What programming language should we use for our new AI agent project?"
            context "We're building an AI agent system that needs to handle concurrency, integrate with web services, and support long-term maintenance."
            options ["Clojure - functional, great concurrency, Lisp heritage"
                     "Python - extensive AI libraries, large community"
                     "Rust - performance, memory safety, growing ecosystem"
                     "TypeScript - web-native, type safety, full-stack capability"]]
        
        (println "User query:" user-query)
        (println "Context:" context)
        (println "Options:" (count options))
        
        ;; Step 1: Multi-head decision
        (let [decision-result (mh/make-decision orchestrator context options)]
          (println "\nDecision made:" (:decision decision-result))
          (println "Consensus level:" (:consensus decision-result))
          
          ;; Verify decision
          (is (:decision decision-result)
              "Should make a decision")
          (is (<= 0.2 (:consensus decision-result) 1.0)
              "Consensus should be reasonable")
          
          ;; Step 2: Store interaction in knowledge graph
          (let [agent-response (str "Based on multi-head analysis, we recommend " 
                                    (:decision decision-result) 
                                    ". Consensus level: " 
                                    (:consensus decision-result))]
            (kgi/store-interaction user-query agent-response)
            (println "\nInteraction stored in knowledge graph"))
          
          ;; Step 3: Extract knowledge from the process
          (let [combined-text (str context " " (str/join " " options))
                extracted-facts (kgi/extract-simple-facts combined-text)]
            (println "\nExtracted facts:" (count extracted-facts))
            (is (vector? extracted-facts)
                "Should extract facts"))
          
          ;; Step 4: Generate final response
          (let [final-response (format "After consulting our expert heads, we recommend %s. The decision was reached with %.0f%% consensus among our specialized advisors."
                                       (:decision decision-result)
                                       (* 100 (:consensus decision-result)))]
            (println "\nFinal response:" final-response)
            (is (str/includes? final-response "recommend")
                "Response should include recommendation")))))))

;; ============================================================================
;; Scenario 2: Feature Prioritization
;; ============================================================================

(deftest ^:scenario test-feature-prioritization-scenario
  (testing "Product feature prioritization workflow"
    (println "\n=== Scenario: Feature Prioritization ===")
    
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          ;; Create custom heads for product decisions
          heads [(tf/create-mock-decision-head :user-research "User Researcher" "user needs" "User Profiles" 0.9)
                 (tf/create-mock-decision-head :engineering "Engineer" "technical feasibility" "API Integration" 0.8)
                 (tf/create-mock-decision-head :business "Business Analyst" "ROI" "Analytics Dashboard" 0.85)
                 (tf/create-mock-decision-head :design "Designer" "user experience" "UI Redesign" 0.7)]
          orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph) :heads heads)]
      
      ;; Product team meeting scenario
      (let [context "Q2 planning: We need to prioritize features for our AI agent platform with limited engineering resources."
            options ["User Profiles - personalized agent interactions"
                     "API Integration - connect to external services"
                     "Analytics Dashboard - track agent performance"
                     "UI Redesign - improve user interface"
                     "Multi-language Support - international expansion"]]
        
        (println "Planning context:" context)
        (println "Feature options:" (count options))
        
        ;; Collaborative decision making
        (let [decision-result (mh/make-decision orchestrator context options)]
          (println "\nTeam decision:" (:decision decision-result))
          (println "Consensus:" (:consensus decision-result))
          
          ;; Analyze evaluations
          (println "\nTeam evaluations:")
          (doseq [eval (:evaluations decision-result)]
            (println "  " (:head-name eval) "->" (:choice eval) "(confidence:" (:confidence eval) ")"))
          
          ;; Verify collaborative aspects
          (is (> (count (:evaluations decision-result)) 1)
              "Multiple heads should evaluate")
          (is (every? :choice (:evaluations decision-result))
              "All heads should make choices")
          
          ;; Store team decision
          (let [meeting-notes (str "Q2 Planning Decision: " (:decision decision-result)
                                   "\nConsensus: " (:consensus decision-result)
                                   "\nParticipants: " (count heads) " specialized roles")]
            (kgi/store-interaction context meeting-notes)
            (println "\nMeeting notes stored")))))))

;; ============================================================================
;; Scenario 3: Continuous Learning Agent
;; ============================================================================

(deftest ^:scenario test-continuous-learning-scenario
  (testing "Agent that learns from past decisions"
    (println "\n=== Scenario: Continuous Learning ===")
    
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      ;; Simulate multiple decision sessions
      (println "Simulating learning over multiple decisions...")
      
      (doseq [session (range 3)]
        (let [session-context (format "Decision session %d: Architecture pattern selection" (inc session))
              options (case session
                        0 ["Microservices" "Monolith" "Serverless"]
                        1 ["Event-Driven" "Request-Response" "CQRS"]
                        2 ["Hexagonal" "Layered" "Clean Architecture"])]
          
          (println (format "\nSession %d: %s" (inc session) session-context))
          
          ;; Make decision
          (let [result (mh/make-decision orchestrator session-context options)]
            (println "  Decision:" (:decision result))
            (println "  Consensus:" (:consensus result))
            
            ;; Store for learning
            (kg/store-fact knowledge-graph 
                          (keyword (str "session-" session))
                          :decision (:decision result))
            (kg/store-fact knowledge-graph
                          (keyword (str "session-" session))
                          :consensus (:consensus result)))))
      
      ;; Query learning history
      (println "\nLearning history from knowledge graph:")
      (let [decisions (kg/find-entities knowledge-graph :decision)]
        (println "  Total decisions recorded:" (count decisions))
        (is (>= (count decisions) 3)
            "Should record multiple decisions"))
      
      ;; Demonstrate pattern recognition (simplified)
      (println "\nPattern recognition demonstration:")
      (let [all-facts (kg/get-facts knowledge-graph :session-0)]
        (println "  First session facts:" (count all-facts))
        (is (seq all-facts)
            "Should have facts from first session")))))

;; ============================================================================
;; Scenario 4: Error Recovery and Resilience
;; ============================================================================

(deftest ^:scenario test-error-recovery-scenario
  (testing "Agent handling errors and edge cases"
    (println "\n=== Scenario: Error Recovery ===")
    
    (let [;; LLM provider that sometimes fails
          llm-provider (reify llm/ILLMProvider
                         (complete [_ messages _]
                           (let [prompt (-> messages first :content)]
                             (if (str/includes? prompt "fail")
                               (throw (ex-info "Simulated API failure" {}))
                               {:text "{\"choice\": \"Fallback\", \"reasoning\": \"Default\", \"confidence\": 0.5}"})))
                         (stream [_ _ _] (async/chan))
                         (embed [_ _ _] []))
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      ;; Test 1: Normal operation
      (println "Test 1: Normal decision")
      (let [normal-result (tf/with-timeout
                            5000
                            #(mh/make-decision orchestrator 
                                               "Normal context" 
                                               ["Option A" "Option B"]))]
        (is (:decision normal-result)
            "Should handle normal case")
        (println "  Result: Success"))
      
      ;; Test 2: Partial failure (some heads fail)
      (println "\nTest 2: Partial failure simulation")
      (let [;; Create mixed heads (some work, some fail)
            working-head (tf/create-mock-decision-head :working "Working" "test" "Option A" 0.8)
            failing-head (reify mh/IDecisionHead
                           (evaluate [_ _ _]
                             (throw (ex-info "Head failure" {})))
                           (specialty [_] "failing")
                           (confidence [_ _] 0)
                           (explain [_ _] "Failed"))
            mixed-orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph)
                                      :heads [working-head failing-head])]
        
        (let [mixed-result (mh/make-decision mixed-orchestrator 
                                             "Mixed context" 
                                             ["A" "B"])]
          (is (:decision mixed-result)
              "Should still make decision despite partial failure")
          (println "  Result: Degraded but functional")))
      
      ;; Test 3: Complete failure recovery
      (println "\nTest 3: Complete failure with fallback")
      (let [fallback-context "This should trigger failure"
            fallback-options ["fail option 1" "fail option 2"]]
        
        (try
          (mh/make-decision orchestrator fallback-context fallback-options)
          (is false "Should have thrown exception")
          (catch Exception e
            (println "  Expected exception caught:" (.getMessage e))
            (is (instance? Exception e)
                "Should catch exceptions"))))
      
      ;; Test 4: Invalid input handling
      (println "\nTest 4: Invalid input handling")
      (tf/assert-no-exceptions
       #(mh/make-decision orchestrator "" []))
      (println "  Result: Handled gracefully"))))

;; ============================================================================
;; Scenario 5: Real-time Collaboration Simulation
;; ============================================================================

(deftest ^:scenario test-real-time-collaboration
  (testing "Simulating real-time team collaboration"
    (println "\n=== Scenario: Real-time Collaboration ===")
    
    (let [knowledge-graph (tf/create-mock-knowledge-graph)
          ;; Simulate distributed team members
          team-members [{:id :alice :role "Data Scientist" :bias "data-driven"}
                        {:id :bob :role "Software Engineer" :bias "scalability"}
                        {:id :charlie :role "Product Manager" :bias "user-value"}
                        {:id :diana :role "Security Expert" :bias "safety"}]
          
          ;; Create heads for each team member
          heads (map (fn [member]
                       (tf/create-mock-decision-head
                        (:id member)
                        (:role member)
                        (:bias member)
                        (str "Option from " (:role member))
                        0.8))
                     team-members)
          
          orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph) :heads heads)]
      
      ;; Real-time design discussion
      (let [design-brief "Design the data pipeline for our AI agent: needs to handle real-time user queries, batch processing of training data, and secure access control."
            architecture-options ["Lambda Architecture - real-time + batch processing"
                                  "Kappa Architecture - event streaming only"
                                  "Micro-batching - small batch intervals"
                                  "Hybrid Approach - combine based on use case"]]
        
        (println "Design brief:" design-brief)
        (println "Team members:" (count team-members))
        (println "Architecture options:" (count architecture-options))
        
        ;; Simulate discussion rounds
        (println "\nSimulating discussion rounds...")
        
        (doseq [round (range 2)]
          (println (format "\nRound %d:" (inc round)))
          
          (let [round-context (format "%s (Round %d)" design-brief (inc round))
                result (mh/make-decision orchestrator round-context architecture-options)]
            
            (println "  Tentative decision:" (:decision result))
            (println "  Team consensus:" (:consensus result))
            
            ;; Store discussion round
            (kg/store-fact knowledge-graph
                          (keyword (str "round-" round))
                          :decision (:decision result))
            (kg/store-fact knowledge-graph
                          (keyword (str "round-" round))
                          :consensus (:consensus result))
            (kg/store-fact knowledge-graph
                          (keyword (str "round-" round))
                          :participants (count team-members)))))
      
      ;; Final decision with improved consensus
      (println "\nFinal decision after discussion:")
      (let [final-result (mh/make-decision orchestrator 
                                           (str design-brief " - FINAL")
                                           architecture-options)]
        (println "  Final choice:" (:decision final-result))
        (println "  Final consensus:" (:consensus final-result))
        
        (is (:decision final-result)
            "Should reach final decision")
        (is (<= 0.3 (:consensus final-result) 1.0)
            "Should have reasonable consensus after discussion")
        
        ;; Generate meeting summary
        (let [summary (format "Design Decision Summary:\n- Chosen architecture: %s\n- Team consensus: %.0f%%\n- Participants: %d specialized roles\n- Process: %d discussion rounds"
                              (:decision final-result)
                              (* 100 (:consensus final-result))
                              (count team-members)
                              2)]
          (println "\n" summary)
          (kgi/store-interaction design-brief summary))))))

;; ============================================================================
;; Test Runner for Scenarios
;; ============================================================================

(defn run-scenario-tests
  "Run all scenario tests."
  []
  (println "Running end-to-end scenario tests...")
  (println "=" 60)
  
  (run-tests #"test-technology-selection-scenario")
  (run-tests #"test-feature-prioritization-scenario")
  (run-tests #"test-continuous-learning-scenario")
  (run-tests #"test-error-recovery-scenario")
  (run-tests #"test-real-time-collaboration")
  
  (println "\n" "=" 60)
  (println "Scenario tests completed."))

(comment
  ;; Run all scenarios
  (run-scenario-tests)
  
  ;; Run specific scenario
  (run-tests 'agent.end-to-end-tests/test-technology-selection-scenario)
  
  ;; Create test agent for manual testing
  (def scenario-agent
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)]
      {:llm-provider llm-provider
       :knowledge-graph knowledge-graph
       :orchestrator (mh/create-orchestrator llm-provider knowledge-graph)}))
  
  ;; Manual scenario test
  (let [agent (:orchestrator scenario-agent)]
    (mh/make-decision agent 
                      "Test manual scenario" 
                      ["Option 1" "Option 2" "Option 3"]))
  )
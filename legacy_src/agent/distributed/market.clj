(ns agent.distributed.market
  "Market-based task allocation using auction mechanisms.
   
   Implements:
   - English auction (ascending price)
   - Virtual currency system
   - Contract Net Protocol
   - Bid strategies
   
   Protocols:
   - IAuctionMarket: Core market operations
   - IBidStrategy: Bid calculation strategies
   - IMarketParticipant: Agent participation interface"
  (:require
   [clojure.core.async :as async :refer [go chan >! <! >!! <!! put! take!]]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log])
  (:import
   (java.time Instant)
   (java.util UUID)))

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol IAuctionMarket
  "Core market operations for task allocation."
  
  (announce-task [this task]
    "Announce a task to the market. Returns auction details.")
  
  (submit-bid [this agent-id task-id bid]
    "Submit a bid for a task. Returns bid acceptance status.")
  
  (close-auction [this task-id]
    "Close auction and select winner. Returns winner details.")
  
  (get-task-status [this task-id]
    "Get current status of a task.")
  
  (get-agent-balance [this agent-id]
    "Get agent's virtual currency balance.")
  
  (transfer-currency [this from-id to-id amount]
    "Transfer currency between agents.")
  
  (get-market-stats [this]
    "Get market statistics."))

(defprotocol IBidStrategy
  "Bid calculation strategies."
  
  (calculate-bid [this task context]
    "Calculate bid for a task based on strategy."))

(defprotocol IMarketParticipant
  "Agent participation in market."
  
  (evaluate-task [this task]
    "Evaluate task and decide whether to bid.")
  
  (submit-bid-for-task [this task-id]
    "Submit bid for a task.")
  
  (receive-payment [this amount]
    "Receive payment for completed task.")
  
  (get-balance [this]
    "Get current balance."))

;; ============================================================================
;; Specs
;; ============================================================================

(s/def ::agent-id string?)
(s/def ::task-id string?)
(s/def ::currency-amount (s/and number? pos?))
(s/def ::bid-amount ::currency-amount)
(s/def ::capabilities (s/coll-of keyword? :kind set?))
(s/def ::estimated-cost ::currency-amount)
(s/def ::estimated-reward ::currency-amount)

(s/def ::task
  (s/keys :req-un [::task-id
                   ::description
                   ::required-capabilities
                   ::estimated-cost
                   ::estimated-reward
                   ::deadline]))

(s/def ::bid
  (s/keys :req-un [::agent-id
                   ::task-id
                   ::bid-amount
                   ::capabilities
                   ::estimated-completion-time]))

(s/def ::auction
  (s/keys :req-un [::task-id
                   ::task
                   ::status
                   ::announced-at
                   ::bids
                   ::winner
                   ::winning-bid
                   ::closed-at]))

(s/def ::market-state
  (s/keys :req-un [::tasks
                   ::agents
                   ::bids
                   ::currency
                   ::statistics]))

;; ============================================================================
;; Core Implementation
;; ============================================================================

(defrecord EnglishAuctionMarket [tasks agents bids currency statistics]
  IAuctionMarket
  
  (announce-task [this task]
    (let [task-id (str (UUID/randomUUID))
          auction {:task-id task-id
                   :task task
                   :status :open
                   :announced-at (Instant/now)
                   :bids {}
                   :winner nil
                   :winning-bid nil
                   :closed-at nil}]
      
      (swap! tasks assoc task-id auction)
      
      ;; Log announcement
      (log/info "Task announced" {:task-id task-id
                                  :description (:description task)
                                  :reward (:estimated-reward task)})
      
      ;; Notify all agents (in real implementation, would use pub/sub)
      (doseq [[agent-id agent] @agents]
        (when (implements? IMarketParticipant agent)
          (try
            (evaluate-task agent task)
            (catch Exception e
              (log/error e "Agent failed to evaluate task" {:agent-id agent-id})))))
      
      auction))
  
  (submit-bid [this agent-id task-id bid]
    (when-let [auction (get @tasks task-id)]
      (when (= :open (:status auction))
        (let [agent-balance (get @currency agent-id 0)
              bid-amount (:amount bid)]
          
          ;; Check if agent has enough currency
          (if (>= agent-balance bid-amount)
            (do
              (swap! bids update task-id
                     (fn [current-bids]
                       (assoc (or current-bids {})
                              agent-id bid)))
              
              (log/info "Bid submitted" {:agent-id agent-id
                                         :task-id task-id
                                         :bid-amount bid-amount})
              
              {:bid-accepted true
               :task-id task-id
               :agent-id agent-id
               :bid bid})
            
            (do
              (log/warn "Insufficient balance for bid" {:agent-id agent-id
                                                        :balance agent-balance
                                                        :bid-amount bid-amount})
              {:bid-accepted false
               :reason :insufficient-balance}))))))
  
  (close-auction [this task-id]
    (when-let [auction (get @tasks task-id)]
      (when (= :open (:status auction))
        (let [task-bids (get @bids task-id {})
              
              ;; Select winner: highest bid wins (English auction)
              winner (when (seq task-bids)
                       (apply max-key (fn [[_ bid]] (:amount bid)) task-bids))
              
              [winner-id winning-bid] winner]
          
          (if winner-id
            (let [bid-amount (:amount winning-bid)]
              
              ;; Transfer currency from winner to task publisher
              (swap! currency update winner-id - bid-amount)
              
              ;; Update auction status
              (swap! tasks update task-id
                     assoc :status :awarded
                           :winner winner-id
                           :winning-bid bid-amount
                           :closed-at (Instant/now))
              
              ;; Update statistics
              (swap! statistics update :auctions-closed (fnil inc 0))
              (swap! statistics update :total-currency-moved (fnil + 0) bid-amount)
              
              (log/info "Auction closed" {:task-id task-id
                                          :winner winner-id
                                          :winning-bid bid-amount
                                          :total-bids (count task-bids)})
              
              {:task-id task-id
               :winner winner-id
               :winning-bid bid-amount
               :awarded true
               :total-bids (count task-bids)})
            
            (do
              ;; No bids, close auction without winner
              (swap! tasks update task-id
                     assoc :status :closed
                           :closed-at (Instant/now))
              
              (log/info "Auction closed with no bids" {:task-id task-id})
              
              {:task-id task-id
               :winner nil
               :awarded false
               :total-bids 0}))))))
  
  (get-task-status [this task-id]
    (get @tasks task-id))
  
  (get-agent-balance [this agent-id]
    (get @currency agent-id 0))
  
  (transfer-currency [this from-id to-id amount]
    (when (and (pos? amount)
               (>= (get @currency from-id 0) amount))
      (swap! currency update from-id - amount)
      (swap! currency update to-id (fnil + 0) amount)
      
      (swap! statistics update :currency-transfers (fnil inc 0))
      (swap! statistics update :total-currency-transferred (fnil + 0) amount)
      
      {:success true
       :from from-id
       :to to-id
       :amount amount}))
  
  (get-market-stats [this]
    @statistics))

;; ============================================================================
;; Bid Strategies
;; ============================================================================

(defrecord CostPlusBidStrategy [profit-margin]
  IBidStrategy
  
  (calculate-bid [this task context]
    (let [estimated-cost (:estimated-cost task)
          bid (* estimated-cost (+ 1.0 profit-margin))]
      {:amount bid
       :strategy :cost-plus
       :profit-margin profit-margin})))

(defrecord CompetitiveBidStrategy [market-history discount-rate]
  IBidStrategy
  
  (calculate-bid [this task context]
    (let [estimated-cost (:estimated-cost task)
          similar-tasks (filter #(similar-task? % task) market-history)
          winning-bids (map :winning-bid similar-tasks)
          avg-winning-bid (if (seq winning-bids)
                            (/ (reduce + winning-bids) (count winning-bids))
                            estimated-cost)
          bid (* avg-winning-bid (- 1.0 discount-rate))]
      {:amount bid
       :strategy :competitive
       :discount-rate discount-rate
       :similar-tasks-count (count similar-tasks)})))

(defrecord UtilityMaximizingBidStrategy [utility-share risk-tolerance]
  IBidStrategy
  
  (calculate-bid [this task context]
    (let [estimated-utility (- (:estimated-reward task) (:estimated-cost task))
          base-bid (* estimated-utility utility-share)
          risk-adjustment (* base-bid risk-tolerance)
          bid (+ base-bid risk-adjustment)]
      {:amount bid
       :strategy :utility-maximizing
       :utility-share utility-share
       :risk-tolerance risk-tolerance})))

(defrecord AdaptiveBidStrategy [learning-rate exploration-rate history]
  IBidStrategy
  
  (calculate-bid [this task context]
    (let [similar-past (filter #(similar-task? % task) history)
          exploration? (< (rand) exploration-rate)]
      
      (if (and (seq similar-past) (not exploration?))
        ;; Exploit: use learned strategy
        (let [successful-bids (filter :won similar-past)
              avg-winning-bid (if (seq successful-bids)
                                (/ (reduce + (map :bid-amount successful-bids))
                                   (count successful-bids))
                                (:estimated-cost task))]
          {:amount (* avg-winning-bid (- 1.0 learning-rate))
           :strategy :adaptive-exploit
           :similar-past-count (count similar-past)})
        
        ;; Explore: try new strategy
        (let [exploration-bid (* (:estimated-cost task) (+ 0.8 (rand 0.4)))]
          {:amount exploration-bid
           :strategy :adaptive-explore})))))

;; ============================================================================
;; Market Participant Agent
;; ============================================================================

(defrecord MarketParticipantAgent [agent-id capabilities balance strategy market]
  IMarketParticipant
  
  (evaluate-task [this task]
    (let [capabilities-match? (set/subset? (:required-capabilities task) capabilities)
          estimated-cost (estimate-task-cost task capabilities)
          estimated-reward (:estimated-reward task)
          utility (- estimated-reward estimated-cost)]
      
      (when (and capabilities-match? (> utility 0))
        (let [bid (calculate-bid strategy task {:estimated-cost estimated-cost
                                                :estimated-utility utility
                                                :current-balance @balance})]
          (assoc bid
                 :bid? true
                 :agent-id agent-id
                 :task-id (:task-id task)
                 :estimated-utility utility)))))

  (submit-bid-for-task [this task-id]
    (when-let [auction (get-task-status market task-id)]
      (when (= :open (:status auction))
        (when-let [evaluation (evaluate-task this (:task auction))]
          (when (:bid? evaluation)
            (submit-bid market agent-id task-id evaluation))))))

  (receive-payment [this amount]
    (swap! balance + amount)
    {:agent-id agent-id
     :amount amount
     :new-balance @balance})

  (get-balance [this]
    @balance))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-market
  "Create a new English auction market."
  []
  (->EnglishAuctionMarket
   (atom {})   ; tasks
   (atom {})   ; agents
   (atom {})   ; bids
   (atom {})   ; currency
   (atom {:auctions-announced 0
          :auctions-closed 0
          :total-bids 0
          :total-currency-moved 0
          :currency-transfers 0
          :total-currency-transferred 0})))

(defn register-agent
  "Register an agent with the market."
  [market agent-id initial-balance]
  (swap! (:currency market) assoc agent-id initial-balance)
  {:agent-id agent-id
   :balance initial-balance
   :registered true})

(defn similar-task?
  "Check if two tasks are similar based on capabilities and cost."
  [task1 task2]
  (and
   (= (:required-capabilities task1) (:required-capabilities task2))
   (< (Math/abs (- (:estimated-cost task1) (:estimated-cost task2)))
      (* (:estimated-cost task1) 0.2)))) ; Within 20% cost difference

(defn estimate-task-cost
  "Estimate cost for an agent to complete a task."
  [task capabilities]
  (let [base-cost (:estimated-cost task)
        capability-match-ratio (/ (count (set/intersection capabilities (:required-capabilities task)))
                                 (count (:required-capabilities task)))]
    ;; Better matching capabilities reduce cost
    (* base-cost (+ 0.5 (/ (- 1.0 capability-match-ratio) 2.0)))))

(defn create-task
  "Create a task for auction."
  [description required-capabilities estimated-cost estimated-reward deadline]
  {:task-id (str (UUID/randomUUID))
   :description description
   :required-capabilities (set required-capabilities)
   :estimated-cost estimated-cost
   :estimated-reward estimated-reward
   :deadline deadline})

;; ============================================================================
;; Market Orchestrator (Hybrid Allocation)
;; ============================================================================

(defrecord MarketOrchestrator [orchestrator market market-threshold]
  
  (allocate-task [this task]
    (let [use-market? (or
                       ;; Always use market for high-value tasks
                       (> (:estimated-reward task) 1000)
                       
                       ;; Use market when system load is high
                       (> (get-in orchestrator [:load :current]) 0.8)
                       
                       ;; Random sampling for market experimentation
                       (< (rand) market-threshold))]
      
      (if use-market?
        ;; Market-based allocation
        (let [auction (announce-task market task)]
          {:allocation-method :market
           :task-id (:task-id auction)
           :auction auction
           :market-task-id (:task-id task)})
        
        ;; Traditional load balancing
        (let [agent-id (select-agent orchestrator (:required-capabilities task))]
          (when agent-id
            (assign-task orchestrator task agent-id)
            {:allocation-method :direct
             :agent-id agent-id
             :task task}))))))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Create market
  (def market (create-market))
  
  ;; Register agents with initial balances
  (register-agent market "agent-1" 1000)
  (register-agent market "agent-2" 1000)
  (register-agent market "agent-3" 1000)
  
  ;; Create agents with different strategies
  (def agent1 (->MarketParticipantAgent
               "agent-1"
               #{:web-scraping :data-analysis}
               (atom 1000)
               (->CostPlusBidStrategy 0.2)  ; 20% profit margin
               market))
  
  (def agent2 (->MarketParticipantAgent
               "agent-2"
               #{:nlp :summarization}
               (atom 1000)
               (->CompetitiveBidStrategy [] 0.05)  ; 5% discount
               market))
  
  ;; Create a task
  (def task (create-task
             "Analyze news articles about AI"
             #{:web-scraping :nlp :summarization}
             100    ; estimated cost
             150    ; estimated reward
             (-> (Instant/now) (.plusSeconds 3600))))  ; 1 hour deadline
  
  ;; Announce task to market
  (announce-task market task)
  
  ;; Agents evaluate and bid
  (submit-bid-for-task agent1 (:task-id task))
  (submit-bid-for-task agent2 (:task-id task))
  
  ;; Close auction after some time
  (Thread/sleep 5000)  ; Wait 5 seconds
  (close-auction market (:task-id task))
  
  ;; Check results
  (get-task-status market (:task-id task))
  (get-market-stats market)
  
  ;; Create market orchestrator
  (def orchestrator (->MarketOrchestrator
                     {:load {:current 0.5}
                      :select-agent (fn [caps] "agent-1")
                      :assign-task (fn [task agent] true)}
                     market
                     0.3))  ; 30% chance to use market
  
  ;; Allocate task through orchestrator
  (allocate-task orchestrator task))
# Market-Based Task Allocation Research
Date: 2026-04-15

## Task: Create market-based task allocation (Phase 4, Task 22.3)

## Overview
Market-based task allocation uses economic principles to distribute tasks among agents. Instead of centralized coordination, agents bid on tasks based on their capabilities, costs, and expected utility.

## Key Concepts

### 1. Auction Mechanisms
- **English auction**: Ascending price, highest bid wins
- **Dutch auction**: Descending price, first bidder wins  
- **Vickrey auction**: Sealed bid, second-highest price wins
- **Combinatorial auction**: Bids on bundles of tasks

### 2. Contract Net Protocol
- **Announcement**: Task publisher announces task
- **Bidding**: Agents submit bids with capabilities and prices
- **Awarding**: Publisher selects best bid
- **Execution**: Winner executes task, reports results

### 3. Utility-Based Allocation
- **Agent utility**: Benefit from completing tasks
- **System utility**: Overall efficiency of allocation
- **Nash equilibrium**: Stable allocation where no agent can improve by changing strategy

### 4. Virtual Economy
- **Currency**: Virtual tokens for resource allocation
- **Prices**: Dynamic pricing based on supply/demand
- **Markets**: Specialized markets for different resource types

## Implementation Approaches

### Simple Auction System
```clojure
(defprotocol IAuctionMarket
  (announce-task [this task]
    "Announce a task to the market")
  
  (submit-bid [this agent-id task-id bid]
    "Submit a bid for a task")
  
  (close-auction [this task-id]
    "Close auction and select winner")
  
  (get-task-status [this task-id]
    "Get current status of a task")
  
  (get-agent-balance [this agent-id]
    "Get agent's virtual currency balance"))
```

### Contract Net Implementation
```clojure
(defrecord ContractNetMarket [tasks agents bids currency]
  IAuctionMarket
  
  (announce-task [this task]
    (let [task-id (generate-task-id task)
          auction {:task-id task-id
                   :task task
                   :status :open
                   :announced-at (java.time.Instant/now)
                   :bids {}}]
      (swap! tasks assoc task-id auction)
      ;; Notify all agents
      (broadcast-task-announcement agents task-id task)
      auction))
  
  (submit-bid [this agent-id task-id bid]
    (when-let [auction (get @tasks task-id)]
      (when (= :open (:status auction))
        (let [agent-balance (get @currency agent-id 0)
              bid-amount (:amount bid)]
          ;; Check if agent has enough currency
          (when (>= agent-balance bid-amount)
            (swap! bids update task-id
                   (fn [current-bids]
                     (assoc (or current-bids {})
                            agent-id bid)))
            {:bid-accepted true
             :task-id task-id
             :agent-id agent-id
             :bid bid})))))
  
  (close-auction [this task-id]
    (when-let [auction (get @tasks task-id)]
      (let [task-bids (get @bids task-id {})
            ;; Select winner (highest bid or best utility)
            winner (select-winner task-bids (:task auction))]
        
        (when winner
          (let [winner-id (:agent-id winner)
                bid-amount (:amount (:bid winner))]
            ;; Transfer currency
            (swap! currency update winner-id - bid-amount)
            ;; Update task status
            (swap! tasks update task-id
                   assoc :status :awarded
                         :winner winner-id
                         :awarded-at (java.time.Instant/now)
                         :winning-bid bid-amount)
            
            {:task-id task-id
             :winner winner-id
             :winning-bid bid-amount
             :awarded true})))))
  
  ;; Other methods...
  )
```

## Design Considerations

### 1. Bid Evaluation
- **Price-based**: Lowest/highest bid wins
- **Capability-based**: Best matching capabilities
- **Reputation-based**: Consider agent reliability
- **Composite scoring**: Weighted combination of factors

### 2. Market Types
- **Spot market**: Immediate task allocation
- **Forward market**: Future task commitments  
- **Options market**: Right but not obligation to execute
- **Combinatorial market**: Complex task bundles

### 3. Economic Models
- **Capitalism**: Agents own resources, compete freely
- **Socialism**: Central allocation with fairness constraints
- **Mixed economy**: Combination of market and planned allocation

### 4. Incentive Mechanisms
- **Rewards**: Payment for task completion
- **Penalties**: Costs for failures or delays
- **Reputation**: Track record affects future opportunities
- **Sliding scales**: Dynamic pricing based on system load

## Integration with Existing System

### Enhanced Agent with Market Participation
```clojure
(defrecord MarketParticipantAgent [agent-node market balance strategy]
  IAgentNode
  ;; Implement agent methods...
  
  ;; Market participation methods
  (evaluate-task [this task]
    (let [capabilities-match? (matches-capabilities? task (:capabilities agent-node))
          expected-cost (estimate-cost task)
          expected-reward (estimate-reward task)
          utility (- expected-reward expected-cost)]
      
      (when (and capabilities-match? (> utility 0))
        {:bid true
         :amount (calculate-bid strategy utility expected-cost)
         :estimated-utility utility
         :estimated-cost expected-cost})))
  
  (submit-bid [this task-id bid]
    (submit-bid market (:agent-id agent-node) task-id bid))
  
  (receive-payment [this amount]
    (swap! balance + amount))
  
  (get-balance [this]
    @balance))
```

### Market-Enhanced Orchestrator
```clojure
(defrecord MarketOrchestrator [orchestrator market]
  IHealthMonitor
  ILoadBalancer
  ICheckpointable
  ;; Implement existing protocols...
  
  IAuctionMarket
  ;; Implement market methods...
  
  ;; Hybrid allocation: use market for some tasks, direct assignment for others
  (allocate-task [this task]
    (if (should-use-market? task)
      ;; Use market-based allocation
      (let [auction (announce-task market task)]
        {:allocation-method :market
         :task-id (:task-id auction)
         :auction auction})
      
      ;; Use traditional load balancing
      (let [agent-id (select-agent orchestrator (:required-capabilities task))]
        (when agent-id
          (assign-task orchestrator task agent-id)
          {:allocation-method :direct
           :agent-id agent-id
           :task task})))))
```

## Bid Strategies

### 1. Cost-Plus Bidding
```clojure
(defn cost-plus-bid [estimated-cost profit-margin]
  (* estimated-cost (+ 1.0 profit-margin)))
```

### 2. Competitive Bidding
```clojure
(defn competitive-bid [estimated-cost market-history]
  (let [similar-tasks (filter-similar-tasks market-history)
        winning-bids (map :winning-bid similar-tasks)
        avg-winning-bid (if (seq winning-bids)
                          (/ (reduce + winning-bids) (count winning-bids))
                          estimated-cost)]
    (* avg-winning-bid 0.95))) ;; 5% below average
```

### 3. Utility-Maximizing Bidding
```clojure
(defn utility-maximizing-bid [estimated-utility risk-tolerance]
  (let [base-bid (* estimated-utility 0.7) ;; 70% of utility
        risk-adjustment (* base-bid risk-tolerance)]
    (+ base-bid risk-adjustment)))
```

### 4. Adaptive Bidding
```clojure
(defrecord AdaptiveBidder [learning-rate exploration-rate history]
  (calculate-bid [this task context]
    (let [similar-past (find-similar-history history task)
          exploration? (< (rand) exploration-rate)]
      
      (if (and (seq similar-past) (not exploration?))
        ;; Exploit: use learned strategy
        (learned-bid similar-past context)
        
        ;; Explore: try new strategy
        (exploratory-bid task context)))))
```

## Market Dynamics

### 1. Price Discovery
- **Initial prices**: Based on estimated costs
- **Price adjustment**: Based on supply/demand
- **Market clearing**: Equilibrium where supply = demand
- **Price volatility**: Measure of market stability

### 2. Market Efficiency
- **Allocative efficiency**: Tasks go to most capable agents
- **Productive efficiency**: Tasks completed at lowest cost
- **Dynamic efficiency**: Adaptation to changing conditions
- **Informational efficiency**: Prices reflect all available information

### 3. Market Failures
- **Monopoly**: Single agent dominates market
- **Externalities**: Effects not reflected in prices
- **Information asymmetry**: Unequal information among agents
- **Public goods**: Tasks that benefit all agents

## Implementation Plan

### Phase 1: Basic Auction System
1. Implement simple English auction
2. Add virtual currency system
3. Create bid submission and evaluation
4. Test with simulated agents

### Phase 2: Advanced Market Features
1. Add multiple auction types
2. Implement contract net protocol
3. Create reputation system
4. Add market monitoring and analytics

### Phase 3: Integration and Optimization
1. Integrate with existing orchestrator
2. Implement hybrid allocation strategies
3. Add adaptive bidding algorithms
4. Create market simulation tools

### Phase 4: Production Features
1. Add market regulation mechanisms
2. Implement fraud detection
3. Create market data APIs
4. Add performance optimization

## Testing Strategy

### Unit Tests
1. Auction mechanics (bidding, closing, winner selection)
2. Currency transactions
3. Bid strategy calculations
4. Market state management

### Integration Tests
1. Multiple agents competing for tasks
2. Market clearing under different conditions
3. Integration with health monitoring
4. Failure recovery in market transactions

### Simulation Tests
1. Large-scale market simulations
2. Stress testing under high load
3. Long-term market stability
4. Economic equilibrium analysis

## Performance Considerations

### Scalability
- **Bid processing**: O(n) where n = number of bids
- **Market clearing**: Complexity depends on auction type
- **State management**: Memory usage for market state
- **Network overhead**: Bid submission and result notification

### Optimization
1. **Batch processing**: Process bids in batches
2. **Lazy evaluation**: Only compute when needed
3. **Caching**: Cache frequently accessed market data
4. **Compression**: Compress bid data for storage/transmission

## Next Steps
1. Design auction market protocol
2. Implement basic English auction
3. Create virtual currency system
4. Develop simple bid strategies
5. Integrate with existing agent system

## References
1. [Market-Based Control: A Paradigm for Distributed Resource Allocation](https://www.cs.cmu.edu/~softagents/papers/market-based-control.pdf)
2. [Contract Net Protocol](https://www.cs.cmu.edu/~softagents/papers/contract-net.pdf)
3. [Auction Theory](https://www.econ.ucla.edu/workshops/papers/Contract/Auction%20theory%20Milgrom.pdf)
4. [Multi-Agent Systems: A Modern Approach to Distributed Artificial Intelligence](https://mitpress.mit.edu/books/multi-agent-systems)
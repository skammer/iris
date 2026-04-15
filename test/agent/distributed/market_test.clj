(ns agent.distributed.market-test
  "Tests for market-based task allocation."
  (:require
   [clojure.test :refer :all]
   [agent.distributed.market :as market]
   [clojure.spec.alpha :as s]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn market-fixture
  "Create a fresh market for each test."
  [f]
  (let [m (market/create-market)]
    ;; Register some test agents
    (market/register-agent m "test-agent-1" 1000)
    (market/register-agent m "test-agent-2" 1000)
    (market/register-agent m "test-agent-3" 1000)
    (f m)))

(use-fixtures :each market-fixture)

(defn create-test-task
  "Create a test task."
  []
  {:task-id "test-task-1"
   :description "Test task"
   :required-capabilities #{:web-scraping :data-analysis}
   :estimated-cost 100
   :estimated-reward 150
   :deadline (-> (Instant/now) (.plusSeconds 3600))})

;; ============================================================================
;; Unit Tests
;; ============================================================================

(deftest test-market-creation
  (testing "Market creation"
    (let [m (market/create-market)]
      (is (satisfies? market/IAuctionMarket m))
      (is (map? (market/get-market-stats m))))))

(deftest test-agent-registration
  (testing "Agent registration and balance"
    (let [m (market/create-market)]
      (market/register-agent m "new-agent" 500)
      (is (= 500 (market/get-agent-balance m "new-agent"))))))

(deftest test-task-announcement
  (testing "Task announcement"
    (let [m (market/create-market)
          task (create-test-task)
          auction (market/announce-task m task)]
      
      (is (= :open (:status auction)))
      (is (= (:task-id task) (:task-id auction)))
      (is (instance? Instant (:announced-at auction))))))

(deftest test-bid-submission
  (testing "Bid submission with sufficient balance"
    (let [m (market/create-market)
          task (create-test-task)
          _ (market/announce-task m task)
          bid {:agent-id "test-agent-1"
               :task-id (:task-id task)
               :amount 120
               :capabilities #{:web-scraping :data-analysis}
               :estimated-completion-time 300}
          result (market/submit-bid m "test-agent-1" (:task-id task) bid)]
      
      (is (:bid-accepted result))
      (is (= "test-agent-1" (:agent-id result)))))

  (testing "Bid submission with insufficient balance"
    (let [m (market/create-market)
          task (create-test-task)
          _ (market/announce-task m task)
          ;; Register agent with low balance
          _ (market/register-agent m "poor-agent" 50)
          bid {:agent-id "poor-agent"
               :task-id (:task-id task)
               :amount 100  ; More than balance
               :capabilities #{:web-scraping :data-analysis}
               :estimated-completion-time 300}
          result (market/submit-bid m "poor-agent" (:task-id task) bid)]
      
      (is (not (:bid-accepted result)))
      (is (= :insufficient-balance (:reason result))))))

(deftest test-auction-closing
  (testing "Auction closing with bids"
    (let [m (market/create-market)
          task (create-test-task)
          _ (market/announce-task m task)
          
          ;; Submit bids
          bid1 {:agent-id "test-agent-1"
                :task-id (:task-id task)
                :amount 120
                :capabilities #{:web-scraping :data-analysis}
                :estimated-completion-time 300}
          
          bid2 {:agent-id "test-agent-2"
                :task-id (:task-id task)
                :amount 140  ; Higher bid
                :capabilities #{:web-scraping :data-analysis}
                :estimated-completion-time 250}
          
          _ (market/submit-bid m "test-agent-1" (:task-id task) bid1)
          _ (market/submit-bid m "test-agent-2" (:task-id task) bid2)
          
          ;; Close auction
          result (market/close-auction m (:task-id task))]
      
      (is (:awarded result))
      (is (= "test-agent-2" (:winner result)))  ; Highest bid wins
      (is (= 140 (:winning-bid result)))
      (is (= 2 (:total-bids result)))
      
      ;; Check balances
      (is (= 860 (market/get-agent-balance m "test-agent-2")))  ; 1000 - 140
      ))

  (testing "Auction closing without bids"
    (let [m (market/create-market)
          task (create-test-task)
          _ (market/announce-task m task)
          result (market/close-auction m (:task-id task))]
      
      (is (not (:awarded result)))
      (is (nil? (:winner result)))
      (is (= 0 (:total-bids result))))))

(deftest test-currency-transfer
  (testing "Currency transfer between agents"
    (let [m (market/create-market)
          _ (market/register-agent m "agent-a" 1000)
          _ (market/register-agent m "agent-b" 1000)
          result (market/transfer-currency m "agent-a" "agent-b" 200)]
      
      (is (:success result))
      (is (= 800 (market/get-agent-balance m "agent-a")))
      (is (= 1200 (market/get-agent-balance m "agent-b")))))

  (testing "Currency transfer with insufficient funds"
    (let [m (market/create-market)
          _ (market/register-agent m "agent-a" 100)
          _ (market/register-agent m "agent-b" 1000)
          result (market/transfer-currency m "agent-a" "agent-b" 200)]  ; More than balance
      
      (is (nil? result))  ; Should return nil when transfer fails
      (is (= 100 (market/get-agent-balance m "agent-a")))
      (is (= 1000 (market/get-agent-balance m "agent-b"))))))

;; ============================================================================
;; Bid Strategy Tests
;; ============================================================================

(deftest test-cost-plus-strategy
  (testing "Cost-plus bid calculation"
    (let [strategy (market/->CostPlusBidStrategy 0.2)  ; 20% profit margin
          task {:estimated-cost 100
                :estimated-reward 150}
          context {:estimated-cost 100
                   :estimated-utility 50
                   :current-balance 1000}
          bid (market/calculate-bid strategy task context)]
      
      (is (= :cost-plus (:strategy bid)))
      (is (= 120.0 (:amount bid)))  ; 100 * 1.2
      (is (= 0.2 (:profit-margin bid))))))

(deftest test-competitive-strategy
  (testing "Competitive bid calculation"
    (let [market-history [{:winning-bid 110}
                          {:winning-bid 120}
                          {:winning-bid 115}]
          strategy (market/->CompetitiveBidStrategy market-history 0.05)  ; 5% discount
          task {:estimated-cost 100
                :estimated-reward 150}
          context {:estimated-cost 100
                   :estimated-utility 50
                   :current-balance 1000}
          bid (market/calculate-bid strategy task context)]
      
      (is (= :competitive (:strategy bid)))
      ;; Average winning bid = (110+120+115)/3 = 115
      ;; 5% discount = 115 * 0.95 = 109.25
      (is (approx= 109.25 (:amount bid) 0.01))
      (is (= 0.05 (:discount-rate bid)))
      (is (= 3 (:similar-tasks-count bid))))))

(deftest test-utility-maximizing-strategy
  (testing "Utility-maximizing bid calculation"
    (let [strategy (market/->UtilityMaximizingBidStrategy 0.7 0.1)  ; 70% utility share, 10% risk
          task {:estimated-cost 100
                :estimated-reward 150}
          context {:estimated-cost 100
                   :estimated-utility 50  ; 150 - 100
                   :current-balance 1000}
          bid (market/calculate-bid strategy task context)]
      
      (is (= :utility-maximizing (:strategy bid)))
      ;; Base bid = 50 * 0.7 = 35
      ;; Risk adjustment = 35 * 0.1 = 3.5
      ;; Total = 38.5
      (is (approx= 38.5 (:amount bid) 0.01))
      (is (= 0.7 (:utility-share bid)))
      (is (= 0.1 (:risk-tolerance bid))))))

;; ============================================================================
;; Market Participant Tests
;; ============================================================================

(deftest test-market-participant
  (testing "Market participant evaluation"
    (let [m (market/create-market)
          strategy (market/->CostPlusBidStrategy 0.2)
          agent (market/->MarketParticipantAgent
                 "test-agent"
                 #{:web-scraping :data-analysis}
                 (atom 1000)
                 strategy
                 m)
          task {:task-id "test-task"
                :description "Test"
                :required-capabilities #{:web-scraping :data-analysis}
                :estimated-cost 100
                :estimated-reward 150
                :deadline (-> (Instant/now) (.plusSeconds 3600))}
          evaluation (market/evaluate-task agent task)]
      
      (is (:bid? evaluation))
      (is (= "test-agent" (:agent-id evaluation)))
      (is (= "test-task" (:task-id evaluation)))
      (is (pos? (:estimated-utility evaluation)))))

  (testing "Market participant with mismatched capabilities"
    (let [m (market/create-market)
          strategy (market/->CostPlusBidStrategy 0.2)
          agent (market/->MarketParticipantAgent
                 "test-agent"
                 #{:nlp :summarization}  ; Different capabilities
                 (atom 1000)
                 strategy
                 m)
          task {:task-id "test-task"
                :description "Test"
                :required-capabilities #{:web-scraping :data-analysis}  ; Mismatch
                :estimated-cost 100
                :estimated-reward 150
                :deadline (-> (Instant/now) (.plusSeconds 3600))}
          evaluation (market/evaluate-task agent task)]
      
      (is (nil? evaluation))))  ; Should not bid

  (testing "Market participant payment"
    (let [m (market/create-market)
          strategy (market/->CostPlusBidStrategy 0.2)
          agent (market/->MarketParticipantAgent
                 "test-agent"
                 #{:web-scraping :data-analysis}
                 (atom 1000)
                 strategy
                 m)
          payment (market/receive-payment agent 200)]
      
      (is (= "test-agent" (:agent-id payment)))
      (is (= 200 (:amount payment)))
      (is (= 1200 (:new-balance payment)))
      (is (= 1200 (market/get-balance agent))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-market-integration
  (testing "Complete market workflow"
    (let [m (market/create-market)
          
          ;; Register agents
          _ (market/register-agent m "agent-a" 1000)
          _ (market/register-agent m "agent-b" 1000)
          
          ;; Create agents with different strategies
          agent-a (market/->MarketParticipantAgent
                   "agent-a"
                   #{:web-scraping :data-analysis}
                   (atom 1000)
                   (market/->CostPlusBidStrategy 0.2)
                   m)
          
          agent-b (market/->MarketParticipantAgent
                   "agent-b"
                   #{:web-scraping :data-analysis :nlp}
                   (atom 1000)
                   (market/->CompetitiveBidStrategy [] 0.05)
                   m)
          
          ;; Create task
          task (market/create-task
                "Analyze data"
                #{:web-scraping :data-analysis}
                100
                150
                (-> (Instant/now) (.plusSeconds 3600)))
          
          ;; Announce task
          auction (market/announce-task m task)]
      
      (is (= :open (:status auction)))
      
      ;; Agents bid
      (let [bid-result-a (market/submit-bid-for-task agent-a (:task-id task))
            bid-result-b (market/submit-bid-for-task agent-b (:task-id task))]
        
        (is bid-result-a)
        (is bid-result-b))
      
      ;; Close auction
      (Thread/sleep 100)  ; Small delay
      (let [close-result (market/close-auction m (:task-id task))]
        
        (is (:awarded close-result))
        (is (contains? #{"agent-a" "agent-b"} (:winner close-result)))
        
        ;; Check market stats
        (let [stats (market/get-market-stats m)]
          (is (pos? (:auctions-closed stats)))
          (is (pos? (:total-currency-moved stats))))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(def task-gen
  "Generator for tasks."
  (gen/hash-map
   :task-id gen/string-alphanumeric
   :description gen/string
   :required-capabilities (gen/set (gen/elements [:web-scraping :data-analysis :nlp :summarization]))
   :estimated-cost (gen/choose 50 500)
   :estimated-reward (gen/choose 100 1000)
   :deadline (gen/return (-> (Instant/now) (.plusSeconds 3600)))))

(def bid-gen
  "Generator for bids."
  (gen/hash-map
   :agent-id gen/string-alphanumeric
   :task-id gen/string-alphanumeric
   :amount (gen/choose 1 1000)
   :capabilities (gen/set (gen/elements [:web-scraping :data-analysis :nlp :summarization]))
   :estimated-completion-time (gen/choose 60 3600)))

(deftest market-properties
  (testing "Market invariant: balance never negative"
    (let [prop (prop/for-all [tasks (gen/vector task-gen 1 5)
                              bids (gen/vector bid-gen 1 10)]
                 (let [m (market/create-market)
                       ;; Register agents with initial balances
                       _ (doseq [bid bids]
                           (market/register-agent m (:agent-id bid) 1000))
                       
                       ;; Announce tasks
                       _ (doseq [task tasks]
                           (market/announce-task m task))
                       
                       ;; Submit bids
                       _ (doseq [bid bids]
                           (market/submit-bid m (:agent-id bid) (:task-id bid) bid))
                       
                       ;; Close auctions
                       _ (doseq [task tasks]
                           (market/close-auction m (:task-id task)))
                       
                       ;; Check all balances
                       balances (map #(market/get-agent-balance m (:agent-id %)) bids)]
                   
                   ;; All balances should be >= 0
                   (every? #(>= % 0) balances)))]
      
      (is (tc/quick-check 100 prop))))

  (testing "Market invariant: currency conservation"
    (let [prop (prop/for-all [transfers (gen/vector (gen/tuple gen/string-alphanumeric
                                                               gen/string-alphanumeric
                                                               (gen/choose 1 500))
                                                    1 10)]
                 (let [m (market/create-market)
                       ;; Register agents
                       agent-ids (distinct (concat (map first transfers)
                                                   (map second transfers)))
                       _ (doseq [agent-id agent-ids]
                           (market/register-agent m agent-id 1000))
                       
                       ;; Perform transfers
                       results (map (fn [[from to amount]]
                                      (market/transfer-currency m from to amount))
                                    transfers)
                       
                       ;; Calculate total currency
                       total-after (reduce + (map #(market/get-agent-balance m %) agent-ids))]
                   
                   ;; Total currency should remain constant
                   (= total-after (* (count agent-ids) 1000))))]
      
      (is (tc/quick-check 50 prop)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn approx=
  "Check if two numbers are approximately equal."
  [a b epsilon]
  (< (Math/abs (- a b)) epsilon))

(defn run-all-tests
  "Run all market tests."
  []
  (run-tests 'agent.distributed.market-test))

(comment
  ;; Run tests
  (run-all-tests)
  
  ;; Run specific test
  (test-market-creation)
  
  ;; Interactive testing
  (let [m (market/create-market)
        task (create-test-task)]
    (market/announce-task m task)
    (market/get-market-stats m)))
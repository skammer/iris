(ns agent.telegram.rich-fallback-test
  (:require
   [agent.telegram.test-support :refer [chat-stub recording-opts rich-test-config temp-db-path update-for]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(deftest telegram-rich-draft-failure-downgrades-turn-to-legacy
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        rich-draft-calls (atom 0)
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        opts (recording-opts recorders
                             :rich-draft-fn (fn [_ _ _]
                                              (swap! rich-draft-calls inc)
                                              (throw (ex-info "rich down"
                                                              {:type :rich-down}))))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (doseq [d ["a" "b" "c"]]
                                   (Thread/sleep 1300)
                                   (emit! :message-update {:delta d}))
                                 (emit! :message-end {:content "abc" :final? true})
                                 {:content "abc"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (testing "one rich attempt, then sticky legacy downgrade"
        (is (= 1 @rich-draft-calls))
        (is (pos? (count @(:drafts recorders))))
        (is (empty? @(:rich-drafts recorders))))
      (testing "failure recorded"
        (let [failure (first (filter #(= :telegram.operation.failed (:event-type %)) @events))]
          (is (= :rich-draft-update (get-in failure [:payload :operation])))
          (is (= :rich-down (get-in failure [:payload :type])))))
      (testing "final goes through legacy after downgrade"
        (is (= [{:chat-id 100 :text "abc"}] @(:sent recorders)))
        (is (empty? @(:rich-sent recorders))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-final-failure-falls-back-to-legacy
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        opts (recording-opts recorders
                             :rich-send-fn (fn [_ _]
                                             (throw (ex-info "rich final down"
                                                             {:type :rich-down}))))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:thinking-delta "hmm"})
                                 (emit! :message-update {:delta "answer"})
                                 (emit! :message-end {:content "answer" :final? true})
                                 {:content "answer"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (is (= [{:chat-id 100 :text "answer"}] @(:sent recorders)))
      (is (= [{:chat-id 100
               :text "<blockquote expandable>thinking\n\nhmm</blockquote>"}]
             @(:html-sent recorders)))
      (let [failure (first (filter #(= :telegram.operation.failed (:event-type %)) @events))]
        (is (= :rich-finalize (get-in failure [:payload :operation]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-disabled-keeps-legacy-path
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (recording-opts recorders)]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:delta "plain"})
                                 (emit! :message-end {:content "plain" :final? true})
                                 {:content "plain"}))]
        (is (= :processed
               (telegram/process-update! system
                                         (assoc (rich-test-config) :rich-messages? false)
                                         opts
                                         (update-for 1 100 7 "hi")))))
      (is (= [{:chat-id 100 :text "plain"}] @(:sent recorders)))
      (is (empty? @(:rich-sent recorders)))
      (is (empty? @(:rich-drafts recorders)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-group-chat-sends-rich-final-without-drafts
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (recording-opts recorders)
        update (assoc-in (update-for 1 -200 7 "hi") [:message :chat :type] "group")]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-end {:content "# Report" :final? true})
                                 {:content "# Report"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts update))))
      (is (= [{:chat-id -200 :markdown "# Report"}] @(:rich-sent recorders)))
      (is (empty? @(:rich-drafts recorders)))
      (is (empty? @(:sent recorders)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

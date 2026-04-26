(ns agent.telegram-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-telegram-" ".db")))

(defn update-for
  [update-id chat-id user-id text]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :text text}})

(deftest telegram-reuses-session-per-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        calls (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true
                            :user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))
              :run-chat-fn (fn [_ {:keys [session-id messages]}]
                             (swap! calls conj {:session-id session-id
                                                :messages messages})
                             {:content "pong"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= :processed
             (telegram/process-update! system config opts (update-for 2 100 7 "again"))))
      (is (= :processed
             (telegram/process-update! system config opts (update-for 3 200 8 "other"))))
      (is (= [100 100 200] (mapv :chat-id @sent)))
      (is (= ["pong" "pong" "pong"] (mapv :text @sent)))
      (is (= 3 (count @calls)))
      (is (= (:session-id (first @calls))
             (:session-id (second @calls))))
      (is (not= (:session-id (first @calls))
                (:session-id (nth @calls 2))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-reset-replaces-session
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true
                            :user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (telegram/process-update! system config opts (update-for 1 100 7 "/start"))
      (let [before (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
        (telegram/process-update! system config opts (update-for 2 100 7 "/reset"))
        (let [after (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
          (is (not= before after))
          (is (= "Session reset." (:text (last @sent))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-allowlist-blocks-unknown
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:user-ids ["7"]
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (= :blocked
             (telegram/process-update! system config opts (update-for 1 100 8 "hi"))))
      (is (empty? @sent))
      (is (= :telegram.blocked (:event-type (first @events))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-denies-empty-allowlist-by-default
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (= :blocked
             (telegram/process-update! system config opts (update-for 1 100 8 "hi"))))
      (is (empty? @sent))
      (is (= :telegram.blocked (:event-type (first @events))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-poll-retries-failed-update-before-advancing-offset
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        polls (atom [])
        attempts (atom 0)
        update (update-for 41 100 7 "hi")
        system {:store store
                :config {:channel-adapters {:telegram {:enabled true
                                                       :bot-token "token"
                                                       :poll-timeout-seconds 0
                                                       :poll-limit 1
                                                       :allowlist {:allow-all? true}}}}
                :event-sink #(swap! events conj %)}
        service (telegram/create-service
                 system
                 {:get-updates-fn (fn [{:keys [offset]}]
                                    (swap! polls conj offset)
                                    (if (< (count @polls) 3) [update] []))
                  :send-message-fn (fn [_ _] nil)
                  :run-chat-fn (fn [_ _]
                                 (if (= 1 (swap! attempts inc))
                                   (throw (ex-info "boom" {}))
                                   {:content "ok"}))})]
    (try
      (telegram/start! service)
      (Thread/sleep 1500)
      (telegram/stop! service 1000)
      (is (= [nil nil 42] (take 3 @polls)))
      (is (= 2 @attempts))
      (is (= 42 (:next_offset (sqlite/get-channel-offset store :telegram))))
      (is (= "processed" (:status (sqlite/get-channel-inbox-update store :telegram 41))))
      (finally
        (telegram/stop! service 1000)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-ensure-is-concurrency-stable
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        mapping {:source :telegram
                 :external-chat-id 100
                 :title "Telegram: Test"
                 :metadata {:chat {:id 100}}}]
    (try
      (let [sessions (->> (repeatedly 20 #(future (:session-id (sqlite/ensure-channel-session! store mapping))))
                          doall
                          (mapv deref))]
        (is (= 1 (count (set sessions)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

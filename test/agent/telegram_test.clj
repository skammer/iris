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
                :allowlist {:user-ids []
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
                :allowlist {:user-ids []
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

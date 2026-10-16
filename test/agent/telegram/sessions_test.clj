(ns agent.telegram.sessions-test
  (:require
   [agent.telegram.test-support :refer [chat-stub temp-db-path update-for]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [agent.telegram.sessions :as telegram-sessions]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is ]])
  (:import
   (java.time Instant)))

(deftest telegram-reuses-session-per-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        calls (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true
                            :user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id messages]} emit!]
                                 (swap! calls conj {:session-id session-id
                                                    :messages messages})
                                 (emit! :message-end {:content "pong" :final? true})
                                 {:content "pong"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
        (is (= :processed
               (telegram/process-update! system config opts (update-for 2 100 7 "again"))))
        (is (= :processed
               (telegram/process-update! system config opts (update-for 3 200 8 "other")))))
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

(deftest telegram-session-reset-policy-rotates-after-idle
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        chat {:id 100 :type "private" :first_name "Test"}
        policy {:mode :idle :idle-minutes 1440}
        start (Instant/parse "2026-08-20T10:00:00Z")]
    (try
      (let [first-mapping (telegram-sessions/ensure-session! store chat policy start)
            active-mapping (telegram-sessions/ensure-session!
                            store chat policy (.plusSeconds start (* 23 60 60)))
            reset-mapping (telegram-sessions/ensure-session!
                           store chat policy (.plusSeconds start (* 48 60 60)))]
        (is (= (:session-id first-mapping) (:session-id active-mapping)))
        (is (not= (:session-id active-mapping) (:session-id reset-mapping)))
        (is (= :idle (:reset-reason reset-mapping)))
        (is (= (:session-id active-mapping) (:previous-session-id reset-mapping))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-reset-policy-default-keeps-session
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        chat {:id 100 :type "private" :first_name "Test"}
        start (Instant/parse "2026-01-01T00:00:00Z")]
    (try
      (let [before (telegram-sessions/ensure-session! store chat nil start)
            after (telegram-sessions/ensure-session!
                   store chat nil (.plusSeconds start (* 365 24 60 60)))]
        (is (= (:session-id before) (:session-id after)))
        (is (nil? (:reset-reason after))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-reset-policy-rotates-at-daily-boundary
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        chat {:id 100 :type "private" :first_name "Test"}
        policy {:mode :daily :at-hour 4}
        start (Instant/parse "2026-08-20T00:00:00Z")]
    (try
      (let [before (telegram-sessions/ensure-session! store chat policy start)
            after (telegram-sessions/ensure-session!
                   store chat policy (.plusSeconds start (* 48 60 60)))]
        (is (not= (:session-id before) (:session-id after)))
        (is (= :daily (:reset-reason after))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-reset-policy-does-not-expire-legacy-mapping-on-first-touch
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        chat {:id 100 :type "private" :first_name "Test"}
        policy {:mode :idle :idle-minutes 60}
        old (Instant/parse "2026-01-01T00:00:00Z")
        now (Instant/parse "2026-08-24T00:00:00Z")]
    (try
      (let [legacy (sqlite/ensure-channel-session!
                    store
                    {:source :telegram
                     :external-chat-id 100
                     :title "Telegram: Test"
                     :metadata {:chat chat}
                     :now (str old)})
            first-touch (telegram-sessions/ensure-session! store chat policy now)
            after-idle (telegram-sessions/ensure-session!
                        store chat policy (.plusSeconds now 3601))]
        (is (= (:session-id legacy) (:session-id first-touch)))
        (is (nil? (:reset-reason first-touch)))
        (is (not= (:session-id first-touch) (:session-id after-idle)))
        (is (= :idle (:reset-reason after-idle))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-auto-reset-notifies-and-uses-new-session
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        calls (atom [])
        chat {:id 100 :type "private" :first_name "Test"}
        old-mapping (sqlite/ensure-channel-session!
                     store
                     {:source :telegram
                      :external-chat-id 100
                      :title "Telegram: Test"
                      :metadata {:chat chat :session-reset-tracked? true}
                      :now (str (.minusSeconds (Instant/now) 7200))})
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :session-reset {:mode :idle :idle-minutes 60 :notify? true}
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id]} emit!]
                                 (swap! calls conj session-id)
                                 (emit! :message-end {:content "pong" :final? true})
                                 {:content "pong"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (= ["Session reset after inactivity." "pong"]
             (mapv :text @sent)))
      (is (= 1 (count @calls)))
      (is (not= (:session-id old-mapping) (first @calls)))
      (is (= :idle
             (get-in (first (filter #(= :telegram.session.auto-reset
                                            (:event-type %))
                                    @events))
                     [:payload :reason])))
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

(deftest telegram-prompt-command-manages-session-mode
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (telegram/process-update! system config opts (update-for 1 100 7 "/prompt"))
      (telegram/process-update! system config opts (update-for 2 100 7 "/prompt code"))
      (let [session-id (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
        (is (= "code" (:active-mode (sqlite/get-session store session-id))))
        (telegram/process-update! system config opts (update-for 3 100 7 "/prompt off"))
        (is (nil? (:active-mode (sqlite/get-session store session-id))))
        (telegram/process-update! system config opts (update-for 4 100 7 "/prompt missing"))
        (telegram/process-update! system config opts (update-for 5 100 7 "/help"))
        (is (str/includes? (:text (nth @sent 0)) "Prompt mode: off"))
        (is (str/includes? (:text (nth @sent 0)) "Available: ask"))
        (is (= "Prompt mode: code." (:text (nth @sent 1))))
        (is (= "Prompt mode off." (:text (nth @sent 2))))
        (is (str/includes? (:text (nth @sent 3)) "Unknown prompt mode: missing"))
        (is (str/includes? (:text (nth @sent 4)) "/prompt [name|off]")))
      (finally
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

(ns agent.telegram-test
  (:require
   [agent.telegram.test-support :refer [chat-stub eventually temp-db-path update-for]]
   [agent.channels.core :as channels]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is ]]))

(deftest telegram-advertises-only-implemented-adapter-capabilities
  (let [service (telegram/create-service {:config {:channel-adapters {:telegram {:bot-token "token"}}}})
        caps (:capabilities (channels/describe-adapter service))]
    (is (= #{:supports-outbound
             :supports-typing}
           caps))
    (is (empty? (channels/capability-validation-errors service)))))

(deftest telegram-async-chat-does-not-send-agent-lifecycle-messages
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        active-tasks (atom {})
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:async-chat? true
              :active-tasks active-tasks
              :send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:delta "pong"})
                                 (emit! :message-end {:content "pong" :final? true})
                                 {:content "pong"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (eventually #(empty? @active-tasks)))
      (is (not-any? #(#{"Agent started." "Agent stopped."} (:text %)) @sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-service-start-stop-sends-lifecycle-to-known-chats
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        marked (atom [])
        system {:store store
                :config {:channel-adapters {:telegram {:enabled true
                                                       :bot-token "token"
                                                       :poll-timeout-seconds 1
                                                       :poll-limit 1
                                                       :allowlist {:allow-all? true}}}}
                :event-sink (fn [_] nil)}
        service (telegram/create-service
                 system
                 {:get-updates-fn (fn [_]
                                    (Thread/sleep 50)
                                    [])
                  :send-message-fn (fn [chat-id text]
                                     (swap! sent conj {:chat-id chat-id :text text}))
                  :send-message-with-reply-markup-fn
                  (fn [chat-id text reply-markup]
                    (swap! marked conj {:chat-id chat-id
                                        :text text
                                        :reply-markup reply-markup}))})]
    (try
      (sqlite/ensure-channel-session!
       store
       {:source :telegram
        :external-chat-id 100
        :title "Telegram: Test"
        :metadata {:chat {:id 100 :type "private"}}})
      (telegram/start! service)
      (is (= [{:chat-id 100
               :text "Agent started."
               :reply-markup {:remove_keyboard true}}]
             @marked))
      (is (empty? @sent))
      (telegram/stop! service 1000)
      (is (= [{:chat-id 100 :text "Agent stopped."}] @sent))
      (telegram/stop! service 1000)
      (is (= 1 (count @sent)))
      (finally
        (telegram/stop! service 1000)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-service-resolves-reloaded-system
  (let [system-ref (atom nil)
        old-system {:version :old
                    :system-ref system-ref
                    :config {:channel-adapters {:telegram {}}}}
        new-system (assoc old-system :version :new)
        service (telegram/create-service old-system)]
    (reset! system-ref old-system)
    (reset! system-ref new-system)
    (is (= :new (:version (#'telegram/current-system (:system service)))))))

(deftest telegram-skills-command-and-slash-skill-ack-test
  (let [path (temp-db-path)
        root (.toFile (java.nio.file.Files/createTempDirectory "iris-telegram-skills-" (make-array java.nio.file.attribute.FileAttribute 0)))
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n\nUse review checklist.")
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        prompts (atom [])
        system {:store store
                :skills-registry {:dirs [(.getAbsolutePath root)]}
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-chat-action-fn (fn [& _] nil)
              :send-message-draft-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [chat-opts emit!]
                                 (swap! prompts conj (get-in chat-opts [:messages 0 :content]))
                                 (emit! :message-end {:content "ok" :final? true})
                                 {:content "ok"}))]
        (telegram/process-update! system config opts (update-for 1 100 7 "/skills rev"))
        (telegram/process-update! system config opts (update-for 2 100 7 "/review this")))
      (is (str/includes? (:text (first @sent)) "/review - Review code"))
      (is (= "Skills: /review" (:text (second @sent))))
      (is (= "/review this" (first @prompts)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
        (io/delete-file path true)))))

(deftest telegram-stop-cancels-active-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        active-tasks (atom {})
        task-started (promise)
        release-task (promise)
        task-id "task-1"
        task (future
               (deliver task-started true)
               @release-task)
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:active-tasks active-tasks
              :send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (true? (deref task-started 1000 false)))
      (swap! active-tasks assoc 100 {:id task-id :future task})
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "/stop"))))
      (is (= [{:chat-id 100 :text "Stopping."}] @sent))
      (is (nil? (get @active-tasks 100)))
      (deliver release-task true)
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

(deftest telegram-native-stop-preserves-partial-and-cancels-matching-draft
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        finalized (atom 0)
        events (atom [])
        active-tasks (atom {100 {:session-id "session-1"
                                 :draft-id (atom 42)
                                 :finalize! #(swap! finalized inc)}})
        system {:store store :event-sink #(swap! events conj %)}
        config {:allowlist {:user-ids ["100"]}}
        update {:update_id 1
                :stopped_message_generation
                {:chat {:id 100 :type "private"}
                 :draft_id 42}}]
    (try
      (is (= :processed
             (telegram/process-update! system config {:active-tasks active-tasks} update)))
      (is (= 1 @finalized))
      (is (nil? (get @active-tasks 100)))
      (is (= :telegram.generation-stopped
             (:event-type (last @events))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-native-stop-ignores-stale-draft
  (let [active-tasks (atom {100 {:session-id "session-1"
                                 :draft-id (atom 41)}})
        update {:update_id 1
                :stopped_message_generation
                {:chat {:id 100 :type "private"}
                 :draft_id 42}}]
    (is (= :ignored
           (telegram/process-update! {:event-sink (fn [_] nil)}
                                     {:allowlist {:allow-all? true}}
                                     {:active-tasks active-tasks}
                                     update)))
    (is (contains? @active-tasks 100))))

(deftest telegram-poll-advances-offset-past-failed-update
  ;; A poison update must not head-of-line-block the channel: it is preserved
  ;; as :failed in channel_inbox and the poller moves on to later updates.
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        polls (atom [])
        attempts (atom 0)
        poison (update-for 41 100 7 "hi")
        follow-up (update-for 42 100 7 "hello again")
        system {:store store
                :config {:channel-adapters {:telegram {:enabled true
                                                       :bot-token "token"
                                                       :poll-timeout-seconds 0
                                                       :poll-limit 1
                                                       :allowlist {:allow-all? true}}}}
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        service (telegram/create-service
                 system
                 {:get-updates-fn (fn [{:keys [offset]}]
                                    (swap! polls conj offset)
                                    (cond
                                      (nil? offset) [poison]
                                      (= 42 offset) [follow-up]
                                      :else []))
                  :send-message-fn (fn [_ _] nil)
                  :send-message-draft-fn (fn [& _] nil)
                  :send-chat-action-fn (fn [& _] nil)
                  :async-chat? false})]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (if (= 1 (swap! attempts inc))
                                   (throw (ex-info "boom" {}))
                                   (do
                                     (emit! :message-end {:content "ok" :final? true})
                                     {:content "ok"}))))]
        (telegram/start! service)
        (Thread/sleep 2500)
        (telegram/stop! service 1000))
      (is (= [nil 42 43] (take 3 @polls)))
      (is (= 2 @attempts))
      (is (= 43 (:next_offset (sqlite/get-channel-offset store :telegram))))
      (is (= "failed" (:status (sqlite/get-channel-inbox-update store :telegram 41))))
      (is (= "processed" (:status (sqlite/get-channel-inbox-update store :telegram 42))))
      (finally
        (telegram/stop! service 1000)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

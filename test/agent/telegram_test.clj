(ns agent.telegram-test
  (:require
   [agent.broker.core :as broker]
   [agent.channels.core :as channels]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [agent.telegram.api :as telegram-api]
   [agent.telegram.approvals :as telegram-approvals]
   [agent.telegram.streaming :as telegram-streaming]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.service :as tool-service]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-telegram-" ".db")))

(defn chat-stub
  "`chat/run!` replacement for the broker-events chat runner. Calls
   `(handler opts emit!)` where `emit!` publishes a session event (event-type +
   payload) on the system :event-bus, then emits the terminal agent-end event
   the runner waits for and returns the handler's result map. When the handler
   throws, no agent-end is emitted — the runner must treat the error itself as
   terminal."
  [handler]
  (fn [system {:keys [session-id] :as opts}]
    (let [bus (or (:event-bus system) (:broker system))
          emit! (fn [event-type payload]
                  (doseq [message (broker/event->messages
                                   {:event-type (name event-type)
                                    :entity-type "session"
                                    :entity-id session-id
                                    :payload payload})]
                    (broker/publish! bus message)))
          result (handler opts emit!)]
      (emit! :agent-end {:stop-reason (or (:stop-reason result) :completed)})
      result)))

(defn update-for
  [update-id chat-id user-id text]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :text text}})

(defn callback-update-for
  [update-id chat-id user-id message-id data]
  {:update_id update-id
   :callback_query {:id (str "callback-" update-id)
                    :from {:id user-id}
                    :message {:message_id message-id
                              :chat {:id chat-id
                                     :type "private"
                                     :first_name "Test"}}
                    :data data}})

(defn photo-update-for
  [update-id chat-id user-id caption]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :caption caption
             :photo [{:file_id "small" :file_size 10 :width 10 :height 10}
                     {:file_id "big" :file_size 20 :width 20 :height 20}]}})

(defn voice-update-for
  [update-id chat-id user-id]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :voice {:file_id "voice-1"
                     :file_size 12
                     :mime_type "audio/ogg"}}})

(deftest telegram-advertises-only-implemented-adapter-capabilities
  (let [service (telegram/create-service {:config {:channel-adapters {:telegram {:bot-token "token"}}}})
        caps (:capabilities (channels/describe-adapter service))]
    (is (= #{:supports-outbound
             :supports-typing}
           caps))
    (is (empty? (channels/capability-validation-errors service)))))

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

(deftest telegram-get-updates-includes-callback-queries
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [_ method body]
	                                          (swap! calls conj {:method method :body body})
	                                          [])]
      (telegram-api/get-updates! "token" {:offset 10 :timeout 1 :limit 2})
      (is (= [{:method "getUpdates"
               :body {:timeout 1
                      :limit 2
                      :allowed_updates ["message" "callback_query"]
                      :offset 10}}]
             @calls)))))

(deftest telegram-approval-card-puts-details-in-expandable-quote
  (let [approval {:id "app-1"
                  :tool-name "shell"
                  :reason "Agent requested shell"
                  :input {:argv ["tavily.sh" "weather <x>"]}}
        html (telegram-approvals/card-html approval)]
    (is (str/includes? html "Tool approval required"))
    (is (str/includes? html "Reason: Agent requested shell"))
    (is (str/includes? html "<blockquote expandable>details"))
    (is (str/includes? html "approval_id: app-1"))
    (is (str/includes? html "&lt;x&gt;"))))

(deftest telegram-approval-callback-approves-and-runs-tool
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        registry (tool-service/create-tool-registry
                  {:cfg {:shell {:roots ["."]
                                 :working-dir "."
                                 :default-decision :ask
                                 :rules []}}
                   :event-sink (fn [_] nil)
                   :store store})
        sent (atom [])
        answers (atom [])
        edits (atom [])
        continuation-messages (atom [])
        continuation-context (atom nil)
        continuation-session-id (atom nil)
        approval (tool-approvals/create-request!
                  store
                  {:tool-name :shell
                   :input {:argv ["printf" "ok"]}
                   :requested-by "telegram-session"
                   :reason "test"})
        system {:store store
                :tool-registry registry
                :config {:tools {:yolo? false}}
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :answer-callback-query-fn (fn [callback-id body]
                                          (swap! answers conj {:callback-id callback-id :body body}))
              :edit-message-reply-markup-fn (fn [chat-id message-id reply-markup]
                                              (swap! edits conj {:chat-id chat-id
                                                                 :message-id message-id
                                                                 :reply-markup reply-markup}))
              :send-chat-action-fn (fn [_ _] nil)
              :send-message-draft-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id messages context]} emit!]
                                 (reset! continuation-session-id session-id)
                                 (reset! continuation-messages messages)
                                 (reset! continuation-context context)
                                 (emit! :message-end {:content "agent continued" :final? true})
                                 {:content "agent continued"}))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (callback-update-for 1 100 7 55 (str "ta:run:" (:id approval)))))))
      (is (= "approved" (:status (tool-approvals/get-request store (:id approval)))))
      (is (= [{:callback-id "callback-1" :body {:text "Running."}}] @answers))
      (is (= [{:chat-id 100 :message-id 55 :reply-markup nil}] @edits))
      (is (= [{:chat-id 100 :text "shell status: ok"}
              {:chat-id 100 :text "agent continued"}]
             @sent))
      (is (str/includes? (:content (last @continuation-messages)) "stdout:\nok"))
      (is (= "telegram-session" @continuation-session-id))
      (is (= {:telegram-chat-id 100} @continuation-context))
      (is (not (some #(str/includes? (:text %) "Tool executed") @sent)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-approval-continuation-sends-nested-approval-card
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        registry (tool-service/create-tool-registry
                  {:cfg {:shell {:roots ["."]
                                 :working-dir "."
                                 :default-decision :ask
                                 :rules []}}
                   :event-sink (fn [_] nil)
                   :store store})
        sent (atom [])
        html-sent (atom [])
        answers (atom [])
        edits (atom [])
        first-approval (tool-approvals/create-request!
                        store
                        {:tool-name :shell
                         :input {:argv ["printf" "ok"]}
                         :requested-by "telegram-session"
                         :reason "first"})
        nested-approval (tool-approvals/create-request!
                         store
                         {:tool-name :shell
                          :input {:argv ["pwd"]}
                          :requested-by "telegram-session"
                          :reason "nested"})
        system {:store store
                :tool-registry registry
                :config {:tools {:yolo? false}}
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-html-message-with-reply-markup-fn
              (fn [chat-id text reply-markup]
                (swap! html-sent conj {:chat-id chat-id
                                       :text text
                                       :reply-markup reply-markup}))
              :answer-callback-query-fn (fn [callback-id body]
                                          (swap! answers conj {:callback-id callback-id :body body}))
              :edit-message-reply-markup-fn (fn [chat-id message-id reply-markup]
                                              (swap! edits conj {:chat-id chat-id
                                                                 :message-id message-id
                                                                 :reply-markup reply-markup}))
              :send-chat-action-fn (fn [_ _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id]} emit!]
                                 (let [content (str "Tool approval required: shell approval_id="
                                                    (:id nested-approval))]
                                   (emit! :tool-execution-update {:kind :approval-required
                                                                  :approvals [nested-approval]})
                                   (emit! :message-end {:content content
                                                        :final? true
                                                        :stop-reason :approval-required})
                                   {:content content
                                    :session-id session-id
                                    :stop-reason :approval-required
                                    :approvals [nested-approval]})))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (callback-update-for 1 100 7 55 (str "ta:run:" (:id first-approval)))))))
      (is (= [{:chat-id 100 :text "shell status: ok"}] @sent))
      (is (= 1 (count @html-sent)))
      (is (str/includes? (:text (first @html-sent)) "Tool approval required"))
      (is (str/includes? (:text (first @html-sent)) (:id nested-approval)))
      (is (= [[{:text "Approve & run"
                :callback_data (str "ta:run:" (:id nested-approval))}
               {:text "Deny"
                :callback_data (str "ta:deny:" (:id nested-approval))}]]
             (get-in (first @html-sent) [:reply-markup :inline_keyboard])))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-approval-callback-denies-tool
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        answers (atom [])
        edits (atom [])
        approval (tool-approvals/create-request!
                  store
                  {:tool-name :shell
                   :input {:argv ["printf" "ok"]}
                   :requested-by "telegram-session"
                   :reason "test"})
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :answer-callback-query-fn (fn [callback-id body]
                                          (swap! answers conj {:callback-id callback-id :body body}))
              :edit-message-reply-markup-fn (fn [chat-id message-id reply-markup]
                                              (swap! edits conj {:chat-id chat-id
                                                                 :message-id message-id
                                                                 :reply-markup reply-markup}))}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (callback-update-for 1 100 7 55 (str "ta:deny:" (:id approval))))))
      (is (= "denied" (:status (tool-approvals/get-request store (:id approval)))))
      (is (= [{:callback-id "callback-1" :body {:text "Denied."}}] @answers))
      (is (= [{:chat-id 100 :message-id 55 :reply-markup nil}] @edits))
      (is (= [{:chat-id 100 :text "Tool denied."}] @sent))
      ;; Double-tapping Deny must answer gracefully, not raise a decision conflict.
      (is (= :processed
             (telegram/process-update! system config opts
                                       (callback-update-for 2 100 7 55 (str "ta:deny:" (:id approval))))))
      (is (= {:text "Already denied."} (:body (second @answers))))
      (is (= 1 (count @sent)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

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

(deftest telegram-streams-private-chat-replies
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        drafts (atom [])
        deltas ["hello" " " "world" "!"]
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [chat-id draft-id text]
                                       (swap! drafts conj {:chat-id chat-id
                                                           :draft-id draft-id
                                                           :text text}))
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id]} emit!]
                                 (doseq [d deltas]
                                   (Thread/sleep 700) ;; force flush throttle
                                   (emit! :message-update {:delta d}))
                                 (emit! :message-end {:content (apply str deltas)
                                                      :final? true})
                                 {:content (apply str deltas)
                                  :session-id session-id}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (= [{:chat-id 100 :text "hello world!"}] @sent))
      (is (pos? (count @drafts)))
      (is (every? #(= 100 (:chat-id %)) @drafts))
      (let [draft-ids (set (map :draft-id @drafts))]
        (is (= 1 (count draft-ids))
            "all draft chunks should share one draft_id"))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-sends-thinking-as-expandable-html-quote
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        html-sent (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [_ _ _] nil)
              :send-html-message-fn (fn [chat-id text]
                                      (swap! html-sent conj {:chat-id chat-id :text text}))
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id]} emit!]
                                 (emit! :message-update {:thinking-delta "think <x>"})
                                 (emit! :message-update {:delta "answer"})
                                 (emit! :message-end {:content "answer" :final? true})
                                 {:content "answer"
                                  :session-id session-id}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (= [{:chat-id 100 :text "answer"}] @sent))
      (is (= [{:chat-id 100
               :text "<blockquote expandable>thinking\n\nthink &lt;x&gt;</blockquote>"}]
             @html-sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-records-draft-send-failures
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [_ _ _]
                                       (throw (ex-info "draft failed" {:type :draft-down})))
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:delta "hello"})
                                 (emit! :message-end {:content "hello" :final? true})
                                 {:content "hello"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (= [{:chat-id 100 :text "hello"}] @sent))
      (let [failure (first (filter #(= :telegram.operation.failed (:event-type %)) @events))]
        (is (= :draft-update (get-in failure [:payload :operation])))
        (is (= "draft failed" (get-in failure [:payload :message])))
        (is (= :draft-down (get-in failure [:payload :type]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-records-typing-failures
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        typing-failure (promise)
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [event]
                              (when (= :telegram.operation.failed (:event-type event))
                                (deliver typing-failure event)))}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-chat-action-fn (fn [_ _]
                                     (throw (ex-info "typing failed" {:type :typing-down})))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ _emit!]
                                 (is (some? (deref typing-failure 1000 nil)))
                                 {:content "pong"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (let [failure (deref typing-failure 1000 nil)]
        (is (= :typing (get-in failure [:payload :operation])))
        (is (= "typing failed" (get-in failure [:payload :message])))
        (is (= :typing-down (get-in failure [:payload :type]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-finalizes-streamed-draft-before-tool-call-summary
  ;; Regression: Telegram drafts are ephemeral — sending any regular message
  ;; clears the in-flight draft. Streamed text emitted before a mid-turn tool
  ;; call must be promoted to a real sendMessage before the tool-call summary
  ;; is sent, otherwise it's lost from the chat history.
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        drafts (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)
                :config {:tools {:display {:telegram {:show-tool-calls? true
                                                      :preview-chars 1600
                                                      :args-preview-chars 1200
                                                      :per-tool {}}}}}}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        step1-deltas ["cherry " "paragraph"]
        step2-deltas ["final " "answer"]
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [chat-id draft-id text]
                                       (swap! drafts conj {:chat-id chat-id
                                                           :draft-id draft-id
                                                           :text text}))
              :send-chat-action-fn (fn [& _] nil)}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [session-id]} emit!]
                                 (doseq [d step1-deltas]
                                   (Thread/sleep 700)
                                   (emit! :message-update {:delta d}))
                                 (emit! :tool-execution-end
                                        {:tool-call {:id "c1" :function {:name "list_dir"}}
                                         :receipt {:status :completed
                                                   :tool-name "list_dir"
                                                   :input {:path "./obsidian"}
                                                   :result {:files ["a.md"]}}})
                                 (emit! :message-end {:content (apply str step1-deltas)
                                                      :tool-turn? true
                                                      :role "assistant"})
                                 (doseq [d step2-deltas]
                                   (Thread/sleep 700)
                                   (emit! :message-update {:delta d}))
                                 (emit! :message-end {:content (apply str step2-deltas)
                                                      :final? true})
                                 {:content (apply str step2-deltas)
                                  :session-id session-id}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (let [texts (mapv :text @sent)]
        (is (some #(= "cherry paragraph" %) texts)
            "streamed pre-tool-call text must be promoted to a real message")
        (is (some #(= "🔧 list_dir status: completed path: ./obsidian" %) texts)
            "tool-call summary must include tool name and status")
        (is (some #(= "final answer" %) texts)
            "final answer must be sent as a real message")
        (is (= "final answer" (last texts))
            "final answer is the last message sent")
        (is (= 1 (count (filter #(= "cherry paragraph" %) texts)))
            "tool-turn message-end must not replay already streamed text into the next draft"))
      (let [draft-ids (set (map :draft-id @drafts))]
        (is (>= (count draft-ids) 2)
            "draft id should rotate after finalize so step 2 streams onto a fresh draft slot"))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-sends-typing-while-chat-running
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        actions (atom [])
        typing-seen (promise)
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-chat-action-fn (fn [chat-id action]
                                     (swap! actions conj {:chat-id chat-id
                                                          :action action})
                                     (deliver typing-seen true))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ _emit!]
                                 (is (true? (deref typing-seen 1000 false)))
                                 {:content "pong"}))]
        (is (= :processed
               (telegram/process-update! system config opts (update-for 1 100 7 "hi")))))
      (is (some #(= {:chat-id 100 :action "typing"} %) @actions))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-send-chat-action-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-chat-action! "token" 100 "typing")
      (is (= [{:token "token"
               :method "sendChatAction"
               :body {:chat_id 100
                      :action "typing"}}]
             @calls)))))

(deftest telegram-send-message-disables-link-preview
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-message! "token" 100 "https://example.com")
      (is (= [{:token "token"
               :method "sendMessage"
               :body {:chat_id 100
                      :text "https://example\\.com"
                      :parse_mode "MarkdownV2"
                      :link_preview_options {:is_disabled true}}}]
             @calls)))))

(deftest telegram-send-html-message-uses-html-parse-mode
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-html-message! "token" 100 "<blockquote expandable>x</blockquote>")
      (is (= [{:token "token"
               :method "sendMessage"
               :body {:chat_id 100
                      :text "<blockquote expandable>x</blockquote>"
                      :parse_mode "HTML"
                      :link_preview_options {:is_disabled true}}}]
             @calls)))))

(deftest telegram-photo-command-sends-photo
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        photos (atom [])
        system {:store store :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-photo-fn (fn [chat-id url caption]
                               (swap! photos conj {:chat-id chat-id
                                                   :url url
                                                   :caption caption}))}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 1 100 7 "/photo https://example.com/a.png nice cat"))))
      (is (= [{:chat-id 100 :url "https://example.com/a.png" :caption "nice cat"}] @photos))
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 2 100 7 "/photo"))))
      (is (= "Usage: /photo <url> [caption]" (:text (last @sent))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-photo-message-downloads-and-sends-rich-content-to-chat-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        calls (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)
              :get-file-fn (fn [token file-id]
                             (swap! calls conj {:op :get-file
                                                :token token
                                                :file-id file-id})
                             {:file_path "photos/big.jpg"
                              :file_size 11})
              :download-file-fn (fn [token file-path]
                                  (swap! calls conj {:op :download
                                                     :token token
                                                     :file-path file-path})
                                  (.getBytes "image-bytes" "UTF-8"))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} emit!]
                                 (swap! calls conj {:op :chat
                                                    :messages messages})
                                 (emit! :message-end {:content "ok" :final? true})
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (photo-update-for 1 100 7 "what is this?")))))
      (let [content (->> @calls (filter #(= :chat (:op %))) first :messages first :content)]
        (is (= [{:type :text :text "what is this?"}
                {:type :image
                 :source {:type :base64
                          :media-type "image/jpeg"
                          :value "aW1hZ2UtYnl0ZXM="}
                 :alt "Telegram photo"
                 :filename "big.jpg"}]
               content)))
      (is (= [{:op :get-file :token "token" :file-id "big"}
              {:op :download :token "token" :file-path "photos/big.jpg"}]
             (take 2 @calls)))
      (is (= [{:chat-id 100 :text "ok"}] @sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-voice-message-becomes-audio-content-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        seen (atom nil)
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [_ _] nil)
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)
              :get-file-fn (fn [_ _] {:file_path "voice/file.ogg"
                                      :file_size 12})
              :download-file-fn (fn [_ _] (.getBytes "ogg" "UTF-8"))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} _emit!]
                                 (reset! seen (-> messages first :content))
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (voice-update-for 2 100 7)))))
      (is (= [{:type :text :text "Analyze attached audio."}
              {:type :audio
               :source {:type :base64
                        :media-type "audio/ogg"
                        :value "b2dn"}
               :alt "Telegram voice message"
               :filename "file.ogg"}]
             @seen))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-file-command-sends-document
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        docs (atom [])
        system {:store store :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-document-fn (fn [chat-id url caption]
                                  (swap! docs conj {:chat-id chat-id
                                                    :url url
                                                    :caption caption}))}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 1 100 7 "/file https://example.com/a.pdf"))))
      (is (= [{:chat-id 100 :url "https://example.com/a.pdf" :caption nil}] @docs))
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

(deftest draft-id-stays-positive
  (let [next-draft-id @#'telegram-streaming/next-draft-id
        rotate-draft-id @#'telegram-streaming/rotate-draft-id
        max-id @#'telegram-streaming/max-draft-id]
    (testing "generated ids are in [1, max]"
      (let [id (next-draft-id)]
        (is (pos? id))
        (is (<= id max-id))))
    (testing "rotation never yields 0/negative/over-max and wraps at the ceiling"
      (is (= 1 (rotate-draft-id max-id)))
      (let [ids (take 5000 (iterate rotate-draft-id (- max-id 2)))]
        (is (every? pos? ids))
        (is (every? #(<= % max-id) ids))
        (is (some #(= 1 %) ids) "wraps back to 1")))))

;; --- rich messages (Bot API 10.1) --------------------------------------------

(defn- rich-test-config []
  {:bot-token "token"
   :rich-messages? true
   :allowlist {:allow-all? true}})

(defn- recording-opts
  [{:keys [sent html-sent drafts rich-sent rich-drafts]}
   & {:keys [rich-draft-fn rich-send-fn]}]
  {:send-message-fn (fn [chat-id text]
                      (swap! sent conj {:chat-id chat-id :text text}))
   :send-html-message-fn (fn [chat-id text]
                           (swap! html-sent conj {:chat-id chat-id :text text}))
   :send-message-draft-fn (fn [chat-id draft-id text]
                            (swap! drafts conj {:chat-id chat-id
                                                :draft-id draft-id
                                                :text text}))
   :send-rich-message-fn (or rich-send-fn
                             (fn [chat-id markdown]
                               (swap! rich-sent conj {:chat-id chat-id
                                                      :markdown markdown})))
   :send-rich-message-draft-fn (or rich-draft-fn
                                   (fn [chat-id draft-id markdown]
                                     (swap! rich-drafts conj {:chat-id chat-id
                                                              :draft-id draft-id
                                                              :markdown markdown})))
   :send-chat-action-fn (fn [& _] nil)})

(deftest telegram-rich-streams-thinking-and-finalizes-with-details
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
                               (fn [{:keys [session-id]} emit!]
                                 (emit! :message-update {:thinking-delta "pondering"})
                                 (Thread/sleep 1300) ;; pass flush throttle
                                 (emit! :message-update {:delta "**answer**"})
                                 (emit! :message-end {:content "**answer**" :final? true})
                                 {:content "**answer**"
                                  :session-id session-id}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (testing "drafts stream through sendRichMessageDraft with live thinking"
        (is (pos? (count @(:rich-drafts recorders))))
        (is (some #(str/includes? (:markdown %) "<tg-thinking>")
                  @(:rich-drafts recorders)))
        (is (empty? @(:drafts recorders))))
      (testing "final lands via sendRichMessage with collapsed thinking"
        (is (= 1 (count @(:rich-sent recorders))))
        (let [{:keys [chat-id markdown]} (first @(:rich-sent recorders))]
          (is (= 100 chat-id))
          (is (str/includes? markdown "<details><summary>thinking</summary>"))
          (is (str/includes? markdown "pondering"))
          (is (str/includes? markdown "**answer**"))
          (is (not (str/includes? markdown "<tg-thinking")))))
      (testing "legacy senders untouched"
        (is (empty? @(:sent recorders)))
        (is (empty? @(:html-sent recorders))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

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

(deftest telegram-inbound-rich-message-converts-to-markdown
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        seen-content (atom nil)
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (recording-opts recorders)
        update {:update_id 1
                :message {:message_id 1
                          :from {:id 7}
                          :chat {:id 100 :type "private" :first_name "Test"}
                          :rich_message {:blocks [{:type "heading" :text "Hi" :size 1}
                                                  {:type "paragraph" :text "body"}]}}}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} emit!]
                                 (reset! seen-content (-> messages first :content))
                                 (emit! :message-end {:content "ok" :final? true})
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts update))))
      (is (= "# Hi\n\nbody" @seen-content))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-send-rich-message-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-rich-message! "token" 100 "# Hello")
      (telegram-api/send-rich-message! "token" 100 "pick" {:reply-markup {:inline_keyboard []}})
      (telegram-api/send-rich-message-draft! "token" 100 42 "partial"))
    (is (= [{:token "token"
             :method "sendRichMessage"
             :body {:chat_id 100
                    :rich_message {:markdown "# Hello"}
                    :link_preview_options {:is_disabled true}}}
            {:token "token"
             :method "sendRichMessage"
             :body {:chat_id 100
                    :rich_message {:markdown "pick"}
                    :reply_markup {:inline_keyboard []}
                    :link_preview_options {:is_disabled true}}}
            {:token "token"
             :method "sendRichMessageDraft"
             :body {:chat_id 100
                    :draft_id 42
                    :rich_message {:markdown "partial"}
                    :link_preview_options {:is_disabled true}}}]
           @calls))))

(deftest telegram-rich-draft-failure-during-thinking-shows-placeholder
  ;; Regression: a rich-draft rejection mid-thinking used to leave the chat
  ;; blank (legacy drafts carry no thinking) until the old draft's TTL wiped
  ;; it. The downgrade path now sends an empty legacy draft, which Telegram
  ;; renders as a native "Thinking..." placeholder.
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (recording-opts recorders
                             :rich-draft-fn (fn [_ _ _]
                                              (throw (ex-info "rich down"
                                                              {:type :rich-down}))))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:thinking-delta "long pondering"})
                                 (Thread/sleep 1300)
                                 (emit! :message-update {:thinking-delta " continues"})
                                 (Thread/sleep 1300)
                                 (emit! :message-update {:delta "answer"})
                                 (emit! :message-end {:content "answer" :final? true})
                                 {:content "answer"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (testing "thinking-only downgrade keeps a placeholder draft alive"
        (is (some #(= "" (:text %)) @(:drafts recorders))
            "empty draft text renders Telegram's native Thinking placeholder"))
      (testing "final reply still lands via legacy"
        (is (= [{:chat-id 100 :text "answer"}] @(:sent recorders)))
        (is (= [{:chat-id 100
                 :text "<blockquote expandable>thinking\n\nlong pondering continues</blockquote>"}]
               @(:html-sent recorders))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

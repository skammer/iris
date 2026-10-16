(ns agent.telegram.adapter-streaming-test
  (:require
   [agent.telegram.test-support :refer [chat-stub temp-db-path update-for]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [agent.telegram.streaming :as telegram-streaming]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

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
        (is (some #(= (str "Completed supporting work\n\n"
                           "🔧 list_dir status: completed path: ./obsidian")
                      %)
                  texts)
            "tool-call summary must be aggregated")
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

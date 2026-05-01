(ns agent.telegram-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-telegram-" ".db")))

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
              :chat-fn (fn [_ {:keys [session-id messages]}]
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
                  :async-chat? false
	                  :chat-fn (fn [_ _]
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

(deftest telegram-streams-private-chat-replies
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        drafts (atom [])
        deltas ["hello" " " "world" "!"]
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [chat-id draft-id text]
                                       (swap! drafts conj {:chat-id chat-id
                                                           :draft-id draft-id
                                                           :text text}))
              :chat-fn (fn [_ {:keys [session-id on-delta]}]
                                (doseq [d deltas]
                                  (Thread/sleep 700) ;; force flush throttle
                                  (on-delta d))
                                {:content (apply str deltas)
                                 :session-id session-id
                                 :stream? true})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= [{:chat-id 100 :text "hello world!"}] @sent))
      (is (pos? (count @drafts)))
      (is (every? #(= 100 (:chat-id %)) @drafts))
      (let [draft-ids (set (map :draft-id @drafts))]
        (is (= 1 (count draft-ids))
            "all draft chunks should share one draft_id"))
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
              :chat-fn (fn [_ {:keys [session-id on-delta on-tool-call]}]
                         (doseq [d step1-deltas]
                           (Thread/sleep 700)
                           (on-delta d))
                         (on-tool-call {:tool-call {:id "c1" :function {:name "list_dir"}}
                                        :receipt {:status :completed
                                                  :tool-name "list_dir"
                                                  :input {:path "./obsidian"}
                                                  :result {:files ["a.md"]}}})
                         (doseq [d step2-deltas]
                           (Thread/sleep 700)
                           (on-delta d))
                         {:content (apply str step2-deltas)
                          :session-id session-id
                          :stream? true})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (let [texts (mapv :text @sent)]
        (is (some #(= "cherry paragraph" %) texts)
            "streamed pre-tool-call text must be promoted to a real message")
        (is (some #(= "final answer" %) texts)
            "final answer must be sent as a real message")
        (is (= "final answer" (last texts))
            "final answer is the last message sent"))
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
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-chat-action-fn (fn [chat-id action]
                                     (swap! actions conj {:chat-id chat-id
                                                          :action action})
                                     (deliver typing-seen true))
              :chat-fn (fn [_ _]
                         (is (true? (deref typing-seen 1000 false)))
                         {:content "pong"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (some #(= {:chat-id 100 :action "typing"} %) @actions))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-send-chat-action-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram/send-chat-action! "token" 100 "typing")
      (is (= [{:token "token"
               :method "sendChatAction"
               :body {:chat_id 100
                      :action "typing"}}]
             @calls)))))

(deftest telegram-send-html-message-uses-html-parse-mode
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram/send-html-message! "token" 100 "<blockquote expandable>x</blockquote>")
      (is (= [{:token "token"
               :method "sendMessage"
               :body {:chat_id 100
                      :text "<blockquote expandable>x</blockquote>"
                      :parse_mode "HTML"}}]
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

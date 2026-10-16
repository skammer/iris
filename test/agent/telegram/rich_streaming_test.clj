(ns agent.telegram.rich-streaming-test
  (:require
   [agent.telegram.test-support :refer [chat-stub recording-opts rich-test-config temp-db-path update-for]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest telegram-finalizes-with-full-content-after-incomplete-deltas
  (doseq [mode [:legacy :rich :downgraded]]
    (testing (name mode)
      (let [path (temp-db-path)
            store (sqlite/create-store {:path path :evict-on-close? true})
            recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                       :rich-sent (atom []) :rich-drafts (atom [])}
            system {:store store
                    :event-bus (system-events/create-event-bus)
                    :event-sink (fn [_] nil)}
            prefix "Проверенные и стабильные вариан"
            final (str prefix "ты - Qwen3, GigaEmbeddings, BGE-M3, e5.")
            config (assoc (rich-test-config) :rich-messages? (not= :legacy mode))
            opts (if (= :downgraded mode)
                   (recording-opts recorders
                                   :rich-draft-fn (fn [& _]
                                                    (throw (ex-info "rich draft unavailable" {}))))
                   (recording-opts recorders))]
        (try
          (with-redefs [chat/run! (chat-stub
                                   (fn [_ emit!]
                                     (emit! :message-update {:delta prefix})
                                     (emit! :message-end {:content final :final? true})
                                     {:content final}))]
            (is (= :processed
                   (telegram/process-update! system config opts
                                             (update-for 1 100 7 "hi")))))
          (let [texts (concat (map :text @(:sent recorders))
                              (map :markdown @(:rich-sent recorders)))]
            (is (= [final] (vec texts))
                "send authoritative final content once, replacing the incomplete preview"))
          (finally
            (sqlite/close-store! store)
            (io/delete-file path true)))))))

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

(deftest telegram-rich-final-clears-stale-reply-keyboard
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        rich-sent (atom [])
        rich-with-markup (atom [])
        expiry (future (Thread/sleep 60000))
        reply-keyboards (atom {100 {:id "old" :future expiry}})
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)
                :telegram-reply-keyboards reply-keyboards}
        opts (assoc (recording-opts
                     {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                      :rich-sent rich-sent :rich-drafts (atom [])})
                    :send-rich-message-with-reply-markup-fn
                    (fn [chat-id markdown reply-markup]
                      (swap! rich-with-markup conj
                             {:chat-id chat-id
                              :markdown markdown
                              :reply-markup reply-markup})))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:delta "fresh answer"})
                                 (emit! :message-end {:content "fresh answer" :final? true})
                                 {:content "fresh answer"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "new context")))))
      (is (= [{:chat-id 100
               :markdown "fresh answer"
               :reply-markup {:remove_keyboard true}}]
             @rich-with-markup))
      (is (nil? (get @reply-keyboards 100)))
      (is (empty? @rich-sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-final-keeps-keyboard-from-current-ask
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        rich-sent (atom [])
        rich-with-markup (atom [])
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (assoc (recording-opts
                     {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                      :rich-sent rich-sent :rich-drafts (atom [])})
                    :send-rich-message-with-reply-markup-fn
                    (fn [& args] (swap! rich-with-markup conj args)))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :tool-execution-end
                                        {:receipt {:tool-name "telegram_ask"
                                                   :status :ok}})
                                 (emit! :message-update {:delta "waiting"})
                                 (emit! :message-end {:content "waiting" :final? true})
                                 {:content "waiting"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "ask me")))))
      (is (empty? @rich-with-markup))
      (is (some #(= "waiting" (:markdown %)) @rich-sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-aggregates-consecutive-tool-calls
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (assoc (recording-opts recorders)
                    :tool-summary-title-fn
                    (fn [receipts]
                      (is (= 2 (count receipts)))
                      "Искал информацию по Ясной Поляне"))]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :tool-execution-start
                                        {:tool-name "homeassistant"
                                         :tool-call-id "c1"})
                                 (emit! :tool-execution-end
                                        {:receipt {:tool-name "homeassistant"
                                                   :tool-call-id "c1"
                                                   :status :ok
                                                   :input {:action "get_state"
                                                           :entity_id "sensor.one"}}})
                                 (emit! :tool-execution-start
                                        {:tool-name "shell"
                                         :tool-call-id "c2"})
                                 (Thread/sleep 1100)
                                 (emit! :tool-execution-end
                                        {:receipt {:tool-name "shell"
                                                   :tool-call-id "c2"
                                                   :status :error
                                                   :reason "HTTP 404"
                                                   :input {:argv ["echo" "ok"]}}})
                                 (emit! :message-update {:delta "done"})
                                 (emit! :message-end {:content "done" :final? true})
                                 {:content "done"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (is (some #(= "<tg-thinking>Calling 1 tool... homeassistant</tg-thinking>"
                    (:markdown %))
                @(:rich-drafts recorders)))
      (is (some #(= "<tg-thinking>Calling 2 tools... shell</tg-thinking>"
                    (:markdown %))
                @(:rich-drafts recorders)))
      (is (= 2 (count @(:rich-sent recorders))))
      (let [summary (:markdown (first @(:rich-sent recorders)))]
        (is (str/starts-with? summary
                              "<details><summary>Искал информацию по Ясной Поляне</summary>"))
        (is (str/includes? summary "<blockquote>"))
        (is (str/includes? summary "<b>1. homeassistant</b> · ok"))
        (is (str/includes? summary "<b>2. shell</b> · error"))
        (is (str/includes? summary "HTTP 404"))
        (is (= 2 (count (re-seq #"<pre>" summary)))))
      (is (= "done" (:markdown (last @(:rich-sent recorders))))
          "final answer follows the aggregated tool summary")
      (is (empty? @(:sent recorders)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-reuses-live-draft-across-assistant-and-tool-status
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
                                 (emit! :message-update {:delta "before tool"})
                                 (Thread/sleep 1100)
                                 (emit! :tool-execution-start
                                        {:tool-name "shell" :tool-call-id "c1"})
                                 (Thread/sleep 1100)
                                 (emit! :tool-execution-end
                                        {:receipt {:tool-name "shell"
                                                   :tool-call-id "c1"
                                                   :status :ok
                                                   :input {:argv ["true"]}}})
                                 (emit! :message-end
                                        {:role "assistant"
                                         :tool-turn? true
                                         :content "before tool"})
                                 (emit! :message-update {:delta "after tool"})
                                 (emit! :message-end {:content "after tool" :final? true})
                                 {:content "after tool"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "hi")))))
      (let [drafts @(:rich-drafts recorders)]
        (is (some #(= "before tool" (:markdown %)) drafts))
        (is (some #(str/includes? (:markdown %) "Calling 1 tool... shell") drafts))
        (is (= 1 (count (set (map :draft-id drafts))))
            "one draft id lets tool status replace the persisted assistant preview"))
      (is (= 1 (count (filter #(= "before tool" (:markdown %))
                             @(:rich-sent recorders))))
          "assistant segment is persisted exactly once")
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-rich-empty-partial-does-not-downgrade-ha-report
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        rich-draft-attempts (atom [])
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink #(swap! events conj %)}
        opts (recording-opts
              recorders
              :rich-draft-fn
              (fn [chat-id draft-id markdown]
                (swap! rich-draft-attempts conj markdown)
                (if (= "---" markdown)
                  (throw (ex-info "Telegram API request failed"
                                  {:type :telegram-api-error
                                   :body {:description
                                          "Bad Request: RICH_MESSAGE_EMPTY"}}))
                  (swap! (:rich-drafts recorders)
                         conj
                         {:chat-id chat-id
                          :draft-id draft-id
                          :markdown markdown}))))
        report (str "---\n\n## 🌿 Растения\n\n"
                    "| Растение | Влажность |\n"
                    "|---|---|\n"
                    "| Драцена | 82% |")]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [_ emit!]
                                 (emit! :message-update {:delta "---"})
                                 (Thread/sleep 1100)
                                 (emit! :message-update
                                        {:delta (subs report 3)})
                                 (emit! :message-end {:content report :final? true})
                                 {:content report}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts
                                         (update-for 1 100 7 "/ha-report")))))
      (is (= "---" (first @rich-draft-attempts)))
      (is (some #(str/includes? % "| Растение | Влажность |")
                @rich-draft-attempts))
      (is (= [{:chat-id 100 :markdown report}] @(:rich-sent recorders)))
      (is (empty? @(:sent recorders))
          "transient empty RichBlock prefix must not force legacy MarkdownV2")
      (is (empty? (filter #(= :telegram.operation.failed (:event-type %)) @events)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

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

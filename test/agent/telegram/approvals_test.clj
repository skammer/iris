(ns agent.telegram.approvals-test
  (:require
   [agent.telegram.test-support :refer [callback-update-for chat-stub temp-db-path]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [agent.telegram.approvals :as telegram-approvals]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.service :as tool-service]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is ]]))

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

(deftest telegram-rich-approval-card-uses-native-buttons
  (let [approval {:id "app-1"
                  :tool-name "shell"
                  :input {}}
        pending (telegram-approvals/rich-card-html approval)
        completed (telegram-approvals/rich-card-html approval :completed)]
    (is (str/includes? pending "<tg-button-row align=\"left\">"))
    (is (str/includes? pending "type=\"callback_data\" style=\"success\""))
    (is (str/includes? pending "data=\"ta:run:app-1\""))
    (is (str/includes? pending "style=\"danger\""))
    (is (str/includes? completed "type=\"disabled\" style=\"success\""))))

(deftest telegram-rich-approval-callback-disables-native-buttons
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        edits (atom [])
        approval (tool-approvals/create-request!
                  store
                  {:tool-name :shell
                   :input {:argv ["pwd"]}
                   :requested-by "telegram-session"})
        callback (assoc-in
                  (callback-update-for 1 100 7 55
                                       (str "ta:deny:" (:id approval)))
                  [:callback_query :message :rich_message]
                  {:blocks []})
        system {:store store :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :rich-messages? true
                :allowlist {:allow-all? true}}
        opts {:edit-rich-message-fn
              (fn [chat-id message-id markdown]
                (swap! edits conj {:chat-id chat-id
                                   :message-id message-id
                                   :markdown markdown}))
              :answer-callback-query-fn (fn [& _] nil)
              :send-message-fn (fn [& _] nil)}]
    (try
      (is (= :processed (telegram/process-update! system config opts callback)))
      (is (= 1 (count @edits)))
      (is (str/includes? (:markdown (first @edits))
                         "type=\"disabled\" style=\"danger\""))
      (is (str/includes? (:markdown (first @edits)) ">Denied</tg-button>"))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

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

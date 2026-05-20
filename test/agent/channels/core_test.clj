(ns agent.channels.core-test
  (:require
   [agent.channels.core :as channels]
   [clojure.test :refer :all]))

(deftest registry-registers-adapters-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :telegram
                                "Telegram"
                                :polling
                                #{:supports-outbound :supports-streaming})
                  :health-fn (fn [] {:healthy true
                                     :enabled false})})
        registry (-> (channels/create-registry)
                     (channels/register-adapter adapter))
        listed (channels/list-adapters registry)
        health (channels/registry-health registry)]
    (is (= [:telegram] (mapv :name listed)))
    (is (= 1 (:count health)))
    (is (true? (:healthy health)))))

(deftest optional-operations-default-unsupported-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :basic
                                "Basic"
                                :none
                                #{})
                  :health-fn (fn [] {:healthy true})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported"
                          (channels/send-draft! adapter
                                                (channels/create-send-message "draft" "user-1"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported"
                          (channels/send-typing! adapter "user-1")))))

(deftest capability-validation-rejects-missing-optional-protocol-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :bad
                                "Bad"
                                :none
                                #{:supports-draft-updates})
                  :health-fn (fn [] {:healthy true})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"capability validation"
                          (channels/register-adapter (channels/create-registry) adapter)))))

(deftest channel-message-shapes-include-thread-attachments-and-metadata-test
  (let [send-message (channels/create-send-message
                      "hello"
                      "chat-1"
                      :thread-id "thread-1"
                      :subject "subject"
                      :attachments [{:type :file :id "f1"}]
                      :cancellation-token :token
                      :metadata {:k "v"})
        inbound-message (channels/create-inbound-message
                         "hi"
                         "user-1"
                         :reply-target "chat-1"
                         :channel :telegram
                         :thread-scope "chat-1"
                         :attachments [{:type :image :id "i1"}])]
    (is (= "chat-1" (:recipient send-message)))
    (is (= "thread-1" (:thread-id send-message)))
    (is (= :token (:cancellation-token send-message)))
    (is (= [{:type :file :id "f1"}] (:attachments send-message)))
    (is (= "user-1" (:sender inbound-message)))
    (is (= "chat-1" (:reply-target inbound-message)))
    (is (= :telegram (:channel inbound-message)))
    (is (= "chat-1" (:thread-scope inbound-message)))))

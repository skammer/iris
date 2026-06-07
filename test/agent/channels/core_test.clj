(ns agent.channels.core-test
  (:require
   [agent.channels.core :as channels]
   [clojure.test :refer [deftest is]]))

(defn thrown-message?
  [exception-class message-pattern f]
  (try
    (f)
    false
    (catch Throwable e
      (and (instance? exception-class e)
           (boolean (re-find message-pattern (.getMessage e)))))))

(deftest registry-registers-adapters-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :telegram
                                "Telegram"
                                :polling
                                #{})
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
    (is (thrown-message? clojure.lang.ExceptionInfo
                         #"unsupported"
                         #(channels/send-typing! adapter "user-1")))))

(deftest capability-validation-rejects-missing-optional-protocol-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :bad
                                "Bad"
                                :none
                                #{:supports-typing})
                  :health-fn (fn [] {:healthy true})})]
    (is (thrown-message? clojure.lang.ExceptionInfo
                         #"capability validation"
                         #(channels/register-adapter (channels/create-registry) adapter)))))

(deftest outbound-capability-requires-custom-adapter-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :bad
                                "Bad"
                                :none
                                #{:supports-outbound})
                  :health-fn (fn [] {:healthy true})})]
    (is (thrown-message? clojure.lang.ExceptionInfo
                         #"capability validation"
                         #(channels/register-adapter (channels/create-registry) adapter)))))

(deftest channel-message-shapes-include-thread-attachments-and-metadata-test
  (let [send-message (channels/create-send-message
                      "hello"
                      "chat-1"
                      :thread-id "thread-1"
                      :subject "subject"
                      :attachments [{:type :file :id "f1"}]
                      :cancellation-token :token
                      :metadata {:k "v"})]
    (is (= "chat-1" (:recipient send-message)))
    (is (= "thread-1" (:thread-id send-message)))
    (is (= :token (:cancellation-token send-message)))
    (is (= [{:type :file :id "f1"}] (:attachments send-message)))))

(deftest send-channel-message-passes-normalized-message-test
  (let [sent (atom nil)
        adapter (reify
                  channels/IChannelAdapter
                  (describe-adapter [_]
                    (channels/create-adapter-description
                     :custom
                     "Custom"
                     :none
                     #{:supports-outbound}))
                  (adapter-health-check [_] {:healthy true})
                  (start-adapter! [this] this)
                  (stop-adapter! [this] this)
                  (send-adapter-message! [_ destination message]
                    (reset! sent {:destination destination
                                  :message message})
                    :sent))
        registry (channels/register-adapter (channels/create-registry) adapter)]
    (is (= :sent
           (channels/send-channel-message!
            (get-in registry [:adapters :custom])
            {:content "hello"
             :recipient "chat-1"
             :attachments nil
             :metadata nil})))
    (is (= {:destination "chat-1"
            :message {:type :channel/send-message
                      :content "hello"
                      :recipient "chat-1"
                      :attachments []
                      :metadata {}}}
           @sent))))

(deftest send-channel-message-validates-content-test
  (is (thrown-message? clojure.lang.ExceptionInfo
                       #"non-blank"
                       #(channels/create-send-message "" "chat-1"))))

(deftest register-adapter-rejects-bad-names-test
  (let [registry (channels/create-registry)
        unnamed (channels/create-adapter
                 {:description (channels/create-adapter-description nil "Missing" :none #{})})
        named (channels/create-adapter
               {:description (channels/create-adapter-description :dup "Dup" :none #{})})]
    (is (thrown-message? clojure.lang.ExceptionInfo
                         #"name is required"
                         #(channels/register-adapter registry unnamed)))
    (is (thrown-message? clojure.lang.ExceptionInfo
                         #"already registered"
                         #(-> registry
                              (channels/register-adapter named)
                              (channels/register-adapter named))))))

(deftest registry-health-isolates-adapter-failures-test
  (let [adapter (reify
                  channels/IChannelAdapter
                  (describe-adapter [_]
                    (channels/create-adapter-description :bad-health "Bad Health" :none #{}))
                  (adapter-health-check [_]
                    (throw (ex-info "boom" {:type :boom})))
                  (start-adapter! [this] this)
                  (stop-adapter! [this] this)
                  (send-adapter-message! [_ _ _] nil))
        health (-> (channels/create-registry)
                   (channels/register-adapter adapter)
                   (channels/registry-health))]
    (is (false? (:healthy health)))
    (is (= :boom (get-in health [:adapters 0 :health :type])))))

(deftest adapter-description-rejects-unknown-capabilities-test
  (is (thrown-message? clojure.lang.ExceptionInfo
                       #"Unsupported channel capabilities"
                       #(channels/create-adapter-description
                         :bad
                         "Bad"
                         :none
                         #{:supports-reactions}))))

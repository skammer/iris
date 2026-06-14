(ns agent.magi.core-test
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.magi.core :as magi]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]))

(defrecord StaticProvider [response requests]
  llm/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    {:role "assistant"
     :content (if (string? response) response (json/generate-string response))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- provider [response]
  (->StaticProvider response (atom [])))

(defn- service [responses]
  (magi/create-service
   (assoc config/default-config :magi {:timeout-ms 1000})
   {:providers (into {}
                    (map (fn [[role response]]
                           [role (provider response)]))
                    responses)}))

(deftest judge-response-mapping-test
  (is (= :error (:decision (magi/judge-responses {:melchior {:response :yes}
                                                  :balthasar {:response :error}
                                                  :casper {:response :yes}}))))
  (is (= :info (:decision (magi/judge-responses {:melchior {:response :yes}
                                                 :balthasar {:response :info}
                                                 :casper {:response :yes}}))))
  (is (= :no (:decision (magi/judge-responses {:melchior {:response :yes}
                                               :balthasar {:response :no}
                                               :casper {:response :conditional}}))))
  (is (= :conditional (:decision (magi/judge-responses {:melchior {:response :yes}
                                                        :balthasar {:response :conditional}
                                                        :casper {:response :yes}}))))
  (is (= :yes (:decision (magi/judge-responses {:melchior {:response :yes}
                                                :balthasar {:response :yes}
                                                :casper {:response :yes}})))))

(deftest decide-runs-filter-triumvirate-and-judge-test
  (let [svc (service {:filter {:kind "yes-no"
                               :domain "tool-approval"
                               :risk "low"
                               :question "Allow?"
                               :expected_response "permit"
                               :context {}}
                      :melchior {:response "yes" :comment "ok"}
                      :balthasar {:response "yes" :comment "ok"}
                      :casper {:response "yes" :comment "ok"}
                      :judge {:decision "yes" :reason "all yes"}})
        result (magi/decide svc {:question "Allow?" :context {}})]
    (is (= :yes (:decision result)))
    (is (= [:melchior :balthasar :casper] (keys (:agents result))))
    (is (= :yes-no (get-in result [:filter :kind])))))

(deftest filter-cannot-drop-supplied-context-test
  (let [melchior-requests (atom [])
        svc (magi/create-service
             (assoc config/default-config :magi {:timeout-ms 1000})
             {:providers {:filter (->StaticProvider {:kind "yes-no"
                                                      :domain "tool-approval"
                                                      :risk "low"
                                                      :question "Allow?"
                                                      :expected_response "permit"
                                                      :context {}}
                                                     (atom []))
                          :melchior (->StaticProvider {:response "yes"} melchior-requests)
                          :balthasar (provider {:response "yes"})
                          :casper (provider {:response "yes"})
                          :judge (provider {:decision "yes" :reason "all yes"})}})
        request-context {:user-request "check my public ip"
                         :recent-messages [{:role "user"
                                            :content "check my public ip"}]}
        result (magi/decide svc {:question "Allow?"
                                 :context {:tool-name "shell"
                                           :input {:argv ["curl" "-s" "https://httpbin.org/ip"]}
                                           :request-context request-context}})]
    (is (= :yes (:decision result)))
    (is (= request-context
           (get-in result [:filter :context :request-context])))
    (is (= request-context
           (-> @melchior-requests
               first
               (get-in [:messages 1 :content])
               (json/parse-string true)
               (get-in [:context :request-context]))))))

(deftest approval-question-includes-request-context-test
  (let [request-context {:user-request "run curl"
                         :recent-messages [{:role "user" :content "run curl"}]}
        question (magi/approval-question {:id "approval-1"
                                          :requested-by "session-1"
                                          :requested-permissions #{:shell-exec}
                                          :reason "confirm ip"}
                                         {:name :shell
                                          :category :system
                                          :operation :execute
                                          :routing-categories [:shell]}
                                         {:argv ["curl" "-s" "https://httpbin.org/ip"]}
                                         {:user "session-1"
                                          :request-id "request-1"
                                          :magi-context request-context})]
    (is (= request-context (get-in question [:context :request-context])))
    (is (= {:argv ["curl" "-s" "https://httpbin.org/ip"]}
           (get-in question [:context :input])))))

(deftest malformed-agent-output-becomes-error-test
  (let [svc (service {:filter {:kind "yes-no"
                               :domain "tool-approval"
                               :risk "low"
                               :question "Allow?"
                               :expected_response "permit"
                               :context {}}
                      :melchior {:response "yes"}
                      :balthasar {:response "maybe"}
                      :casper {:response "yes"}
                      :judge {:decision "error" :reason "bad agent"}})
        result (magi/decide svc {:question "Allow?" :context {}})]
    (is (= :error (:decision result)))
    (is (= :error (get-in result [:agents :balthasar :response])))))

(deftest provider-selection-falls-back-to-active-model-test
  (let [svc (magi/create-service config/default-config
                                 {:default-provider (provider {:kind "info"
                                                              :domain "policy"
                                                              :risk "low"
                                                              :question "x"
                                                              :expected_response "opine"
                                                              :context {}})})]
    (is (= {:provider :ollama :model "llama3.2:3b"}
           (get-in svc [:provider-selections :melchior])))))

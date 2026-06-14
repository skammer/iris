(ns agent.tools.common.magi-test
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.magi.core :as magi]
   [agent.tools.common.magi :as magi-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]))

(defrecord StaticProvider [response]
  llm/ILLMProviderInvoke
  (invoke [_ _request]
    {:role "assistant"
     :content (json/generate-string response)
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- service []
  (magi/create-service
   config/default-config
   {:providers {:filter (->StaticProvider {:kind "yes-no"
                                           :domain "policy"
                                           :risk "low"
                                           :question "Trust?"
                                           :expected_response "permit"
                                           :context {}})
                :melchior (->StaticProvider {:response "yes"})
                :balthasar (->StaticProvider {:response "yes"})
                :casper (->StaticProvider {:response "yes"})
                :judge (->StaticProvider {:decision "yes" :reason "all yes"})}}))

(deftest magi-tool-is-read-only-and-requires-permission-test
  (let [tool (magi-tool/create-magi-tool (service))
        registry (tools/register-tool (tools/create-registry) tool)
        description (tools/describe tool)]
    (is (= :read (:operation description)))
    (is (false? (:approval-sensitive? description)))
    (is (false? (:activates-tools? description)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Insufficient permissions"
         (tools/execute-tool registry :magi {:question "Trust?"} {:permissions #{}})))
    (is (= :yes
           (:decision (tools/execute-tool registry
                                          :magi
                                          {:question "Trust?"}
                                          {:permissions #{:magi-evaluate}}))))))

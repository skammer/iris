(ns agent.tools.approvals-test
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.magi.core :as magi]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.approvals :as approvals]
   [agent.tools.common.shell :as shell]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.java.io :as io]
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

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-magi-" ".db")))

(defn- agent-response [decision]
  (case decision
    :yes "yes"
    :conditional "conditional"
    :no "no"
    :error "error"
    "yes"))

(defn- magi-service [decision reason]
  (magi/create-service
   (assoc config/default-config
          :magi {:enabled? true
                 :mode :auto-approve
                 :fallback :human
                 :tool-categories #{:all}
                 :timeout-ms 1000})
   {:providers {:filter (->StaticProvider {:kind "yes-no"
                                           :domain "tool-approval"
                                           :risk "low"
                                           :question "Allow?"
                                           :expected_response "permit"
                                           :context {}})
                :melchior (->StaticProvider {:response "yes"})
                :balthasar (->StaticProvider {:response (agent-response decision)})
                :casper (->StaticProvider {:response "yes"})
                :judge (->StaticProvider {:decision (name decision)
                                          :reason reason})}}))

(defn- registry [store svc events]
  (-> (tools/create-registry
       {:event-sink #(swap! events conj %)
        :approval-check (approvals/create-policy-hook
                         {:store store
                          :magi-service svc
                          :event-sink #(swap! events conj %)
                          :approval-ttl-seconds 900})})
      (tools/register-tool
       (shell/create-shell-tool {:default-decision :ask
                                 :rules []}))))

(deftest magi-auto-yes-approves-and-allows-tool-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        reg (registry store (magi-service :yes "all yes") events)]
    (try
      (is (= "ok"
             (:stdout (tools/execute-tool reg
                                          :shell
                                          {:argv ["printf" "ok"]}
                                          {:permissions #{:shell-exec}
                                           :user "tester"}))))
      (let [approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= "approved" (:status approval)))
        (is (= "magi" (:actor approval)))
        (is (= "magi: yes" (:decision-reason approval))))
      (is (some #(= :tool.approval.magi_evaluated (:event-type %)) @events))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest magi-auto-conditional-denies-with-retryable-reason-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        reg (registry store (magi-service :conditional "needs narrower command") events)]
    (try
      (let [error (try
                    (tools/execute-tool reg
                                        :shell
                                        {:argv ["printf" "ok"]}
                                        {:permissions #{:shell-exec}
                                         :user "tester"})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :tool-blocked (:type (ex-data error))))
        (is (re-find #"denied until retry satisfies" (.getMessage error))))
      (let [approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= "denied" (:status approval)))
        (is (= "magi" (:actor approval)))
        (is (re-find #"needs narrower command" (:decision-reason approval))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

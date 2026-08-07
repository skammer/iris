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

(defn- registry
  ([store svc events] (registry store svc events nil))
  ([store svc events after-execute]
  (-> (tools/create-registry
       {:event-sink #(swap! events conj %)
        :approval-check (approvals/create-policy-hook
                         {:store store
                          :magi-service svc
                          :event-sink #(swap! events conj %)
                          :approval-ttl-seconds 900})
        :after-execute after-execute})
      (tools/register-tool
       (shell/create-shell-tool {:default-decision :ask
                                 :rules []})))))

(defn- approval-sensitive-tool [tool-name result]
  (tools/create-tool
   {:description (tools/create-tool-description
                  tool-name
                  "Approval-sensitive test tool"
                  :category :system
                  :required-permissions #{:cron-read}
                  :input-schema [:map [:action :string]]
                  :operation :act
                  :approval-sensitive? true
                  :sensitive (constantly true))
    :execute-fn (fn [_ _] result)}))

(deftest magi-auto-yes-approves-and-allows-tool-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        hook-context (atom nil)
        reg (registry store
                      (magi-service :yes "all yes")
                      events
                      (fn [hook]
                        (reset! hook-context (:context hook))
                        hook))]
    (try
      (is (= "ok"
             (:stdout (tools/execute-tool reg
                                          :shell
                                          {:argv ["printf" "ok"]
                                           :purpose "confirm shell output"}
                                          {:permissions #{:shell-exec}
                                           :user "tester"}))))
      (let [approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= "approved" (:status approval)))
        (is (= "confirm shell output" (:reason approval)))
        (is (= "magi" (:actor approval)))
        (is (= "magi: yes - all yes" (:decision-reason approval))))
      (is (= "magi: yes - all yes" (:approval-reason @hook-context)))
      (is (= "magi: yes - all yes"
             (some #(when (= :tool-execution-end (:event-type %))
                      (get-in % [:payload :approval-reason]))
                   @events)))
      (is (some #(= :tool.approval.magi_evaluated (:event-type %)) @events))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest approval-sensitive-metadata-covers-cronjob-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        reg (-> (registry store (magi-service :yes "safe cron mutation") events)
                (tools/register-tool (approval-sensitive-tool :cronjob {:created true})))]
    (try
      (is (= {:created true}
             (tools/execute-tool reg
                                 :cronjob
                                 {:action "create"}
                                 {:permissions #{:cron-read}
                                  :user "tester"})))
      (let [approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= "cronjob" (:tool-name approval)))
        (is (= "approved" (:status approval)))
        (is (= "magi" (:actor approval)))
        (is (= #{:cron-read :cron-manage}
               (:requested-permissions approval))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest magi-auto-conditional-leaves-human-review-pending-test
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
        (is (= :approval-required (:type (ex-data error)))))
      (let [approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= "pending" (:status approval)))
        (is (nil? (:actor approval)))
        (is (nil? (:decision-reason approval))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest magi-auto-pending-block-carries-approval-id-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        reg (registry store (magi-service :error "provider failed") events)]
    (try
      (let [error (try
                    (tools/execute-tool reg
                                        :shell
                                        {:argv ["printf" "ok"]}
                                        {:permissions #{:shell-exec}
                                         :user "tester"})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))
            approval (first (sqlite/list-tool-approvals store {:limit 10}))]
        (is (= :approval-required (:type (ex-data error))))
        (is (= "pending" (:status approval)))
        (is (= (:id approval) (:approval-id (ex-data error)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest denied-approval-resolution-exposes-decision-reason-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [approval (approvals/create-request!
                      store
                      {:tool-name :shell
                       :input {:argv ["printf" "ok"]}
                       :requested-by "tester"
                       :reason "test"
                       :expires-at (approvals/expires-at 900)})
            _ (approvals/deny! store (:id approval) "operator" "manual deny reason")
            error (try
                    (approvals/resolve-valid-request
                     store
                     (:id approval)
                     :shell
                     {:argv ["printf" "ok"]}
                     {:user "tester"})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :approval-not-approved (:type (ex-data error))))
        (is (= "manual deny reason" (:reason (ex-data error)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest review-records-exclude-expired-and-prioritize-actionable-test
  (let [expired {:id "expired" :status "pending" :expires-at "2000-01-01T00:00:00Z"}
        pending {:id "pending" :status "pending" :expires-at "2999-01-01T00:00:00Z"}
        recent (mapv (fn [n] {:id (str "recent-" n) :status "approved"}) (range 50))]
    (with-redefs [approvals/list-requests
                  (fn [_ opts]
                    (if (= "pending" (:status opts))
                      [expired pending]
                      recent))]
      (let [records (approvals/list-review-records ::store {:limit 50})]
        (is (= 50 (count records)))
        (is (= "pending" (:id (first records))))
        (is (not-any? #(= "expired" (:id %)) records))))))

(deftest expired-request-cannot-be-approved-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [approval (approvals/create-request!
                      store
                      {:tool-name :shell
                       :input {:argv ["printf" "ok"]}
                       :requested-by "tester"
                       :reason "expired"
                       :expires-at "2000-01-01T00:00:00Z"})
            error (try
                    (approvals/approve! store (:id approval) "operator" "late")
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :approval-expired (:type (ex-data error))))
        (is (= "pending" (:status (approvals/get-request store (:id approval))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

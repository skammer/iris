(ns agent.cron-test
  (:require
   [agent.chat.kernel-ops :as kernel-ops]
   [agent.chat.turn :as chat-turn]
   [agent.cron.schedule :as schedule]
   [agent.cron.store :as cron-store]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.common.cron :as cron-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)))

(defn- temp-store []
  (let [path (.getAbsolutePath (java.io.File/createTempFile "iris-cron-" ".db"))]
    {:path path :store (sqlite/create-store {:path path})}))

(deftest typed-schedule-next-fire-test
  (testing "five-field cron and timezone"
    (is (= ["2026-08-10T06:00:00Z" "2026-08-11T06:00:00Z"]
           (schedule/next-fires {:kind :cron :expression "0 9 * * 1-5"}
                                "UTC" (Instant/parse "2026-08-07T06:00:00Z") 2))))
  (testing "anchored interval never drifts"
    (is (= "2026-08-07T04:00:00Z"
           (str (schedule/next-fire {:kind :interval :every-seconds 7200
                                     :anchor-at "2026-08-07T00:00:00Z"}
                                    "UTC" (Instant/parse "2026-08-07T02:00:01Z"))))))
  (testing "missing expression names the required field"
    (let [error (try
                  (schedule/normalize {:kind :cron :cron "0 9 * * *"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "cron schedule requires schedule.expression" (.getMessage error)))
      (is (= :expression (:field (ex-data error))))))
  (testing "one shot exhausts"
    (is (nil? (schedule/next-fire {:kind :at :at "2026-08-07T01:00:00Z"}
                                  "UTC" (Instant/parse "2026-08-07T01:00:00Z"))))))

(deftest cron-tool-publishes-and-validates-typed-schedule-test
  (let [tool (cron-tool/create-cronjob-tool nil)
        validate-input (:validate-fn tool)
        description (tools/describe tool)
        schedule-json-schema (get-in description [:input-schema :properties :schedule])
        valid {:action "preview"
               :name "weekday-report"
               :prompt "Report"
               :schedule {:kind "cron" :expression "0 9 * * 1-5"}}]
    (is (= (assoc valid :action :preview) (validate-input valid)))
    (is (re-find #"expression" (pr-str schedule-json-schema)))
    (is (re-find #"Five-field UNIX cron" (pr-str schedule-json-schema)))
    (let [error (try
                  (validate-input (assoc-in valid [:schedule :cron] "0 9 * * 1-5"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :validation-failed (:type (ex-data error)))))))

(deftest cron-claim-session-and-overlap-test
  (let [{:keys [path store]} (temp-store)
        job (cron-store/create-job!
             store
             {:name "logs" :prompt "inspect" :schedule {:kind :cron :expression "0 * * * *"}
              :timezone "UTC" :status :active :notification {:policy :never}
              :next-run-at "2026-08-07T01:00:00Z" :created-by "test"})
        claim (cron-store/claim-scheduled!
               store job {:owner-id "owner-a" :snapshot {:name "logs"}
                          :next-run-at "2026-08-07T02:00:00Z"})]
    (try
      (is (= :claimed (:status claim)))
      (is (= :cron (:kind (sqlite/get-session store (:session-id claim)))))
      (is (empty? (sqlite/list-sessions store)))
      (is (= [(:session-id claim)] (mapv :id (sqlite/list-sessions store {:kind :cron}))))
      (is (nil? (cron-store/claim-scheduled!
                 store job {:owner-id "owner-b" :snapshot {}
                            :next-run-at "2026-08-07T02:00:00Z"})))
      (is (= :active-overlap
             (:type (ex-data
                     (try
                       (cron-store/claim-manual! store job {:owner-id nil :snapshot {}})
                       (catch clojure.lang.ExceptionInfo e e))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest cron-tool-action-policy-is-visible-and-enforced-test
  (let [http-tool (tools/create-tool
                   {:description (tools/create-tool-description
                                  :http "HTTP"
                                  :input-schema [:map {:closed true}
                                                 [:method [:enum :get :post]]]
                                  :required-permissions #{:http-request})
                    :execute-fn (fn [_ _] {:ok true})})
        registry (tools/register-tool (tools/create-registry) http-tool)
        options (#'chat-turn/runtime-loop-options
                 {:tool-registry registry}
                 {:history [] :context-injectors [] :model "test" :provider-config ::provider
                  :allowed-tools #{:http} :allowed-actions {:http #{:get :head}}
                  :chat-profile {} :max-steps 1 :stream-content? false
                  :max-parallelism 1 :yolo? false :cancelled? (atom false)}
                 {:event-sink nil :ops nil :on-thinking-delta nil})]
    (is (= #{"get" "head"}
           (set (get-in options [:tools 0 :input-schema :properties :method :enum]))))
    (is (= :tool-blocked
           (:type (ex-data
                   (try
                     (#'kernel-ops/enforce-action! :http {:method :post}
                                                   {:allowed-actions {:http #{:get}}})
                     (catch clojure.lang.ExceptionInfo e e))))))))

(deftest queued-manual-run-survives-startup-reconciliation-test
  (let [{:keys [path store]} (temp-store)
        job (cron-store/create-job!
             store
             {:name "queued" :prompt "inspect" :schedule {:kind :at :at "2026-08-08T00:00:00Z"}
              :timezone "UTC" :status :active :notification {:policy :never}
              :next-run-at "2026-08-08T00:00:00Z" :created-by "test"})
        run (cron-store/claim-manual! store job {:owner-id nil :snapshot {}})]
    (try
      (cron-store/abandon-active! store nil)
      (is (= :claimed (:status (cron-store/get-run store (:id run)))))
      (is (= (:id run) (:id (cron-store/adopt-run! store (:id run) "owner"))))
      (cron-store/abandon-active! store "owner")
      (is (= :abandoned (:status (cron-store/get-run store (:id run)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

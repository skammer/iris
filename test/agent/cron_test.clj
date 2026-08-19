(ns agent.cron-test
  (:require
   [agent.api.handlers.ui :as ui-handlers]
   [agent.chat.kernel-ops :as kernel-ops]
   [agent.chat.turn :as chat-turn]
   [agent.cron.schedule :as schedule]
   [agent.cron.service :as cron-service]
   [agent.cron.store :as cron-store]
   [agent.llm.registry :as llm-registry]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.common.cron :as cron-tool]
   [agent.tools.core :as tools]
   [agent.ui.cron :as ui-cron]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)))

(defn- temp-store []
  (let [path (.getAbsolutePath (java.io.File/createTempFile "iris-cron-" ".db"))]
    {:path path :store (sqlite/create-store {:path path})}))

(defn- test-system [store]
  (let [llm-cfg {:active-provider :deepseek
                 :providers {:deepseek {:type :deepseek
                                        :model "deepseek-v4-flash"
                                        :models {"deepseek-v4-flash" {}}}}}
        config {:llm llm-cfg
                :cron {:timezone "UTC" :provider nil :model nil :tool-profile :cron-observe}
                :tools {:profiles {:cron-observe {:permissions [:filesystem-read]
                                                  :allowed-tools [:fs_read]}}}}
        system-ref (atom nil)
        service (cron-service/create-service system-ref store (:cron config))
        system {:config config
                :store store
                :cron-service service
                :llm-registry (llm-registry/create-registry llm-cfg)
                :tool-registry (tools/create-registry)}]
    (reset! system-ref system)
    system))

(deftest cron-ui-tabs-and-delivery-controls-test
  (let [{:keys [path store]} (temp-store)
        system (test-system store)
        job (cron-store/create-job!
             store
             {:name "daily" :prompt "Report" :schedule {:kind :cron :expression "0 9 * * *"}
              :timezone "UTC" :status :active :notification {:policy :never}
              :next-run-at "2026-08-11T09:00:00Z" :created-by "test"})
        run (cron-store/claim-manual! store job {:snapshot job})
        _ (cron-store/finish-run! store (:id run) :succeeded
                                  {:output "Full result"
                                   :notification-status :not-requested})]
    (try
      (let [jobs-html (ui-cron/fragment system {:tab :jobs :limit 20})
            runs-html (ui-cron/fragment system {:tab :runs :limit 20})
            stats-html (ui-cron/fragment system {:tab :stats :limit 20})
            new-html (ui-cron/fragment system {:tab :new :limit 20})
            editor-html (ui-cron/job-editor-detail-fragment system job)]
        (is (= 4 (count (re-seq #"role=\"tab\"" jobs-html))))
        (is (str/includes? jobs-html "Persistent schedules"))
        (is (str/includes? jobs-html "cron-job-link"))
        (is (str/includes? jobs-html "<td><div class=\"cron-actions\">"))
        (is (not (str/includes? jobs-html "<td class=\"cron-actions\">")))
        (is (str/includes? jobs-html "scrollIntoView"))
        (is (not (str/includes? jobs-html "Scheduler")))
        (is (not (str/includes? jobs-html "Recent runs")))
        (is (str/includes? runs-html "Recent runs"))
        (is (str/includes? runs-html "cron-run-row--has-result"))
        (is (str/includes? runs-html "cron-run-result-row"))
        (is (str/includes? runs-html "colspan=\"7\""))
        (is (str/includes? runs-html "cron-audit-links"))
        (is (not (str/includes? runs-html "<td><span class=\"status-badge status-badge--succeeded\">succeeded</span><details")))
        (is (not (str/includes? runs-html "Persistent schedules")))
        (is (str/includes? stats-html "Scheduler"))
        (is (str/includes? stats-html "Active jobs"))
        (is (not (str/includes? stats-html "Persistent schedules")))
        (is (str/includes? new-html "Create job"))
        (is (str/includes? new-html "Telegram — always send result"))
        (is (str/includes? editor-html "update-run"))
        (is (str/includes? editor-html "this.form.elements.action.value=&apos;update-run&apos;"))
        (is (str/includes? editor-html "Save &amp; run"))
        (is (str/includes? editor-html "notify_policy.value === &apos;never&apos;")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest cron-stats-renders-week-gantt-and-month-calendar-test
  (let [{:keys [path store]} (temp-store)
        system (test-system store)]
    (try
      (doseq [job [{:name "Daily report"
                    :schedule {:kind :cron :expression "0 9 * * *"}
                    :next-run-at "2026-08-10T09:00:00Z"}
                   {:name "Monthly archive"
                    :schedule {:kind :cron :expression "0 7 1 * *"}
                    :next-run-at "2026-09-01T07:00:00Z"}]]
        (cron-store/create-job!
         store
         (merge job {:prompt "Run" :timezone "UTC" :status :active
                     :notification {:policy :never} :created-by "test"})))
      (let [week-html (ui-cron/fragment system {:tab :stats :view :week
                                                 :date "2026-08-10" :limit 20})
            calendar-html (ui-cron/fragment system {:tab :stats :view :calendar
                                                     :date "2026-08-10" :limit 20})]
        (is (str/includes? week-html "cron-gantt"))
        (is (= 7 (count (re-seq #"cron-gantt__day-heading" week-html))))
        (is (str/includes? week-html "Mon 10"))
        (is (str/includes? week-html "Sun 16"))
        (is (str/includes? week-html "Daily report"))
        (is (str/includes? week-html "left: 37.5000%"))
        (is (str/includes? calendar-html "cron-calendar"))
        (is (str/includes? calendar-html "August 2026"))
        (is (str/includes? calendar-html "Monthly archive"))
        (is (str/includes? calendar-html "07:00"))
        (is (str/includes? calendar-html "view=calendar&amp;date=2026-07-10"))
        (is (str/includes? calendar-html "view=calendar&amp;date=2026-09-10")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest cron-ui-run-action-claims-manual-run-test
  (let [{:keys [path store]} (temp-store)
        system (test-system store)
        job (cron-store/create-job!
             store
             {:name "manual-report" :prompt "Report" :schedule {:kind :cron :expression "0 9 * * *"}
              :timezone "UTC" :status :active :notification {:policy :never}
              :next-run-at "2026-08-11T09:00:00Z" :created-by "test"})]
    (try
      (let [response (ui-handlers/cron-action
                      system
                      {:form-params {"id" (:id job)
                                     "revision" (str (:revision job))
                                     "action" "run"
                                     "cron_tab" "jobs"}})
            run (first (cron-store/list-runs store (:id job) 1))]
        (is (= 200 (:status response)))
        (is (= :manual (:trigger run)))
        (is (= :claimed (:status run)))
        (is (str/includes? (:body response) "Recent runs")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest typed-schedule-next-fire-test
  (testing "five-field cron and timezone"
    (is (= ["2026-08-10T07:00:00Z" "2026-08-11T07:00:00Z"]
           (schedule/next-fires {:kind :cron :expression "0 9 * * 1-5"}
                                "Europe/Berlin" (Instant/parse "2026-08-07T07:00:00Z") 2))))
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
    (is (re-find #"inherit configured cron defaults" (:description description)))
    (is (re-find #"kind.*origin" (:description description)))
    (is (re-find #"cron_notify" (pr-str (get-in description [:input-schema :properties :notification]))))
    (let [error (try
                  (validate-input (assoc-in valid [:schedule :cron] "0 9 * * 1-5"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :validation-failed (:type (ex-data error)))))))

(deftest cron-job-defaults-and-string-overrides-test
  (let [llm-cfg {:active-provider :deepseek
                 :providers {:deepseek {:type :deepseek
                                        :model "deepseek-v4-flash"
                                        :models {"deepseek-v4-flash" {}}}}}
        system {:config {:llm llm-cfg
                         :tools {:profiles {:cron-observe {:permissions [:filesystem-read]
                                                          :allowed-tools [:fs_read]}}}}
                :llm-registry (llm-registry/create-registry llm-cfg)
                :tool-registry (tools/create-registry)}
        service (cron-service/create-service
                 (atom system) nil
                 {:timezone "UTC" :provider nil :model nil :tool-profile :cron-observe})
        base {:name "daily" :prompt "Inspect"
              :schedule {:kind :cron :expression "0 9 * * *"}}
        inherited (cron-service/preview service base)
        overridden (cron-service/preview
                    service
                    (assoc base :provider "deepseek" :model "deepseek-v4-flash"
                                :tool-profile "cron-observe"))]
    (is (= {:provider :deepseek :model "deepseek-v4-flash"}
           (:resolved-model inherited)))
    (is (= :cron-observe (get-in inherited [:resolved-tools :tool-profile])))
    (is (= {:provider :deepseek :model "deepseek-v4-flash"}
           (:resolved-model overridden)))
    (is (= :cron-observe (get-in overridden [:resolved-tools :tool-profile])))))

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

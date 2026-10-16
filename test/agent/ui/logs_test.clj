(ns agent.ui.logs-test
  (:require
   [agent.ui.test-support :refer [temp-db-path]]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as trace]
   [agent.ui :as ui]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is ]])
  (:import
   (org.jsoup Jsoup)))

(deftest logs-fragment-shows-events-and-trace-state
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "iris-ui-trace-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        runtime-trace (trace/create-trace {:mode :rolling :path "trace.jsonl"} (.getPath dir))]
    (try
      (sqlite/log-event! store {:event-type :test.event
                                :entity-type :test
                                :entity-id "1"
                                :payload {:ok true}})
      (trace/record-event! runtime-trace {:event-type :llm.call
                                          :success true
                                          :payload {:model "m"}})
      (let [html (ui/logs-fragment {:store store :trace runtime-trace})]
        (is (str/includes? html "Event Log"))
        (is (str/includes? html "Runtime Trace"))
        (is (str/includes? html "test.event"))
        (is (str/includes? html "llm.call"))
        (is (str/includes? html "log-metrics"))
        (is (= 2 (.size (.select (Jsoup/parse html) ".log-table"))))
        (is (= 2 (.size (.select (Jsoup/parse html) ".log-record"))))
        (is (str/includes? html "data-preserve-attr=\"open\""))
        (is (not (str/includes? html "data-on-interval")))
        (is (not (str/includes? html "&quot;ok&quot;")))
        (is (not (str/includes? html "event-item"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)
        (io/delete-file dir true)))))

(deftest magi-fragment-shows-decision-log
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (sqlite/log-event! store
                         {:event-type :tool.approval.magi_evaluated
                          :entity-type :tool_approval
                          :entity-id "approval-1"
                          :payload {:tool-name "shell"
                                    :input {:argv ["printf" "ok"]}
                                    :filter {:kind :yes-no
                                             :domain :tool-approval
                                             :risk :low
                                             :question "Allow?"
                                             :expected-response :permit
                                             :context {}}
                                    :agents {:melchior {:response :yes :comment "ok"}
                                             :balthasar {:response :conditional :comment "narrow scope"}
                                             :casper {:response :yes :comment "ok"}}
                                    :judge {:decision :conditional
                                            :reason "narrow scope"}
                                    :decision :conditional
                                    :reason "narrow scope"
                                    :duration-ms 12}})
      (sqlite/log-event! store
                         {:event-type :tool-execution-end
                          :entity-type :tool
                          :entity-id "magi"
                          :payload {:tool-name "magi"
                                    :status "succeeded"
                                    :receipt {:input {:question "double check"}
                                              :result {:filter {:kind :yes-no
                                                                :domain :policy
                                                                :risk :low
                                                                :question "double check"
                                                                :expected-response :permit
                                                                :context {}}
                                                       :agents {:melchior {:response :yes}
                                                                :balthasar {:response :yes}
                                                                :casper {:response :yes}}
                                                       :decision :yes
                                                       :reason "all yes"}}
                                    :duration-ms 7}})
      (sqlite/log-event! store
                         {:event-type :memory.vault.magi_evaluated
                          :entity-type :vault_note
                          :entity-id "mem-1"
                          :payload {:source "manual"
                                    :note {:id "mem-1" :title "Stable preference"}
                                    :input {:question "Promote note?"}
                                    :filter {:kind :yes-no
                                             :domain :memory-promotion
                                             :risk :medium
                                             :question "Promote note?"
                                             :expected-response :permit}
                                    :agents {:melchior {:response :yes}
                                             :balthasar {:response :yes}
                                             :casper {:response :yes}}
                                    :judge {:decision :yes :reason "supported"}
                                    :decision :yes
                                    :reason "supported"
                                    :duration-ms 9
                                    :applied true}})
      (let [html (ui/magi-fragment {:store store})
            event (first (sqlite/list-events store {:event-type :tool.approval.magi_evaluated
                                                     :limit 1}))
            detail-html (ui/magi-detail-fragment event)]
        (is (str/includes? html "decision log"))
        (is (str/includes? html "Invocation Log"))
        (is (not (str/includes? html "Decision Console")))
        (is (str/includes? html "latest 3 records"))
        (is (str/includes? html "approval"))
        (is (str/includes? html "tool"))
        (is (str/includes? html "/ui/magi/1/detail"))
        (is (not (str/includes? html "BALTHASAR")))
        (is (str/includes? html "CONDITIONAL"))
        (is (not (str/includes? html "printf")))
        (is (str/includes? html "vault-note"))
        (is (str/includes? detail-html "BALTHASAR"))
        (is (str/includes? detail-html "MELCHIOR"))
        (is (str/includes? detail-html "input"))
        (is (str/includes? detail-html "filter"))
        (is (str/includes? detail-html "judge"))
        (is (str/includes? detail-html "event"))
        (is (str/includes? detail-html "printf")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(ns agent.runtime.schema-test
  (:require
   [agent.runtime.schema :as runtime-schema]
   [clojure.test :refer :all]))

(deftest validates-canonical-message-blocks-test
  (doseq [block [{:type :text :text "hello"}
                 {:type :thinking :text "reasoning" :signature "sig"}
                 {:type :image :source {:type :url :value "https://example.com/a.png"} :alt "a"}
                 {:type :audio :source {:type :base64 :media-type "audio/wav" :value "UklGRg=="} :filename "a.wav"}
                 {:type :video :source {:type :base64 :media-type "video/mp4" :value "AAAA"} :filename "a.mp4"}
                 {:type :file :source {:type :base64 :media-type "application/pdf" :value "JVBERi0="} :filename "a.pdf"}
                 {:type :tool-call :id "call-1" :name "fs" :arguments {:action "list"}}
                 {:type :tool-result :tool-call-id "call-1" :status :ok :content {:ok true}}
                 {:type :custom :kind :notice :data {:text "x"}}]]
    (is (= block (runtime-schema/validate-message-block! block)))))

(deftest normalizes-provider-style-blocks-test
  (is (= [{:type :tool-call
           :id "call-1"
           :name "fs"
           :arguments {:path "."}}]
         (runtime-schema/normalize-content
          [{:type "toolCall"
            :id "call-1"
            :tool-name "fs"
            :input {:path "."}}]))))

(deftest validates-canonical-assistant-turn-test
  (let [turn {:provider :openrouter
              :model "anthropic/claude"
              :response-model "anthropic/claude"
              :response-id "resp-1"
              :content [{:type :thinking :text "hidden"}
                        {:type :text :text "visible"}]
              :usage {:prompt-tokens 10
                      :completion-tokens 4
                      :cached-tokens 3
                      :cost-usd 0.01}
              :stop-reason :stop
              :error nil
              :timestamp "2026-05-19T00:00:00Z"}]
    (is (= turn (runtime-schema/validate-assistant-turn! turn)))))

(deftest validates-all-canonical-runtime-events-test
  (doseq [event-type runtime-schema/runtime-event-types]
    (is (= event-type
           (:event-type
            (runtime-schema/validate-runtime-event!
             {:event-type event-type
              :entity-type :session
              :entity-id "session-1"
              :request-id "request-1"
              :timestamp "2026-05-19T00:00:00Z"
              :payload {:ok true}}))))))

(deftest maps-legacy-events-to-canonical-events-test
  (is (= :agent-start
         (runtime-schema/legacy-event-type->canonical "chat.started")))
  (is (= :message-end
         (runtime-schema/legacy-event-type->canonical :completion.completed)))
  (is (= :tool-execution-start
         (runtime-schema/legacy-event-type->canonical "tool.execution.requested")))
  (is (= {:event-type :tool-execution-end
          :entity-type "session"
          :entity-id "s1"
          :request-id "r1"
          :timestamp "2026-05-19T00:00:00Z"
          :payload {:status :ok
                    :legacy-event-type "tool.execution.succeeded"}}
         (runtime-schema/legacy-event->canonical
          {:event-type "tool.execution.succeeded"
           :entity-type "session"
           :entity-id "s1"
           :request-id "r1"
           :created-at "2026-05-19T00:00:00Z"
           :payload {:status :ok}}))))

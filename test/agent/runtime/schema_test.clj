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
                 {:type :tool-call :id "call-1" :name "fs_list" :arguments {:path "."}}
                 {:type :tool-result :tool-call-id "call-1" :status :ok :content {:ok true}}
                 {:type :custom :kind :notice :data {:text "x"}}]]
    (is (= block (runtime-schema/validate-message-block! block)))))

(deftest normalizes-provider-style-blocks-test
  (is (= [{:type :tool-call
           :id "call-1"
           :name "fs_list"
           :arguments {:path "."}}]
         (runtime-schema/normalize-content
          [{:type "toolCall"
            :id "call-1"
            :tool-name "fs_list"
            :input {:path "."}}]))))

(deftest normalizes-openai-media-content-parts-test
  (is (= [{:type :text :text "inspect"}
          {:type :image
           :source {:type :base64
                    :value "aGVsbG8="
                    :media-type "image/png"}}
          {:type :video
           :source {:type :base64
                    :value "AAAA"
                    :media-type "video/mp4"}
           :filename "clip.mp4"}]
         (runtime-schema/normalize-content
          [{:type "text" :text "inspect"}
           {:type "image_url"
            :image_url {:url "data:image/png;base64,aGVsbG8="}}
           {:type "file"
            :file {:file_data "AAAA"
                   :media-type "video/mp4"
                   :filename "clip.mp4"}}]))))

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
  (doseq [event [{:event-type :agent-start :payload {:message-count 1 :stream false}}
                 {:event-type :agent-end :payload {:stop-reason :completed :steps 1 :stream false}}
                 {:event-type :turn-start :payload {:step 0}}
                 {:event-type :turn-end :payload {:step 0 :directives [] :receipts []}}
                 {:event-type :message-start :payload {:role "assistant" :step 0}}
                 {:event-type :message-update :payload {:role "assistant" :delta "x" :append? true}}
                 {:event-type :message-end :payload {:role "assistant" :content "x" :final? true}}
                 {:event-type :nudge-injected :payload {:step 0 :reason "bare-text" :content "retry"}}
                 {:event-type :guardrail-blocked :payload {:step 0 :action "retry" :reason "bare-text"}}
                 {:event-type :tool-execution-start :payload {:tool-name "fs_list" :tool-call-id "call_1"}}
                 {:event-type :tool-execution-update :payload {:tool-name "fs_list" :tool-call-id "call_1" :progress 1}}
                 {:event-type :tool-execution-end :payload {:tool-name "fs_list" :tool-call-id "call_1" :status "ok" :duration-ms 1.0}}]]
    (is (= (:event-type event)
           (:event-type
            (runtime-schema/validate-runtime-event!
             (merge {:entity-type :session
                     :entity-id "session-1"
                     :request-id "request-1"
                     :timestamp "2026-05-19T00:00:00Z"}
                    event)))))))

(deftest rejects-invalid-runtime-event-payload-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"runtime-event failed schema validation"
       (runtime-schema/validate-runtime-event!
        {:event-type :tool-execution-end
         :entity-type :session
         :entity-id "session-1"
         :request-id "request-1"
         :timestamp "2026-05-19T00:00:00Z"
         :payload {:tool-name "fs_list"
                   :status "ok"}}))))

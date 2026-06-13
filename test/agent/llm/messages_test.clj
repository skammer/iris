(ns agent.llm.messages-test
  (:require
   [agent.llm.messages :as llm-messages]
   [clojure.test :refer [deftest is]]))

(deftest converts-rich-messages-to-openai-compatible-shape-test
  (is (= [{:role "system" :content "sys"}
          {:role "user"
           :content [{:type "text" :text "look"}
                     {:type "image_url"
                      :image_url {:url "https://example.com/a.png"}}]}
          {:role "assistant"
           :content "calling"
           :tool_calls [{:id "call-1"
                         :type "function"
                         :function {:name "fs_list"
                                    :arguments "{\"path\":\".\"}"}}]}
          {:role "tool"
           :tool_call_id "call-1"
           :content "{\"status\":\"ok\"}"}]
         (llm-messages/internal->openai-compatible
          [{:role :system
            :content [{:type :text :text "sys"}]}
           {:role :user
            :content [{:type :text :text "look"}
                      {:type :image
                       :source {:type :url
                                :value "https://example.com/a.png"}}]}
           {:role :assistant
            :content [{:type :text :text "calling"}
                      {:type :tool-call
                       :id "call-1"
                       :name :fs_list
                       :arguments {:path "."}}]}
           {:role :tool
            :content [{:type :tool-result
                       :tool-call-id "call-1"
                       :content {:status "ok"}}]}]))))

(deftest converts-base64-image-to-openai-compatible-data-url-test
  (is (= [{:role "user"
           :content [{:type "text" :text "look"}
                     {:type "image_url"
                      :image_url {:url "data:image/jpeg;base64,YWJjZA=="}}]}]
         (llm-messages/internal->openai-compatible
          [{:role :user
            :content [{:type :text :text "look"}
                      {:type :image
                       :source {:type :base64
                                :media-type "image/jpeg"
                                :value "YWJjZA=="}}]}]))))

(deftest converts-rich-messages-to-ollama-shape-test
  (is (= [{:role "user"
           :content "describe"
           :images ["aGVsbG8="]}
          {:role "assistant"
          :content ""
          :tool_calls [{:id "call-2"
                         :type "function"
                         :function {:name "memory_recall"
                                    :arguments "{\"query\":\"iris\"}"}}]}]
         (llm-messages/internal->ollama
          [{:role :user
            :content [{:type :text :text "describe"}
                      {:type :image
                       :source {:type :base64
                                :media-type "image/png"
                                :value "aGVsbG8="}}]}
           {:role :assistant
            :content [{:type :tool-call
                       :id "call-2"
                       :name "memory_recall"
                       :arguments {:query "iris"}}]}]))))

(deftest converts-audio-video-and-files-to-openai-compatible-parts-test
  (is (= [{:role "user"
           :content [{:type "text" :text "inspect"}
                     {:type "input_audio"
                      :input_audio {:data "UklGRg=="
                                    :format "wav"}}
                     {:type "file"
                      :file {:file_data "AAAA"
                             :filename "clip.mp4"}}
                     {:type "file"
                      :file {:file_data "JVBERi0="
                             :filename "doc.pdf"}}]}]
         (llm-messages/internal->openai-compatible
          [{:role :user
            :content [{:type :text :text "inspect"}
                      {:type :audio
                       :source {:type :base64
                                :media-type "audio/wav"
                                :value "UklGRg=="}
                       :filename "voice.wav"}
                      {:type :video
                       :source {:type :base64
                                :media-type "video/mp4"
                                :value "AAAA"}
                       :filename "clip.mp4"}
                      {:type :file
                       :source {:type :base64
                                :media-type "application/pdf"
                                :value "JVBERi0="}
                       :filename "doc.pdf"}]}]))))

(deftest normalizes-provider-response-to-assistant-turn-test
  (let [turn (llm-messages/provider-response->assistant-turn
              :openrouter
              "anthropic/claude"
              {:id "resp-1"
               :model "anthropic/claude"
               :content "done"
               :reasoning_content "thinking"
               :tool-calls [{:id "call-1"
                             :type "function"
                             :function {:name "fs_list"
                                        :arguments "{\"path\":\".\"}"}}]
               :usage {:prompt-tokens 12
                       :completion-tokens 5
                       :cached-tokens 2
                       :cost-usd 0.01}
               :finish-reason "tool_calls"
               :timestamp "2026-05-19T00:00:00Z"})]
    (is (= :openrouter (:provider turn)))
    (is (= "resp-1" (:response-id turn)))
    (is (= [:thinking :text :tool-call] (mapv :type (:content turn))))
    (is (= {:prompt-tokens 12
            :completion-tokens 5
            :cached-tokens 2
            :cost-usd 0.01}
           (:usage turn)))))

(deftest round-trips-tool-call-and-tool-result-test
  (let [provider-tool-call {:id "call-1"
                            :type "function"
                            :function {:name "fs_list"
                                       :arguments "{\"path\":\".\"}"}}
        internal-call (llm-messages/provider-tool-call->internal provider-tool-call)]
    (is (= {:type :tool-call
            :id "call-1"
            :name "fs_list"
            :arguments {:path "."}
            :raw provider-tool-call}
           internal-call))
    (is (= provider-tool-call
           (llm-messages/internal-tool-call->provider-tool-call internal-call)))
    (is (= {:role "tool"
            :tool_call_id "call-1"
            :content "{\"status\":\"ok\"}"}
           (llm-messages/internal-tool-result->provider-tool-result
            {:type :tool-result
             :tool-call-id "call-1"
             :content {:status "ok"}})))))

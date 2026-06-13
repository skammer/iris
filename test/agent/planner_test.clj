(ns agent.planner-test
  (:require
   [agent.llm.core :as llm]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.telemetry.observer :as telemetry-observer]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest bundled-prompt-modes-test
  (is (= ["ask"
          "brainstorm"
          "code"
          "debug"
          "default"
          "frontend-design"
          "plan"
          "review"
          "review-security"
          "simplify"
          "write-prompt"]
         (prompts/list-modes)))
  (is (str/includes? (prompts/get-mode "code") "## Coding Mode"))
  (is (= "## Read-Only Mode"
         (first (str/split-lines (:content (first (prompts/apply-mode [] "ask")))))))
  (is (= [{:role "user" :content "keep"}]
         (prompts/apply-mode [{:role "user" :content "keep"}] nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Prompt mode not found"
                        (prompts/get-mode "missing"))))

(deftest planner-completes-from-text-content-test
  (let [provider (reify llm/ILLMProviderInvoke
                   (invoke [_ request]
                     (is (nil? (:structured-output request)))
                     (is (nil? (:response-format request)))
                     (is (nil? (:tools request)))
                     {:role "assistant"
                      :content "ok"
                      :tool-calls []
                      :usage nil
                      :raw nil})
                   (generate [this messages opts]
                     (llm/invoke this (assoc opts :messages messages))))
        step (planner/plan-step! provider {:messages [{:role "user" :content "finish"}]})]
    (is (= :complete (get-in step [:directives 0 :type])))
    (is (= "ok" (get-in step [:directives 0 :payload :result])))))

(deftest planner-passes-native-tool-defs-and-converts-tool-calls-test
  (let [tool {:name :fs_list
              :description "Filesystem"
              :input-schema {:type "object"
                             :properties {:path {:type "string"}}
                             :required ["path"]
                             :additionalProperties false}}
        captured (atom nil)
        provider (reify llm/ILLMProviderInvoke
                   (invoke [_ request]
                     (reset! captured request)
                     {:role "assistant"
                      :content nil
                      :tool-calls [{:id "call_1"
                                    :type "function"
                                    :function {:name "fs_list"
                                               :arguments "{\"path\":\".\"}"}}]
                      :usage nil
                      :raw nil})
                   (generate [this messages opts]
                     (llm/invoke this (assoc opts :messages messages))))
        step (planner/plan-step! provider {:messages [{:role "user" :content "list files"}]
                                           :tools [tool]})]
    (is (= "function" (get-in @captured [:tools 0 :type])))
    (is (= "fs_list" (get-in @captured [:tools 0 :function :name])))
    (is (nil? (:structured-output @captured)))
    (is (= :tool-call (get-in step [:directives 0 :type])))
    (is (= "fs_list" (get-in step [:directives 0 :payload :tool-name])))
    (is (= {:path "."} (get-in step [:directives 0 :payload :input])))
    (is (= "call_1" (get-in step [:directives 0 :payload :context :provider-tool-call-id])))))

(deftest planner-passes-session-id-test
  (let [captured (atom nil)
        provider (reify llm/ILLMProviderInvoke
                   (invoke [_ request]
                     (reset! captured request)
                     {:role "assistant"
                      :content "ok"
                      :tool-calls []
                      :usage nil
                      :raw nil})
                   (generate [this messages opts]
                     (llm/invoke this (assoc opts :messages messages))))]
    (planner/plan-step! provider {:messages [{:role "user" :content "hi"}]
                                  :session-id "session-1"})
    (is (= "session-1" (:session-id @captured)))))

(deftest planner-llm-call-event-includes-cached-tokens-test
  (let [events (atom [])
        observer (reify telemetry-observer/IObserver
                   (record-event! [_ event] (swap! events conj event))
                   (record-metric! [_ _metric] nil)
                   (flush! [_] nil)
                   (observer-name [_] "test"))
        provider (reify llm/ILLMProviderInvoke
                   (invoke [_ _request]
                     {:role "assistant"
                      :content "ok"
                      :tool-calls []
                      :usage {:tokens 30
                              :prompt-tokens 20
                              :completion-tokens 10
                              :cached-tokens 7}
                      :raw nil})
                   (generate [this messages opts]
                     (llm/invoke this (assoc opts :messages messages))))]
    (planner/plan-step! provider {:messages [{:role "user" :content "hi"}]
                                  :observer observer})
    (is (= 7 (->> @events
                  (filter #(= :llm/call (:event-type %)))
                  first
                  :payload
                  :cached-tokens)))))

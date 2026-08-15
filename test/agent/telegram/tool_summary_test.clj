(ns agent.telegram.tool-summary-test
  (:require
   [agent.llm.core :as llm]
   [agent.telegram.tool-summary :as tool-summary]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest generates-title-with-original-request-and-bounded-tool-context-test
  (let [request (atom nil)
        provider (reify llm/ILLMProviderInvoke
                   (invoke [_ value]
                     (reset! request value)
                     {:content "«Искал информацию по Ясной Поляне»"})
                   (generate [_ _ _] {:content "unused"}))
        system {:llm-provider provider
                :config {:llm {:active-provider :test
                               :providers {:test {:type :openai-compatible
                                                  :model "test-model"}}}}}
        title (tool-summary/generate-title
               system
               "session-1"
               "Хочу съездить в Ясную Поляну"
               [{:tool-name :http
                 :status :error
                 :reason "HTTP 404"
                 :input {:purpose "Найти маршрут"}}])]
    (is (= "Искал информацию по Ясной Поляне" title))
    (is (= "test-model" (:model @request)))
    (is (str/includes? (get-in @request [:messages 1 :content])
                       "Хочу съездить в Ясную Поляну"))
    (is (= 40 (:max-tokens @request)))))

(deftest generates-localized-fallback-without-provider-test
  (is (= "Выполнил вспомогательные действия"
         (tool-summary/generate-title {} "session-1" "Найди музей" [])))
  (is (= "Completed supporting work"
         (tool-summary/generate-title {} "session-1" "Find a museum" []))))

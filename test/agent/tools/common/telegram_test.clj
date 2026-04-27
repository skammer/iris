(ns agent.tools.common.telegram-test
  (:require
   [agent.telegram :as telegram]
   [agent.tools.common.telegram :as t-tool]
   [agent.tools.core :as tools]
   [clojure.test :refer :all]))

(deftest enabled-only-when-token-set
  (is (false? (t-tool/enabled? {})))
  (is (false? (t-tool/enabled? {:bot-token ""})))
  (is (true? (t-tool/enabled? {:bot-token "abc"}))))

(deftest send-photo-tool-requires-chat-id
  (let [tool (t-tool/create-send-photo-tool {:bot-token "t"})
        ex (try (tools/execute tool {:photo "https://example.com/a.png"} {})
                (catch Exception e e))]
    (is (= :missing-context (:type (ex-data ex))))))

(deftest send-photo-tool-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [token method body]
                                          (swap! calls conj {:token token :method method :body body})
                                          {:ok true})]
      (let [tool (t-tool/create-send-photo-tool {:bot-token "t"})
            result (tools/execute tool
                                  {:photo "https://example.com/a.png" :caption "hi"}
                                  {:telegram-chat-id 100})]
        (is (= true (:sent result)))
        (is (= 100 (:chat-id result)))
        (is (= [{:token "t"
                 :method "sendPhoto"
                 :body {:chat_id 100
                        :photo "https://example.com/a.png"
                        :caption "hi"
                        :parse_mode "HTML"}}]
               @calls))))))

(deftest send-document-tool-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [_ method body]
                                          (swap! calls conj {:method method :body body})
                                          {:ok true})]
      (let [tool (t-tool/create-send-document-tool {:bot-token "t"})]
        (tools/execute tool
                       {:document "https://example.com/a.pdf"}
                       {:telegram-chat-id 200})
        (is (= "sendDocument" (-> @calls first :method)))
        (is (= 200 (-> @calls first :body :chat_id)))
        (is (= "https://example.com/a.pdf" (-> @calls first :body :document)))
        (is (not (contains? (-> @calls first :body) :caption)))))))

(deftest send-photo-tool-validates-input
  (let [tool (t-tool/create-send-photo-tool {:bot-token "t"})
        ex (try (tools/execute tool {:photo ""} {:telegram-chat-id 100})
                (catch Exception e e))]
    (is (#{:validation-failed} (:type (ex-data ex))))))

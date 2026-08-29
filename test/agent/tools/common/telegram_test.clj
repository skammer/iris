(ns agent.tools.common.telegram-test
  (:require
   [agent.telegram.api :as telegram-api]
   [agent.tools.common.telegram :as t-tool]
   [agent.tools.core :as tools]
   [clojure.test :refer [deftest is]]))

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
    (with-redefs [telegram-api/request! (fn [token method body]
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
                        :parse_mode "MarkdownV2"}}]
               @calls))))))

(deftest send-document-tool-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [_ method body]
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

(deftest send-document-tool-defaults-sample-document
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [_ method body]
                                          (swap! calls conj {:method method :body body})
                                          {:ok true})]
      (let [tool (t-tool/create-send-document-tool {:bot-token "t"})]
        (tools/execute tool {} {:telegram-chat-id 200})
        (is (= "sendDocument" (-> @calls first :method)))
        (is (= 200 (-> @calls first :body :chat_id)))
        (is (= "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
               (-> @calls first :body :document)))))))

(deftest ask-tool-sends-reply-keyboard
  (let [calls (atom [])
        reply-keyboards (atom {})]
    (with-redefs [telegram-api/send-message-with-reply-markup!
                  (fn [token chat-id text reply-markup]
                    (swap! calls conj {:token token
                                       :chat-id chat-id
                                       :text text
                                       :reply-markup reply-markup})
                    {:ok true})]
      (let [tool (t-tool/create-ask-tool {:bot-token "t"
                                          :reply-keyboards reply-keyboards
                                          :ask-timeout-seconds 3600})
            result (tools/execute tool
                                  {:question "Deploy?"
                                   :choices ["Yes" "No" "Later"]
                                   :input-placeholder "Pick option"}
                                  {:telegram-chat-id 200})]
        (is (= true (:sent result)))
        (is (= true (:awaiting-reply result)))
        (is (= [{:token "t"
                 :chat-id 200
                 :text "Deploy?"
                 :reply-markup {:keyboard [[{:text "Yes"} {:text "No"}]
                                           [{:text "Later"}]]
                                :resize_keyboard true
                                :one_time_keyboard true
                                :input_field_placeholder "Pick option"}}]
               @calls))
        (is (some? (get @reply-keyboards 200)))
        (future-cancel (:future (get @reply-keyboards 200)))))))

(deftest ask-tool-expires-and-removes-reply-keyboard
  (let [calls (atom [])
        expired (promise)
        reply-keyboards (atom {})]
    (with-redefs [telegram-api/send-message-with-reply-markup!
                  (fn [_ chat-id text reply-markup]
                    (swap! calls conj {:chat-id chat-id
                                       :text text
                                       :reply-markup reply-markup})
                    (when (= {:remove_keyboard true} reply-markup)
                      (deliver expired true))
                    {:ok true})]
      (let [tool (t-tool/create-ask-tool {:bot-token "t"
                                          :reply-keyboards reply-keyboards
                                          :ask-timeout-seconds 1})]
        (tools/execute tool
                       {:question "Still needed?" :choices ["Yes" "No"]}
                       {:telegram-chat-id 200})
        (is (= true (deref expired 2000 false)))
        (is (= {:chat-id 200
                :text "Question expired."
                :reply-markup {:remove_keyboard true}}
               (last @calls)))
        (is (nil? (get @reply-keyboards 200)))))))

(deftest send-document-tool-uploads-local-file-inside-root
  (let [tmp (java.nio.file.Files/createTempDirectory "iris-telegram-doc" (make-array java.nio.file.attribute.FileAttribute 0))
        file (.resolve tmp "sample.txt")
        calls (atom [])]
    (spit (.toFile file) "hello")
    (with-redefs [telegram-api/send-document-file! (fn [_ chat-id document caption]
                                                 (swap! calls conj {:chat-id chat-id
                                                                    :document document
                                                                    :caption caption})
                                                 {:ok true})]
      (let [tool (t-tool/create-send-document-tool {:bot-token "t"
                                                    :document-roots [(str tmp)]})
            result (tools/execute tool
                                  {:document (str file) :caption "local"}
                                  {:telegram-chat-id 200})]
        (is (= true (:uploaded? result)))
        (is (= 200 (-> @calls first :chat-id)))
        (is (= (.getCanonicalPath (.toFile file))
               (.getCanonicalPath (-> @calls first :document))))
        (is (= "local" (-> @calls first :caption)))))))

(deftest send-photo-tool-validates-input
  (let [tool (t-tool/create-send-photo-tool {:bot-token "t"})
        ex (try (tools/execute tool {:photo ""} {:telegram-chat-id 100})
                (catch Exception e e))]
    (is (#{:validation-failed} (:type (ex-data ex))))))

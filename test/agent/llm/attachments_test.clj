(ns agent.llm.attachments-test
  (:require
   [agent.config :as config]
   [agent.llm.attachments :as attachments]
   [agent.llm.core :as llm]
   [agent.llm.fallback :as fallback]
   [agent.llm.providers.openai-compatible :as provider]
   [agent.test-runner :as runner]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]])
  (:import (java.io ByteArrayInputStream)))

(use-fixtures :each runner/with-isolated-home)

(def pdf-block
  {:type :file :filename "report.pdf"
   :source {:type :base64 :media-type "application/pdf" :value "b3BhcXVlLWJ5dGVz"}})

(def history [{:role "user" :content [{:type :text :text "Inspect when needed."} pdf-block]}])

(defn- reference-path [text]
  (second (re-find #"Local file: ([^\n]+)" text)))

(deftest unsupported-attachment-is-opaque-local-reference
  (let [prepared (attachments/prepare-messages history {})
        text (get-in prepared [0 :content 1 :text])
        path (reference-path text)]
    (is (str/includes? text "report.pdf (application/pdf)"))
    (is (str/includes? text "Use available tools"))
    (is (= "opaque-bytes" (slurp path)))
    (is (not (str/includes? text "b3BhcXVlLWJ5dGVz")))
    (is (= prepared (attachments/prepare-messages history {})))
    (is (= pdf-block (get-in history [0 :content 1])))))

(deftest reuses-telegram-local-copy
  (let [file (io/file (config/data-dir (config/global-config-dir)) "telegram-media" "report.pdf")]
    (io/make-parents file)
    (spit file "original")
    (let [block (assoc-in pdf-block [:source :local-path] (.getAbsolutePath file))
          prepared (attachments/prepare-messages [{:role "user" :content [block]}] {})]
      (is (= (.getAbsolutePath file)
             (reference-path (get-in prepared [0 :content 0 :text]))))
      (is (not (.exists (io/file (config/data-dir (config/global-config-dir)) "attachments")))))))

(deftest url-reference-needs-no-download
  (let [block {:type :file :filename "report.pdf"
               :source {:type :url :value "https://example.com/report.pdf"}}
        prepared (attachments/prepare-messages [{:role "user" :content [block]}] {})]
    (is (str/includes? (get-in prepared [0 :content 0 :text])
                       "URL: https://example.com/report.pdf"))))

(deftest each-media-type-needs-its-own-capability
  (doseq [[kind flag] [[:image :supports-vision] [:audio :supports-audio]
                      [:video :supports-video] [:file :supports-files]]]
    (let [block (assoc pdf-block :type kind)
          input [{:role "user" :content [block]}]]
      (is (= kind (get-in (attachments/prepare-messages input {flag true}) [0 :content 0 :type])))
      (is (= :text (get-in (attachments/prepare-messages input {flag false}) [0 :content 0 :type]))))))

(deftest chat-and-responses-streaming-use-references
  (doseq [api [:chat-completions :responses]
          streaming? [false true]]
    (let [body (atom nil)
          client (provider/create-openai-compatible-provider {:model "plain" :api api})
          stream-text (if (= api :responses)
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\ndata: [DONE]\n\n"
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")]
      (with-redefs [http/post (fn [_ request]
                               (reset! body (json/parse-string (:body request) true))
                               {:status 200
                                :body (if streaming?
                                        (ByteArrayInputStream. (.getBytes stream-text "UTF-8"))
                                        (if (= api :responses)
                                          {:output [{:type "message" :content [{:type "output_text" :text "ok"}]}]}
                                          {:choices [{:message {:content "ok"}}]}))})]
        (llm/invoke client (cond-> {:messages history}
                             streaming? (assoc :on-content-delta (fn [_] nil))))
        (let [content (get-in @body [(if (= api :responses) :input :messages) 0 :content])]
          (is (string? content))
          (is (str/includes? content "Local file:"))
          (is (not (str/includes? (json/generate-string @body) "b3BhcXVlLWJ5dGVz"))))))))

(deftest fallback-rechecks-the-next-model-capabilities
  (let [seen (atom [])
        native (provider/create-openai-compatible-provider
                {:model "native" :max-retries 0 :models {"native" {:supports-files true}}})
        plain (provider/create-openai-compatible-provider {:model "plain"})
        chain (fallback/->FallbackProvider [{:provider :native :model "native" :client native}
                                           {:provider :plain :model "plain" :client plain}])]
    (with-redefs [http/post (fn [_ request]
                             (swap! seen conj (json/parse-string (:body request) true))
                             (if (= 1 (count @seen))
                               {:status 503}
                               {:status 200 :body {:choices [{:message {:content "ok"}}]}}))]
      (is (= "ok" (:content (llm/invoke chain {:messages history})))))
    (is (= "file" (get-in (first @seen) [:messages 0 :content 1 :type])))
    (is (str/includes? (get-in (second @seen) [:messages 0 :content]) "Local file:"))))

(deftest model-override-enables-native-media-only-for-that-model
  (let [bodies (atom [])
        client (provider/create-openrouter-provider
                {:model "plain" :models {"native" {:input-modalities #{:text :file}}}})]
    (with-redefs [http/post (fn [_ request]
                             (swap! bodies conj (json/parse-string (:body request) true))
                             {:status 200 :body {:choices [{:message {:content "ok"}}]}})]
      (llm/invoke client {:messages history})
      (llm/invoke client {:messages history :model "native"}))
    (is (string? (get-in (first @bodies) [:messages 0 :content])))
    (is (= "file" (get-in (second @bodies) [:messages 0 :content 1 :type])))))

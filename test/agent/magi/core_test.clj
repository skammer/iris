(ns agent.magi.core-test
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.magi.core :as magi]
   [agent.magi.file-review :as file-review]
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord StaticProvider [response requests]
  llm/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    {:role "assistant"
     :content (if (string? response) response (json/generate-string response))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- provider [response]
  (->StaticProvider response (atom [])))

(defrecord ScriptedProvider [responses requests]
  llm/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    (let [response (first (first (swap-vals! responses rest)))]
      (if (:tool-calls response)
        (merge {:role "assistant"
                :content ""
                :usage nil
                :raw nil}
               response)
        {:role "assistant"
         :content (if (string? response) response (json/generate-string response))
         :tool-calls []
         :usage nil
         :raw nil})))
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- scripted-provider [responses]
  (->ScriptedProvider (atom responses) (atom [])))

(defn- service [responses]
  (magi/create-service
   (assoc config/default-config :magi {:timeout-ms 1000})
   {:providers (into {}
                    (map (fn [[role response]]
                           [role (provider response)]))
                    responses)}))

(deftest judge-response-mapping-test
  (is (= :error (:decision (magi/judge-responses {:melchior {:response :yes}
                                                  :balthasar {:response :error}
                                                  :casper {:response :yes}}))))
  (is (= :info (:decision (magi/judge-responses {:melchior {:response :yes}
                                                 :balthasar {:response :info}
                                                 :casper {:response :yes}}))))
  (is (= :no (:decision (magi/judge-responses {:melchior {:response :yes}
                                               :balthasar {:response :no}
                                               :casper {:response :conditional}}))))
  (is (= :conditional (:decision (magi/judge-responses {:melchior {:response :yes}
                                                        :balthasar {:response :conditional}
                                                        :casper {:response :yes}}))))
  (is (= :yes (:decision (magi/judge-responses {:melchior {:response :yes}
                                                :balthasar {:response :yes}
                                                :casper {:response :yes}})))))

(deftest decide-runs-filter-triumvirate-and-judge-test
  (let [svc (service {:filter {:kind "yes-no"
                               :domain "tool-approval"
                               :risk "low"
                               :question "Allow?"
                               :expected_response "permit"
                               :context {}}
                      :melchior {:response "yes" :comment "ok"}
                      :balthasar {:response "yes" :comment "ok"}
                      :casper {:response "yes" :comment "ok"}
                      :judge {:decision "yes" :reason "all yes"}})
        result (magi/decide svc {:question "Allow?" :context {}})]
    (is (= :yes (:decision result)))
    (is (= [:melchior :balthasar :casper] (keys (:agents result))))
    (is (= :yes-no (get-in result [:filter :kind])))))

(deftest filter-cannot-drop-supplied-context-test
  (let [melchior-requests (atom [])
        svc (magi/create-service
             (assoc config/default-config :magi {:timeout-ms 1000})
             {:providers {:filter (->StaticProvider {:kind "yes-no"
                                                      :domain "tool-approval"
                                                      :risk "low"
                                                      :question "Allow?"
                                                      :expected_response "permit"}
                                                     (atom []))
                          :melchior (->StaticProvider {:response "yes"} melchior-requests)
                          :balthasar (provider {:response "yes"})
                          :casper (provider {:response "yes"})
                          :judge (provider {:decision "yes" :reason "all yes"})}})
        request-context {:user-request "check my public ip"
                         :recent-messages [{:role "user"
                                            :content "check my public ip"}]}
        result (magi/decide svc {:question "Allow?"
                                 :context {:tool-name "shell"
                                           :input {:argv ["curl" "-s" "https://httpbin.org/ip"]}
                                           :request-context request-context}})]
    (is (= :yes (:decision result)))
    (is (= request-context
           (get-in result [:filter :context :request-context])))
    (is (= request-context
           (-> @melchior-requests
               first
               (get-in [:messages 1 :content])
               (json/parse-string true)
               (get-in [:context :request-context]))))))

(deftest approval-question-includes-request-context-test
  (let [request-context {:user-request "run curl"
                         :recent-messages [{:role "user" :content "run curl"}]}
        question (magi/approval-question {:id "approval-1"
                                          :requested-by "session-1"
                                          :requested-permissions #{:shell-exec}
                                          :reason "confirm ip"}
                                         {:name :shell
                                          :category :system
                                          :operation :execute
                                          :routing-categories [:shell]}
                                         {:argv ["curl" "-s" "https://httpbin.org/ip"]}
                                         {:user "session-1"
                                          :request-id "request-1"
                                          :magi-context request-context})]
    (is (= request-context (get-in question [:context :request-context])))
    (is (= {:argv ["curl" "-s" "https://httpbin.org/ip"]}
           (get-in question [:context :input])))))

(deftest malformed-agent-output-becomes-error-test
  (let [svc (service {:filter {:kind "yes-no"
                               :domain "tool-approval"
                               :risk "low"
                               :question "Allow?"
                               :expected_response "permit"
                               :context {}}
                      :melchior {:response "yes"}
                      :balthasar {:response "maybe"}
                      :casper {:response "yes"}
                      :judge {:decision "error" :reason "bad agent"}})
        result (magi/decide svc {:question "Allow?" :context {}})]
    (is (= :error (:decision result)))
    (is (= :error (get-in result [:agents :balthasar :response])))))

(deftest unsupported-filter-explains-context-budget-test
  (let [svc (service {:filter {:kind "unsupported"
                               :domain "memory-promotion"
                               :risk "low"
                               :question ""
                               :expected_response "opine"
                               :context {}}})
        result (magi/decide svc {:question "Review?"
                                 :context {:blob (apply str (repeat 20000 "x"))}})]
    (is (= :info (:decision result)))
    (is (= "input context exceeded MAGI budget" (:reason result)))))

(deftest provider-selection-falls-back-to-active-model-test
  (let [svc (magi/create-service config/default-config
                                 {:default-provider (provider {:kind "info"
                                                              :domain "policy"
                                                              :risk "low"
                                                              :question "x"
                                                              :expected_response "opine"
                                                              :context {}})})]
    (is (= {:provider :ollama :model "llama3.2:3b"}
           (get-in svc [:provider-selections :melchior])))))

(deftest file-review-gives-tools-only-to-triumvirate-and-forces-budget-verdict-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "iris-magi-review-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "review.clj")
        _ (spit file "(defn unsafe [] :fixed)\n")
        registry (reduce tools/register-tool
                         (tools/create-registry)
                         (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]}))
        filter-provider (provider {:kind "yes-no"
                                   :domain "policy"
                                   :risk "low"
                                   :question "Is the implementation correct?"
                                   :expected_response "opine"})
        melchior (scripted-provider
                  [{:tool-calls [{:id "read-1"
                                  :function {:name "fs_search"
                                             :arguments (json/generate-string
                                                         {:path (.getAbsolutePath root)
                                                          :query "unsafe"})}}]}
                   {:response "yes" :comment "review.clj:1 is fixed"}])
        balthasar (scripted-provider [{:response "yes" :comment "safe"}])
        casper (scripted-provider [{:response "yes" :comment "useful"}])
        judge-provider (provider {:decision "yes" :reason "all yes"})
        svc (magi/create-service
             (assoc config/default-config
                    :magi {:timeout-ms 1000
                           :file-review {:max-tool-calls 1
                                         :max-tool-rounds 1
                                         :timeout-ms 3000
                                         :max-evidence-chars 4000
                                         :max-tool-result-chars 2000}})
             {:providers {:filter filter-provider
                          :melchior melchior
                          :balthasar balthasar
                          :casper casper
                          :judge judge-provider}
              :tool-registry-fn (constantly registry)})
        result (magi/decide svc {:question "Review agent result"
                                 :file-review? true
                                 :context {:changed-files [(.getAbsolutePath file)]}})
        melchior-review (get-in result [:agents :melchior :file-review])]
    (try
      (is (= :yes (:decision result)))
      (is (= "succeeded" (get-in melchior-review [:trace 0 :status])))
      (is (= 1 (get-in melchior-review [:budget :calls])))
      (is (true? (get-in melchior-review [:budget :exhausted?])))
      (is (= #{"fs_list" "fs_read" "fs_search"}
             (->> (first @(:requests melchior))
                  :tools
                  (map #(get-in % [:function :name]))
                  set)))
      (is (nil? (:tools (second @(:requests melchior)))))
      (is (str/includes? (get-in (second @(:requests melchior))
                                 [:messages (dec (count (:messages (second @(:requests melchior)))) )
                                  :content])
                         "budget exhausted"))
      (is (nil? (:tools (first @(:requests filter-provider)))))
      (is (nil? (:tools (first @(:requests judge-provider)))))
      (is (not (str/includes? (pr-str (:trace melchior-review)) "(defn unsafe")))
      (finally
        (io/delete-file file true)
        (.delete root)))))

(deftest file-review-is-opt-in-test
  (let [providers {:filter (provider {:kind "yes-no"
                                      :domain "policy"
                                      :risk "low"
                                      :question "Allow?"
                                      :expected_response "permit"})
                   :melchior (provider {:response "yes"})
                   :balthasar (provider {:response "yes"})
                   :casper (provider {:response "yes"})
                   :judge (provider {:decision "yes" :reason "all yes"})}
        svc (magi/create-service config/default-config {:providers providers})
        result (magi/decide svc {:question "Allow?" :context {}})]
    (is (= :yes (:decision result)))
    (is (every? nil?
                (for [role [:melchior :balthasar :casper]
                      :let [request (first @(-> providers role :requests))]]
                  (:tools request))))
    (is (every? #(not (contains? % :file-review)) (vals (:agents result))))))

(deftest file-review-blocks-write-tools-even-when-registry-has-them-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "iris-magi-block-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "protected.txt")
        _ (spit file "unchanged")
        registry (reduce tools/register-tool
                         (tools/create-registry {:approval-check (fn [_] {:allow true})})
                         (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]}))
        provider (scripted-provider
                  [{:tool-calls [{:id "write-1"
                                  :function {:name "fs_write"
                                             :arguments (json/generate-string
                                                         {:path (.getAbsolutePath file)
                                                          :content "changed"})}}]}
                   {:response "no" :comment "write blocked"}])
        result (file-review/run!
                {:provider provider
                 :role :melchior
                 :system-prompt "Review."
                 :payload {:question "Safe?"}
                 :schema {:type "object"
                          :properties {:response {:type "string"}
                                       :comment {:type "string"}}
                          :required ["response"]}
                 :timeout-ms 1000
                 :config {:max-tool-calls 1
                          :max-tool-rounds 1
                          :max-evidence-chars 2000
                          :max-tool-result-chars 1000}
                 :tool-registry-fn (constantly registry)})]
    (try
      (is (= "no" (get-in result [:output :response])))
      (is (= "failed" (get-in result [:trace 0 :status])))
      (is (str/includes? (get-in result [:trace 0 :error]) "not allowed"))
      (is (= "unchanged" (slurp file)))
      (finally
        (io/delete-file file true)
        (.delete root)))))

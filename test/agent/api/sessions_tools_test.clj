(ns agent.api.sessions-tools-test
  (:require
   [agent.api.test-support :refer [free-port http-get http-post started-test-system temp-db-path]]
   [agent.api :as api]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as events]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest session-mode-api-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [created-body (-> (http-post (str base-url "/v1/sessions") {:title "mode"})
                             :body
                             (json/parse-string true))
            session-id (:id created-body)
            set-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                    {:mode "code"})
            set-body (json/parse-string (:body set-response) true)
            clear-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                      {:mode nil})
            clear-body (json/parse-string (:body clear-response) true)
            unknown-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                        {:mode "missing"})
            unknown-body (json/parse-string (:body unknown-response) true)
            missing-response (http-post (str base-url "/v1/sessions/missing/mode")
                                        {:mode "code"})
            missing-body (json/parse-string (:body missing-response) true)]
        (is (= 200 (:status set-response)))
        (is (= "code" (get-in set-body [:data :active_mode])))
        (is (= "code" (get-in set-body [:data :state :active_mode])))
        (is (= 200 (:status clear-response)))
        (is (contains? (:data clear-body) :active_mode))
        (is (nil? (get-in clear-body [:data :active_mode])))
        (is (= 400 (:status unknown-response)))
        (is (= "unknown_mode" (:error unknown-body)))
        (is (some #{"code"} (get-in unknown-body [:details :available_modes])))
        (is (= 404 (:status missing-response)))
        (is (= "session_not_found" (:error missing-body))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest session-project-api-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [create-response (http-post (str base-url "/v1/sessions")
                                       {:title "project" :project_id "Alpha.One"})
            created (json/parse-string (:body create-response) true)
            session-id (:id created)
            set-response (http-post (str base-url "/v1/sessions/" session-id "/project")
                                    {:project_id "beta"})
            set-body (json/parse-string (:body set-response) true)
            clear-response (http-post (str base-url "/v1/sessions/" session-id "/project")
                                      {:project_id nil})
            clear-body (json/parse-string (:body clear-response) true)
            invalid-response (http-post (str base-url "/v1/sessions/" session-id "/project")
                                        {:project_id "bad project!"})]
        (is (= 201 (:status create-response)))
        (is (= "alpha.one" (:project_id created)))
        (is (= "beta" (get-in set-body [:data :project_id])))
        (is (= 200 (:status clear-response)))
        (is (not (contains? (:data clear-body) :project_id)))
        (is (= 400 (:status invalid-response))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest chat-completions-accepts-rich-media-content-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(assoc-in % [:memory :notes :extractor :enabled] false))]
    (try
      (let [response (http-post (str base-url "/v1/chat/completions")
                                {:messages [{:role "user"
                                             :content [{:type "text"
                                                        :text "inspect"}
                                                       {:type "image_url"
                                                        :image_url {:url "data:image/png;base64,aGVsbG8="}}
                                                       {:type "file"
                                                        :file {:file_data "AAAA"
                                                               :media-type "video/mp4"
                                                               :filename "clip.mp4"}}]}]})
            user-message (last (filter #(= "user" (:role %))
                                       @(:messages* (:llm-provider system))))]
        (is (= 200 (:status response)))
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
               (:content user-message))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest slash-commands-api-lists-skill-catalog-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        root (.toFile (java.nio.file.Files/createTempDirectory "iris-api-skills-" (make-array java.nio.file.attribute.FileAttribute 0)))
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n")
        {:keys [system server]} (started-test-system path port #(assoc-in % [:skills :dirs] [(.getAbsolutePath root)]))]
    (try
      (let [response (http-get (str base-url "/v1/slash-commands?prefix=rev&page=1&page_size=10"))
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "iris.slash_commands_page" (:object body)))
        (is (= 1 (:total body)))
        (is (= "review" (get-in body [:items 0 :name]))))
      (finally
        (api/stop-server! server)
        (sqlite/close-store! (:store system))
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
        (io/delete-file path true)))))

(deftest api-tool-permissions-come-from-config-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                          path
                          port
                          #(assoc-in % [:tools :permissions :api] []))]
    (try
      (let [denied (http-post (str base-url "/v1/tools/fs_list/execute")
                              {:input {:path "."}
                               :permissions ["filesystem-read"]})]
        (is (= 403 (:status denied))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-tool-policy-exposes-approval-required-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                          path
                          port
                          #(-> %
                               (assoc-in [:tools :permissions :api] [:filesystem-read :filesystem-write])
                               (assoc-in [:tools :policy :blocklist] [])))]
    (try
      (let [write-denied (http-post (str base-url "/v1/tools/fs_write/execute")
                                    {:input {:path "target/api-tool-policy-test.txt"
                                             :content "blocked"}})
            write-denied-body (json/parse-string (:body write-denied) true)]
        (is (= 403 (:status write-denied)))
        (is (= "approval_required" (:error write-denied-body))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-tool-approval-permissions-are-validated-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                           path
                           port
                           #(-> %
                                (assoc-in [:tools :permissions :api] [])
                                (assoc-in [:tools :policy :blocklist] [])))]
    (try
      (let [fake-approval-read (http-post (str base-url "/v1/tools/fs_list/execute")
                                          {:input {:path "."}
                                           :approval_id "missing-approval"})
            shell-approval-create (http-post (str base-url "/v1/tool-approvals")
                                             {:tool "shell"
                                              :input {:argv ["printf" "approved-api"]}
                                              :requested_by "api"})
            shell-approval-id (get-in (json/parse-string (:body shell-approval-create) true) [:data :id])
            _shell-approval-approve (http-post (str base-url "/v1/tool-approvals/" shell-approval-id "/approve")
                                               {:actor "tester"})
            shell-approved-exec (http-post (str base-url "/v1/tools/shell/execute")
                                           {:input {:argv ["printf" "approved-api"]}
                                            :approval_id shell-approval-id})
            shell-approved-body (json/parse-string (:body shell-approved-exec) true)]
        (is (= 404 (:status fake-approval-read)))
        (is (= 200 (:status shell-approved-exec)))
        (is (= "approved-api" (get-in shell-approved-body [:data :stdout]))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-domain-errors-and-normalized-provider-models-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(-> %
                                       (assoc-in [:memory :vault :writable?] false)))]
    (try
      (let [session-id (:id (json/parse-string
                             (:body (http-post (str base-url "/v1/sessions") {:title "branches"}))
                             true))
            _ (events/log-event! system {:event-type :test.one
                                         :entity-type :test
                                         :entity-id "one"
                                         :payload {}})
            _ (events/log-event! system {:event-type :test.two
                                         :entity-type :test
                                         :entity-id "two"
                                         :payload {}})
            vault-missing (http-post (str base-url "/v1/memory/vault/read")
                                     {:path "memory/missing.md"})
            vault-read-only (http-post
                             (str base-url "/v1/memory/vault/propose-update")
                             {:note_id "missing"
                              :expected_revision "missing"
                              :changes {:body "blocked"}})
            unknown-provider-health (http-get (str base-url "/v1/providers/missing/health"))
            unknown-provider-models (http-get (str base-url "/v1/providers/missing/models"))
            provider-models (json/parse-string
                             (:body (http-get (str base-url "/v1/providers/ollama/models")))
                             true)
            missing-leaf (http-post (str base-url "/v1/sessions/" session-id "/leaf")
                                    {:entry_id "missing-entry"})
            limited-events (json/parse-string
                            (:body (http-get (str base-url "/v1/events?limit=1")))
                            true)]
        (is (= 404 (:status vault-missing)))
        (is (= 403 (:status vault-read-only)))
        (is (= 404 (:status unknown-provider-health)))
        (is (= 404 (:status unknown-provider-models)))
        (is (contains? (first (:data provider-models)) :model_id))
        (is (not (contains? (first (:data provider-models)) :model-id)))
        (is (= 404 (:status missing-leaf)))
        (is (= 1 (count (:data limited-events)))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

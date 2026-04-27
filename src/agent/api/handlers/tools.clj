(ns agent.api.handlers.tools
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(defn split-command-plain [command]
  (let [trimmed (str/trim (or command ""))]
    (when (str/blank? trimmed)
      (throw (errors/api-error 400 "bad_request" "command must be a non-blank string")))
    (vec (remove str/blank? (str/split trimmed #"\s+")))))

(defn split-command-optional [command]
  (let [trimmed (str/trim (or command ""))]
    (when-not (str/blank? trimmed)
      (split-command-plain trimmed))))

(defn tool-input-from-map [tool-name body]
  (case tool-name
    :fs (cond-> {:action (:action body)
                 :path (:path body)}
          (contains? body :content) (assoc :content (:content body)))
    :shell (cond-> {:argv (or (:argv body)
                              (split-command-plain (:command body)))}
             (not (str/blank? (:working_dir body))) (assoc :working-dir (:working_dir body)))
    (throw (errors/api-error 400 "bad_request" "Unsupported tool"))))

(defn configured-tool-permissions [system profile]
  (set (get-in system [:config :tools :permissions profile] #{})))

(defn execution-context [system profile tool-name input
                         {:keys [approval-id user request-id activity]}]
  (let [granted (if approval-id
                  (tool-approvals/granted-permissions tool-name input)
                  (configured-tool-permissions system profile))]
    (cond-> {:permissions granted
             :approval-id approval-id
             :yolo? (true? (get-in system [:config :tools :yolo?]))
             :user (or user "api")
             :request-id request-id}
      activity (assoc :activity activity))))

(defn list-tools [system _request]
  (responses/json-response 200
                           {:data (mapv ser/tool->response
                                        (tools/list-tools (:tool-registry system)))}))

(defn execute-tool [system request tool-name]
  (let [body (h/read-json-body request)
        input (:input body)
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (try
      (responses/json-response 200
                               {:data (tools/execute-tool (:tool-registry system)
                                                          tool-key
                                                          input
                                                          (execution-context system :api tool-key input
                                                                             {:approval-id approval-id
                                                                              :user "api"
                                                                              :activity (:activity body)}))})
      (catch Exception e
        (throw (errors/tool-error->api-error e))))))

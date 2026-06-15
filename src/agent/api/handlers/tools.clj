(ns agent.api.handlers.tools
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.service :as tool-service]
   [clojure.string :as str]))

(defn split-command-plain [command]
  (let [trimmed (str/trim (or command ""))]
    (when (str/blank? trimmed)
      (throw (errors/api-error 400 "bad_request" "command must be a non-blank string")))
    (vec (remove str/blank? (str/split trimmed #"\s+")))))

(defn tool-input-from-map [tool-name body]
  (case tool-name
    (:fs_read :fs_list :fs_delete :fs_mkdir) {:path (:path body)}
    (:fs_write :fs_create) (cond-> {:path (:path body)}
                             (contains? body :content) (assoc :content (:content body)))
    :fs_replace (cond-> {:path (:path body)
                         :old-string (:old_string body)
                         :new-string (:new_string body)}
                  (contains? body :replace_all) (assoc :replace-all? true))
    :shell (cond-> {:argv (or (:argv body)
                              (split-command-plain (:command body)))}
             (not (str/blank? (:working_dir body))) (assoc :working-dir (:working_dir body)))
    (throw (errors/api-error 400 "bad_request" "Unsupported tool"))))

(defn configured-tool-permissions [system profile]
  (tool-service/tool-permissions system profile))

(defn execution-context [system profile tool-name input
                         {:keys [approval-id user request-id activity]}]
  (let [user* (or user (name profile))
        approval-context {:user user*}
        approval (when approval-id
                   (tool-approvals/resolve-valid-request
                    (:store system)
                    approval-id
                    tool-name
                    input
                    approval-context))
        granted (if approval-id
                  (:permissions approval)
                  (configured-tool-permissions system profile))]
    (cond-> {:permissions granted
             :approval-id approval-id
             :yolo? (true? (get-in system [:config :tools :yolo?]))
             :user user*
             :request-id request-id}
      (get-in approval [:approval :decision-reason])
      (assoc :approval-reason (get-in approval [:approval :decision-reason]))

      activity (assoc :activity activity))))

(defn list-tools [system _request]
  (responses/json-response 200
                           {:data (mapv ser/tool->response
                                        (tool-service/list-tools system))}))

(defn execute-tool [system request tool-name]
  (let [body (h/read-json-body request)
        input (:input body)
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (responses/json-response 200
                             {:data (tool-service/execute-tool
                                     system
                                     tool-key
                                     input
                                     (execution-context system :api tool-key input
                                                        {:approval-id approval-id
                                                         :user "api"
                                                         :activity (:activity body)}))})))

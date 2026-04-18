(ns agent.federation.http
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]))

(defn create-forwarder
  ([] (create-forwarder {}))
  ([{:keys [timeout-ms inbox-path]
     :or {timeout-ms 10000
          inbox-path "/v1/federation/inbox"}}]
   (fn [{:keys [peer-id peer remote-agent-id envelope]}]
     (let [base-url (:base-url peer)
           url (str (str/replace (or base-url "") #"/+$" "") inbox-path)
           response (http/post url
                               {:socket-timeout timeout-ms
                                :connection-timeout timeout-ms
                                :content-type :json
                                :accept :json
                                :throw-exceptions false
                                :body (json/generate-string {:peer_id peer-id
                                                            :to_agent_ref remote-agent-id
                                                            :envelope envelope})})
           parsed (when (seq (:body response))
                    (json/parse-string (:body response) true))]
       {:status (:status response)
        :ok? (<= 200 (:status response) 299)
        :body parsed}))))

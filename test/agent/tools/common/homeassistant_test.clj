(ns agent.tools.common.homeassistant-test
  (:require
   [agent.tools.common.homeassistant :as ha-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- registry
  ([cfg] (registry cfg nil))
  ([cfg approval-check]
   (-> (tools/create-registry (cond-> {}
                              approval-check (assoc :approval-check approval-check)))
       (tools/register-tool (ha-tool/create-homeassistant-tool cfg)))))

(def configured-cfg
  {:base-url "http://ha.local:8123"
   :token "secret-token"
   :allowed-domains #{:light :switch :scene :script}
   :timeout-ms 5000})

(deftest homeassistant-get-state-builds-token-auth-request
  (let [requests (atom [])]
    (with-redefs [http/request (fn [request]
                                 (swap! requests conj request)
                                 {:status 200
                                  :headers {"content-type" "application/json"}
                                  :body "{\"state\":\"on\",\"entity_id\":\"light.kitchen\"}"})]
      (let [result (tools/execute-tool (registry configured-cfg)
                                       :homeassistant
                                       {:action "get_state"
                                        :entity_id "light.kitchen"}
                                       {:permissions #{:homeassistant}})
            request (first @requests)]
        (is (= :get (:method request)))
        (is (= "http://ha.local:8123/api/states/light.kitchen" (:url request)))
        (is (= "Bearer secret-token" (get-in request [:headers "Authorization"])))
        (is (= "on" (get-in result [:body :state])))
        (is (not (str/includes? (pr-str result) "secret-token")))))))

(deftest homeassistant-call-service-requires-approval-and-sends-entity-body
  (let [requests (atom [])
        input {:action "call_service"
               :domain "light"
               :service "turn_on"
               :entity_id "light.kitchen"
               :data {:brightness_pct 70}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires approval policy"
         (tools/execute-tool (registry configured-cfg)
                             :homeassistant
                             input
                             {:permissions #{:homeassistant}})))
    (with-redefs [http/request (fn [request]
                                 (swap! requests conj request)
                                 {:status 200
                                  :headers {"content-type" "application/json"}
                                  :body "[{\"entity_id\":\"light.kitchen\",\"state\":\"on\"}]"})]
      (let [result (tools/execute-tool (registry configured-cfg (constantly {:allow true}))
                                       :homeassistant
                                       input
                                       {:permissions #{:homeassistant}})
            request (first @requests)
            body (json/parse-string (:body request) true)]
        (is (= :post (:method request)))
        (is (= "http://ha.local:8123/api/services/light/turn_on" (:url request)))
        (is (= {:brightness_pct 70 :entity_id "light.kitchen"} body))
        (is (= "call_service" (:action result)))))))

(deftest homeassistant-denies-non-allowlisted-service-domain
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"not allowlisted"
       (tools/execute-tool (registry configured-cfg (constantly {:allow true}))
                           :homeassistant
                           {:action "call_service"
                            :domain "lock"
                            :service "unlock"
                            :entity_id "lock.front_door"}
                           {:permissions #{:homeassistant}}))))

(deftest homeassistant-service-calls-require-entity-by-default
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires entity_id"
       (tools/execute-tool (registry configured-cfg (constantly {:allow true}))
                           :homeassistant
                           {:action "call_service"
                            :domain "light"
                            :service "turn_on"}
                           {:permissions #{:homeassistant}}))))

(deftest homeassistant-description-keeps-read-actions-compact
  (let [description (tools/describe (ha-tool/create-homeassistant-tool configured-cfg))]
    (is (= :homeassistant (:name description)))
    (is (= :action (:action-key description)))
    (is (= #{:get_state :list_states :list_services}
           (:read-only-actions description)))
    (is (false? (:approval-sensitive? description)))))

(deftest homeassistant-health-redacts-token
  (let [tool (ha-tool/create-homeassistant-tool configured-cfg)
        health (tools/health-check tool)]
    (is (true? (:healthy health)))
    (is (= "http://ha.local:8123" (get-in health [:details :base-url])))
    (is (not (str/includes? (pr-str health) "secret-token")))))

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

(deftest homeassistant-list-states-returns-compact-limited-results
  (with-redefs [http/request (fn [_]
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body (json/generate-string
                                       [{:entity_id "sensor.plant_soil_moisture"
                                         :state "41"
                                         :attributes {:friendly_name "Plant soil moisture"
                                                      :device_class "moisture"
                                                      :unit_of_measurement "%"}
                                         :last_updated "2026-07-06T17:00:00Z"}
                                        {:entity_id "sensor.temperature"
                                         :state "22"
                                         :attributes {:friendly_name "Temperature"}}])})]
    (let [result (tools/execute-tool (registry configured-cfg)
                                     :homeassistant
                                     {:action "list_states"
                                      :limit 1}
                                     {:permissions #{:homeassistant}})]
      (is (= "list_states" (:action result)))
      (is (= {:entity-count 2
              :matched 2
              :returned 1
              :more_available true
              :limit 1
              :query ""
              :domain nil
              :device_class nil
              :entities [{:entity_id "sensor.plant_soil_moisture"
                          :state "41"
                          :friendly_name "Plant soil moisture"
                          :device_class "moisture"
                          :unit_of_measurement "%"
                          :last_updated "2026-07-06T17:00:00Z"}]}
             (:body result)))
      (is (= "homeassistant.list_states ok: returned 1/2 matched, total 2, limit 1, more true\nfilters: query=\"\" | domain=* | device_class=*\nsensor.plant_soil_moisture = 41 | Plant soil moisture | moisture | % | 2026-07-06T17:00:00Z"
             (:result-text result)))
      (is (not (str/includes? (pr-str result) ":attributes"))))))

(deftest homeassistant-get-states-returns-one-exact-compact-batch
  (let [requests (atom [])]
    (with-redefs [http/request (fn [request]
                                 (swap! requests conj request)
                                 {:status 200
                                  :headers {"content-type" "application/json"}
                                  :body (json/generate-string
                                         [{:entity_id "sensor.unrelated"
                                           :state "99"
                                           :attributes {:friendly_name "Unrelated"}}
                                          {:entity_id "sensor.plant_temperature"
                                           :state "24.6"
                                           :attributes {:friendly_name "Plant temperature"
                                                        :device_class "temperature"
                                                        :unit_of_measurement "°C"}}
                                          {:entity_id "sensor.plant_moisture"
                                           :state "41"
                                           :attributes {:friendly_name "Plant moisture"
                                                        :device_class "moisture"
                                                        :unit_of_measurement "%"}}])})]
      (let [result (tools/execute-tool (registry configured-cfg)
                                       :homeassistant
                                       {:action "get_states"
                                        :entity_ids ["sensor.plant_moisture"
                                                     "sensor.missing"
                                                     "sensor.plant_temperature"]}
                                       {:permissions #{:homeassistant}})]
        (is (= 1 (count @requests)))
        (is (= "http://ha.local:8123/api/states" (:url (first @requests))))
        (is (= {:requested 3
                :returned 2
                :missing ["sensor.missing"]
                :entities [{:entity_id "sensor.plant_moisture"
                            :state "41"
                            :friendly_name "Plant moisture"
                            :device_class "moisture"
                            :unit_of_measurement "%"}
                           {:entity_id "sensor.plant_temperature"
                            :state "24.6"
                            :friendly_name "Plant temperature"
                            :device_class "temperature"
                            :unit_of_measurement "°C"}]}
               (:body result)))
        (is (= "homeassistant.get_states ok: returned 2/3 requested, missing 1\nmissing: sensor.missing\nsensor.plant_moisture = 41 | Plant moisture | moisture | %\nsensor.plant_temperature = 24.6 | Plant temperature | temperature | °C"
               (:result-text result)))
        (is (not (str/includes? (pr-str result) ":attributes")))))))

(deftest homeassistant-search-states-filters-compact-results
  (with-redefs [http/request (fn [_]
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body (json/generate-string
                                       [{:entity_id "sensor.plant_soil_moisture"
                                         :state "41"
                                         :attributes {:friendly_name "Plant soil moisture"
                                                      :device_class "moisture"
                                                      :unit_of_measurement "%"}}
                                        {:entity_id "sensor.hall_temperature"
                                         :state "22"
                                         :attributes {:friendly_name "Hall temperature"
                                                      :device_class "temperature"
                                                      :unit_of_measurement "C"}}])})]
    (let [result (tools/execute-tool (registry configured-cfg)
                                     :homeassistant
                                     {:action "search_states"
                                      :query "soil moisture"
                                      :domain "sensor"
                                      :device_class "moisture"}
                                     {:permissions #{:homeassistant}})]
      (is (= "search_states" (:action result)))
      (is (= 1 (get-in result [:body :matched])))
      (is (= [{:entity_id "sensor.plant_soil_moisture"
               :state "41"
               :friendly_name "Plant soil moisture"
               :device_class "moisture"
               :unit_of_measurement "%"}]
             (get-in result [:body :entities])))
      (is (= "homeassistant.search_states ok: returned 1/1 matched, total 2, limit 25, more false\nfilters: query=\"soil moisture\" | domain=sensor | device_class=moisture\nsensor.plant_soil_moisture = 41 | Plant soil moisture | moisture | %"
             (:result-text result))))))

(deftest homeassistant-list-services-returns-compact-result-text
  (with-redefs [http/request (fn [_]
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body (json/generate-string
                                       [{:domain "light"
                                         :services {"turn_on" {:description "Turn on a light"}
                                                    "turn_off" {:description "Turn off a light"}}}
                                        {:domain "switch"
                                         :services {"toggle" {:description "Toggle a switch"}}}])})]
    (let [result (tools/execute-tool (registry configured-cfg)
                                     :homeassistant
                                     {:action "list_services"}
                                     {:permissions #{:homeassistant}})]
      (is (= "homeassistant.list_services ok: domains 2, services 3\nlight: turn_off, turn_on\nswitch: toggle"
             (:result-text result)))
      (is (= 2 (count (:body result)))))))

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
    (is (= #{:get_state :get_states :list_states :search_states :list_services}
           (:read-only-actions description)))
    (is (false? (:approval-sensitive? description)))))

(deftest homeassistant-health-redacts-token
  (let [tool (ha-tool/create-homeassistant-tool configured-cfg)
        health (tools/health-check tool)]
    (is (true? (:healthy health)))
    (is (= "http://ha.local:8123" (get-in health [:details :base-url])))
    (is (not (str/includes? (pr-str health) "secret-token")))))

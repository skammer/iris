(ns agent.wasm.bundles-test
  (:require
   [agent.config :as config]
   [agent.skills :as skills]
   [agent.tools.core :as tools]
   [agent.tools.service :as tool-service]
   [agent.wasm.bundles :as bundles]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def homeassistant-root "export/homeassistant-wasm-skill")

(defn- bundle-cfg []
  {:enabled? true
   :dev-roots [homeassistant-root]
   :enabled []
   :settings {"iris.homeassistant" {:ha_host "http://ha.local:8123"
                                    :ha_api_key "secret-token"
                                    :allowed_domains ["light"]
                                    :global_services []}}})

(defn- registry []
  (tool-service/create-tool-registry
   {:cfg (assoc (:tools config/default-config)
                :wasm-bundles (bundle-cfg))}))

(deftest discovers-homeassistant-bundle-test
  (let [bundle (first (bundles/discover-bundles (bundle-cfg)))]
    (is (= "iris.homeassistant" (:id bundle)))
    (is (= "homeassistant" (:name bundle)))
    (is (str/ends-with? (:module-path bundle) "module.wasm"))
    (is (= [:map {:closed true}
            [:action [:enum "get_state" "get_states" "list_states" "search_states" "list_services" "call_service"]]
            [:data {:optional true} [:map-of :any :any]]
            [:device_class {:optional true} :string]
            [:domain {:optional true} :string]
            [:entity_id {:optional true} :string]
            [:entity_ids {:optional true} [:vector {:min 1, :max 200} :string]]
            [:limit {:optional true} [:int {:min 1 :max 200}]]
            [:query {:optional true} :string]
            [:service {:optional true} :string]]
           (:input-schema bundle)))))

(deftest bundle-skill-is-visible-test
  (let [registry (skills/create-registry {:dirs []
                                          :bundle-dirs [homeassistant-root]})
        catalog (skills/skill-catalog registry)]
    (is (= ["homeassistant"] (mapv :name catalog)))
    (is (= :filesystem (:source (first catalog))))))

(deftest registers-homeassistant-tool-from-bundle-test
  (let [tool (tools/get-tool (registry) :homeassistant)
        description (tools/describe tool)]
    (is (some? tool))
    (is (= :wasm-bundle (:source description)))
    (is (= #{:wasm-execute} (:required-permissions description)))
    (is (= #{:get_state :get_states :list_states :search_states :list_services}
           (:read-only-actions description)))))

(deftest homeassistant-bundle-search-states-test
  (let [requests (atom [])]
    (with-redefs [http/request (fn [request]
                                 (swap! requests conj request)
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
                                                        :device_class "temperature"}}])})]
      (let [result (tools/execute-tool (registry)
                                       :homeassistant
                                       {:action "search_states"
                                        :query "soil"
                                        :domain "sensor"
                                        :limit 1}
                                       {:permissions #{:wasm-execute}})
            request (first @requests)]
        (is (= :get (:method request)))
        (is (= "http://ha.local:8123/api/states" (:url request)))
        (is (= "Bearer secret-token" (get-in request [:headers "Authorization"])))
        (is (= 1 (get-in result [:body :matched])))
        (is (str/includes? (:result-text result) "sensor.plant_soil_moisture"))))))

(deftest homeassistant-bundle-call-service-test
  (let [requests (atom [])]
    (with-redefs [http/request (fn [request]
                                 (swap! requests conj request)
                                 {:status 200
                                  :headers {"content-type" "application/json"}
                                  :body "[{\"entity_id\":\"light.kitchen\",\"state\":\"on\"}]"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Sensitive tool requires approved request"
           (tools/execute-tool (registry)
                               :homeassistant
                               {:action "call_service"
                                :domain "light"
                                :service "turn_on"
                                :entity_id "light.kitchen"}
                               {:permissions #{:wasm-execute}})))
      (let [result (tools/execute-tool (registry)
                                       :homeassistant
                                       {:action "call_service"
                                        :domain "light"
                                        :service "turn_on"
                                        :entity_id "light.kitchen"
                                        :data {:brightness_pct 70}}
                                       {:permissions #{:wasm-execute}
                                        :yolo? true})
            request (first @requests)
            body (json/parse-string (:body request) true)]
        (is (= :post (:method request)))
        (is (= "http://ha.local:8123/api/services/light/turn_on" (:url request)))
        (is (= {:brightness_pct 70 :entity_id "light.kitchen"} body))
        (is (= "call_service" (get-in result [:result :action])))))))

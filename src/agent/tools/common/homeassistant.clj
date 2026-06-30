(ns agent.tools.common.homeassistant
  "Home Assistant REST API bridge with config-held credentials and narrow
   service-call safety policy."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   [java.net URI]))

(def ^:private actions #{:get_state :list_states :list_services :call_service})
(def ^:private read-actions #{:get_state :list_states :list_services})
(def ^:private default-allowed-domains #{:light :switch :scene :script})

(defn- clean-string [value]
  (some-> value str str/trim not-empty))

(defn- normalize-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/replace (str/trim value) "-" "_")))
    :else value))

(defn- token-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) (some-> value clean-string str/lower-case)
    :else nil))

(defn- normalize-domain [value]
  (some-> value token-name keyword))

(defn- normalize-set [values default]
  (let [xs (set (keep normalize-domain values))]
    (if (seq xs) xs default)))

(defn- service-id [domain service]
  (str (name domain) "." (name service)))

(defn- normalize-global-services [values]
  (set (keep (fn [value]
               (some-> value str str/trim str/lower-case not-empty))
             values)))

(defn- configured? [config]
  (boolean (and (clean-string (:base-url config))
                (clean-string (:token config)))))

(defn- require-config! [config]
  (when-not (configured? config)
    (throw (tools/tool-error :homeassistant-not-configured
                             "Home Assistant base-url and token are required"
                             {:missing (cond-> []
                                         (not (clean-string (:base-url config))) (conj :base-url)
                                         (not (clean-string (:token config))) (conj :token))}))))

(defn- join-url [base path]
  (let [base* (if (str/ends-with? base "/") base (str base "/"))
        path* (if (str/starts-with? path "/") (subs path 1) path)]
    (str (.resolve (URI. base*) path*))))

(defn- json-response? [response]
  (some-> (or (get-in response [:headers "content-type"])
              (get-in response [:headers "Content-Type"]))
          str/lower-case
          (str/includes? "json")))

(defn- parse-body [response]
  (let [body (:body response)]
    (if (and (string? body) (json-response? response))
      (try
        (json/parse-string body true)
        (catch Exception e
          (throw (tools/tool-error :invalid-json-response
                                   "Home Assistant response declared JSON but body did not parse"
                                   {:message (.getMessage e)}))))
      body)))

(defn- request! [config method path & [{:keys [body timeout-ms]}]]
  (require-config! config)
  (let [timeout-ms* (long (or timeout-ms (:timeout-ms config) 10000))
        url (join-url (:base-url config) path)
        response (http/request (cond-> {:method method
                                        :url url
                                        :headers {"Authorization" (str "Bearer " (:token config))
                                                  "Content-Type" "application/json"}
                                        :socket-timeout timeout-ms*
                                        :conn-timeout timeout-ms*
                                        :throw-exceptions false}
                                 (some? body)
                                 (assoc :body (json/generate-string body)
                                        :content-type :json
                                        :accept :json)))
        status (:status response)
        parsed-body (parse-body response)]
    (if (<= 200 status 299)
      {:status status
       :body parsed-body}
      (throw (tools/tool-error :homeassistant-http-error
                               (str "Home Assistant request failed: " status)
                               {:status status
                                :body parsed-body
                                :path path
                                :method method})))))

(defn- validate-tokenless-url [value]
  (when-let [url (clean-string value)]
    (try
      (let [uri (URI. url)
            scheme (some-> (.getScheme uri) str/lower-case)]
        (when-not (#{"http" "https"} scheme)
          (throw (tools/validation-error "Home Assistant base-url must be http or https"
                                         {:base-url url})))
        url)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception _
        (throw (tools/validation-error "Home Assistant base-url must be an absolute URL"
                                       {:base-url url}))))))

(defn- service-name? [value]
  (boolean (and (string? value)
                (re-matches #"[A-Za-z0-9_]+" value))))

(defn- entity-id? [value]
  (boolean (and (string? value)
                (re-matches #"[A-Za-z0-9_.]+" value))))

(defn- validate-input [config input]
  (let [action (normalize-keyword (:action input))
        entity-id (clean-string (:entity_id input))
        domain (normalize-domain (:domain input))
        service (normalize-domain (:service input))
        data (or (:data input) {})]
    (when-not (contains? actions action)
      (throw (tools/validation-error "action must be a supported Home Assistant action"
                                     {:action (:action input)})))
    (when (and entity-id (not (entity-id? entity-id)))
      (throw (tools/validation-error "entity_id must look like domain.name"
                                     {:entity_id entity-id})))
    (case action
      :get_state
      (when-not entity-id
        (throw (tools/validation-error "get_state requires entity_id" {:input input})))

      :call_service
      (let [domain-name (token-name (:domain input))
            service-name (token-name (:service input))
            allowed-domains (normalize-set (:allowed-domains config) default-allowed-domains)
            global-services (normalize-global-services (:global-services config))]
        (when-not (and domain (service-name? domain-name))
          (throw (tools/validation-error "call_service requires domain"
                                         {:domain (:domain input)})))
        (when-not (and service (service-name? service-name))
          (throw (tools/validation-error "call_service requires service"
                                         {:service (:service input)})))
        (when-not (or (contains? allowed-domains :all)
                      (contains? allowed-domains domain))
          (throw (tools/tool-error :homeassistant-domain-not-allowed
                                   "Home Assistant domain is not allowlisted"
                                   {:domain (name domain)
                                    :allowed-domains (mapv name allowed-domains)})))
        (when-not (or entity-id
                      (contains? global-services (service-id domain service)))
          (throw (tools/validation-error "call_service requires entity_id unless the service is globally allowlisted"
                                         {:domain (name domain)
                                          :service (name service)}))))

      nil)
    (cond-> {:action action}
      entity-id (assoc :entity_id entity-id)
      domain (assoc :domain (name domain))
      service (assoc :service (name service))
      (seq data) (assoc :data data)
      (:timeout-ms input) (assoc :timeout-ms (:timeout-ms input)))))

(defn- service-body [{:keys [entity_id data]}]
  (cond-> (or data {})
    entity_id (assoc :entity_id entity_id)))

(defn create-homeassistant-tool [cfg]
  (let [config (merge {:enabled false
                       :timeout-ms 10000
                       :allowed-domains default-allowed-domains
                       :global-services #{}}
                      cfg)
        base-url (validate-tokenless-url (:base-url config))
        config* (cond-> config base-url (assoc :base-url base-url))]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :homeassistant
       "Home Assistant API bridge for states, services, and controlled service calls."
       :category :api
       :timeout-ms (:timeout-ms config*)
       :required-permissions #{:homeassistant}
       :input-schema [:map {:closed true}
                      [:action [:or
                                [:enum :get_state :list_states :list_services :call_service]
                                [:enum "get_state" "list_states" "list_services" "call_service"]]]
                      [:entity_id {:optional true} :string]
                      [:domain {:optional true} :string]
                      [:service {:optional true} :string]
                      [:data {:optional true} [:map-of :any :any]]
                      [:timeout-ms {:optional true} [:int {:min 1}]]]
       :operation :act
       :routing-categories #{:homeassistant :smart-home :api}
       :approval-sensitive? false
       :action-key :action
       :read-only-actions read-actions
       :parallel-safe-actions read-actions
       :sensitive (fn [input]
                    (= :call_service (normalize-keyword (:action input)))))
      :validate-fn (partial validate-input config*)
      :health-fn (fn []
                   {:healthy (configured? config*)
                    :details {:configured? (boolean (configured? config*))
                              :base-url (:base-url config*)
                              :timeout-ms (:timeout-ms config*)
                              :allowed-domains (mapv name (normalize-set (:allowed-domains config*) default-allowed-domains))}})
      :execute-fn
      (fn [{:keys [action entity_id domain service timeout-ms] :as input} _context]
        (case action
          :list_states
          (assoc (request! config* :get "/api/states" {:timeout-ms timeout-ms})
                 :action "list_states")

          :get_state
          (assoc (request! config* :get (str "/api/states/" entity_id) {:timeout-ms timeout-ms})
                 :action "get_state"
                 :entity_id entity_id)

          :list_services
          (assoc (request! config* :get "/api/services" {:timeout-ms timeout-ms})
                 :action "list_services")

          :call_service
          (assoc (request! config*
                           :post
                           (str "/api/services/" domain "/" service)
                           {:body (service-body input)
                            :timeout-ms timeout-ms})
                 :action "call_service"
                 :domain domain
                 :service service
                 :entity_id entity_id)))})))

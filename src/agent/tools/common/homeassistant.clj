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

(def ^:private actions #{:get_state :list_states :search_states :list_services :call_service})
(def ^:private read-actions #{:get_state :list_states :search_states :list_services})
(def ^:private default-allowed-domains #{:light :switch :scene :script})
(def ^:private default-state-limit 25)
(def ^:private max-state-limit 200)

(defn- clean-string [value]
  (some-> value str str/trim not-empty))

(defn- one-line [value]
  (some-> value str (str/replace #"\s+" " ") str/trim not-empty))

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

(defn- state-domain [state]
  (some-> (:entity_id state)
          str
          (str/split #"\." 2)
          first
          clean-string
          str/lower-case))

(defn- state-attribute [state key]
  (get-in state [:attributes key]))

(defn- state-summary [state]
  (let [friendly-name (state-attribute state :friendly_name)
        device-class (state-attribute state :device_class)
        unit (state-attribute state :unit_of_measurement)]
    (cond-> {:entity_id (:entity_id state)
             :state (:state state)}
      friendly-name (assoc :friendly_name friendly-name)
      device-class (assoc :device_class device-class)
      unit (assoc :unit_of_measurement unit)
      (:last_updated state) (assoc :last_updated (:last_updated state)))))

(defn- join-parts [& parts]
  (str/join " | " (keep one-line parts)))

(defn- state-summary-line [state]
  (let [summary (if (or (contains? state :friendly_name)
                        (not (contains? state :attributes)))
                  state
                  (state-summary state))
        entity-state (str (:entity_id summary) " = " (:state summary))
        details (join-parts (:friendly_name summary)
                            (:device_class summary)
                            (:unit_of_measurement summary)
                            (:last_updated summary))]
    (if (str/blank? details)
      entity-state
      (str entity-state " | " details))))

(defn- states-result-text [action body]
  (let [header (format "homeassistant.%s ok: returned %d/%d matched, total %d, limit %d, more %s"
                       (name action)
                       (:returned body)
                       (:matched body)
                       (:entity-count body)
                       (:limit body)
                       (boolean (:more_available body)))
        filters (join-parts (str "query=" (pr-str (or (:query body) "")))
                            (str "domain=" (or (:domain body) "*"))
                            (str "device_class=" (or (:device_class body) "*")))
        lines (map state-summary-line (:entities body))]
    (str/join "\n" (cond-> [header]
                     (not (str/blank? filters)) (conj (str "filters: " filters))
                     true (into lines)))))

(defn- service-names [domain-entry]
  (->> (:services domain-entry)
       keys
       (map name)
       sort
       vec))

(defn- services-result-text [response]
  (let [domains (vec (or (:body response) []))
        rows (mapv (fn [domain-entry]
                     (str (:domain domain-entry) ": "
                          (str/join ", " (service-names domain-entry))))
                   domains)
        service-count (reduce + (map (comp count service-names) domains))]
    (str/join "\n"
              (into [(format "homeassistant.list_services ok: domains %d, services %d"
                             (count domains)
                             service-count)]
                    rows))))

(defn- single-state-result-text [action response]
  (str "homeassistant." (name action) " ok: "
       (state-summary-line (:body response))))

(defn- call-service-result-text [{:keys [domain service entity_id status body]}]
  (let [states (when (sequential? body)
                 (map state-summary-line body))]
    (str/join "\n"
              (cond-> [(str "homeassistant.call_service ok: "
                            domain "." service
                            (when entity_id (str " " entity_id))
                            " status=" status)]
                (seq states) (into states)))))

(defn- query-terms [query]
  (->> (str/split (str/lower-case (or (clean-string query) "")) #"[^\p{L}\p{N}_]+")
       (keep clean-string)
       set))

(defn- state-haystack [state]
  (str/lower-case
   (str/join " "
             (keep identity
                   [(:entity_id state)
                    (:state state)
                    (state-attribute state :friendly_name)
                    (state-attribute state :device_class)
                    (state-attribute state :unit_of_measurement)]))))

(defn- state-matches-query? [terms state]
  (or (empty? terms)
      (let [haystack (state-haystack state)]
        (some #(str/includes? haystack %) terms))))

(defn- state-matches-domain? [domain state]
  (or (nil? domain)
      (= (name domain) (state-domain state))))

(defn- state-matches-device-class? [device-class state]
  (or (nil? device-class)
      (= (str/lower-case device-class)
         (some-> (state-attribute state :device_class) str/lower-case))))

(defn- state-limit [value]
  (max 1 (min max-state-limit (long (or value default-state-limit)))))

(defn- compact-states-response [response {:keys [query domain device_class limit]}]
  (let [states (vec (or (:body response) []))
        terms (query-terms query)
        matched (filterv #(and (state-matches-query? terms %)
                               (state-matches-domain? domain %)
                               (state-matches-device-class? device_class %))
                         states)
        limit* (state-limit limit)
        returned (subvec matched 0 (min limit* (count matched)))]
    (assoc response
           :body {:entity-count (count states)
                  :matched (count matched)
                  :returned (count returned)
                  :more_available (> (count matched) (count returned))
                  :limit limit*
                  :query (or query "")
                  :domain (some-> domain name)
                  :device_class device_class
                  :entities (mapv state-summary returned)})))

(defn- validate-input [config input]
  (let [action (normalize-keyword (:action input))
        entity-id (clean-string (:entity_id input))
        domain (normalize-domain (:domain input))
        service (normalize-domain (:service input))
        query (clean-string (:query input))
        device-class (some-> (:device_class input) clean-string str/lower-case)
        limit (:limit input)
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
      query (assoc :query query)
      device-class (assoc :device_class device-class)
      limit (assoc :limit limit)
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
       "Home Assistant API bridge. Use search_states with query/domain/device_class to find entities; list_states returns compact limited state summaries to avoid truncating full HA state dumps."
       :category :api
       :timeout-ms (:timeout-ms config*)
       :required-permissions #{:homeassistant}
       :input-schema [:map {:closed true}
                      [:action [:or
                                [:enum :get_state :list_states :search_states :list_services :call_service]
                                [:enum "get_state" "list_states" "search_states" "list_services" "call_service"]]]
                      [:entity_id {:optional true} :string]
                      [:query {:optional true} :string]
                      [:domain {:optional true} :string]
                      [:device_class {:optional true} :string]
                      [:limit {:optional true} [:int {:min 1 :max 200}]]
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
          (let [response (assoc (compact-states-response
                                  (request! config* :get "/api/states" {:timeout-ms timeout-ms})
                                  input)
                                :action "list_states")]
            (assoc response :result-text (states-result-text :list_states (:body response))))

          :search_states
          (let [response (assoc (compact-states-response
                                  (request! config* :get "/api/states" {:timeout-ms timeout-ms})
                                  input)
                                :action "search_states")]
            (assoc response :result-text (states-result-text :search_states (:body response))))

          :get_state
          (let [response (assoc (request! config* :get (str "/api/states/" entity_id) {:timeout-ms timeout-ms})
                                :action "get_state"
                                :entity_id entity_id)]
            (assoc response :result-text (single-state-result-text :get_state response)))

          :list_services
          (let [response (assoc (request! config* :get "/api/services" {:timeout-ms timeout-ms})
                                :action "list_services")]
            (assoc response :result-text (services-result-text response)))

          :call_service
          (let [response (assoc (request! config*
                                           :post
                                           (str "/api/services/" domain "/" service)
                                           {:body (service-body input)
                                            :timeout-ms timeout-ms})
                                :action "call_service"
                                :domain domain
                                :service service
                                :entity_id entity_id)]
            (assoc response :result-text (call-service-result-text response)))))})))

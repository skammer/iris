(ns agent.tools.common.http
  "HTTP request tool with method allowlisting, bounded response handling, JSON
   parsing, and private-address protection for agent-initiated network calls."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   [java.net Inet4Address Inet6Address InetAddress URI UnknownHostException]
   [org.apache.http.conn DnsResolver]))

(def ^:private allowed-methods
  #{:get :post :put :patch :delete :head})

(defn- normalize-method [method]
  (cond
    (keyword? method) method
    (string? method) (keyword (str/lower-case method))
    :else nil))

(defn- header-value [headers header-name]
  (let [target (str/lower-case header-name)]
    (some (fn [[k v]]
            (when (= target (str/lower-case (name k))) v))
          headers)))

(defn- json-response? [response]
  (some-> response
          :headers
          (header-value "content-type")
          str/lower-case
          (str/includes? "json")))

(defn- parse-response-body [response]
  (let [body (:body response)]
    (if (and (string? body) (json-response? response))
      (try
        (json/parse-string body true)
        (catch Exception e
          (throw (tools/tool-error :invalid-json-response
                                   "HTTP response declared JSON but body did not parse"
                                   {:message (.getMessage e)}))))
      body)))

(defn- parse-uri! [url]
  (try
    (URI. url)
    (catch Exception _
      (throw (tools/validation-error "url must be an absolute HTTP(S) URL"
                                     {:url url})))))

(defn- ipv4-octets [^Inet4Address address]
  (mapv #(bit-and 0xff %) (.getAddress address)))

(defn- private-ipv4-octets? [[a b _c _d]]
  (or (= a 0)
      (= a 10)
      (= a 127)
      (= [169 254] [a b])
      (and (= a 172) (<= 16 b 31))
      (= [192 168] [a b])
      (and (= a 100) (<= 64 b 127))
      (<= 224 a 239)))

(defn- ipv4-mapped-octets [^Inet6Address address]
  (let [bytes (.getAddress address)]
    (when (and (every? zero? (map #(bit-and 0xff (aget bytes %)) (range 10)))
               (= 0xff (bit-and 0xff (aget bytes 10)))
               (= 0xff (bit-and 0xff (aget bytes 11))))
      (mapv #(bit-and 0xff (aget bytes %)) (range 12 16)))))

(defn- private-ip? [^InetAddress address]
  (or (.isAnyLocalAddress address)
      (.isLoopbackAddress address)
      (.isLinkLocalAddress address)
      (.isSiteLocalAddress address)
      (.isMulticastAddress address)
      (and (instance? Inet4Address address)
           (private-ipv4-octets? (ipv4-octets address)))
      (and (instance? Inet6Address address)
           (or (some-> address ipv4-mapped-octets private-ipv4-octets?)
               (let [first-byte (bit-and 0xff (aget (.getAddress address) 0))]
                 (= 0xfc (bit-and 0xfe first-byte)))))))

(defn- default-resolve-host [host]
  (vec (InetAddress/getAllByName host)))

(def ^:private default-config
  {:default-headers {"User-Agent" "iris/0.1"}
   :timeout-ms 30000
   :max-timeout-ms 30000
   :max-response-bytes 1048576
   :allow-private? false
   :max-redirects 3
   :parallel-safe-read-methods? false
   :resolve-host-fn default-resolve-host})

(defn- response-location [response]
  (or (get-in response [:headers "Location"])
      (get-in response [:headers "location"])))

(defn- validate-url! [config url]
  (let [uri (parse-uri! url)
        scheme (some-> (.getScheme uri) str/lower-case)
        host (.getHost uri)]
    (when-not (#{"http" "https"} scheme)
      (throw (tools/validation-error "url scheme must be http or https"
                                     {:url url
                                      :scheme scheme})))
    (when (str/blank? host)
      (throw (tools/validation-error "url host is required" {:url url})))
    (let [addresses (vec ((:resolve-host-fn config) host))]
      (when (empty? addresses)
        (throw (tools/validation-error "url host did not resolve" {:url url
                                                                   :host host})))
      (when-not (:allow-private? config)
        (when-let [blocked (some #(when (private-ip? %) %) addresses)]
          (throw (tools/tool-error :url-not-allowed
                                   "URL resolves to non-public address"
                                   {:url url
                                    :host host
                                    :address (.getHostAddress ^InetAddress blocked)}))))
      {:url url
       :host host
       :addresses addresses})))

(defn validate-public-url!
  "Validate an HTTP(S) URL and reject private-address resolutions. Returns the
   resolved target so other network tools can reuse the same security policy."
  ([url]
   (validate-public-url! {} url))
  ([opts url]
   (validate-url! (merge default-config opts {:allow-private? false}) url)))

(defn- pinned-dns-resolver [host addresses]
  (let [host* (str/lower-case host)
        addresses* (into-array InetAddress addresses)]
    (reify DnsResolver
      (^"[Ljava.net.InetAddress;" resolve [_ ^String requested-host]
       (if (= host* (str/lower-case requested-host))
         (aclone addresses*)
         (throw (UnknownHostException. requested-host)))))))

(defn- validate-input [input]
  (let [url (:url input)
        method (normalize-method (or (:method input) :get))]
    (when-not (and (string? url) (not (str/blank? url)))
      (throw (tools/validation-error "url must be a non-blank string" {:input input})))
    (when-not (allowed-methods method)
      (throw (tools/validation-error "method must be a supported HTTP verb" {:method (:method input)})))
    (-> input
        (assoc :url url)
        (assoc :method method))))

(defn create-http-tool
  [opts]
  (let [config (merge default-config opts)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :http
       "Raw HTTP client for JSON APIs, RSS, raw files, and explicit text endpoints. Use web_search/web_extract for web research and HTML pages."
       :category :api
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:http-request}
       :input-schema [:map {:closed true}
                      [:url :string]
                      [:method {:optional true}
                       [:or
                        [:enum :get :post :put :patch :delete :head]
                        [:enum "get" "post" "put" "patch" "delete" "head"
                         "GET" "POST" "PUT" "PATCH" "DELETE" "HEAD"]]]
                      [:headers {:optional true} [:map-of :string :string]]
                      [:params {:optional true} [:map-of :any :any]]
                      [:body {:optional true} :any]
                      [:timeout-ms {:optional true} [:int {:min 1}]]]
       :operation :act
       :approval-sensitive? false
       :action-key :method
       :read-only-actions (when (:parallel-safe-read-methods? config) #{:get :head})
       :parallel-safe-actions (when (:parallel-safe-read-methods? config) #{:get :head}))
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                   :details {:timeout-ms (:timeout-ms config)
                              :max-timeout-ms (:max-timeout-ms config)
                              :max-response-bytes (:max-response-bytes config)
                              :allow-private? (:allow-private? config)
                              :max-redirects (:max-redirects config)}})
      :execute-fn
      (fn [input _context]
        (let [timeout-ms (min (long (or (:timeout-ms input) (:timeout-ms config)))
                              (long (:max-timeout-ms config)))
              request-opts (fn [{:keys [url host addresses]}]
                             (cond-> {:method (:method input)
                                      :url url
                                      :headers (merge (:default-headers config) (:headers input))
                                      :query-params (:params input)
                                      :socket-timeout timeout-ms
                                      :conn-timeout timeout-ms
                                      :dns-resolver (pinned-dns-resolver host addresses)
                                      :follow-redirects false
                                      :throw-exceptions false}
                               (contains? input :body)
                               (assoc :body (json/generate-string (:body input))
                                      :content-type :json
                                      :accept :json)))
              response (loop [url (:url input)
                              redirects-left (:max-redirects config)]
                         (let [target (validate-url! config url)
                               response (http/request (request-opts target))
                               status (:status response)]
                           (if (and (<= 300 status 399)
                                    (response-location response))
                             (do
                               (when (zero? redirects-left)
                                 (throw (tools/tool-error :too-many-redirects
                                                          "HTTP redirect limit exceeded"
                                                          {:url url
                                                           :max-redirects (:max-redirects config)})))
                               (let [next-url (str (.resolve (URI. url)
                                                              (response-location response)))]
                                 (recur next-url (dec redirects-left))))
                             response)))
              status (:status response)
              body-size (alength (.getBytes (str (:body response)) "UTF-8"))
              _ (when (> body-size (:max-response-bytes config))
                  (throw (tools/tool-error :response-too-large
                                           "HTTP response exceeds max-response-bytes"
                                           {:url (:url input)
                                            :size body-size
                                            :max-response-bytes (:max-response-bytes config)})))
              parsed-body (parse-response-body response)]
          (if (<= 200 status 299)
            {:status status
             :headers (:headers response)
             :body parsed-body}
            (throw (tools/tool-error
                    :http-error
                    (str "HTTP request failed: " status)
                    {:status status
                     :body parsed-body
                     :url (:url input)
                     :method (:method input)})))))})))

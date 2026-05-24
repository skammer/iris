(ns agent.tools.common.http
  "Rewritten HTTP tool."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   [java.net Inet4Address Inet6Address InetAddress URI]))

(def ^:private allowed-methods
  #{:get :post :put :patch :delete :head})

(defn- normalize-method [method]
  (cond
    (keyword? method) method
    (string? method) (keyword (str/lower-case method))
    :else nil))

(defn- parse-response-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn- parse-uri! [url]
  (try
    (URI. url)
    (catch Exception _
      (throw (tools/validation-error "url must be an absolute HTTP(S) URL"
                                     {:url url})))))

(defn- ipv4-octets [^Inet4Address address]
  (mapv #(bit-and 0xff %) (.getAddress address)))

(defn- private-ip? [^InetAddress address]
  (or (.isAnyLocalAddress address)
      (.isLoopbackAddress address)
      (.isLinkLocalAddress address)
      (.isSiteLocalAddress address)
      (.isMulticastAddress address)
      (and (instance? Inet4Address address)
           (let [[a b] (ipv4-octets address)]
             (or (= [169 254] [a b])
                 (and (= a 100) (<= 64 b 127)))))
      (and (instance? Inet6Address address)
           (let [first-byte (bit-and 0xff (aget (.getAddress address) 0))]
             (= 0xfc (bit-and 0xfe first-byte))))))

(defn- default-resolve-host [host]
  (vec (InetAddress/getAllByName host)))

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
    (when-not (:allow-private? config)
      (let [addresses ((:resolve-host-fn config) host)]
        (when (empty? addresses)
          (throw (tools/validation-error "url host did not resolve" {:url url
                                                                     :host host})))
        (when-let [blocked (some #(when (private-ip? %) %) addresses)]
          (throw (tools/tool-error :url-not-allowed
                                   "URL resolves to non-public address"
                                   {:url url
                                    :host host
                                    :address (.getHostAddress ^InetAddress blocked)})))))
    url))

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
  (let [config (merge {:default-headers {"User-Agent" "iris/0.1"}
                       :timeout-ms 30000
                       :max-timeout-ms 30000
                       :max-response-bytes 1048576
                       :allow-private? false
                       :max-redirects 3
                       :parallel-safe-read-methods? false
                       :resolve-host-fn default-resolve-host}
                      opts)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :http
       "HTTP client tool"
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
                      [:timeout-ms {:optional true} [:int {:min 1}]]])
       :operation :act
       :approval-sensitive? false
       :action-key :method
       :read-only-actions (when (:parallel-safe-read-methods? config) #{:get :head})
       :parallel-safe-actions (when (:parallel-safe-read-methods? config) #{:get :head})
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
              request-opts (fn [url]
                             (cond-> {:method (:method input)
                                      :url url
                                      :headers (merge (:default-headers config) (:headers input))
                                      :query-params (:params input)
                                      :socket-timeout timeout-ms
                                      :conn-timeout timeout-ms
                                      :follow-redirects false
                                      :throw-exceptions false}
                               (contains? input :body)
                               (assoc :body (json/generate-string (:body input))
                                      :content-type :json
                                      :accept :json)))
              response (loop [url (:url input)
                              redirects-left (:max-redirects config)]
                         (validate-url! config url)
                         (let [response (http/request (request-opts url))
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
                                 (validate-url! config next-url)
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
              parsed-body (some-> (:body response) parse-response-body)]
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

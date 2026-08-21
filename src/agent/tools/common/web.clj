(ns agent.tools.common.web
  "Provider-independent web search and clean page extraction. Tries Tavily,
   then local Searcharvester without exposing provider credentials or routing
   controls to the model."
  (:require
   [agent.tools.common.http :as http-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.net URI]))

(def default-config
  {:enabled true
   :provider-order [:tavily :searchharvester]
   :timeout-ms 30000
   :max-response-bytes 1048576
   :extract-max-chars 7000
   :cache-max-entries 256
   :searchharvester {:enabled true
                     :base-url "http://127.0.0.1:8000"}
   :tavily {:enabled true
            :base-url "https://api.tavily.com"
            :api-key nil
            :api-key-file "~/.config/iris/secrets/tavily-api-key"}})

(defn normalize-config [opts]
  (let [opts* (or opts {})]
    (-> default-config
        (merge opts*)
        (assoc :searchharvester (merge (:searchharvester default-config)
                                       (:searchharvester opts*)))
        (assoc :tavily (merge (:tavily default-config)
                              (:tavily opts*))))))

(defn- nonblank [value]
  (when (some? value)
    (let [value* (str/trim (str value))]
      (when-not (str/blank? value*) value*))))

(defn- expand-home [path]
  (let [path* (str path)
        home (System/getProperty "user.home")]
    (cond
      (= path* "~") home
      (str/starts-with? path* "~/") (str home (subs path* 1))
      :else path*)))

(defn- read-key-file [path]
  (when-let [path* (nonblank path)]
    (let [file (io/file (expand-home path*))]
      (when (.isFile file)
        (nonblank (slurp file))))))

(defn- tavily-api-key [config]
  (let [tavily (:tavily config)]
    (or (nonblank (:api-key tavily))
        (nonblank (System/getenv "TAVILY_API_KEY"))
        (read-key-file (or (System/getenv "TAVILY_API_KEY_FILE")
                           (:api-key-file tavily))))))

(defn- parse-json-response! [config provider response]
  (let [status (long (or (:status response) 0))
        body (str (or (:body response) ""))
        body-size (alength (.getBytes body "UTF-8"))]
    (when (> body-size (:max-response-bytes config))
      (throw (tools/tool-error :response-too-large
                               "Web provider response exceeds max-response-bytes"
                               {:provider provider
                                :size body-size
                                :max-response-bytes (:max-response-bytes config)})))
    (when-not (<= 200 status 299)
      (throw (tools/tool-error :web-provider-error
                               (str (name provider) " request failed: HTTP " status)
                               {:provider provider
                                :status status
                                :body-preview (subs body 0 (min 500 (count body)))})))
    (try
      (json/parse-string body true)
      (catch Exception e
        (throw (tools/tool-error :invalid-json-response
                                 (str (name provider) " returned invalid JSON")
                                 {:provider provider
                                  :message (.getMessage e)}))))))

(defn- post-json! [config provider url payload headers]
  (let [timeout-ms (long (:timeout-ms config))
        response (client/post url
                              {:headers (merge {"Content-Type" "application/json"
                                                "User-Agent" "iris-web-tool/1.0"}
                                               headers)
                               :body (json/generate-string payload)
                               :socket-timeout timeout-ms
                               :conn-timeout timeout-ms
                               :throw-exceptions false})]
    (parse-json-response! config provider response)))

(defn- provider-url [config provider endpoint]
  (str (str/replace (get-in config [provider :base-url]) #"/$" "") endpoint))

(defn- searchharvester-request! [config endpoint payload]
  (when-not (true? (get-in config [:searchharvester :enabled]))
    (throw (tools/tool-error :provider-unavailable
                             "Searcharvester provider is disabled"
                             {:provider :searchharvester})))
  (post-json! config
              :searchharvester
              (provider-url config :searchharvester endpoint)
              payload
              {}))

(defn- tavily-request! [config endpoint payload]
  (when-not (true? (get-in config [:tavily :enabled]))
    (throw (tools/tool-error :provider-unavailable
                             "Tavily provider is disabled"
                             {:provider :tavily})))
  (let [api-key (tavily-api-key config)]
    (when-not api-key
      (throw (tools/tool-error :provider-unavailable
                               "Tavily API key is not configured"
                               {:provider :tavily})))
    (post-json! config
                :tavily
                (provider-url config :tavily endpoint)
                payload
                {"Authorization" (str "Bearer " api-key)})))

(defn- normalize-search-results [results]
  (->> results
       (filter map?)
       (mapv (fn [result]
               (cond-> {:title (:title result)
                        :url (:url result)
                        :content (:content result)}
                 (some? (:score result)) (assoc :score (:score result)))))
       (filterv #(and (nonblank (:url %))
                      (or (nonblank (:title %)) (nonblank (:content %)))))))

(defn- search-with-provider! [config provider {:keys [query max-results depth topic]}]
  (case provider
    :searchharvester
    (let [response (searchharvester-request! config "/search"
                                              {:query query
                                               :max_results max-results})
          results (normalize-search-results (:results response))]
      (when (empty? results)
        (throw (tools/tool-error :empty-web-results
                                 "Searcharvester returned no search results"
                                 {:provider provider :query query})))
      {:provider "searchharvester"
       :query (or (:query response) query)
       :results results})

    :tavily
    (let [response (tavily-request! config "/search"
                                     {:query query
                                      :max_results max-results
                                      :search_depth (name depth)
                                      :topic (name topic)
                                      :include_images false
                                      :include_answer false})
          results (normalize-search-results (:results response))]
      (when (empty? results)
        (throw (tools/tool-error :empty-web-results
                                 "Tavily returned no search results"
                                 {:provider provider :query query})))
      (cond-> {:provider "tavily"
               :query (or (:query response) query)
               :results results}
        (:response_time response) (assoc :response-time (:response_time response))
        (:request_id response) (assoc :request-id (:request_id response))))

    (throw (tools/validation-error "unsupported web provider" {:provider provider}))))

(defn- route! [providers attempt-fn]
  (letfn [(attempt [[provider & remaining] errors]
            (when-not provider
              (throw (tools/tool-error
                      :web-providers-failed
                      "All configured web providers failed"
                      {:provider-errors errors})))
            (try
              (attempt-fn provider)
              (catch Exception error
                (attempt remaining
                         (conj errors {:provider provider
                                       :error (ex-message error)})))))]
    (attempt providers [])))

(defn normalize-url [url]
  (let [uri (URI. (str/trim url))
        scheme (some-> (.getScheme uri) str/lower-case)
        host (some-> (.getHost uri) str/lower-case)
        port (.getPort uri)
        port* (if (or (and (= scheme "http") (= port 80))
                      (and (= scheme "https") (= port 443)))
                -1
                port)
        path (or (nonblank (.getRawPath uri)) "/")
        path* (if (and (> (count path) 1) (str/ends-with? path "/"))
                (str/replace path #"/+$" "")
                path)
        host* (if (and host (str/includes? host ":")) (str "[" host "]") host)]
    (str scheme "://" host*
         (when (not= -1 port*) (str ":" port*))
         path*
         (when-let [query (.getRawQuery uri)] (str "?" query)))))

(defn- validate-extract-input [config input]
  (let [url (nonblank (:url input))]
    (when-not url
      (throw (tools/validation-error "url must be a non-blank string" {:input input})))
    (http-tool/validate-public-url! (select-keys config [:resolve-host-fn]) url)
    (assoc input :url (normalize-url url))))

(defn- bounded-content [content max-chars]
  (let [content* (str (or content ""))
        chars (count content*)
        truncated? (and (pos? max-chars) (> chars max-chars))]
    {:content (if truncated? (subs content* 0 max-chars) content*)
     :chars chars
     :returned-chars (if truncated? max-chars chars)
     :truncated truncated?}))

(defn- normalized-extract-result [provider result max-chars]
  (let [content (or (:content result) (:raw_content result))]
    (when-not (nonblank content)
      (throw (tools/tool-error :empty-web-content
                               (str (name provider) " returned no extracted content")
                               {:provider provider :url (:url result)})))
    (merge {:provider (name provider)
            :url (:url result)
            :title (:title result)}
           (bounded-content content max-chars))))

(defn- extract-with-provider!
  [config provider {:keys [url size query chunks-per-source max-chars]}]
  (case provider
    :searchharvester
    (let [response (searchharvester-request! config "/extract"
                                              {:url url :size (name size)})]
      (normalized-extract-result provider response max-chars))

    :tavily
    (let [payload (cond-> {:urls [url]
                           :extract_depth "advanced"
                           :format "markdown"
                           :include_images false
                           :timeout (/ (:timeout-ms config) 1000.0)}
                    query (assoc :query query
                                 :chunks_per_source chunks-per-source))
          response (tavily-request! config "/extract" payload)
          result (first (:results response))]
      (when-not result
        (throw (tools/tool-error :empty-web-content
                                 "Tavily returned no extracted result"
                                 {:provider provider
                                  :url url
                                  :failed-results (:failed_results response)})))
      (cond-> (normalized-extract-result provider result max-chars)
        (:response_time response) (assoc :response-time (:response_time response))
        (:request_id response) (assoc :request-id (:request_id response))))

    (throw (tools/validation-error "unsupported web provider" {:provider provider}))))

(defn- cache-key [context input]
  [(:request-id context)
   (select-keys input [:url :size :query :chunks-per-source :max-chars])])

(defn- cache-get [cache key]
  (get-in @cache [:entries key]))

(defn- cache-put! [cache max-entries key value]
  (swap! cache
         (fn [{:keys [order entries]}]
           (let [new-key? (not (contains? entries key))
                 order* (cond-> (vec order) new-key? (conj key))
                 entries* (assoc entries key value)
                 overflow (max 0 (- (count order*) max-entries))
                 evicted (take overflow order*)]
             {:order (vec (drop overflow order*))
              :entries (apply dissoc entries* evicted)})))
  value)

(defn create-web-search-tool [opts]
  (let [config (normalize-config opts)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :web_search
       "Search the public web. Uses Tavily first and Searcharvester as fallback; returns compact source snippets."
       :category :web
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:http-request}
       :input-schema [:map {:closed true}
                      [:query :string]
                      [:max-results {:optional true} [:int {:min 1 :max 20}]]
                      [:depth {:optional true} [:enum "basic" "advanced"]]
                      [:topic {:optional true} [:enum "general" "news" "finance"]]]
       :operation :read
       :parallel-safe? true)
      :validate-fn
      (fn [input]
        (when-not (nonblank (:query input))
          (throw (tools/validation-error "query must be a non-blank string" {:input input})))
        input)
      :health-fn
      (fn [] {:healthy true
              :details {:provider-order (:provider-order config)
                        :providers {:searchharvester (true? (get-in config [:searchharvester :enabled]))
                                    :tavily (true? (get-in config [:tavily :enabled]))}}})
      :execute-fn
      (fn [input _context]
        (let [input* {:query (str/trim (:query input))
                      :max-results (long (or (:max-results input) 5))
                      :depth (keyword (or (:depth input) "basic"))
                      :topic (keyword (or (:topic input) "general"))}]
          (route! (:provider-order config)
                  #(search-with-provider! config % input*))))})))

(defn create-web-extract-tool [opts]
  (let [config (normalize-config opts)
        cache (atom {:order [] :entries {}})]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :web_extract
       "Extract clean Markdown from one public URL. Uses Tavily advanced first and Searcharvester/Trafilatura as fallback."
       :category :web
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:http-request}
       :input-schema [:map {:closed true}
                      [:url :string]
                      [:size {:optional true} [:enum "s" "m" "l"]]
                      [:query {:optional true} [:maybe :string]]
                      [:chunks-per-source {:optional true} [:int {:min 1 :max 5}]]
                      [:max-chars {:optional true} [:int {:min 1 :max 25000}]]]
       :operation :read
       :parallel-safe? true)
      :validate-fn
      (fn [input]
        (when (and (:chunks-per-source input) (not (nonblank (:query input))))
          (throw (tools/validation-error "chunks-per-source requires query" {:input input})))
        (validate-extract-input config input))
      :health-fn
      (fn [] {:healthy true
              :details {:provider-order (:provider-order config)
                        :providers {:searchharvester (true? (get-in config [:searchharvester :enabled]))
                                    :tavily (true? (get-in config [:tavily :enabled]))}
                        :cache-max-entries (:cache-max-entries config)}})
      :execute-fn
      (fn [input context]
        (let [input* {:url (:url input)
                      :size (keyword (or (:size input) "m"))
                      :query (nonblank (:query input))
                      :chunks-per-source (long (or (:chunks-per-source input) 3))
                      :max-chars (long (or (:max-chars input) (:extract-max-chars config)))}
              key (cache-key context input*)]
          (if-let [cached (cache-get cache key)]
            (assoc cached :cached true)
            (let [result (route! (:provider-order config)
                                 #(extract-with-provider! config % input*))
                  result* (assoc result :cached false)]
              (cache-put! cache (:cache-max-entries config) key result*)))))})))

(defn create-web-tools [opts]
  [(create-web-search-tool opts)
   (create-web-extract-tool opts)])

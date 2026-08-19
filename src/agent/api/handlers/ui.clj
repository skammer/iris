(ns agent.api.handlers.ui
  (:require
   [agent.api.errors :as errors]
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.cron.service :as cron-service]
   [agent.defaults :as defaults]
   [agent.memory.core :as memory]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.memory.recall :as memory-recall]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.sessions.service :as session-service]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.ui.cron :as ui-cron]
   [agent.ui.memory :as ui-memory]
   [agent.ui.render]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.nio.file Files)
   (java.util Base64)))

(def ^:private max-chat-image-bytes (* 10 1024 1024))
(defonce ^:private ui-session-streams (atom {}))

(defn- request-ui-limit
  ([request] (request-ui-limit request 20 100))
  ([request default maximum]
   (let [raw (-> request :parameters :query :limit)]
     (try
       (-> (if (str/blank? raw) default (Long/parseLong raw))
           (max 1)
           (min maximum)
           long)
       (catch NumberFormatException _ default)))))

(defn- close-ui-session-stream! [client-id]
  (when-let [ctx (and (not (str/blank? client-id))
                      (get @ui-session-streams client-id))]
    (swap! ui-session-streams dissoc client-id)
    (streaming/close! ctx)))

(defn- register-ui-session-stream! [client-id ctx]
  (when-not (str/blank? client-id)
    (close-ui-session-stream! client-id)
    (swap! ui-session-streams assoc client-id ctx)
    (streaming/register-cleanup!
     ctx
     #(swap! ui-session-streams
             (fn [streams]
               (if (identical? ctx (get streams client-id))
                 (dissoc streams client-id)
                 streams))))))

(defn- uploaded-files [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn- image-upload->block [{:keys [filename content-type tempfile size] :as upload}]
  (when (and (map? upload)
             (not (str/blank? (or filename "")))
             tempfile)
    (let [media-type (or content-type "application/octet-stream")
          size* (long (or size (.length tempfile)))]
      (when-not (str/starts-with? (str/lower-case media-type) "image/")
        (throw (errors/api-error 400 "bad_request" "Only image uploads are supported.")))
      (when (> size* max-chat-image-bytes)
        (throw (errors/api-error 400 "bad_request" "Image upload exceeds 10MB.")))
      {:type :image
       :source {:type :base64
                :media-type media-type
                :value (.encodeToString (Base64/getEncoder)
                                        (Files/readAllBytes (.toPath tempfile)))}
       :filename filename
       :alt filename})))

(defn- chat-content [prompt image]
  (let [prompt* (str/trim (or prompt ""))
        image-blocks (keep image-upload->block (uploaded-files image))
        blocks (cond-> []
                 (not (str/blank? prompt*)) (conj {:type :text :text prompt*})
                 (seq image-blocks) (into image-blocks))]
    (when (empty? blocks)
      (throw (errors/api-error 400 "bad_request" "Expected prompt or image.")))
    (if (seq image-blocks)
      blocks
      prompt*)))

(defn shell [system request]
  (responses/html-response 200
                           (let [query (-> request :parameters :query)]
                             (ui/shell-fragment system
                                                {:tab (some-> (:tab query) keyword)
                                                 :session-id (:session_id query)}))))

(defn route [system request]
  (let [query (-> request :parameters :query)]
    (when-not (= "chat" (some-> (:tab query) str/lower-case))
      (close-ui-session-stream! (:client_id query)))
    (responses/html-response 200
                             (ui/route-fragment system
                                                {:tab (some-> (:tab query) keyword)
                                                 :session-id (:session_id query)}))))

(defn dashboard [system _request]
  (responses/html-response 200 (ui/dashboard-fragment system)))

(defn cron [system request]
  (let [query (-> request :parameters :query)
        limit (request-ui-limit request 20 100)
        tab (some-> (:tab query) keyword)
        view (some-> (:view query) keyword)]
    (responses/html-response 200 (ui/cron-fragment system {:limit limit
                                                            :tab tab
                                                            :view view
                                                            :date (:date query)}))))

(defn cron-status [system request]
  (let [query (-> request :parameters :query)
        limit (request-ui-limit request 20 100)
        tab (or (some-> (:tab query) keyword) :jobs)
        view (or (some-> (:view query) keyword) :week)]
    (responses/html-response 200 (ui-cron/status-fragment system limit tab view (:date query)))))

(defn cron-job-detail [system _request job-id]
  (responses/html-response
   200
   (ui-cron/job-editor-detail-fragment
    system
    (cron-service/get-job (:cron-service system) job-id))))

(defn cron-run-detail [system _request run-id]
  (responses/html-response
   200
   (ui-cron/run-detail-fragment
    (cron-service/get-run (:cron-service system) run-id))))

(defn- cron-form-schedule [{:keys [schedule_kind cron_expression at every_seconds anchor_at]}]
  (case schedule_kind
    "at" {:kind :at :at at}
    "interval" {:kind :interval :every-seconds (parse-long every_seconds)
                 :anchor-at (or (not-empty anchor_at) (str (java.time.Instant/now)))}
    {:kind :cron :expression cron_expression}))

(defn- cron-model-pin [model-pair]
  (when-not (str/blank? model-pair)
    (let [[provider model] (str/split model-pair #"\|" 2)]
      {:provider (keyword provider) :model model})))

(defn cron-create [system request]
  (let [{:keys [name prompt timezone max_occurrences tool_profile model_pair
                notify_policy telegram_recipient] :as body} (h/read-form-body request)
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        input (cond-> (merge {:name name :prompt prompt :timezone timezone
                       :schedule (cron-form-schedule body)
                       :notification {:policy (keyword (or notify_policy "never")) :target target}}
                             (cron-model-pin model_pair))
                (not (str/blank? max_occurrences)) (assoc :max-occurrences (parse-long max_occurrences))
                (not (str/blank? tool_profile)) (assoc :tool-profile (keyword tool_profile)))]
    (cron-service/create-job! (:cron-service system) input {:created-by "ui"})
    (responses/html-response 200 (ui/cron-fragment system {:tab :jobs}))))

(defn cron-preview [system request]
  (let [{:keys [name prompt timezone max_occurrences tool_profile model_pair
                notify_policy telegram_recipient] :as body} (h/read-form-body request)
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        input (cond-> (merge {:name name :prompt prompt :timezone timezone
                       :schedule (cron-form-schedule body)
                       :notification {:policy (keyword (or notify_policy "never")) :target target}}
                             (cron-model-pin model_pair))
                (not (str/blank? max_occurrences)) (assoc :max-occurrences (parse-long max_occurrences))
                (not (str/blank? tool_profile)) (assoc :tool-profile (keyword tool_profile)))
        preview (cron-service/preview (:cron-service system) input)]
    (responses/html-response
     200
     (agent.ui.render/render
      [:div#cron-preview.cron-preview
       [:strong "Next runs"]
       [:ol (for [instant (:next-runs preview)] [:li instant])]
       [:small (str (get-in preview [:resolved-model :provider]) "/"
                    (get-in preview [:resolved-model :model]) " · "
                    (get-in preview [:resolved-tools :tool-profile]))]]))))

(defn- cron-update! [service id revision body]
  (let [{:keys [name prompt timezone max_occurrences tool_profile
                model_pair notify_policy telegram_recipient]} body
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        changes (merge {:name name :prompt prompt :timezone timezone
                        :schedule (cron-form-schedule body)
                        :notification {:policy (keyword (or notify_policy "never")) :target target}
                        :max-occurrences (when-not (str/blank? max_occurrences)
                                           (parse-long max_occurrences))
                        :tool-profile (when-not (str/blank? tool_profile) (keyword tool_profile))}
                       (or (cron-model-pin model_pair) {:provider nil :model nil}))]
    (cron-service/update-job! service id revision changes)))

(defn cron-action [system request]
  (let [{:keys [id revision action cron_tab] :as body} (h/read-form-body request)
        service (:cron-service system)
        revision* (parse-long revision)]
    (case action
      "pause" (cron-service/set-status! service id :paused revision*)
      "resume" (cron-service/set-status! service id :active revision*)
      "delete" (cron-service/set-status! service id :deleted revision*)
      "run" (cron-service/run-now! service id)
      "update" (cron-update! service id revision* body)
      "update-run" (let [job (cron-update! service id revision* body)]
                     (cron-service/run-now! service (:id job)))
      (throw (errors/api-error 400 "bad_request" "Unknown cron action")))
    (responses/html-response
     200
     (ui/cron-fragment system
                       {:tab (if (contains? #{"run" "update-run"} action)
                               :runs
                               (or (some-> cron_tab keyword) :jobs))}))))

(defn operator-board [system _request]
  (responses/html-response 200 (ui/operator-board-fragment system)))

(defn magi [system request]
  (let [limit (request-ui-limit request 25 200)]
    (responses/html-response 200 (ui/magi-fragment system {:limit limit}))))

(defn magi-detail [system _request event-id]
  (responses/html-response
   200
   (ui/magi-detail-fragment
    (when-let [id (parse-long event-id)]
      (sqlite/get-event (:store system) id)))))

(defn sessions [system request]
  (responses/html-response 200
                           (ui/sessions-fragment system
                                                 (-> request :parameters :query :session_id))))

(defn create-session [system request]
  (h/read-form-body request)
  (let [session (session-service/create-session! system nil)]
	    (streaming/once-response
	     request
         {:metrics (:sse-metrics system)}
	     (fn [ctx]
       (streaming/send-datastar-patch!
        ctx
        (str (ui/sessions-fragment system (:id session))
             (ui/session-detail-fragment system (:id session))
             (ui/dashboard-fragment system)
             (ui/router-state-fragment (ui/session-route-path system (:id session)))))))))

(defn session-detail [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (responses/html-response 200
                             (str (ui/sessions-fragment system session-id)
                                  (ui/session-detail-fragment system session-id)
                                  (ui/router-state-fragment
                                   (ui/session-route-path system session-id))))))

(defn session-messages [system request]
  (let [query (-> request :parameters :query)
        session-id (:session_id query)]
    (h/ensure-session-exists! system session-id)
    (responses/html-response 200
                             (ui/session-messages-fragment system session-id
                                                           {:limit (:limit query)}))))

(defn chat-tool-detail [system request]
  (let [{:keys [session_id message_id tool_call_id]} (-> request :parameters :query)]
    (h/ensure-session-exists! system session_id)
    (responses/html-response
     200
     (agent.ui.render/tool-detail-fragment
      (sqlite/list-messages (:store system) session_id)
      message_id
      tool_call_id))))

(defn- relevant-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (or (contains? #{"message.updated"
                        "session.created"
                        "session.title.updated"
                        "session-state-changed"
                        "turn-queued"}
                      (:event-type event))
           (contains? #{"agent-start"
                        "agent-end"
                        "turn-start"
                        "turn-end"
                        "message-start"
                        "message-update"
                        "message-end"
                        "tool-execution-start"
                        "tool-execution-update"
                        "tool-execution-end"}
                      (:event-type event)))))

(defn- terminal-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "agent-end" (:event-type event))))

(defn- message-stream-update-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "message-update" (:event-type event))
       (or (string? (get-in event [:payload :delta]))
           (string? (get-in event [:payload :thinking-delta])))))

(defn- stream-ending-message-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "message-end" (:event-type event))
       (let [payload (:payload event)]
         (or (:final? payload)
             (:tool-turn? payload)))))

(defn- title-updated-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "session.title.updated" (:event-type event))))

(defn- session-shell-fragments [system session-id]
  (str (ui/sessions-fragment system session-id)
       (ui/session-detail-fragment system session-id)))

(defn session-live-response
  [system request]
  (let [query (-> request :parameters :query)
        session-id (:session_id query)
        client-id (:client_id query)
        broker-instance (or (:event-bus system) (:broker system))]
    (h/ensure-session-exists! system session-id)
	    (streaming/managed-response
	     request
	     {:name :ui-session-live
          :metrics (:sse-metrics system)
	      :on-error (fn [_ _] nil)}
     (fn [ctx]
       (register-ui-session-stream! client-id ctx)
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)
             heartbeat-ms 5000]
         (loop []
           (when (streaming/open? ctx)
             (let [heartbeat (async/timeout heartbeat-ms)
                   [value source] (async/alts!! [ch heartbeat])]
               (cond
                 (= source heartbeat)
                 (when (streaming/send-sse-text! ctx ":\n\n")
                   (recur))

                 value
                 (let [event (:payload value)]
                   (when (relevant-session-event? event session-id)
                     (streaming/send-datastar-patch!
                      ctx
                      (if (title-updated-event? event session-id)
                        (session-shell-fragments system session-id)
                        (ui/session-messages-fragment system session-id))))
                   (recur)))))))))))

(defn chat-action [system request]
  (let [body (h/read-form-body request)
        {:keys [session_id prompt image]} body
        content (try
                  (chat-content prompt image)
                  (catch Exception e
                    ;; Forensics for empty-prompt 400s: record what the form
                    ;; actually delivered (keys + sizes, never content).
                    (when-let [event-sink (:event-sink system)]
                      (event-sink {:event-type :ui.chat.bad-request
                                   :entity-type :session
                                   :entity-id (str session_id)
                                   :payload {:form-keys (mapv name (keys body))
                                             :prompt-chars (count (str prompt))
                                             :content-type (get-in request [:headers "content-type"])}}))
                    (throw e)))]
    (h/ensure-session-exists! system session_id)
	    (streaming/managed-response
	     request
	     {:name :ui-chat
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _]
                  (streaming/send-datastar-patch!
                   ctx
                   (ui/session-messages-fragment system session_id)))}
     (fn [ctx]
       (let [broker-instance (or (:event-bus system) (:broker system))
             final-fallback-ms 1000
             subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)
             streaming-state (atom {})
             push! (fn
                     ([]
                      (streaming/send-datastar-patch!
                       ctx
                       (ui/session-messages-fragment system session_id)))
                     ([streaming]
                      (streaming/send-datastar-patch!
                       ctx
                       (ui/session-messages-fragment system
                                                     session_id
                                                     {:streaming streaming}))))
             final-pushed? (atom false)
             push-final! (fn []
                           (when-not @final-pushed?
                             (reset! final-pushed? true)
                             (push!)))
             push-delta! (fn [delta]
                           (when-not (str/blank? (str delta))
                             (push! (swap! streaming-state
                                           update :content (fnil str "") delta))))
             push-thinking! (fn [delta]
                              (when-not (str/blank? (str delta))
                                (push! (swap! streaming-state
                                              update :thinking (fnil str "") delta))))
             result-ch (streaming/run-task!
                        ctx
                        #(chat/run! system
                                    {:messages [{:role "user" :content content}]
                                     :session-id session_id
                                     :stream? true
                                     :on-delta push-delta!
                                     :on-thinking-delta push-thinking!}))]
         (push!)
         (loop [done? false
                result-ch* result-ch
                terminal? false
                fallback-ch nil]
           (when-not (and done? terminal?)
             (let [ports (cond-> [ch]
                           result-ch* (conj result-ch*)
                           fallback-ch (conj fallback-ch))
                   [value port] (async/alts!! ports)]
               (cond
                 (= port result-ch*)
                 (if-let [error (:error value)]
                   (throw error)
                   (if terminal?
                     (push-final!)
                     (recur true nil terminal? (async/timeout final-fallback-ms))))

                 (= port fallback-ch)
                 (push-final!)

                 (= port ch)
                 (if-let [event (:payload value)]
                   (let [terminal?* (or terminal?
                                        (terminal-session-event? event session_id))]
                     (cond
                       (message-stream-update-event? event session_id)
                       nil

                       (stream-ending-message-event? event session_id)
                       (do
                         (reset! streaming-state {})
                         (push-final!))

                       (title-updated-event? event session_id)
                       (streaming/send-datastar-patch!
                        ctx
                        (session-shell-fragments system session_id))

                       (relevant-session-event? event session_id)
                       (push!))
                     (if (and done? terminal?*)
                       (push-final!)
                       (recur done? result-ch* terminal?* fallback-ch)))
                   (when done?
                     (push-final!))))))))))))

(defn chat-stop [system request]
  (let [{:keys [session_id]} (h/read-form-body request)]
    (h/ensure-session-exists! system session_id)
    (chat/cancel-session! system session_id)
    (responses/html-response 200
                             (str (ui/session-messages-fragment system session_id)
                                  "<div id=\"chat-status\" class=\"meta chat-status\">stopping...</div>"))))

(defn system-reload [system request]
  (let [body (h/read-form-body request)
        mode (keyword (or (:mode body) "soft"))
        control (:system-control system)]
    ((:reload! control) system {:mode mode :source "ui"})
    (responses/html-response 200 (ui/dashboard-fragment ((:current-system control) system)))))

(defn events [system _request]
  (responses/html-response 200 (ui/events-fragment system)))

(defn logs [system request]
  (let [limit (request-ui-limit request 20 200)]
    (responses/html-response 200 (ui/logs-fragment system {:limit limit}))))

(defn log-detail [system _request source entry-id]
  (let [source* (keyword source)
        record (case source*
                 :event (when-let [id (parse-long entry-id)]
                          (sqlite/get-event (:store system) id))
                 :trace (->> (runtime-trace/load-events
                              (:trace system)
                              {:limit (or (get-in system [:trace :rolling-max-entries]) 1000)})
                             (some #(when (= entry-id (:id %)) %)))
                 nil)]
    (responses/html-response 200 (ui/log-detail-fragment source* record))))

(defn events-live-response
  [system request]
  (let [broker-instance (or (:event-bus system) (:broker system))]
	    (streaming/managed-response
	     request
	     {:name :ui-events-live
          :metrics (:sse-metrics system)
	      :on-error (fn [_ _] nil)}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (streaming/send-datastar-patch! ctx (ui/events-fragment system))
         (loop []
           (when (streaming/take! ctx ch)
             (streaming/send-datastar-patch! ctx (ui/events-fragment system))
            (recur))))))))

(defn memory-workspace [system request]
  (let [limit (request-ui-limit request 20 100)]
    (responses/html-response
     200
     (ui/memory-workspace-fragment system nil {:limit limit}))))

(defn memory-vault-detail [system _request note-id]
  (let [store (get-in system [:memory-service :store])
        note (sqlite/get-vault-note-by-id store note-id)
        review (first (sqlite/list-events store
                                          {:event-type memory-magi-review/review-event-type
                                           :entity-type :vault_note
                                           :entity-id note-id
                                           :limit 1}))]
    (responses/html-response 200 (ui-memory/vault-note-detail-fragment note review))))

(defn memory-update-detail [system _request update-id]
  (responses/html-response
   200
   (ui-memory/memory-update-detail-fragment
    (sqlite/get-memory-note-update (get-in system [:memory-service :store]) update-id))))

(defn memory-search [system request]
  (let [{:keys [query]} (h/read-form-body request)]
    (responses/html-response 200
                             (ui/memory-search-results-fragment
                              (memory-recall/recall (:memory-service system) query)))))

(defn- parse-int-form [value]
  (when-not (str/blank? (str value))
    (Integer/parseInt (str value))))

(defn- form-scope [{:keys [scope_type scope_id]}]
  (when-not (str/blank? (str scope_type))
    (cond-> {:type scope_type}
      (not (str/blank? (str scope_id))) (assoc :id scope_id))))

(defn- memory-tool-input [{:keys [action query old_text new_text
                                  expected_revision]
                           :as body}]
  (let [action* (keyword (str/lower-case (str action)))
        scratchpad-replace? (= :scratchpad-replace action*)]
    (cond-> {:action action}
      (not (str/blank? (str query))) (assoc :query query)
      (parse-int-form (:limit body)) (assoc :limit (parse-int-form (:limit body)))
      (form-scope body) (assoc :scope (form-scope body))
      (or (not (str/blank? (str old_text))) scratchpad-replace?)
      (assoc :old-text (or old_text ""))
      (or (not (str/blank? (str new_text))) scratchpad-replace?)
      (assoc :new-text (or new_text ""))
      (not (str/blank? (str expected_revision))) (assoc :expected-revision expected_revision))))

(defn- memory-tool-target [{:keys [action] :as input}]
  (case (keyword (str/lower-case (str action)))
	    :recall [:memory_recall (select-keys input [:query :limit :scope])]
	    :vault-search [:vault_search (select-keys input [:query :limit])]
	    :scratchpad-read [:scratchpad_read (select-keys input [:scope])]
	    :scratchpad-search [:scratchpad_search (select-keys input [:query :scope])]
	    :scratchpad-replace [:scratchpad_replace (select-keys input [:old-text :new-text :expected-revision :scope])]))

(defn- memory-search-source-json [system {:keys [action query limit scope]}]
  (when (and (= :recall (keyword (str/lower-case (str action))))
             (not (str/blank? (str query))))
    (memory-recall/recall (:memory-service system)
                          query
                          (cond-> {}
                            limit (assoc :limit limit)
                            scope (assoc :scope scope)))))

(defn memory-tool-run [system request]
  (try
    (let [body (h/read-form-body request)
          input (memory-tool-input body)
          [tool-name tool-input] (memory-tool-target input)
          result (tools/execute-tool (:tool-registry system)
                                     tool-name
                                     tool-input
                                     (assoc
                                      (tools-h/execution-context system :ui tool-name tool-input
                                                                 {:user "ui-memory"})
                                      :permissions #{:memory-read :memory-write}))]
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? true
         :input input
         :result result
         :source-json (memory-search-source-json system input)})))
    (catch Exception e
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? false
         :error (.getMessage e)
         :details (ex-data e)})))))

(defn- memory-reset-response [system surface reset!]
  (try
    (responses/html-response
     200
     (ui/memory-workspace-fragment system
                                   {:ok? true
                                    :surface surface
                                    :result (reset!)}))
    (catch Exception e
      (responses/html-response
       200
       (ui/memory-workspace-fragment system
                                     {:ok? false
                                      :surface surface
                                      :error (.getMessage e)
                                      :details (ex-data e)})))))

(defn memory-vault-reindex [system _request]
  (memory-reset-response system
                         "Vault reindex"
                         #(memory/reindex-vault! (:memory-service system))))

(defn memory-vault-status [system request]
  (let [body (h/read-form-body request)]
    (memory-reset-response system
                           "Vault note"
                           #(if (= "approved" (:status body))
                              (memory/promote-vault-note!
                               (:memory-service system)
                               (:path body)
                               (select-keys body [:scope]))
                              (memory/update-vault-note-iris!
                               (:memory-service system)
                               (:path body)
                               (select-keys body [:scope :status]))))))

(defn memory-vault-magi [system request]
  (let [{:keys [path action]} (h/read-form-body request)
        review? (= "review" action)]
    (memory-reset-response system
                           (if review? "MAGI review" "MAGI advice")
                           #(memory-magi-review/review-note!
                             system
                             path
                             {:apply? review?
                              :source (if review? :manual :advice)}))))

(defn memory-vault-magi-update [system request]
  (let [{:keys [update_id action]} (h/read-form-body request)
        review? (= "review" action)]
    (memory-reset-response system
                           (if review? "MAGI update review" "MAGI update advice")
                           #(memory-magi-review/review-update!
                             system
                             update_id
                             {:apply? review?
                              :source (if review? :manual :advice)}))))

(defn memory-vault-move [system request]
  (let [body (h/read-form-body request)]
    (memory-reset-response system
                           "Vault note"
                           #(memory/move-vault-note!
                             (:memory-service system)
                             (:path body)
                             (:folder body)))))

(defn list-tool-approvals [system request]
  (let [limit (request-ui-limit request)]
    (responses/html-response 200
                             (ui/tool-approvals-fragment
                              (tool-approvals/list-review-records (:store system) {:limit limit})
                              {:limit limit}))))

(defn tool-approvals-status [system request]
  (let [limit (request-ui-limit request)]
    (responses/html-response
     200
     (ui/tool-approvals-status-fragment
      (tool-approvals/list-review-records (:store system) {:limit 100})
      limit))))

(defn tool-approval-detail [system _request approval-id]
  (responses/html-response 200
                           (ui/tool-approval-detail-fragment
                            (tool-approvals/get-request (:store system) approval-id))))

(defn tool-approval-decision [system request approval-id status]
  (let [body (h/read-form-body request)
        actor (or (:actor body) "operator")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (tool-approvals/log-decision! (:event-sink system) updated status actor reason)
    (responses/html-response 200
                             (ui/tool-approvals-fragment
                              (tool-approvals/list-review-records (:store system) {:limit 20})))))

(defn tool-approval-run [system _request approval-id]
  (let [{:keys [tool-name input permissions approval]} (tool-approvals/resolve-approved-request (:store system) approval-id)
        user (or (not-empty (:requested-by approval)) "ui")]
    (try
      (responses/html-response
       200
       (str (ui/tool-approvals-fragment
             (tool-approvals/list-review-records (:store system) {:limit 20}))
            (ui/tool-results-fragment
             tool-name
             200
             {:result (tools/execute-tool (:tool-registry system)
                                          tool-name
                                          input
                                          {:permissions permissions
                                           :approval-id approval-id
                                           :approval-reason (:decision-reason approval)
                                           :user user
                                           :activity (:activity input)})})))
      (catch Exception e
        (let [api-e (errors/domain-error->api-error e)]
          (responses/html-response
           (:status (ex-data api-e))
           (ui/tool-results-fragment
            tool-name
            (:status (ex-data api-e))
            {:error (:error (ex-data api-e))
             :message (.getMessage api-e)
             :details (:details (ex-data api-e))})))))))

(ns agent.memory.idle
  "Idle-session memory extraction worker."
  (:require
   [agent.chat :as chat]
   [agent.memory.core :as memory]
   [agent.memory.user-profile :as user-profile]
   [agent.persistence.sqlite :as sqlite]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def default-config
  {:enabled true
   :idle-timeout-minutes 45
   :poll-interval-seconds 60
   :failure-cooldown-minutes 15
   :max-sessions 20
   :max-messages 80
   :max-events 40
   :min-confidence 0.85
   :include-events? true})

(def ^:private user-message-max-chars 2400)
(def ^:private assistant-message-max-chars 5000)
(def ^:private other-message-max-chars 1200)
(def ^:private tool-message-max-chars 500)
(def ^:private event-max-chars 800)
(def ^:private transcript-max-chars 40000)

(defn- idle-config [system]
  (merge default-config
         (get-in system [:config :memory :notes :idle-extraction])))

(defn- non-negative-number [value fallback]
  (if (number? value)
    (max 0.0 (double value))
    fallback))

(defn- positive-long [value fallback maximum]
  (let [value* (if (and (integer? value) (pos? value))
                 value
                 fallback)]
    (long (min maximum value*))))

(defn- seconds [minutes]
  (long (* 60 (non-negative-number minutes 0.0))))

(defn- idle-before [^Instant now cfg]
  (str (.minusSeconds now (seconds (:idle-timeout-minutes cfg)))))

(defn- next-attempt-at [^Instant now cfg]
  (str (.plusSeconds now (seconds (:failure-cooldown-minutes cfg)))))

(defn- active-session? [system session-id]
  (let [{:keys [working? queued-count loop-active?]} (chat/session-state system session-id)]
    (or working?
        (pos? (long (or queued-count 0)))
        loop-active?)))

(defn- tool-result-summary [content]
  (try
    (let [payload (json/parse-string content true)
          input (:input payload)
          result (:result payload)
          purpose (some-> (:purpose input)
                          str
                          (util/truncate 240 #(str " [truncated " % " chars]")))
          result-status (when (map? result) (:status result))
          exit (when (map? result) (:exit result))]
      (str/join
       " "
       (cond-> [(str "tool=" (or (:tool-name payload) "unknown"))
                (str "status=" (or (:status payload) "unknown"))]
         (:action input) (conj (str "action=" (:action input)))
         (:method input) (conj (str "method=" (:method input)))
         purpose (conj (str "purpose=" (pr-str purpose)))
         result-status (conj (str "result-status=" result-status))
         (some? exit) (conj (str "exit=" exit)))))
    (catch Exception _
      "tool result omitted")))

(defn- message-content-limit [role]
  (case role
    "user" user-message-max-chars
    "assistant" assistant-message-max-chars
    "tool" tool-message-max-chars
    other-message-max-chars))

(defn- message-line [{:keys [id role content]}]
  (let [content* (if (= "tool" role)
                   (tool-result-summary content)
                   content)]
    (str "[" id "] " role ": "
         (util/truncate content*
                        (message-content-limit role)
                        #(str " [truncated " % " chars]")))))

(defn- safe-payload [event]
  (let [payload (:payload event)]
    (case (:event-type event)
      "tool-execution-end"
      (select-keys payload [:tool-name :status :duration-ms :error :error-type])

      "agent-start"
      (select-keys payload [:message-count :stream])

      "agent-end"
      (select-keys payload [:steps :stop-reason :stream])

      "guardrail-blocked"
      (select-keys payload [:step :action :reason])

      "chat.operation.failed"
      (select-keys payload [:operation :message :type])

      "session.title.updated"
      (select-keys payload [:title])

      {})))

(defn- event-line [event]
  (let [payload (-> (safe-payload event)
                    json/generate-string
                    (util/truncate event-max-chars #(str " [truncated " % " chars]")))]
    (str "[event:" (:id event) "] " (:event-type event) " " payload)))

(defn- event-window [store session-id after-id through-id cfg]
  (if-not (:include-events? cfg)
    {:events [] :watermark through-id}
    (let [limit (positive-long (:max-events cfg) 40 200)
          selected (sqlite/list-memory-events-window
                    store {:session-id session-id
                           :after-id after-id
                           :through-id through-id
                           :limit (inc limit)})
          events (vec (take limit selected))]
      {:events events
       :watermark (if (> (count selected) limit)
                    (:id (last events))
                    through-id)})))

(defn- transcript [messages events]
  (-> (str (str/join "\n\n" (map message-line messages))
           (when (seq events)
             (str "\n\nEvents:\n" (str/join "\n" (map event-line events)))))
      (util/truncate transcript-max-chars
                     #(str "\n\n[transcript truncated " % " chars]"))))

(defn- provider [system]
  (or (:note-llm-provider system)
      (:llm-provider system)))

(defn- log-event! [store event]
  (sqlite/log-event! store event))

(defn- mark-success! [store session-id messages through-event-id saved now]
  (let [last-message (last messages)]
    (sqlite/mark-memory-extraction-success!
     store
     {:session-id session-id
      :last-processed-message-id (:id last-message)
      :last-processed-message-created-at (:created-at last-message)
      :last-processed-event-id through-event-id
      :note-count (count saved)
      :now now})))

(defn- mark-failure! [store session-id error now cfg]
  (sqlite/mark-memory-extraction-failure!
   store
   {:session-id session-id
    :error (or (.getMessage ^Throwable error) (str error))
    :next-attempt-at (next-attempt-at (Instant/parse now) cfg)
    :now now}))

(defn- run-candidate! [system cfg candidate]
  (let [store (:store system)
        session-id (:session-id candidate)
        request-id (str "idle-memory-" session-id "-" (util/now-str))
        through-event-id (sqlite/latest-event-id store)
        messages (sqlite/list-messages-after
                  store
                  session-id
                  {:after-id (:last-processed-message-id candidate)
                   :through-id (:latest-message-id candidate)
                   :limit (positive-long (:max-messages cfg) 80 200)})
        {:keys [events watermark]} (event-window store session-id (:last-processed-event-id candidate) through-event-id cfg)
        now (util/now-str)]
    (when (seq messages)
      (try
        (let [transcript* (transcript messages events)
              saved (memory/extract-and-save-notes!
                     (:memory-service system)
                     (provider system)
                     {:user-message transcript*
                      :assistant-message "Idle end-of-session memory extraction."}
                     {:session-id session-id
                      :source-type "idle-extraction"
                      :source-message-ids (mapv #(str (:id %)) messages)
                      :source-event-ids (mapv #(str (:id %)) events)
                      :source-request-id request-id
                      :extractor {:prompt "note-extraction-idle"}
                      :min-confidence (:min-confidence cfg)
                      :dedupe? true
                      :log-failure? false
                      :throw? true})
              profile-result (when (user-profile/enabled? (:user-profile-service system))
                               (user-profile/learn-from-transcript!
                                (:user-profile-service system)
                                {:session-id session-id
                                 :transcript transcript*}))]
          (mark-success! store session-id messages watermark saved now)
          (log-event! store {:event-type :memory.idle_extraction.completed
                             :entity-type :session
                             :entity-id session-id
                             :request-id request-id
                             :payload {:message-count (count messages)
                                       :event-count (count events)
                                       :note-count (count saved)
                                       :user-profile-updated? (true? (:updated? profile-result))
                                       :last-message-id (:id (last messages))
                                       :last-event-id watermark}})
          (cond-> {:session-id session-id
                   :message-count (count messages)
                   :event-count (count events)
                   :note-count (count saved)}
            profile-result
            (assoc :user-profile-updated? (true? (:updated? profile-result)))))
        (catch Exception e
          (mark-failure! store session-id e now cfg)
          (log-event! store {:event-type :memory.idle_extraction.failed
                             :entity-type :session
                             :entity-id session-id
                             :request-id request-id
                             :payload {:message (.getMessage e)}})
          {:session-id session-id
           :error (.getMessage e)})))))

(defn run-once!
  [system]
  (let [cfg (idle-config system)]
    (if (or (false? (:enabled cfg))
            (false? (get-in system [:memory-service :config :notes :extractor :enabled])))
      {:processed 0 :skipped :disabled}
      (let [store (:store system)
            now (Instant/now)
            candidates (sqlite/list-idle-extraction-candidates
                        store
                        {:idle-before (idle-before now cfg)
                         :now (str now)
                         :limit (positive-long (:max-sessions cfg) 20 200)})
            runnable (remove #(active-session? system (:session-id %)) candidates)
            results (keep #(run-candidate! system cfg %) runnable)]
        {:processed (count results)
         :skipped-active (- (count candidates) (count runnable))
         :note-count (reduce + 0 (map #(or (:note-count %) 0) results))
         :results (vec results)}))))

(defn create-service
  [system-ref]
  {:system-ref system-ref
   :running? (atom false)
   :stop? (atom false)
   :worker (atom nil)})

(defn running? [service]
  (boolean (and service @(:running? service))))

(defn- sleep-until-stop! [stop? millis]
  (let [deadline (+ (System/currentTimeMillis) (long millis))]
    (loop []
      (when (and (not @stop?)
                 (< (System/currentTimeMillis) deadline))
        (Thread/sleep (max 1 (min 1000 (- deadline (System/currentTimeMillis)))))
        (recur)))))

(defn start! [service]
  (when (and service (not @(:running? service)))
    (let [system @(:system-ref service)
          cfg (idle-config system)]
      (when-not (false? (:enabled cfg))
        (reset! (:stop? service) false)
        (reset! (:running? service) true)
        (reset! (:worker service)
                (future
                  (try
                    (while (not @(:stop? service))
                      (when-let [system* @(:system-ref service)]
                        (try
                          (run-once! system*)
                          (catch Exception e
                            (when-let [store (:store system*)]
                              (log-event! store {:event-type :memory.idle_extraction.worker_failed
                                                 :entity-type :system
                                                 :entity-id "memory"
                                                 :payload {:message (.getMessage e)}})))))
                      (sleep-until-stop! (:stop? service)
                                         (* 1000 (positive-long (:poll-interval-seconds cfg) 60 3600))))
                    (finally
                      (reset! (:running? service) false))))))))
  service)

(defn stop! [service]
  (when service
    (reset! (:stop? service) true)
    (when-let [worker @(:worker service)]
      (future-cancel worker))
    (reset! (:worker service) nil)
    (reset! (:running? service) false))
  {:stopped (boolean service)})

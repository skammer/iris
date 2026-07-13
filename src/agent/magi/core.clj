(ns agent.magi.core
  "MAGI oversight service: Filter normalizes a request, the triumvirate votes,
   and Judge aggregates enum-only responses."
  (:require
   [agent.llm.core :as llm]
   [agent.llm.service :as llm-service]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def participant-order [:melchior :balthasar :casper])
(def participant-names {:melchior "MELCHIOR"
                        :balthasar "BALTHASAR"
                        :casper "CASPER"})

(def ^:private filter-kinds #{:yes-no :info :unsupported})
(def ^:private domains #{:tool-approval :memory-promotion :policy :other})
(def ^:private risks #{:low :medium :high :critical})
(def ^:private expected-responses #{:permit :classify :opine})
(def ^:private agent-responses #{:yes :conditional :no :info :error})
(def ^:private judge-decisions #{:yes :conditional :no :info :error})

(def ^:private prompt-paths
  {:filter "prompts/magi/filter.md"
   :melchior "prompts/magi/melchior.md"
   :balthasar "prompts/magi/balthasar.md"
   :casper "prompts/magi/casper.md"
   :judge "prompts/magi/judge.md"})

(def ^:private filter-schema
  {:type "object"
   :properties {:kind {:type "string" :enum ["yes-no" "info" "unsupported"]}
                :domain {:type "string" :enum ["tool-approval" "memory-promotion" "policy" "other"]}
                :risk {:type "string" :enum ["low" "medium" "high" "critical"]}
                :question {:type "string"}
                :expected_response {:type "string" :enum ["permit" "classify" "opine"]}}
   :required ["kind" "domain" "risk" "question" "expected_response"]
   :additionalProperties false})

(def ^:private agent-schema
  {:type "object"
   :properties {:response {:type "string" :enum ["yes" "conditional" "no" "info" "error"]}
                :comment {:type "string"}}
   :required ["response"]
   :additionalProperties false})

(def ^:private judge-schema
  {:type "object"
   :properties {:decision {:type "string" :enum ["error" "info" "no" "conditional" "yes"]}
                :reason {:type "string"}}
   :required ["decision" "reason"]
   :additionalProperties false})

(defn- prompt [role]
  (or (some-> (io/resource (prompt-paths role)) slurp)
      (throw (ex-info "Missing MAGI prompt" {:type :magi-missing-prompt
                                             :role role
                                             :path (prompt-paths role)}))))

(defn- prompts []
  (into {} (map (fn [role] [role (prompt role)])) (keys prompt-paths)))

(defn- normalize-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/replace value #"_" "-")))
    :else value))

(defn- parse-json-content [response]
  (let [content (if (and (map? response) (contains? response :content))
                  (:content response)
                  response)]
    (cond
      (map? content) content
      (string? content) (json/parse-string content true)
      :else (throw (ex-info "MAGI response is not JSON"
                            {:type :magi-invalid-json
                             :content content})))))

(defn- compact-context [context max-chars]
  (let [text (json/generate-string context)]
    (if (and max-chars (< max-chars (count text)))
      {:truncated true
       :json (util/truncate text max-chars #(str " [truncated " % " chars]"))}
      context)))

(defn- structured-output [name schema]
  {:name name
   :schema schema})

(defn- request-messages [system-prompt payload]
  [{:role "system" :content system-prompt}
   {:role "user" :content (json/generate-string payload)}])

(defn- invoke-json [provider role system-prompt payload schema timeout-ms]
  (parse-json-content
   (llm/invoke provider
               {:messages (request-messages system-prompt payload)
                :structured-output (structured-output (str "magi_" (name role)) schema)
                :timeout-ms timeout-ms
                :stream? false})))

(defn- ensure-one-of! [value allowed label]
  (let [value* (normalize-keyword value)]
    (if (contains? allowed value*)
      value*
      (throw (ex-info "Invalid MAGI enum"
                      {:type :magi-invalid-enum
                       :field label
                       :value value
                       :allowed allowed})))))

(defn- normalize-filter-output [m]
  {:kind (ensure-one-of! (:kind m) filter-kinds :kind)
   :domain (ensure-one-of! (:domain m) domains :domain)
   :risk (ensure-one-of! (:risk m) risks :risk)
   :question (or (:question m) "")
   :expected-response (ensure-one-of! (or (:expected_response m)
                                          (:expected-response m))
                                      expected-responses
                                      :expected-response)
   :context (or (:context m) {})})

(defn- normalize-agent-output [m]
  {:response (ensure-one-of! (:response m) agent-responses :response)
   :comment (some-> (:comment m) str str/trim not-empty)})

(defn- normalize-judge-output [m]
  {:decision (ensure-one-of! (:decision m) judge-decisions :decision)
   :reason (or (some-> (:reason m) str str/trim not-empty) "magi decision")})

(defn- default-magi-config [cfg]
  (merge {:enabled? false
          :mode :assistive
          :fallback :human
          :apply-to #{:tool-approvals}
          :tool-categories #{:all}
          :memory-promotion {:mode :manual
                             :scopes #{:all}
                             :poll-interval-seconds 60
                             :failure-cooldown-minutes 15
                             :max-candidates 10}
          :tool {:enabled true}
          :execution :parallel
          :allow-critical? false
          :timeout-ms 30000
          :max-context-chars 12000
          :filter {:provider nil :model nil}
          :judge {:provider nil :model nil}
          :agents {:melchior {:provider nil :model nil}
                   :balthasar {:provider nil :model nil}
                   :casper {:provider nil :model nil}}}
         (:magi cfg)))

(defn- role-override [magi-cfg role]
  (if (= role :filter)
    (:filter magi-cfg)
    (if (= role :judge)
      (:judge magi-cfg)
      (get-in magi-cfg [:agents role]))))

(defn- nil-override? [override]
  (and (nil? (:provider override))
       (nil? (:model override))))

(defn- provider-for [cfg default-provider magi-cfg role]
  (let [override (role-override magi-cfg role)
        override* (cond-> override
                    (:timeout-ms magi-cfg) (assoc :timeout-ms (:timeout-ms magi-cfg)))]
    (if (and default-provider (nil-override? override))
      default-provider
      (llm-service/create-llm-provider-with-override (:llm cfg) override*))))

(defn- provider-selection [cfg magi-cfg role]
  (llm-service/resolve-provider-selection (:llm cfg)
                                          (role-override magi-cfg role)))

(defn create-service
  ([cfg] (create-service cfg {}))
  ([cfg {:keys [default-provider providers prompt-overrides]}]
   (let [magi-cfg (default-magi-config cfg)
         roles (conj participant-order :filter :judge)
         providers* (into {}
                          (map (fn [role]
                                 [role (or (get providers role)
                                           (provider-for cfg default-provider magi-cfg role))]))
                          roles)
         selections (into {}
                          (map (fn [role] [role (provider-selection cfg magi-cfg role)]))
                          roles)]
     {:config magi-cfg
      :providers providers*
      :provider-selections selections
      :prompts (merge (prompts) prompt-overrides)})))

(defn enabled? [service]
  (true? (get-in service [:config :enabled?])))

(defn tool-enabled? [service]
  (true? (get-in service [:config :tool :enabled])))

(defn mode [service]
  (normalize-keyword (get-in service [:config :mode] :assistive)))

(defn memory-promotion-config [service]
  (merge {:mode :manual
          :scopes #{:all}
          :poll-interval-seconds 60
          :failure-cooldown-minutes 15
          :max-candidates 10}
         (get-in service [:config :memory-promotion])))

(defn memory-promotion-mode [service]
  (normalize-keyword (:mode (memory-promotion-config service))))

(defn memory-promotion-enabled? [service]
  (and (enabled? service)
       (not= :off (memory-promotion-mode service))))

(defn fallback [service]
  (normalize-keyword (get-in service [:config :fallback] :human)))

(defn provider-summary [service]
  (:provider-selections service))

(defn- timeout-ms [service]
  (long (or (get-in service [:config :timeout-ms]) 30000)))

(defn- max-context-chars [service]
  (long (or (get-in service [:config :max-context-chars]) 12000)))

(defn classify [service request]
  (let [payload (-> request
                    (update :context compact-context (max-context-chars service)))
        raw (invoke-json (get-in service [:providers :filter])
                         :filter
                         (get-in service [:prompts :filter])
                         payload
                         filter-schema
                         (timeout-ms service))
        normalized (normalize-filter-output raw)]
    (update normalized :context #(merge (:context payload) (or % {})))))

(defn- agent-payload [filter-result]
  {:kind (name (:kind filter-result))
   :domain (name (:domain filter-result))
   :risk (name (:risk filter-result))
   :question (:question filter-result)
   :expected_response (name (:expected-response filter-result))
   :context (:context filter-result)})

(defn ask-agent [service role filter-result]
  (try
    (normalize-agent-output
     (invoke-json (get-in service [:providers role])
                  role
                  (get-in service [:prompts role])
                  (agent-payload filter-result)
                  agent-schema
                  (timeout-ms service)))
    (catch Exception e
      {:response :error
       :comment (.getMessage e)})))

(defn- timed-agent [service role filter-result]
  (let [task (future (ask-agent service role filter-result))
        result (deref task (timeout-ms service) ::timeout)]
    (if (= ::timeout result)
      (do
        (future-cancel task)
        {:response :error
         :comment "timeout"})
      result)))

(defn ask-triumvirate [service filter-result]
  (let [run-one (fn [role] [role (timed-agent service role filter-result)])]
    (if (= :parallel (normalize-keyword (get-in service [:config :execution])))
      (let [tasks (mapv (fn [role] [role (future (timed-agent service role filter-result))])
                        participant-order)]
        (into {}
              (map (fn [[role task]]
                     [role (let [result (deref task (timeout-ms service) ::timeout)]
                             (if (= ::timeout result)
                               (do
                                 (future-cancel task)
                                 {:response :error
                                  :comment "timeout"})
                               result))]))
              tasks))
      (into {} (map run-one participant-order)))))

(defn- comments-for [agents response]
  (->> participant-order
       (keep (fn [role]
               (let [agent-result (get agents role)]
                 (when (= response (:response agent-result))
                   (str (name role) ": " (or (:comment agent-result) (name response)))))))
       (str/join "; ")))

(defn judge-responses [agents]
  (let [responses (set (map :response (vals agents)))]
    (cond
      (contains? responses :error)
      {:decision :error
       :reason (or (not-empty (comments-for agents :error)) "one or more agents errored")}

      (contains? responses :info)
      {:decision :info
       :reason (or (not-empty (comments-for agents :info)) "question is not yes-no")}

      (contains? responses :no)
      {:decision :no
       :reason (or (not-empty (comments-for agents :no)) "one or more agents answered no")}

      (contains? responses :conditional)
      {:decision :conditional
       :reason (or (not-empty (comments-for agents :conditional)) "one or more agents answered conditional")}

      (= #{:yes} responses)
      {:decision :yes
       :reason "all agents answered yes"}

      :else
      {:decision :error
       :reason "invalid agent response set"})))

(defn judge [service agents]
  (let [expected (judge-responses agents)
        payload {:agents (into {}
                               (map (fn [role]
                                      [(name role)
                                       (let [response (get agents role)]
                                         (cond-> {:response (name (:response response))}
                                           (:comment response) (assoc :comment (:comment response))))]))
                               participant-order)}
        raw (invoke-json (get-in service [:providers :judge])
                         :judge
                         (get-in service [:prompts :judge])
                         payload
                         judge-schema
                         (timeout-ms service))
        actual (normalize-judge-output raw)]
    (if (= (:decision expected) (:decision actual))
      (assoc expected :reason (:reason actual))
      {:decision :error
       :reason (str "judge output mismatch: expected "
                    (name (:decision expected))
                    ", got "
                    (name (:decision actual)))})))

(defn- info-result [service filter-result reason]
  {:decision :info
   :reason reason
   :filter filter-result
   :agents {}
   :providers (provider-summary service)})

(defn decide [service request]
  (let [filter-result (try
                        (classify service request)
                        (catch Exception e
                          {:kind :unsupported
                           :domain :other
                           :risk :high
                           :question (:question request)
                           :expected-response :permit
                           :context {}
                           :error (.getMessage e)}))]
    (cond
      (:error filter-result)
      {:decision :error
       :reason (:error filter-result)
       :filter filter-result
       :agents {}
       :providers (provider-summary service)}

      (= :unsupported (:kind filter-result))
      (info-result service filter-result "unsupported question")

      (and (= :critical (:risk filter-result))
           (not (true? (get-in service [:config :allow-critical?]))))
      (info-result service filter-result "critical risk requires human approval")

      (not= :yes-no (:kind filter-result))
      (info-result service filter-result "question is not yes-no")

      :else
      (let [agents (ask-triumvirate service filter-result)
            judge-result (try
                           (judge service agents)
                           (catch Exception e
                             {:decision :error
                              :reason (.getMessage e)}))]
        (merge judge-result
               {:filter filter-result
                :agents agents
                :providers (provider-summary service)})))))

(defn- tool-family [tool-name]
  (case tool-name
    :shell :shell
    (:fs_read :fs_write :fs_create :fs_replace :fs_list :fs_delete :fs_mkdir) :fs
    (:memory_recall :vault_search :scratchpad_read :scratchpad_search
     :scratchpad_replace :memory_extract_session :message_search) :memory
    nil))

(defn- tool-categories [tool-description]
  (let [tool-name (:name tool-description)]
    (set (remove nil?
                 (concat [tool-name
                          (tool-family tool-name)
                          (:category tool-description)
                          (:operation tool-description)]
                         (:routing-categories tool-description))))))

(defn approval-applicable? [service tool-description]
  (let [cfg (:config service)
        apply-to (set (:apply-to cfg))
        configured (set (:tool-categories cfg))]
    (and (map? tool-description)
         (enabled? service)
         (contains? apply-to :tool-approvals)
         (or (contains? configured :all)
             (seq (set/intersection configured
                                    (tool-categories tool-description)))))))

(defn approval-question [approval tool-description input context]
  {:kind :yes-no
   :domain :tool-approval
   :expected-response :permit
   :question "Should Iris allow this tool execution?"
   :context (cond-> {:tool-name (some-> (:name tool-description) name)
                     :category (some-> (:category tool-description) name)
                     :operation (some-> (:operation tool-description) name)
                     :routing-categories (mapv name (:routing-categories tool-description))
                     :approval-id (:id approval)
                     :requested-by (:requested-by approval)
                     :requested-permissions (mapv name (:requested-permissions approval))
                     :reason (:reason approval)
                     :input input
                     :user (:user context)
                     :request-id (:request-id context)}
              (:magi-context context)
              (assoc :request-context (:magi-context context)))})

(ns agent.api.serializers
  "Pure transforms from internal entity maps to JSON-shaped response maps.

  Serializers are declared as data: a field spec maps response keys to
  sources, and `serialize` interprets the spec against an entity. A source
  is one of:

  - a keyword: the value is looked up in the entity as-is
  - a vector `[k f]`: transform `f` is applied to the looked-up value
  - a fn: called with the whole entity

  Optional fields (a second spec map) are assoc'd only when the source
  value is truthy; everything else is always present."
  (:require
   [agent.api.event-compat :as event-compat]
   [clojure.string :as str]))

(defn- ->name [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)))

(defn- json-key [k]
  (keyword (str/replace (->name k) "-" "_")))

(defn- json-keys [m]
  (some-> m (update-keys json-key)))

(defn- each
  "Collection transform: maps `f` over the value with `mapv`."
  [f]
  (fn [coll] (mapv f coll)))

(defn- field-value [entity source]
  (cond
    (keyword? source) (get entity source)
    (vector? source) (let [[k f] source] (f (get entity k)))
    :else (source entity)))

(defn- serialize
  "Interpret field specs against `entity` (see ns docstring for the spec
  shape). `fields` are always present in the output; `optional` entries are
  assoc'd only when the source value is truthy (for `[k f]` sources, the raw
  value before transform)."
  ([entity fields]
   (serialize entity fields nil))
  ([entity fields optional]
   (reduce-kv
    (fn [m response-key source]
      (if (if (vector? source)
            (get entity (first source))
            (field-value entity source))
        (assoc m response-key (field-value entity source))
        m))
    (reduce-kv
     (fn [m response-key source]
       (assoc m response-key (field-value entity source)))
     {}
     fields)
    optional)))

(def ^:private session-state-fields
  {:working [:working? boolean]
   :queued_count #(get % :queued-count 0)
   :active_mode :active-mode
   :active_provider :active-provider
   :active_model :active-model
   :active_request_id :active-request-id
   :active_started_at :active-started-at})

(defn session->response [session]
  (serialize session
             {:id :id
              :title :title
              :active_mode :active-mode
              :created_at :created-at}
             {:state [:state #(serialize % session-state-fields)]}))

(defn message->response [message]
  (serialize message
             {:id :id
              :role :role
              :content :content
              :created_at :created-at}
             {:tool_calls :tool-calls
              :tool_call_id :tool-call-id
              :metadata :metadata
              :excluded_from_context [:excluded-from-context? boolean]}))

(defn tool->response [tool]
  (serialize tool
             {:name [:name ->name]
              :description :description
              :version :version
              :category [:category ->name]
              :required_permissions [:required-permissions (each name)]
              :source [:source ->name]
              :source_details :source-details}))

(defn skill->response [skill]
  (serialize skill
             {:name :name
              :description :description
              :path :path
              :base_dir :base-dir
              :source [:source ->name]}))

(defn channel-adapter->response [adapter]
  (serialize adapter
             {:name [:name ->name]
              :display_name :display-name
              :inbound_mode [:inbound-mode ->name]
              :capabilities [:capabilities (each name)]
              :public_url_required :public-url-required?
              :source [:source ->name]}))

(defn agent->response [agent]
  (serialize agent
             {:id :id
              :name :name
              :kind :kind
              :role :role
              :parent_id :parent-id
              :logical_address :logical-address
              :capabilities [:capabilities vec]
              :tool_access [:tool-access vec]
              :memory_scopes [:memory-scopes vec]
              :budgets :budgets
              :task :task
              :state :state
              :allow_direct :allow-direct?
              :status :status
              :created_at :created-at
              :message_count :message-count}))

(defn interop->response [interop]
  (serialize interop
             {:id :id
              :origin_message_id :origin-message-id
              :request_id :request-id
              :message_type :message-type
              :delivery_mode :delivery-mode
              :from_agent_id :from-agent-id
              :from_peer_id :from-peer-id
              :to_agent_id :to-agent-id
              :to_peer_id :to-peer-id
              :remote_agent_id :remote-agent-id
              :from_address :from-address
              :to_address :to-address
              :route :route
              :content :content
              :status :status
              :delivery_count :delivery-count
              :created_at :created-at
              :last_delivered_at :last-delivered-at
              :forwarded_at :forwarded-at
              :acked_at :acked-at
              :acknowledged_by :acknowledged-by
              :ack_type :ack-type
              :last_error :last-error}))

(defn federated-peer->response [peer]
  (serialize peer
             {:id :id
              :name :name
              :base_url :base-url
              :logical_address_prefix :logical-address-prefix
              :capabilities :capabilities
              :key_ids :key-ids
              :status :status
              :created_at :created-at}))

(defn channel->response [channel]
  (serialize channel
             {:id :id
              :name :name
              :participants [:participants vec]
              :created_at :created-at
              :message_count :message-count}))

(defn channel-message->response [message]
  (serialize message
             {:id :id
              :sender_id :sender-id
              :channel_id :channel-id
              :content :content
              :created_at :created-at}))

(defn event->response [event]
  (serialize (event-compat/canonicalize-event event)
             {:id :id
              :event_type :event-type
              :entity_type :entity-type
              :entity_id :entity-id
              :request_id :request-id
              :payload :payload
              :created_at :created-at}))

(defn approval->response [approval]
  (serialize approval
             {:id :id
              :tool_name :tool-name
              :status :status
              :input :input
              :input_hash :input-hash
              :requested_permissions [:requested-permissions (each name)]
              :requested_by :requested-by
              :reason :reason
              :actor :actor
              :decision_reason :decision-reason
              :expires_at :expires-at
              :created_at :created-at
              :decided_at :decided-at}))

(defn run->response [run]
  (when run
    (serialize run
               {:id :id
                :idempotency_key :idempotency-key
                :agent_id :agent-id
                :parent_run_id :parent-run-id
                :lease_id :lease-id
                :name :name
                :substrate :substrate
                :status :status
                :capabilities :capabilities
                :network_identity :network-identity
                :runner_metadata :runner-metadata
                :run_options :run-options
                :requested_by :requested-by
                :last_error :last-error
                :created_at :created-at
                :started_at :started-at
                :finished_at :finished-at
                :lease [:lease json-keys]
                :heartbeat [:heartbeat json-keys]
                :checkpoint [:checkpoint json-keys]
                :pending_commands [:pending-commands (each json-keys)]})))

(defn heartbeat->response [heartbeat]
  (serialize heartbeat
             {:run_id :run-id
              :sequence_no :sequence-no
              :status :status
              :metrics :metrics
              :observed_at :observed-at}))

(defn checkpoint->response [checkpoint]
  (serialize checkpoint
             {:id :id
              :run_id :run-id
              :sequence_no :sequence-no
              :checkpoint_type :checkpoint-type
              :state :state
              :created_at :created-at}))

(defn run-command->response [command]
  (serialize command
             {:id :id
              :run_id :run-id
              :command_type :command-type
              :payload :payload
              :request_id :request-id
              :response :response
              :status :status
              :created_at :created-at
              :acknowledged_at :acknowledged-at
              :completed_at :completed-at
              :error :error}))

(defn memory-surface->response [surface]
  (serialize surface
             {:name [:name ->name]
              :type [:type ->name]
              :writable [:writable boolean]
              :enabled [:enabled boolean]
              :paths :paths
              :default_limit :default-limit
              :max_limit :max-limit
              :min_score :min-score}))

(defn fact->response [fact]
  (serialize fact
             {:id :id
              :scope :scope
              :subject :subject
              :predicate :predicate
              :object :object
              :source_session_id :source-session-id
              :source_message_ids :source-message-ids
              :source_request_id :source-request-id
              :confidence :confidence
              :created :created?
              :created_at :created-at
              :updated_at :updated-at}))

(defn model->response [model]
  (serialize model
             {:provider [:provider ->name]
              :api_kind [:api-kind ->name]
              :model_id :model-id
              :display_name :display-name
              :context_window :context-window
              :max_output_tokens :max-output-tokens
              :input_modalities [:input-modalities (each name)]
              :tool_support :tool-support
              :reasoning_levels [:reasoning-levels (each name)]
              :cache_support :cache-support
              :transport_support [:transport-support (each name)]
              :usage_cost_support :usage-cost-support}))

(defn provider->response
  "Expects the provider map to carry :active-provider (the registry's
  currently active provider key)."
  [provider]
  (serialize provider
             {:key [:key name]
              :active #(= (:key %) (:active-provider %))
              :api_kind (comp ->name :api-kind :metadata)
              :display_name (comp :display-name :metadata)
              :api_key_configured :api-key-configured?
              :options :options
              :models [:models (each model->response)]}))

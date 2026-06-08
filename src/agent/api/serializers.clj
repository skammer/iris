(ns agent.api.serializers
  "Pure transforms from internal entity maps to JSON-shaped response maps."
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

(defn session->response [session]
  (cond-> {:id (:id session)
           :title (:title session)
           :active_mode (:active-mode session)
           :created_at (:created-at session)}
    (:state session)
    (assoc :state {:working (boolean (get-in session [:state :working?]))
                   :queued_count (get-in session [:state :queued-count] 0)
                   :active_mode (get-in session [:state :active-mode])
                   :active_provider (get-in session [:state :active-provider])
                   :active_model (get-in session [:state :active-model])
                   :active_request_id (get-in session [:state :active-request-id])
                   :active_started_at (get-in session [:state :active-started-at])})))

(defn message->response [message]
  (cond-> {:id (:id message)
           :role (:role message)
           :content (:content message)
           :created_at (:created-at message)}
    (:tool-calls message) (assoc :tool_calls (:tool-calls message))
    (:tool-call-id message) (assoc :tool_call_id (:tool-call-id message))
    (:metadata message) (assoc :metadata (:metadata message))
    (:excluded-from-context? message) (assoc :excluded_from_context true)))

(defn tool->response [tool]
  {:name (->name (:name tool))
   :description (:description tool)
   :version (:version tool)
   :category (->name (:category tool))
   :required_permissions (mapv name (:required-permissions tool))
   :source (->name (:source tool))
   :source_details (:source-details tool)})

(defn skill->response [skill]
  {:name (:name skill)
   :description (:description skill)
   :path (:path skill)
   :base_dir (:base-dir skill)
   :source (->name (:source skill))})

(defn channel-adapter->response [adapter]
  {:name (->name (:name adapter))
   :display_name (:display-name adapter)
   :inbound_mode (->name (:inbound-mode adapter))
   :capabilities (mapv name (:capabilities adapter))
   :public_url_required (:public-url-required? adapter)
   :source (->name (:source adapter))})

(defn agent->response [agent]
  {:id (:id agent)
   :name (:name agent)
   :kind (:kind agent)
   :role (:role agent)
   :parent_id (:parent-id agent)
   :logical_address (:logical-address agent)
   :capabilities (vec (:capabilities agent))
   :tool_access (vec (:tool-access agent))
   :memory_scopes (vec (:memory-scopes agent))
   :budgets (:budgets agent)
   :task (:task agent)
   :state (:state agent)
   :allow_direct (:allow-direct? agent)
   :status (:status agent)
   :created_at (:created-at agent)
   :message_count (:message-count agent)})

(defn interop->response [interop]
  {:id (:id interop)
   :origin_message_id (:origin-message-id interop)
   :request_id (:request-id interop)
   :message_type (:message-type interop)
   :delivery_mode (:delivery-mode interop)
   :from_agent_id (:from-agent-id interop)
   :from_peer_id (:from-peer-id interop)
   :to_agent_id (:to-agent-id interop)
   :to_peer_id (:to-peer-id interop)
   :remote_agent_id (:remote-agent-id interop)
   :from_address (:from-address interop)
   :to_address (:to-address interop)
   :route (:route interop)
   :content (:content interop)
   :status (:status interop)
   :delivery_count (:delivery-count interop)
   :created_at (:created-at interop)
   :last_delivered_at (:last-delivered-at interop)
   :forwarded_at (:forwarded-at interop)
   :acked_at (:acked-at interop)
   :acknowledged_by (:acknowledged-by interop)
   :ack_type (:ack-type interop)
   :last_error (:last-error interop)})

(defn federated-peer->response [peer]
  {:id (:id peer)
   :name (:name peer)
   :base_url (:base-url peer)
   :logical_address_prefix (:logical-address-prefix peer)
   :capabilities (:capabilities peer)
   :key_ids (:key-ids peer)
   :status (:status peer)
   :created_at (:created-at peer)})

(defn channel->response [channel]
  {:id (:id channel)
   :name (:name channel)
   :participants (vec (:participants channel))
   :created_at (:created-at channel)
   :message_count (:message-count channel)})

(defn channel-message->response [message]
  {:id (:id message)
   :sender_id (:sender-id message)
   :channel_id (:channel-id message)
   :content (:content message)
   :created_at (:created-at message)})

(defn event->response [event]
  (let [event* (event-compat/canonicalize-event event)]
    {:id (:id event*)
     :event_type (:event-type event*)
     :entity_type (:entity-type event*)
     :entity_id (:entity-id event*)
     :request_id (:request-id event*)
     :payload (:payload event*)
     :created_at (:created-at event*)}))

(defn approval->response [approval]
  {:id (:id approval)
   :tool_name (:tool-name approval)
   :status (:status approval)
   :input (:input approval)
   :input_hash (:input-hash approval)
   :requested_permissions (mapv name (:requested-permissions approval))
   :requested_by (:requested-by approval)
   :reason (:reason approval)
   :actor (:actor approval)
   :decision_reason (:decision-reason approval)
   :expires_at (:expires-at approval)
   :created_at (:created-at approval)
   :decided_at (:decided-at approval)})

(defn run->response [run]
  (when run
    {:id (:id run)
     :idempotency_key (:idempotency-key run)
     :agent_id (:agent-id run)
     :parent_run_id (:parent-run-id run)
     :lease_id (:lease-id run)
     :name (:name run)
     :substrate (:substrate run)
     :status (:status run)
     :capabilities (:capabilities run)
     :network_identity (:network-identity run)
     :runner_metadata (:runner-metadata run)
     :run_options (:run-options run)
     :requested_by (:requested-by run)
     :last_error (:last-error run)
     :created_at (:created-at run)
     :started_at (:started-at run)
     :finished_at (:finished-at run)
     :lease (json-keys (:lease run))
     :heartbeat (json-keys (:heartbeat run))
     :checkpoint (json-keys (:checkpoint run))
     :pending_commands (mapv json-keys (:pending-commands run))}))

(defn heartbeat->response [heartbeat]
  {:run_id (:run-id heartbeat)
   :sequence_no (:sequence-no heartbeat)
   :status (:status heartbeat)
   :metrics (:metrics heartbeat)
   :observed_at (:observed-at heartbeat)})

(defn checkpoint->response [checkpoint]
  {:id (:id checkpoint)
   :run_id (:run-id checkpoint)
   :sequence_no (:sequence-no checkpoint)
   :checkpoint_type (:checkpoint-type checkpoint)
   :state (:state checkpoint)
   :created_at (:created-at checkpoint)})

(defn run-command->response [command]
  {:id (:id command)
   :run_id (:run-id command)
   :command_type (:command-type command)
   :payload (:payload command)
   :request_id (:request-id command)
   :response (:response command)
   :status (:status command)
   :created_at (:created-at command)
   :acknowledged_at (:acknowledged-at command)
   :completed_at (:completed-at command)
   :error (:error command)})

(defn memory-surface->response [surface]
  {:name (->name (:name surface))
   :type (->name (:type surface))
   :writable (boolean (:writable surface))
   :enabled (boolean (:enabled surface))
   :paths (:paths surface)
   :default_limit (:default-limit surface)
   :max_limit (:max-limit surface)
   :min_score (:min-score surface)})

(defn fact->response [fact]
  {:id (:id fact)
   :scope (:scope fact)
   :subject (:subject fact)
   :predicate (:predicate fact)
   :object (:object fact)
   :source_session_id (:source-session-id fact)
   :source_message_ids (:source-message-ids fact)
   :source_request_id (:source-request-id fact)
   :confidence (:confidence fact)
   :created (:created? fact)
   :created_at (:created-at fact)
   :updated_at (:updated-at fact)})

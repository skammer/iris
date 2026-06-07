CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  title TEXT,
  active_mode TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  tool_calls TEXT,
  tool_call_id TEXT,
  metadata_json TEXT,
  excluded_from_context INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_messages_session_created
ON messages(session_id, created_at);

CREATE TABLE IF NOT EXISTS completions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT,
  provider TEXT NOT NULL,
  model TEXT,
  prompt TEXT,
  response TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_completions_created
ON completions(created_at);

CREATE TABLE IF NOT EXISTS agent_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_type TEXT NOT NULL,
  entity_type TEXT,
  entity_id TEXT,
  request_id TEXT,
  payload TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_events_created
ON agent_events(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_events_entity
ON agent_events(entity_type, entity_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_events_request
ON agent_events(request_id, created_at DESC);

CREATE TABLE IF NOT EXISTS tool_approvals (
  id TEXT PRIMARY KEY,
  tool_name TEXT NOT NULL,
  status TEXT NOT NULL,
  input_json TEXT NOT NULL,
  input_hash TEXT,
  requested_permissions_json TEXT,
  requested_by TEXT,
  reason TEXT,
  actor TEXT,
  decision_reason TEXT,
  expires_at TEXT,
  created_at TEXT NOT NULL,
  decided_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_tool_approvals_status_created
ON tool_approvals(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tool_approvals_tool_created
ON tool_approvals(tool_name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tool_approvals_expires
ON tool_approvals(expires_at);

CREATE TABLE IF NOT EXISTS agent_runs (
  id TEXT PRIMARY KEY,
  idempotency_key TEXT,
  agent_id TEXT NOT NULL,
  parent_run_id TEXT,
  lease_id TEXT,
  name TEXT,
  substrate TEXT NOT NULL,
  status TEXT NOT NULL,
  capabilities_json TEXT,
  network_identity_json TEXT,
  bootstrap_token TEXT,
  bootstrap_spec_json TEXT,
  runner_metadata_json TEXT,
  runner_options_json TEXT,
  requested_by TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL,
  started_at TEXT,
  finished_at TEXT,
  FOREIGN KEY(parent_run_id) REFERENCES agent_runs(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_idempotency_key
ON agent_runs(idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_runs_status_created
ON agent_runs(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_created
ON agent_runs(agent_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_created
ON agent_runs(parent_run_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_leases (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  holder_id TEXT,
  status TEXT NOT NULL,
  acquired_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  released_at TEXT,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_run_leases_run_acquired
ON agent_run_leases(run_id, acquired_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_heartbeats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  status TEXT,
  metrics_json TEXT,
  observed_at TEXT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_heartbeats_run_sequence
ON agent_run_heartbeats(run_id, sequence_no)
WHERE sequence_no IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_heartbeats_run_observed
ON agent_run_heartbeats(run_id, observed_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_commands (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  payload_json TEXT,
  request_id TEXT,
  response_json TEXT,
  status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  acknowledged_at TEXT,
  completed_at TEXT,
  error TEXT,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_commands_run_request
ON agent_run_commands(run_id, request_id)
WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_created
ON agent_run_commands(run_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_status_created
ON agent_run_commands(run_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_checkpoints (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  checkpoint_type TEXT NOT NULL,
  state_json TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_checkpoints_run_sequence_type
ON agent_run_checkpoints(run_id, sequence_no, checkpoint_type)
WHERE sequence_no IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoints_run_created
ON agent_run_checkpoints(run_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_activities (
  activity_key TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  command_id TEXT,
  activity_name TEXT NOT NULL,
  status TEXT NOT NULL,
  input_json TEXT,
  result_json TEXT,
  error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE,
  FOREIGN KEY(command_id) REFERENCES agent_run_commands(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_run_activities_run_created
ON agent_run_activities(run_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_run_activities_command_created
ON agent_run_activities(command_id, created_at DESC);

CREATE TABLE IF NOT EXISTS federation_peer_keys (
  peer_id TEXT NOT NULL,
  key_id TEXT NOT NULL,
  public_key TEXT NOT NULL,
  status TEXT NOT NULL,
  valid_from TEXT,
  valid_until TEXT,
  created_at TEXT NOT NULL,
  PRIMARY KEY(peer_id, key_id)
);

CREATE INDEX IF NOT EXISTS idx_federation_peer_keys_status
ON federation_peer_keys(peer_id, status);

CREATE TABLE IF NOT EXISTS federation_nonces (
  peer_id TEXT NOT NULL,
  nonce TEXT NOT NULL,
  seen_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  PRIMARY KEY(peer_id, nonce)
);

CREATE INDEX IF NOT EXISTS idx_federation_nonces_expires
ON federation_nonces(expires_at);

CREATE TABLE IF NOT EXISTS federation_outbox (
  id TEXT PRIMARY KEY,
  peer_id TEXT NOT NULL,
  key_id TEXT,
  url TEXT,
  envelope_json TEXT NOT NULL,
  state TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT,
  last_error TEXT,
  last_status INTEGER,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_federation_outbox_state_next
ON federation_outbox(state, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_federation_outbox_peer_created
ON federation_outbox(peer_id, created_at DESC);

CREATE TABLE IF NOT EXISTS memory_facts (
  id TEXT PRIMARY KEY,
  scope_type TEXT NOT NULL,
  scope_id TEXT,
  subject TEXT NOT NULL,
  predicate TEXT NOT NULL,
  object TEXT NOT NULL,
  normalized_subject TEXT NOT NULL,
  normalized_predicate TEXT NOT NULL,
  normalized_object TEXT NOT NULL,
  source_session_id TEXT,
  source_message_ids_json TEXT,
  source_request_id TEXT,
  confidence REAL,
  status TEXT NOT NULL,
  metadata_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_facts_dedup
ON memory_facts(
  scope_type,
  coalesce(scope_id, ''),
  normalized_subject,
  normalized_predicate,
  normalized_object
);

CREATE INDEX IF NOT EXISTS idx_memory_facts_scope_updated
ON memory_facts(scope_type, scope_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS channel_session_mappings (
  source TEXT NOT NULL,
  external_chat_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  metadata_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(source, external_chat_id),
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_channel_session_mappings_session
ON channel_session_mappings(session_id);

CREATE TABLE IF NOT EXISTS channel_offsets (
  source TEXT PRIMARY KEY,
  next_offset INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS channel_inbox (
  source TEXT NOT NULL,
  update_id INTEGER NOT NULL,
  status TEXT NOT NULL,
  raw_json TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(source, update_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_inbox_status_updated
ON channel_inbox(source, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS session_entries (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  parent_id TEXT,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
  FOREIGN KEY(parent_id) REFERENCES session_entries(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_session_entries_session_created
ON session_entries(session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_session_entries_parent
ON session_entries(parent_id);

CREATE TABLE IF NOT EXISTS session_leaf_selection (
  session_id TEXT PRIMARY KEY,
  leaf_entry_id TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
  FOREIGN KEY(leaf_entry_id) REFERENCES session_entries(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS todo_lists (
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL,
  slug TEXT NOT NULL,
  description TEXT NOT NULL,
  metadata_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_todo_lists_thread_slug
ON todo_lists(thread_id, slug);

CREATE INDEX IF NOT EXISTS idx_todo_lists_thread_updated
ON todo_lists(thread_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS todo_items (
  id TEXT PRIMARY KEY,
  list_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  content TEXT NOT NULL,
  description TEXT NOT NULL,
  status TEXT NOT NULL,
  priority TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(list_id) REFERENCES todo_lists(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_todo_items_list_position
ON todo_items(list_id, position);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
USING fts5(content, content='messages', content_rowid='id');

CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content)
  VALUES('delete', old.id, old.content);
END;

CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content)
  VALUES('delete', old.id, old.content);
  INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
END;

CREATE VIRTUAL TABLE IF NOT EXISTS agent_events_fts
USING fts5(event_type, entity_id, payload, content='agent_events', content_rowid='id');

CREATE TRIGGER IF NOT EXISTS agent_events_fts_ai AFTER INSERT ON agent_events BEGIN
  INSERT INTO agent_events_fts(rowid, event_type, entity_id, payload)
  VALUES (new.id, new.event_type, new.entity_id, new.payload);
END;

CREATE TRIGGER IF NOT EXISTS agent_events_fts_ad AFTER DELETE ON agent_events BEGIN
  INSERT INTO agent_events_fts(agent_events_fts, rowid, event_type, entity_id, payload)
  VALUES('delete', old.id, old.event_type, old.entity_id, old.payload);
END;

CREATE TRIGGER IF NOT EXISTS agent_events_fts_au AFTER UPDATE ON agent_events BEGIN
  INSERT INTO agent_events_fts(agent_events_fts, rowid, event_type, entity_id, payload)
  VALUES('delete', old.id, old.event_type, old.entity_id, old.payload);
  INSERT INTO agent_events_fts(rowid, event_type, entity_id, payload)
  VALUES (new.id, new.event_type, new.entity_id, new.payload);
END;

CREATE VIRTUAL TABLE IF NOT EXISTS memory_facts_fts
USING fts5(subject, predicate, object, metadata_json, content='memory_facts');

CREATE TRIGGER IF NOT EXISTS memory_facts_fts_ai AFTER INSERT ON memory_facts BEGIN
  INSERT INTO memory_facts_fts(rowid, subject, predicate, object, metadata_json)
  VALUES (new.rowid, new.subject, new.predicate, new.object, new.metadata_json);
END;

CREATE TRIGGER IF NOT EXISTS memory_facts_fts_ad AFTER DELETE ON memory_facts BEGIN
  INSERT INTO memory_facts_fts(memory_facts_fts, rowid, subject, predicate, object, metadata_json)
  VALUES('delete', old.rowid, old.subject, old.predicate, old.object, old.metadata_json);
END;

CREATE TRIGGER IF NOT EXISTS memory_facts_fts_au AFTER UPDATE ON memory_facts BEGIN
  INSERT INTO memory_facts_fts(memory_facts_fts, rowid, subject, predicate, object, metadata_json)
  VALUES('delete', old.rowid, old.subject, old.predicate, old.object, old.metadata_json);
  INSERT INTO memory_facts_fts(rowid, subject, predicate, object, metadata_json)
  VALUES (new.rowid, new.subject, new.predicate, new.object, new.metadata_json);
END;

CREATE VIRTUAL TABLE IF NOT EXISTS todo_items_fts
USING fts5(content, description, status, priority, content='todo_items');

CREATE TRIGGER IF NOT EXISTS todo_items_fts_ai AFTER INSERT ON todo_items BEGIN
  INSERT INTO todo_items_fts(rowid, content, description, status, priority)
  VALUES (new.rowid, new.content, new.description, new.status, new.priority);
END;

CREATE TRIGGER IF NOT EXISTS todo_items_fts_ad AFTER DELETE ON todo_items BEGIN
  INSERT INTO todo_items_fts(todo_items_fts, rowid, content, description, status, priority)
  VALUES('delete', old.rowid, old.content, old.description, old.status, old.priority);
END;

CREATE TRIGGER IF NOT EXISTS todo_items_fts_au AFTER UPDATE ON todo_items BEGIN
  INSERT INTO todo_items_fts(todo_items_fts, rowid, content, description, status, priority)
  VALUES('delete', old.rowid, old.content, old.description, old.status, old.priority);
  INSERT INTO todo_items_fts(rowid, content, description, status, priority)
  VALUES (new.rowid, new.content, new.description, new.status, new.priority);
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_runs_requested_event
AFTER INSERT ON agent_runs
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES ('agent.run.requested', 'agent_run', NEW.id, NEW.idempotency_key,
          json_object('agent-id', NEW.agent_id,
                      'name', NEW.name,
                      'parent-run-id', NEW.parent_run_id,
                      'substrate', NEW.substrate,
                      'lease-id', NEW.lease_id,
                      'requested-by', NEW.requested_by,
                      'capabilities', json(NEW.capabilities_json),
                      'runner-options', json(NEW.runner_options_json)),
          NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_runs_status_event
AFTER UPDATE OF status ON agent_runs
WHEN OLD.status <> NEW.status
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES (CASE
            WHEN NEW.status = 'running' THEN 'agent.run.registered'
            ELSE 'agent.run.' || NEW.status
          END,
          'agent_run', NEW.id, NEW.idempotency_key,
          json_object('status', NEW.status,
                      'last-error', NEW.last_error,
                      'agent-id', NEW.agent_id,
                      'network-identity', json(NEW.network_identity_json),
                      'runner-metadata', json(NEW.runner_metadata_json),
                      'finished-at', NEW.finished_at),
          strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_run_heartbeats_event
AFTER INSERT ON agent_run_heartbeats
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES ('agent.run.heartbeat', 'agent_run', NEW.run_id, NULL,
          json_object('sequence-no', NEW.sequence_no,
                      'status', NEW.status),
          NEW.observed_at);
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_run_checkpoints_event
AFTER INSERT ON agent_run_checkpoints
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES ('agent.run.checkpointed', 'agent_run', NEW.run_id, NULL,
          json_object('sequence-no', NEW.sequence_no,
                      'checkpoint-type', NEW.checkpoint_type),
          NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_run_commands_enqueued_event
AFTER INSERT ON agent_run_commands
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES ('agent.run.command.enqueued', 'agent_run', NEW.run_id, NEW.request_id,
          json_object('command-id', NEW.id,
                      'command-type', NEW.command_type,
                      'request-id', NEW.request_id),
          NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS trg_agent_run_commands_status_event
AFTER UPDATE OF status ON agent_run_commands
WHEN OLD.status <> NEW.status
BEGIN
  INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
  VALUES (CASE
            WHEN NEW.status = 'acknowledged' THEN 'agent.run.command.acknowledged'
            ELSE 'agent.run.command.completed'
          END,
          'agent_run', NEW.run_id, NEW.request_id,
          json_object('command-id', NEW.id,
                      'request-id', NEW.request_id,
                      'status', NEW.status,
                      'error', NEW.error,
                      'response', json(NEW.response_json)),
          coalesce(NEW.completed_at, NEW.acknowledged_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')));
END;

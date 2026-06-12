DROP TRIGGER IF EXISTS trg_agent_run_commands_status_event;
DROP TRIGGER IF EXISTS trg_agent_run_commands_enqueued_event;
DROP TRIGGER IF EXISTS trg_agent_run_checkpoints_event;
DROP TRIGGER IF EXISTS trg_agent_run_heartbeats_event;
DROP TRIGGER IF EXISTS trg_agent_runs_status_event;
DROP TRIGGER IF EXISTS trg_agent_runs_requested_event;

DROP TABLE IF EXISTS agent_run_activities;
DROP TABLE IF EXISTS agent_run_checkpoints;
DROP TABLE IF EXISTS agent_run_commands;
DROP TABLE IF EXISTS agent_run_heartbeats;
DROP TABLE IF EXISTS agent_run_leases;
DROP TABLE IF EXISTS agent_runs;

-- Match transcript ordering, including queued messages activated later.
CREATE INDEX IF NOT EXISTS idx_messages_session_display_order
ON messages(session_id, coalesce(json_extract(metadata_json, '$.activated-at'), created_at), id);

-- Bounded rich-content lookup should not parse every entry in a long session.
CREATE INDEX IF NOT EXISTS idx_session_entries_message_id
ON session_entries(session_id, cast(json_extract(payload_json, '$."message-id"') as integer))
WHERE type = 'message';

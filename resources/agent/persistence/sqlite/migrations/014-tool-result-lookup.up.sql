-- Older rich tool results may store their call ID only in the first
-- tool-result content block. Normalize once so detail reads stay indexed.
UPDATE messages
SET tool_call_id = (
  SELECT json_extract(block.value, '$."tool-call-id"')
  FROM session_entries entry, json_each(entry.payload_json, '$."content-blocks"') block
  WHERE entry.session_id = messages.session_id AND entry.type = 'message'
    AND cast(json_extract(entry.payload_json, '$."message-id"') AS integer) = messages.id
    AND json_extract(block.value, '$.type') = 'tool-result'
  ORDER BY entry.created_at DESC, entry.id DESC, cast(block.key AS integer) ASC
  LIMIT 1
)
WHERE role = 'tool' AND tool_call_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_messages_tool_result
ON messages(session_id, tool_call_id, id) WHERE role = 'tool';

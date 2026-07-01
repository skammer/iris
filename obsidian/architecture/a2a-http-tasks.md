# A2A HTTP Task API

Iris exposes a minimal A2A HTTP+JSON-compatible surface for webhook callers that need submit-and-poll behavior.

Reference spec: https://a2a-protocol.org/latest/specification/

## Endpoints

- `GET /.well-known/agent-card.json`: public discovery document.
- `POST /message:send`: submit a text task and return immediately.
- `GET /tasks/{id}`: poll task status/result.
- `GET /tasks?contextId={session-id}&status={state}`: list recent tasks.
- `POST /tasks/{id}:cancel`: cancel task.

Auth uses the existing Iris API key middleware. When `:api :key` is set, pass `X-API-Key`, `Authorization: Bearer`, or Basic password auth.

## Data Model

- A2A `contextId` is Iris `session_id`.
- A2A task id is a durable `chat_tasks.id`.
- Iris `request-id` is stored in task metadata as `iris/requestId`.
- Final answer is `task.artifacts[0].parts[0].text`.
- Idempotency key is `Idempotency-Key`, falling back to `message.messageId`.

## A2A Compatibility Notes

Implemented subset only:

- `/.well-known/agent-card.json`
- `/message:send`
- `/tasks`
- `/tasks/{id}`
- `/tasks/{id}:cancel`

Known deviations:

- No JSON-RPC or gRPC binding.
- No `/message:stream`, `/tasks/{id}:subscribe`, or push notification config endpoints.
- `/message:send` always returns immediately with `TASK_STATE_SUBMITTED`; it does not implement A2A's blocking default for missing/false `configuration.returnImmediately`.
- Text parts only. `raw`, `url`, and `data` parts are rejected with `CONTENT_TYPE_NOT_SUPPORTED`.
- `message.taskId` is accepted only to infer `contextId`; Iris creates a new task instead of mutating/continuing the existing task.
- Cancel is session-scoped because Iris chat cancellation is session-scoped; canceling one task cancels all non-terminal tasks in the same `contextId`.
- `pageToken` is ignored; list uses newest tasks from the local store.

## Examples

```bash
TASK_ID="$(curl -sS http://127.0.0.1:8080/message:send \
  -H 'Content-Type: application/a2a+json' \
  -H 'Idempotency-Key: webhook-123' \
  -d '{"message":{"messageId":"msg-123","role":"ROLE_USER","parts":[{"text":"Run this task"}]}}' \
  | jq -r '.task.id')"
```

```bash
curl -sS "http://127.0.0.1:8080/tasks/${TASK_ID}?historyLength=2"
```

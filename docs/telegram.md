# Telegram setup

The Telegram adapter (`src/agent/telegram.clj`) long-polls the Bot API and routes each chat into the same session/message tables as the web UI. Each Telegram chat shows up in the **Sessions** sidebar with the title `Telegram: <name>`. Same `chat.id` reuses its session; DMs and groups are distinct sessions.

## 1. Create a bot

1. Message [@BotFather](https://t.me/BotFather) → `/newbot` → follow prompts.
2. Copy the token: `123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11`.

## 2. Find your numeric IDs

Empty allowlist denies everyone. Get IDs with [@userinfobot](https://t.me/userinfobot) (DM it; it replies with your `user-id`). For group chats, add the bot, send a message, then read the `chat.id` from `getUpdates`.

## 3. Configure

### Option A — env vars (recommended for Docker / deploys)

```bash
export AGENT_TELEGRAM_ENABLED=true
export AGENT_TELEGRAM_BOT_TOKEN="123456:ABC-DEF…"
export AGENT_TELEGRAM_ALLOWED_USER_IDS="111222333"          # CSV
# export AGENT_TELEGRAM_ALLOWED_CHAT_IDS="-1001234567890"   # for groups
# export AGENT_TELEGRAM_ALLOW_ALL=true                      # open bot, no allowlist
```

### Option B — EDN (in `config/default.edn` or a `-Dconfig` overlay)

```edn
:channel-adapters {:telegram {:enabled true
                              :bot-token "123456:ABC-DEF…"
                              :poll-timeout-seconds 30
                              :poll-limit 100
                              :allowlist {:allow-all? false
                                          :user-ids ["111222333"]
                                          :chat-ids ["-1001234567890"]}}}
```

Env vars merge on top of EDN (`agent.config/load-config`). Both paths require `:enabled true` **and** a non-nil `:bot-token` for the adapter to start.

## 4. Run

```bash
clojure -M:run
```

The adapter starts automatically when `:enabled` is truthy and the token is set. Send a DM to your bot — you should get a reply, and the chat will appear under **Sessions** as `Telegram: <your name>`.

## Bot capabilities

### Streaming replies (private chats)

Private-chat replies stream via Bot API 9.5 `sendMessageDraft`: as the LLM produces tokens, the bot updates a single animated draft, then commits the final text via `sendMessage`. Throttled at one update per ~1.2s. Group/supergroup chats fall back to regular `sendMessage` updates.

While a chat turn is running, the adapter sends Telegram `typing` chat actions every ~4s. This covers model thinking time, tool work, response waiting, and streaming.

### Slash commands

- `/start`, `/help`, `/reset`, `/memory`, `/status` — built-in.
- `/photo <url> [caption]` — sends a photo by URL or `file_id`.
- `/file <url> [caption]` — sends a document by URL or `file_id`.

### Agent tools

When a bot token is configured, two tools auto-register:

- `telegram_send_photo` `{:photo <url-or-file_id> :caption <opt>}`
- `telegram_send_document` `{:document <url-or-file_id> :caption <opt>}`

Both pull the destination `chat_id` from the active Telegram session via the agent's tool-execution context. They're available in any `chat/run!` path — group chats, the API, or the web UI when a Telegram session is current.

**Caveat**: tools are not invoked in the private-chat streaming path (`chat/stream!` doesn't run the planner). For agent-driven media sends in private chats, either disable streaming or use a group chat.

## Sessions integration

- One session per `chat.id`, stored in `sessions` table; mapping in `channel_session_mappings` (`source = :telegram`).
- Messages stored in the same `messages` table as web chat — agent context, tool calls, memory, all shared.
- Listed by `sqlite/list-sessions` on the Sessions page (no separate Telegram view).
- Reuse verified by `test/agent/telegram_test.clj` (`telegram-reuses-session-per-chat`).

## Troubleshooting

- **No reply, no session**: token wrong, `:enabled` not truthy, or sender not in allowlist. Check merged config: `(agent.config/load-config)` in REPL.
- **Long messages cut**: Telegram caps at 4096 chars; adapter chunks automatically (`telegram.clj:52`).
- **Formatting parse errors**: replies are rendered as Telegram `MarkdownV2`; reserved punctuation is escaped by `agent.telegram.format`.
- **Multiple instances**: only one process can `getUpdates` per token — Telegram returns 409 to the loser.

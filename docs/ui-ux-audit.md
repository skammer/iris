# UI/UX audit — 2026-09-05

Status: deployed and verified on representative production data; long-running performance audit remains open.

| Before | After | Why |
| --- | --- | --- |
| Iris / Runtime, Control Plane, repeated section kickers | Direct page and section names | Remove redundant copy |
| 104px mobile session column alongside transcript | Expandable session list above full-width chat | Readable chat at 320px; accessible toggle |
| Unbounded intrinsic session row width | Bounded grid tracks, title ellipsis, compact dates | Long ephemeral titles fit |
| Large padded tool cards, call IDs, previews | Compact rows, short IDs with full title, 180-character result previews | Preserve useful detail without filling transcript |
| External Google Fonts | Local IoskeleyMono controls and system prose | Remove font stylesheet/font network dependency |
| Full history read/render/send on every delta | Stable streaming target, 50ms burst coalescing | Avoid historical DB reads and transcript retransmission per token |
| Recent history sorted across whole session | Display-order and rich-entry expression indexes | Bound history and rich-content lookup work |
| Gzip quality substring check; Vary on compressed response only | Parsed quality and Vary on both variants; exclude SSE, ranges and no-transform | Correct content negotiation and caching |
| Memory desktop columns overriding mobile rules | Stacked content with outer scrolling; tables scroll locally | Keep panels and controls reachable |

## Verification

- 98 tests / 786 assertions, zero failures or errors: UI, API, API smoke, SQLite, new UI performance tests.
- Changed Clojure files: clj-kondo clean; git diff whitespace check clean.
- Chrome: Chat, Overview, Cron, Tools, Memory, MAGI, Logs at 1440×900, 768×1024, 390×844, 320×640, 844×390. Checked viewport/panel overflow, composer visibility, lazy tool expansion, actual Ephemeral selection and row bounds, mobile toggle state.
- Isolated broker → SSE → Datastar → browser: live text appears; terminal update replaces streaming target; no duplicated answer.
- Synthetic SQLite dataset: 10,000 messages, 2KB content per message. Median of 25 runs, warm local cache:

| Query | Without new indexes | With new indexes |
| --- | ---: | ---: |
| Latest 60 messages, inner selection | 37.16 ms | 0.092 ms |
| 60 rich-content entries | 18.88 ms | 0.058 ms |

The transcript's final ascending sort is over at most 60 selected rows. EXPLAIN confirms both new indexes. Numbers are synthetic query measurements, not production end-to-end latency.

HTTP samples before the final small CSS edits: chat shell 12,500 → 3,452 bytes, CSS 136,046 → 22,557 bytes, JS 21,333 → 6,233 bytes. Existing gzip is active; no buffered gzip added to live SSE.

## Reproduce

Start isolated fixture:

```sh
mkdir -p target/test-iris-config target/test-iris-data
env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data clojure -M:test dev/ui_review.clj
```

Fixture serves http://127.0.0.1:17331 and nREPL 17332, uses a test provider and `target/ui-review/review.db`. Do not run its browser script against a production nREPL.

```sh
npm install --prefix /tmp/iris-playwright playwright
NODE_PATH=/tmp/iris-playwright/node_modules node dev/ui_review.cjs
python3 dev/ui_benchmark.py
```

Chrome must be installed. Browser script exercises synthetic stream state only in this isolated fixture and restores the original function in `finally`. Screenshots and benchmark JSON go under `target/ui-review/`.

## Remaining audit

- [x] Sidebar pages contain 50 sessions plus the selected session when outside the page. Counts stay global. SQL returns only the displayed page and one alternate-kind target; project autocomplete returns at most 30 indexed prefix matches.
- [x] Tool detail reads at most the selected call and its first subsequent result, with rich-entry hydration restricted to those IDs. Migration 014 normalizes legacy rich result IDs and adds the lookup index. Session scope and reused IDs are covered by tests.
- [x] History uses 60-message cursor pages without the 400-message cap. Live events preserve the selected page and reading position; Latest returns to the live tail.
- Measure long-running generation, lifecycle patch frequency and idle refresh traffic; inspect representative populated Memory/approvals/cron/logs, not only empty fixture states.
- [x] Verify serving stack and timings against representative production data after deployment.

Correction on 2026-09-06 (Europe/Moscow): the earlier matching JAR hash
`e7e22c6228b19b3c887ce19244cb34ec08cb566320730418d6cd36736bbcec25`
belonged to the September 3 build on both machines. Schema 12/12 and successful
GET requests verified the old deployment, not these UI changes. The earlier
21-89 ms render timings must not be attributed to the updated serving stack.

Rebuilt and uploaded clean commit `4c219f0c77ffe458b0df440e21afe9bd6867c755`,
then restarted Iris (PID 281882). Local and remote JAR SHA-256:
`192906164a5de5083db8e29577fe4c99adfd34c99a1f851989954c6d1e96a7fa`.
Before restart, backed up SQLite to
`~/.config/iris/data/agent.pre-deploy-20260905T215626Z.db` on the server.
`/health` now reports `ok=true`, schema 15/15, provider `deepseek`, and Telegram
running. The live Overview renders build commit `4c219f0c77ff`.

Authenticated Chrome verification against `http://100.64.0.4:8689`: Chat,
Overview, Cron, Tools, Memory, MAGI, and Logs rendered populated content with
HTTP 200, no browser errors, and no viewport overflow at 1440x900. At 390x844,
Chat keeps its composer visible and its Sessions toggle opens/closes correctly.
Served CSS matches `public/app.css` byte-for-byte; the root page uses a new asset
version and no longer loads Google Fonts. After browser close, SSE metrics showed
15 opened, 15 closed, 15 unsubscribed, and zero errors; broker subscriptions were 0.

Long-running generation, lifecycle patch frequency, and idle refresh traffic
remain open; these require an explicit production interaction rather than GET
render checks.

## Overview charts and metric row — 2026-09-06

The five workspace cards now show daily event counts over seven UTC calendar
days, including today: chat turns, Cron starts, approval requests, memory events,
and all logged events. Empty days remain zero; each bar exposes its date/count.
One date-indexed aggregate reads event types and dates without hydrating payloads.
On production data its median was 7.59 ms over 10 runs; EXPLAIN used
`idx_agent_events_created`. Existing dashboard refresh remains 10 seconds.

The metric grid had five columns for six values. It now has six columns above
760px; mobile retains two columns. Chrome checked 1920, 1440, 1024, 768, 390,
and 320px: all five charts render, metric rows fit, and no horizontal overflow
or browser errors occur, including after a dashboard refresh.

63 focused UI/SQLite tests / 532 assertions passed, including missing days,
UTC boundaries with fractional timestamps, event classification, and zero charts.
Whitespace checks passed. Standalone clj-kondo reports only unresolved
HugSQL-generated SQL functions in the events namespace; compilation/tests pass.

Deployed clean commit `9d6d2061e1b014cbe3b0e3780a9134479908f9f9` to
`agent.tailscale` and restarted Iris (PID 282953). Both JARs have SHA-256
`6a3f15c631605bc61f686264457a33a6b718d16394cbae0a795891f2193a7100`.
Live Overview displays that commit. The same six-width Chrome check passed
against `http://100.64.0.4:8689`, including the 10-second refresh; production
charts contain real event counts. Health is OK, schema 15, provider `deepseek`,
and the Telegram adapter is healthy.

## Tool-detail follow-up

74 tests / 581 assertions passed after targeted lookup change (UI, UI performance,
SQLite, session entries). Coverage includes pending/orphan calls, cross-session
isolation, repeated call IDs, rich blocks preceded by text, entry insertion,
legacy backfill, and indexed query plan. Existing HugSQL-generated SQL vars are
not understood by a standalone clj-kondo invocation; compilation and tests pass.

## Session-list follow-up

- 99 tests / 790 assertions passed (UI, API, UI performance, SQLite and session entries).
- Chrome paging test seeds 120 disposable sessions: advances pages, switches sessions without resetting page, waits for the real 15s sidebar refresh, resolves a project outside initial suggestions, and verifies title updates preserve draft and sidebar page.
- Title events now patch only the two title nodes in both live and POST streams. They do not rebuild the composer or reset session pagination.
- Sidebar polling pauses when the document is hidden or a sidebar control has focus.
- A synthetic indexed in-memory SQLite query over 10,000 sessions with one message each selected 51 recent sessions in 5.82ms median (25 runs). No additional activity cache introduced; session ordering retains existing semantics.
- Migration 015 indexes project-prefix lookup. The default persistence API still permits full enumeration for non-UI callers; UI callers always pass limits.

Run the additional browser gate against the isolated fixture:

```sh
NODE_PATH=/tmp/iris-playwright/node_modules node dev/ui_session_review.cjs
```

The script removes only the sessions it created, including on failure. Screenshot:
`target/ui-review/session-pages-390.png`.

## Real POST streaming and disconnect follow-up

The earlier synthetic GET check missed `/ui/chat` callbacks retransmitting full
history for each delta. POST now consumes ordered runtime deltas already coalesced
at 50ms, patches only the current message, and updates status separately. The
sender's GET connection suppresses duplicate patches while its POST owns the turn;
other tabs continue receiving live updates.

Chrome, actual form submit with 120 chunks spaced 15ms apart and 60 historical
messages (~1.6KB each): 5,282,686 → 204,725 POST bytes (96.1% reduction), 62 → 2
full transcript renders. Sender saw 37 progressive updates, observer 28; neither
regressed. Both tabs rendered the final answer, hid Stop, and released their SSE
subscriptions on close. Exactly four total history renders across the two tabs.
Measured response duration: 2,373ms with an intentionally delayed test provider.

This check exposed 42 retained subscriptions after earlier browser runs. In
http-kit 2.8.0 the current HTTP channel was not attached to the connection, so
socket disconnect did not call its close handler. Updated to 2.9.0-beta4, which
includes the upstream lifecycle fixes. This is a prerelease dependency; it needs
production validation before deployment. References:
[original regression fix](https://github.com/http-kit/http-kit/commit/76b869f),
[response lifecycle fix](https://github.com/http-kit/http-kit/commit/c4a6ff4).

77 focused tests / 717 assertions pass, including repeated SSE disconnects and
bounded POST history patches. Full suite: 791 tests / 3,277 assertions, only two
stale Cron heading expectations failed. Updated those assertions to the simplified
UI; focused Cron rerun passed 15 tests / 99 assertions. No production-code changes
after the full run. Changed Clojure files lint without warnings.

Reproduce in an isolated process (the ordinary fixture may run alongside it):

```sh
env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data clojure -M:test dev/ui_stream_review.clj
NODE_PATH=/tmp/iris-playwright/node_modules node dev/ui_stream_review.cjs
```

This short deterministic generation does not replace the remaining long-duration,
tool-turn, populated-secondary-screen and representative-data checks.

## History pagination follow-up

Replaced expanding transcript downloads with Older / Newer / Latest cursor pages.
Each page contains at most 60 messages. Two index seeks retrieve at most 61 rows
each (timestamp ties and adjacent timestamps); only those bounded candidates are
sorted and at most 60 message IDs are hydrated. EXPLAIN confirms display-order
index range scans including the ID range for equal timestamps.

History mode belongs to the existing browser SSE context, so disconnect cleanup
also releases this state. GET and POST leave a historical page unchanged during
live generation. Latest and a new submission resume current-message updates.
The floating Latest button also returns from history to the current answer.

94 tests / 795 assertions passed (API, UI, UI performance, SQLite). Tests cover
605 tied timestamps, an older message activated last, both directions, foreign
session cursors and empty pages. Chrome traversed 605 messages in 11 pages with
no duplicates or gaps, preserved scrollTop=100 after a new final answer, and
preserved a historical page while 1,200 delayed chunks completed. Latest showed
the final answer once. Two-tab streaming regression passed with four total
history renders and both subscriptions released after close.

```sh
NODE_PATH=/tmp/iris-playwright/node_modules node dev/ui_history_review.cjs
```

Uses the isolated stream fixture; creates and deletes only its own test session.
Screenshot: `target/ui-review/history-390.png`.

Pre-deploy server inspection: healthy, active provider `deepseek`, 238 sessions,
4,814 messages, largest session 452 messages; schema 12. These are observed values,
not the older provider expectation in local deploy notes. Deployment completed on
2026-09-06 as recorded in the corrected production verification above.

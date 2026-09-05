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

Production verification on 2026-09-06: local and remote JAR SHA-256 matched
(`e7e22c6228b19b3c887ce19244cb34ec08cb566320730418d6cd36736bbcec25`),
`/health` returned `ok=true` with schema 12/12, active provider `deepseek`,
and Telegram running. Authenticated requests to Chat, Overview, Cron, Tools,
Memory, MAGI, Logs, sessions, approvals, and operator board returned HTTP 200
without error markers. Populated responses contained 5 chat rows, 7 Cron rows,
3 Memory table rows, 17-23 secondary-screen rows, and 18 Logs rows. Representative
server render timings were 21-89 ms, with no failed requests.

Long-running generation, lifecycle patch frequency, and idle refresh traffic
remain open; these require an explicit production interaction rather than GET
render checks.

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
not the older provider expectation in local deploy notes. Deployment is authorized
once remaining populated-screen and representative-data checks are complete.

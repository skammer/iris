# UI/UX audit — 2026-09-05

Status: first implementation pass verified locally; performance audit remains open.

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

- Bound/paginate session listing without losing access to older sessions or project suggestions.
- [x] Tool detail reads at most the selected call and its first subsequent result, with rich-entry hydration restricted to those IDs. Migration 014 normalizes legacy rich result IDs and adds the lookup index. Session scope and reused IDs are covered by tests.
- History “Load older” caps at 400; lifecycle patches reset the expanded window. Preserve browsing position/window and make older history reachable.
- Measure long-running generation, lifecycle patch frequency and idle refresh traffic; inspect representative populated Memory/approvals/cron/logs, not only empty fixture states.
- Verify serving stack and timings against representative real data after the remaining fixes. No remote deployment performed in this pass.

## Tool-detail follow-up

74 tests / 581 assertions passed after targeted lookup change (UI, UI performance,
SQLite, session entries). Coverage includes pending/orphan calls, cross-session
isolation, repeated call IDs, rich blocks preceded by text, entry insertion,
legacy backfill, and indexed query plan. Existing HugSQL-generated SQL vars are
not understood by a standalone clj-kondo invocation; compilation and tests pass.

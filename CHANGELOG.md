# Changelog

All notable project changes are recorded here. New entries go at the top.

## 2026-07-13

### Changed

- Made Vault Note promotion atomic: approve now moves notes out of `inbox/` into a deterministic durable folder, updates status, reindexes once, and rolls back on failure.
- Added approved-inbox drift reporting to Memory Quality and the periodic MAGI memory worker.
- Added configurable MAGI review for candidate Vault Notes: automatic, manual, assistive, or off.
- Added grouped per-note MAGI `Review` and advice-only actions; only unanimous `yes` promotes, while every other verdict keeps the note candidate.
- Added content-hash idempotency, failure cooldown, audit events, and Vault Note entries in the MAGI decision log.
- Removed the last `2px solid var(--border-visible)` declarations from chat loading indicators.
- Replaced full-shell tab reloads with partial navigation and workspace patches.
- Kept the partial workspace patch root layout-neutral, restoring each page's original independent scroll behavior.
- Restored independent Memory column scrolling on wide screens and one continuous scroll in stacked layouts.
- Prevented Memory grid rows from collapsing under tall panels, eliminating overlapping Vault Notes and Retrieval Lab content.
- Moved Retrieval Lab into the left Memory column directly after Vault; the right column is now dedicated to Vault Notes.
- Added bottom breathing room to the MAGI invocation log so the final row and border remain fully visible.
- Added client-scoped cancellation and server cleanup for Chat SSE streams; switching pages no longer accumulates live connections.
- Stopped classifying expected client-side SSE cancellation as a runtime stream error.
- Added a Safari/WebView-safe client ID fallback when `crypto.randomUUID()` is unavailable.
- Removed duplicate leading refreshes from server-rendered dashboard, sessions, approvals, events, and logs panels.
- Enabled gzip for large text, JSON, JavaScript, SVG, and XML responses while leaving SSE streams uncompressed.
- Made Tool Approval details load only when a row opens, reducing initial markup and DOM size.
- Limited the initial transcript to the latest 60 messages and added progressive older-message loading.
- Standardized Image, Stop, and Send controls to the same `84 × 38px` desktop size.
- Made the desktop UI catalogue own the viewport and scroll its main content independently, while narrow layouts retain normal document scrolling.

### Performance

- Reduced local compressed route payloads to roughly `1.5 KB` for Tools and `2.2 KB` for Chat in the seeded QA scenario.
- Added Playwright coverage for partial routing, gzip, duplicate-request prevention, SSE cleanup, transcript limits, lazy approval details, composer geometry, and catalogue scrolling.

## 2026-07-12

### Added

- Added `DESIGN.md` as the UI system source of truth and `/ui` as its live component catalogue.
- Added responsive Playwright coverage for the control plane, component catalogue, approvals, memory, and logs.
- Added this changelog.

### Changed

- Replaced the blue-tinted interface with a black canvas and warm-gray surfaces, borders, and text.
- Standardized product typography on `IoskeleyMono` and made Markdown tables compact.
- Removed the decorative top-left logo mark; the shell now uses a text-only `IRIS` identity.
- Removed thick side-accent borders across approvals, logs, messages, tools, navigation, alerts, and catalogue examples.
- Redesigned Overview around an agent summary, workspace action tiles, a live-runtime card, and a compact operations board.
- Redesigned Vault Notes into separate candidate and approved groups with distinct cards, readable source metadata, and row-scoped actions.
- Removed manual tool invocation from `/tools` and deleted its UI-only routes and handlers.
- Redesigned Tool Approvals as a dense, approvals-only operator queue with pending-first ordering, compact metrics, expandable details, short UUIDs, and a reason-first column layout.
- Redesigned `/logs` as two compact, expandable tables: durable SQLite events and optional Runtime Trace diagnostics.
- Improved light/dark theme switching with a short fade transition.

### Deployment

- Deployed each verified UI iteration to `agent.example.invalid` with health, LLM, schema, and JAR-hash checks.

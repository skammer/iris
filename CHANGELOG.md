# Changelog

All notable project changes are recorded here. New entries go at the top.

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

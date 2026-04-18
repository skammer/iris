# SQLite Persistence Refactor ADR

Date: 2026-04-18

## Assessment

`src/agent/persistence/sqlite.clj` has real structural debt:

- one large file mixes schema, migrations, query SQL, DAO logic, and DTO shaping
- store-wide lock is too coarse
- raw `DriverManager` connection creation is basic but not ideal long-term
- migrations are homegrown and forward-only
- inline SQL hurts maintainability once schema/domain count grows

## Decision

Adopt the suggestions in stages, not one rewrite:

1. Split namespace/file layout first.
2. Move migrations to Ragtime next.
3. Add datasource/pooling via HikariCP.
4. Narrow locking to transaction-only paths.
5. Move SQL into HugSQL files.

## Guidance Per Suggestion

- Coarse global lock:
  Good catch. Keep lock only around true multi-statement critical sections. Let SQLite WAL handle normal read/write concurrency.

- Connection pool:
  Agree. Add HikariCP once namespace split lands. Current raw connections are acceptable for now but not ideal.

- Down migrations + checksum:
  Agree. At minimum store checksum + irreversible marker. Full `down` support where safe.

- Ragtime:
  Agree. Better than extending custom migration machinery further.

- HugSQL:
  Agree, but after namespace split. Otherwise churn too large.

- Split projections/DTO shaping:
  Agree. High-value, low-risk first step.

## Priority

1. file split
2. Ragtime
3. HikariCP
4. lock narrowing
5. HugSQL

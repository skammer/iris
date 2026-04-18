# API Routing Refactor ADR

Date: 2026-04-18

## Decision

- Replace JDK `HttpServer` with `http-kit`.
- Replace giant `if`/path chain with `reitit` route table.
- Keep existing handler logic alive through Ring→`HttpExchange` adapter for non-streaming endpoints.
- Add Ring middleware layer for request id, structured request logging, and JSON error boundary.
- Use bounded SSE bodies for current streaming endpoints so route/middleware/server refactor stays stable under new stack.

## Rationale

- Biggest immediate problem was routing/server complexity, not endpoint semantics.
- Adapter approach cuts risk: routing/server change lands without rewriting every handler at once.
- Middleware layer creates clean place for auth, rate limit, request id, and cross-cutting concerns.

## Consequences

- `src/agent/api.clj` still contains too many domain handlers; only routing/server concerns were split this pass.
- SSE remains reconnecting/bounded rather than permanently open async push.
- Next refactor should move handlers + validation into dedicated namespaces and add token/rate-limit middleware.

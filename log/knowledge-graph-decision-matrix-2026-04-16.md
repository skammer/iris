# Knowledge Graph Decision Matrix
Date: 2026-04-16

## Goal

Pick a memory/graph direction that is:

- easy to deploy
- preferably embeddable
- powerful enough for agent memory/reasoning
- well supported
- realistic for JVM/Clojure integration

## Important framing

Do **not** choose a graph backend before defining memory surfaces.

Need 4 separate surfaces:

1. event/session log
2. prompt-visible working memory
3. searchable long-term memory
4. optional graph/semantic memory

A graph backend should serve surface `4`, and maybe part of `3`, not replace all memory.

## Candidate matrix

Scores: `1` weak, `5` strong.

| Candidate | Type | Embed | Deploy | Query power | Inference | Support | JVM/Clojure fit | Ops risk | Notes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| Datahike | Datalog graph/db | 5 | 5 | 4 | 2 | 3 | 5 | 2 | Best local-first fit |
| ArcadeDB | Multi-model graph/doc/vector | 4 | 4 | 5 | 2 | 3 | 4 | 3 | Best ambitious embedded engine |
| Jena TDB2 | RDF/SPARQL | 3 | 3 | 5 | 4 | 5 | 4 | 3 | Best semantic standards path |
| RDF4J LMDB | RDF/SPARQL | 3 | 3 | 4 | 4 | 4 | 4 | 3 | Good semantic alternative |
| XTDB | Temporal DB | 4 | 4 | 4 | 1 | 4 | 5 | 2 | Best audit/history substrate, not graph-first |
| Asami | Lightweight graph | 5 | 5 | 2 | 1 | 2 | 5 | 2 | Prototype/simple-only |
| Memgraph | Property graph server | 1 | 2 | 5 | 2 | 4 | 3 | 4 | Strong server DB, weak embed fit |
| Kuzu | Embedded property graph | 4 | 4 | 4 | 1 | 1 | 3 | 5 | Repo archived 2025-10-10 |

## Shortlist

### 1. Best local default: `Datahike`

Why:

- in-process
- deploy-simple
- Clojure-native
- Datalog fits structured memory/entity linking
- history support is useful for memory evolution

Tradeoff:

- less standard graph ecosystem than RDF/Cypher systems
- weaker inference story

Best fit:

- first real graph-backed long-term memory prototype

### 2. Best ambitious embedded option: `ArcadeDB`

Why:

- embedded JVM mode
- graph + document + vector + full-text in one engine
- strong query optionality

Tradeoff:

- larger conceptual surface
- more integration complexity

Best fit:

- later upgrade path if one engine should cover graph + search + vector together

### 3. Best semantic standards option: `Jena TDB2`

Why:

- mature RDF/SPARQL stack
- better inference/ontology path than Datahike
- strong docs/ecosystem

Tradeoff:

- more semantic-web overhead than current needs
- single-JVM access caveats

Best fit:

- if explicit semantic reasoning becomes core requirement

### 4. Best complementary historical substrate: `XTDB`

Why:

- bitemporal/history strengths
- strong audit/event modeling
- JVM/Clojure fit

Tradeoff:

- not primary graph engine

Best fit:

- event/history store under or beside graph memory

## Current recommendation

If implementing in order of value:

1. define memory surfaces
2. keep SQLite as event/session store for now
3. prototype graph memory behind interface with `Datahike`
4. keep `ArcadeDB` as upgrade candidate
5. revisit `Jena TDB2` only if semantic reasoning becomes hard requirement
6. treat `XTDB` as future history/audit option, not current KG default

## Prototype plan

Run same benchmark tasks against `Datahike` and `ArcadeDB` first.

### Benchmark tasks

#### Task 1. Fact storage + retrieval

Store:

- entities
- relationships
- timestamps
- provenance

Query:

- direct lookup
- reverse lookup
- small fan-out traversal

#### Task 2. Multi-hop recall

Example:

- user preference → project → tool policy → recent failure

Need:

- 2 to 3 hop traversal
- filtering by recency/source

#### Task 3. Decision trace storage

Store:

- task
- agent
- tool calls
- outputs
- final decision
- linked artifacts

Query:

- “why did agent choose X?”
- “show failures linked to tool Y”

#### Task 4. Lightweight inference

Need at least:

- relationship expansion
- tag/category inheritance
- simple rule-derived associations

Not full theorem proving.

## Acceptance criteria

Chosen candidate should prove:

1. embeddable local dev setup in <10 minutes
2. straightforward backup story
3. acceptable query ergonomics from Clojure
4. can store provenance/time/source cleanly
5. migration path from current SQLite-backed runtime

## Rejections

Do not choose now:

- `Kuzu` as foundation: archived
- `Memgraph` as default: server-first mismatch
- `Asami` as primary: too limited for likely long-term target

## Decision

Current best decision:

- **Prototype with `Datahike` first**
- **Keep `ArcadeDB` as serious fallback/upgrade**
- **Do not implement KG until memory-surface contract exists**

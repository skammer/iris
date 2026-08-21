# TODO

## Memory rollout

- [x] Run `Memory daily grooming` once on live data; inspect run status, transcript, proposals, note changes, evidence size, duplicates, and recall quality.
- [x] Run `Memory weekly skill distillation` once on live data; inspect generated skill proposals and reject low-signal output.
- [x] Tune `/dream` and `/distill` prompts/limits from observed failures; add regression tests for each fix.
- [x] Add review/apply flow for existing skill updates.
- [x] Add first-class reviewed note operations: merge, delete, move.
- [x] Unify note and skill proposals in Memory review UI.
- [x] Add explicit session `project-id` selection and UI; verify project-scoped recall across chats without cross-project leakage.
- [x] Add same-owner similarity candidates and `/dream` semantic review before merge proposals; never merge from lexical score alone.
- [ ] After 1–2 weeks, measure FTS recall/latency, duplicate rate, evidence size, proposal acceptance, and recall diversity; enable embeddings only if measurements justify it.

## Deferred Nudging Work

- [ ] Context cards from skill/knowledge injection. Not part of P0. Accept when nudges can cite compact, typed cards instead of raw injected prompt text.
- [ ] Durable evidence and evidence-aware compaction. Not part of P0. Accept when tool evidence is retained, scored, and protected during compaction.
- [ ] Browser/http/fs/shell-specific result retention. Not part of P0. Accept when each tool family has tuned retention rules.
- [ ] Text-embedded tool-call parser beyond retry nudging. Not part of P0. Accept when leaked textual tool calls can be parsed safely into native calls.
- [ ] Clarification preflight. Not part of P0. Accept when ambiguous tasks ask before planner/tool execution.
- [ ] `@file` references. Not part of P0. Accept when user text can resolve file refs into bounded context.
- [ ] Git diff context injection. Not part of P0. Accept when dirty diff is selectively injected into coding turns.
- [ ] Recommended sampling defaults. Not part of P0. Accept when small-model profiles include tested temperature/top-p defaults.
- [ ] Guarded-vs-bare eval harness. Not part of P0. Accept when same prompts can run with nudging on/off and compare outcomes.

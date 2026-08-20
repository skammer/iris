---
name: dream
description: >-
  Run Dreaming: update the learned USER.md profile, extract durable chat memory,
  groom existing notes, and distill repeated workflows into Iris skills.
---

# Dreaming

Use when user invokes `/dream`, asks to consolidate memory, groom existing
memories, or distill reusable knowledge from recent chats.

Goal: keep memory compact, current, non-duplicative, and operationally useful.
Do not save memory every turn.

Iris may also run automatic idle extraction after a long quiet period. `/dream`
is still the explicit/manual consolidation path and should be higher intent than
background idle extraction.

Workflow:

1. Identify target session. Default: current `session-id`.
2. Call `memory_extract_session` with target `session-id`. It performs both
   bounded memory extraction and learned-profile maintenance in the managed
   section of `USER.md`. Report whether each changed.
3. Verify profile quality when it changed:
   - keep only stable cross-session identity, communication, accessibility,
     working-style, and assistant-expectation facts;
   - never put project state, one-off requests, guesses, or secrets in USER.md;
   - do not manually rewrite user-owned USER.md content outside Iris's managed
     section.
4. Groom existing memory:
   - search before reading or changing notes;
   - merge duplicates into the strongest approved note;
   - use `memory_propose_update` for corrections or material extensions;
   - prune stale candidate notes only after verifying they are duplicated,
     contradicted, or transient;
   - preserve concise evidence and origin ranges, never transcript dumps.
5. Distill recent chats when a workflow is repeated and verified:
   - call `skills_list`, then read relevant `SKILL.md` files;
   - propose an update when an existing skill already owns the topic;
   - otherwise call `memory_propose_create` with type `Skill` and complete
     `SKILL.md` source; never write into an active skills directory;
   - register drafts only after MAGI/user approval;
   - require YAML `name` and `description`, narrow trigger, exact procedure,
     verification, and stopping condition;
   - create nothing when evidence is weak, one-off, sensitive, or duplicated.
6. Keep only durable facts: explicit preferences, decisions, repeated
   errors/fixes, stable paths, and runbooks. Skip secrets and weak guesses.
7. Reindex and audit vault after memory edits. Call `skills_list` after skill
   edits to verify automatic registry reload.

Budgets:

- Existing-note context: use a small relevant shortlist, not the full vault.
- Evidence: decisive ids or compact paraphrase only; no raw transcript copies.
- Origins: one source/range summary per extraction, not one item per event.

Upstream reference prompt: `resources/prompts/imported/mimo-code/dream.txt`.

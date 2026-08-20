---
name: distill
description: Find repeated manual workflows in recent Iris work and package high-confidence candidates into reusable assets.
---

# Distill

Use when user invokes `/distill` or asks to package repeated workflows.

Goal: convert repeated, verified work patterns into the smallest useful asset: skill, command, runbook, or doc. Create nothing if evidence is weak.

Dreaming may invoke this as its workflow-distillation phase.

Workflow:

1. Call `skills_list`, then inspect relevant `skills/*/SKILL.md`, prompts,
   runbooks, and Obsidian docs.
2. Search recent session messages/events for repeated commands, failures, paths, and manual procedures.
3. Confirm candidates against real traces, not memory-only summaries.
4. Prefer proposing an update to an existing skill over creating a duplicate.
5. Never write directly into an active skills directory. Call
   `memory_propose_create` with type `Skill`; body must be complete `SKILL.md`
   source including YAML `name` and `description`. Registration happens only
   after MAGI/user approval.
6. Package only workflows observed at least twice, or one costly workflow with
   explicit user instruction and verified steps. Include trigger, exact steps,
   verification, and stopping condition.
7. After approval, call `skills_list` to verify registry reload.
8. Keep output compact: created/changed asset, evidence, caveats.

Upstream reference prompt: `resources/prompts/imported/mimo-code/distill.txt`.

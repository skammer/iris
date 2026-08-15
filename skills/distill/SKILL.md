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
4. Prefer extending an existing skill with `fs_replace` over creating a duplicate.
5. For a new Iris skill, use `fs_mkdir` then `fs_create` for user-managed
   `~/skills/<name>/SKILL.md`; include YAML `name` and `description`. Do not
   create it under deployed `~/.config/iris/skills`.
6. Package only workflows observed at least twice, or one costly workflow with
   explicit user instruction and verified steps. Include trigger, exact steps,
   verification, and stopping condition.
7. Call `skills_list` after edits to verify registry reload.
8. Keep output compact: created/changed asset, evidence, caveats.

Upstream reference prompt: `resources/prompts/imported/mimo-code/distill.txt`.

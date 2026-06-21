---
name: distill
description: Find repeated manual workflows in recent Iris work and package high-confidence candidates into reusable assets.
---

# Distill

Use when user invokes `/distill` or asks to package repeated workflows.

Goal: convert repeated, verified work patterns into the smallest useful asset: skill, command, runbook, or doc. Create nothing if evidence is weak.

Workflow:

1. Inspect existing assets first: `skills/*/SKILL.md`, prompts, runbooks, Obsidian docs.
2. Search recent session messages/events for repeated commands, failures, paths, and manual procedures.
3. Confirm candidates against real traces, not memory-only summaries.
4. Prefer extending an existing asset over creating a duplicate.
5. Package only high-confidence workflows with clear trigger, steps, and verification.
6. Keep output compact: created/changed asset, evidence, caveats.

Upstream reference prompt: `resources/prompts/imported/mimo-code/distill.txt`.

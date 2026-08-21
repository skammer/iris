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
4. If an existing skill owns the workflow, report an update candidate as
   unresolved until dedicated skill-update proposals exist. Never create a
   parallel skill.
5. Never write directly into an active skills directory. Call
   `skill_propose_update` for an existing skill, otherwise
   `memory_propose_create` with type `Skill`; body must be complete `SKILL.md`
   source including YAML `name` and `description`. Registration happens only
   after MAGI/user approval.
6. Package only workflows observed at least twice, or one costly workflow with
   explicit user instruction and verified steps. Include trigger, exact steps,
   verification, and stopping condition.
7. Require procedural repetition, not merely two sessions about the same topic.
   Independent evidence means separate user requests with separate successful
   completions. Retries, continuations, parallel attempts, or session splits of
   one migration/task count as one observation.
   Reject candidates that copy personal names, health details, secrets, private
   hosts/remotes, or machine-specific paths. Use configuration inputs instead.
   Do not encode permission bypasses such as writing through `shell` because a
   safer tool lacks access.
   - No literal home/absolute paths or remote URLs anywhere, including evidence.
   - No personal names or health data anywhere, including evidence.
   - Do not bake in git commit/push or other external side effects unless the
     invoking user explicitly requests them each time.
   - Package workflow mechanics, not domain-specific medical advice.
8. After approval, call `skills_list` to verify registry reload.
9. Keep output compact: created/changed asset, evidence, caveats.

Upstream reference prompt: `resources/prompts/imported/mimo-code/distill.txt`.

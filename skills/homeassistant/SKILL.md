---
name: homeassistant
description: Use Home Assistant through the Iris homeassistant tool for smart-home state lookup and safe service calls.
---

# Home Assistant

Use this skill when the user asks to inspect or control smart-home devices.

## Workflow

1. Read before control:
   - Use `homeassistant` with `action: "list_states"` to find entities.
   - Use `action: "get_states"` with `entity_ids` when several exact entities are already known.
   - Use `action: "get_state"` before changing one entity when current state matters.
   - Use `action: "list_services"` if unsure which domain/service exists.
2. Control only with `action: "call_service"` after entity and service are clear.
3. Prefer narrow entity-scoped calls. Do not call global services unless explicitly allowed by config.

## Tool Calls

Read all states:

```json
{"action":"list_states","purpose":"Find matching Home Assistant entities for the user request"}
```

Read one entity:

```json
{"action":"get_state","entity_id":"light.kitchen","purpose":"Check current light state before changing it"}
```

Read several exact entities in one compact call:

```json
{"action":"get_states","entity_ids":["sensor.plant_moisture","sensor.plant_temperature"],"purpose":"Read the requested sensors once"}
```

Call a service:

```json
{"action":"call_service","domain":"light","service":"turn_on","entity_id":"light.kitchen","data":{"brightness_pct":70},"purpose":"Turn on the requested kitchen light"}
```

## Safety

- Default controllable domains are `light`, `switch`, `scene`, and `script`.
- Risky domains such as `lock`, `alarm_control_panel`, `cover`, `climate`, and `automation` require explicit config allowlisting.
- If the user request is ambiguous, list matching entities first and ask or choose only when the target is obvious.
- Do not expose Home Assistant tokens, config secrets, or raw authorization headers.

## Entity Matching

- Match by exact `entity_id` first.
- Then match by friendly name in state attributes.
- If multiple plausible entities remain, avoid service calls until the target is clear.

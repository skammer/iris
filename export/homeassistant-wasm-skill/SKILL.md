---
name: homeassistant
description: Use the bundled Home Assistant WASI tool for smart-home state lookup and safe service calls.
tool: homeassistant
---

# Home Assistant WASM Skill

Distribution package: `homeassistant-0.1.0.skill`.

Use when the user asks to inspect or control Home Assistant devices.

## Workflow

1. Read before control.
2. Use `search_states` to find entities by name/domain/device class.
3. Use `get_state` when current state matters.
4. Use `call_service` only when entity and service are clear.
5. Prefer entity-scoped calls. Global services require `global_services` config.

## Tool Input

```json
{"action":"search_states","query":"kitchen","domain":"light","limit":10}
```

```json
{"action":"get_state","entity_id":"light.kitchen"}
```

```json
{"action":"call_service","domain":"light","service":"turn_on","entity_id":"light.kitchen","data":{"brightness_pct":70}}
```

## Actions

- `list_states`: compact Home Assistant state summaries, default limit `25`, max `200`.
- `search_states`: same as `list_states`, filtered by `query`, `domain`, `device_class`.
- `get_state`: one entity from `/api/states/{entity_id}`.
- `list_services`: compact domain/service list from `/api/services`.
- `call_service`: guarded POST to `/api/services/{domain}/{service}`.

## Safety

- Default controllable domains: `light`, `switch`, `scene`, `script`.
- `allowed_domains` may include `"all"` only for trusted sessions.
- `call_service` requires `entity_id` unless `domain.service` is in `global_services`.
- V1 passes `ha_api_key` to trusted WASM via stdin settings. Do not echo it in user-visible answers.
- For ambiguous targets, search first and ask before control.

## Runtime Contract

This `.skill` zip ships with `SKILL.md`, `tool.json`, docs, and `module.wasm`.
Iris should unpack it, load the skill file, register the tool manifest, and
invoke the module as a WASI Preview 1 command:

```json
{
  "tool": "homeassistant",
  "arguments": {"action":"search_states","query":"soil","domain":"sensor"},
  "settings": {
    "ha_host": "http://homeassistant.local:8123",
    "ha_api_key": "long-lived-access-token",
    "timeout_ms": 10000,
    "allowed_domains": ["light","switch","scene","script"],
    "global_services": []
  },
  "workspace": "/workspace"
}
```

The module imports host function `http.request`. The module builds the full HTTP
request envelope itself, including URL and `Authorization` header, from settings.
This is trusted-binary mode for v1.

Stdout is JSON:

```json
{"ok":true,"action":"search_states","body":{"matched":1},"result_text":"homeassistant.search_states ok: ..."}
```

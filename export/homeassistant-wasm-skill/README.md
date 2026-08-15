# Home Assistant WASM Skill

Rust WASI module plus Iris skill metadata for Home Assistant.

## Layout

```text
homeassistant-wasm-skill/
├── SKILL.md
├── README.md
├── tool.json
├── module.wasm
├── Cargo.toml
└── src/main.rs
```

`tool.json` follows Marginalia `.tool` shape and adds `skill: "SKILL.md"` plus
runtime host-function requirements.

Distribution package:

```text
homeassistant-0.1.0.skill
```

Bundle extensions are semantic zip formats:

- `.tool`: executable tool bundle
- `.skill`: agent skill bundle; may also include executable tools

## Build

```bash
rustup target add wasm32-wasip1
cargo build --release --target wasm32-wasip1
cp target/wasm32-wasip1/release/homeassistant-wasm-skill.wasm module.wasm
```

## Tool ABI

WASI stdin:

```json
{
  "tool": "homeassistant",
  "arguments": {"action":"search_states","query":"soil","domain":"sensor","limit":10},
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

Stdout success:

```json
{"ok":true,"action":"search_states","body":{"matched":1},"result_text":"homeassistant.search_states ok: ..."}
```

Stdout failure:

```json
{"ok":false,"error_type":"homeassistant_http_error","error":"Home Assistant request failed: 401"}
```

## Settings

V1 trusted-binary mode passes these settings directly into WASM stdin:

- `ha_host`: Home Assistant base URL. Alias: `base_url`.
- `ha_api_key`: Home Assistant long-lived access token. Alias: `token`.
- `timeout_ms`: HTTP timeout passed to the host request envelope.
- `allowed_domains`: service-call allowlist.
- `global_services`: services allowed without `entity_id`.

## Host Function

The module imports `http.request`:

```text
request(request_ptr, request_len,
        out_ptr, out_cap,
        status_ptr) -> i32
```

Rules:

- `request`: UTF-8 JSON envelope built by WASM.
- host writes HTTP status as little-endian i32 at `status_ptr`.
- host writes response body bytes into `out_ptr`.
- return value `>= 0`: response byte count.
- return value `< 0`: host error.

Request envelope:

```json
{
  "method": "GET",
  "url": "http://homeassistant.local:8123/api/states",
  "headers": {
    "Authorization": "Bearer long-lived-access-token",
    "Content-Type": "application/json",
    "Accept": "application/json"
  },
  "body": null,
  "timeout_ms": 10000
}
```

In v1 the WASM module receives `ha_api_key` and constructs this envelope itself.
Treat the module as trusted.

## Actions

```json
{"action":"list_states","limit":25}
```

```json
{"action":"search_states","query":"soil moisture","domain":"sensor","device_class":"moisture","limit":10}
```

```json
{"action":"get_state","entity_id":"sensor.plant_soil_moisture"}
```

```json
{"action":"get_states","entity_ids":["sensor.plant_soil_moisture","sensor.plant_temperature"]}
```

```json
{"action":"list_services"}
```

```json
{"action":"call_service","domain":"light","service":"turn_on","entity_id":"light.kitchen","data":{"brightness_pct":70}}
```

`call_service` validates:

- domain/service names: `[A-Za-z0-9_]+`
- entity ids: `[A-Za-z0-9_.]+`
- service domain is in `allowed_domains`, unless `allowed_domains` contains `all`
- entity id is present unless `domain.service` is in `global_services`

## Skill Use

`SKILL.md` tells the agent to search/read first, then call services only when
target and service are clear. Iris phase 2 should load `SKILL.md` from enabled
bundle directories and register `tool.json` as the executable tool.

For distribution, zip this directory's bundle files as `homeassistant-0.1.0.skill`.
For runtime, Iris should unpack the zip and execute from the unpacked install
directory.

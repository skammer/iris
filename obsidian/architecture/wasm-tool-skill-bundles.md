# WASM Tool and Skill Bundles

#architecture #implementation #tools #wasm

Iris supports zip-based WASM bundles for shipping tools and slash skills as
portable packages.

Status: implemented. Operator setup and CLI usage also live in
`docs/wasm-bundles.md`.

## Package Extensions

- `.tool`: executable tool bundle.
- `.skill`: agent skill bundle. It may also include executable tools.

Both are zip files. The extension describes the internal contract, not a new
archive format.

Examples:

```text
homeassistant-0.1.0.skill
weather-0.2.0.tool
```

## Internal Layout

Required for executable bundles:

```text
tool.json
README.md
module.wasm
```

Optional for skill bundles:

```text
SKILL.md
docs/
```

Example:

```text
homeassistant-0.1.0.skill
├── tool.json
├── SKILL.md
├── README.md
├── module.wasm
└── docs/
```

Iris validates zip paths. Paths must be relative and must not contain `..`.

## Manifest

The manifest is always JSON for interop with other runtimes.

```json
{
  "id": "iris.homeassistant",
  "name": "homeassistant",
  "version": "0.1.0",
  "description": "Home Assistant WASI tool plus agent skill.",
  "module": "module.wasm",
  "skill": "SKILL.md",
  "requiredPermissions": ["wasm-execute"],
  "actionKey": "action",
  "readOnlyActions": ["get_state", "list_states"],
  "parallelSafeActions": ["get_state", "list_states"],
  "sensitiveActions": ["call_service"],
  "runtime": {
    "kind": "wasi-preview1",
    "stdin": "json",
    "stdout": "json",
    "requiresHostFunctions": [
      {
        "module": "http",
        "name": "request",
        "params": ["i32", "i32", "i32", "i32", "i32"],
        "results": ["i32"]
      }
    ]
  },
  "schema": {
    "type": "object",
    "properties": {
      "action": {"type": "string"}
    },
    "required": ["action"]
  },
  "settings": []
}
```

Important fields:

- `id`: stable package id. Example: `iris.homeassistant`.
- `name`: model-visible tool name. Must be snake_case ASCII.
- `module`: relative path to WASM module.
- `skill`: optional relative path to `SKILL.md`.
- `requiredPermissions`: Iris permissions needed to call the tool.
- `schema`: JSON schema for tool input.
- `runtime.requiresHostFunctions`: host functions imported by WASM.

## Configuration

Settings live in Iris `config.edn`, not SQLite.

```clojure
:tools
{:wasm-bundles
 {:enabled? true
  :install-dir "bundles/installed"
  :package-dir "bundles/packages"
  :dev-roots ["export/homeassistant-wasm-skill"]
  :enabled ["iris.homeassistant"]
  :settings {"iris.homeassistant"
             {:ha_host "http://homeassistant.local:8123"
              :ha_api_key "long-lived-access-token"
              :allowed_domains ["light" "switch" "scene" "script"]
              :global_services []}}}}
```

`dev-roots` are unpacked bundle directories used during development.
Installed bundles live under:

```text
~/.config/iris/bundles/installed/{id}/{version}/
```

Packages copied during install live under:

```text
~/.config/iris/bundles/packages/
```

## CLI

Install a package:

```bash
iris bundle install path/to/homeassistant-0.1.0.skill
```

List enabled/discovered bundles:

```bash
iris bundle list
```

List installed bundles:

```bash
iris bundle installed
```

Enable a bundle:

```bash
iris bundle enable iris.homeassistant
```

Enable a specific version:

```bash
iris bundle enable iris.homeassistant 0.1.0
```

Disable a bundle:

```bash
iris bundle disable iris.homeassistant
```

The CLI updates `:tools :wasm-bundles :enabled` in `config.edn`.

## Runtime Input

Iris invokes bundles as WASI Preview 1 command modules.

WASM stdin:

```json
{
  "tool": "homeassistant",
  "arguments": {
    "action": "search_states",
    "query": "soil",
    "domain": "sensor"
  },
  "settings": {
    "ha_host": "http://homeassistant.local:8123",
    "ha_api_key": "long-lived-access-token"
  },
  "workspace": "/workspace"
}
```

Stdout should be JSON:

```json
{
  "ok": true,
  "action": "search_states",
  "body": {"matched": 1},
  "result_text": "homeassistant.search_states ok: ..."
}
```

On failure:

```json
{
  "ok": false,
  "error_type": "homeassistant_http_error",
  "error": "Home Assistant request failed: 401"
}
```

Iris parses `result_text` for model-visible output and keeps the structured
`body` for callers.

## Host Functions

V1 supports `http.request`.

ABI:

```text
request(request_ptr, request_len,
        out_ptr, out_cap,
        status_ptr) -> i32
```

The guest writes a JSON request envelope:

```json
{
  "method": "GET",
  "url": "http://homeassistant.local:8123/api/states",
  "headers": {
    "Authorization": "Bearer long-lived-access-token"
  },
  "body": null,
  "timeout_ms": 10000
}
```

Host behavior:

- writes HTTP status to `status_ptr`
- writes response body into `out_ptr`
- returns response byte count
- returns negative code on host error or size cap failure

V1 treats enabled bundles as trusted binaries. Settings, including API keys, are
passed into WASM stdin. Later, untrusted third-party bundles can use narrower
host-owned secret APIs.

## Permissions

Bundle tools use the normal Iris tool permission system.

Example:

```json
"requiredPermissions": ["wasm-execute"]
```

The runtime enforces these like built-in tools. `sensitiveActions` trigger the
existing approval path unless yolo mode or policy allows the action.

## Skills

If `tool.json` includes:

```json
{"skill": "SKILL.md"}
```

Iris loads that file into the slash skill registry. A bundle can therefore ship:

- executable tool metadata
- WASM implementation
- agent workflow instructions
- docs/examples

## Development Flow

1. Create unpacked bundle under `export/<name>/`.
2. Add the path to `:tools :wasm-bundles :dev-roots`.
3. Add settings under `:tools :wasm-bundles :settings`.
4. Run Iris and call the tool.
5. Zip the bundle files as `.tool` or `.skill` for distribution.

For Home Assistant development:

```clojure
:tools
{:wasm-bundles
 {:dev-roots ["export/homeassistant-wasm-skill"]
  :settings {"iris.homeassistant"
             {:ha_host "http://homeassistant.local:8123"
              :ha_api_key "..."}}
  :enabled []}}
```

`dev-roots` are discovered without `:enabled`. Installed bundles use `:enabled`.

## Related Code

- `src/agent/wasm/bundles.clj`
- `src/agent/tools/service.clj`
- `src/agent/skills.clj`
- `export/homeassistant-wasm-skill/`

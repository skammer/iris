# WASM Tool + Skill Bundles Plan

## Goal

Support installable bundles that contain:

- one WASI module
- one model-visible tool manifest
- one optional agent skill file
- docs/examples
- host-function and permission requirements

## Bundle Format

Recommended authoring layout:

```text
bundle-name/
├── tool.json
├── SKILL.md
├── README.md
├── module.wasm
└── docs/
```

Recommended distribution artifacts are zip files with semantic extensions:

- `.tool`: executable tool bundle
- `.skill`: agent skill bundle; may also include executable tools

Do both:

- zip for import/export, sharing, signatures, checksums
- unpacked install dir for runtime execution and inspection

Do not execute directly from zip.

## Runtime Storage

Suggested paths:

```text
~/.config/iris/bundles/packages/{id}-{version}.zip
~/.config/iris/bundles/installed/{id}/{version}/
~/.config/iris/bundles/enabled.edn
```

For dev:

```clojure
:wasm-bundles {:dev-roots ["export/homeassistant-wasm-skill"]}
```

Runtime should resolve enabled bundles to immutable unpacked directories.
Example Home Assistant package: `homeassistant-0.1.0.skill`.

## Manifest

Manifest format: JSON, for interop with Marginalia and other runtimes.

```json
{
  "id": "iris.homeassistant",
  "name": "homeassistant",
  "version": "0.1.0",
  "description": "...",
  "module": "module.wasm",
  "skill": "SKILL.md",
  "schema": {},
  "settings": [],
  "runtime": {
    "kind": "wasi-preview1",
    "stdin": "json",
    "stdout": "json",
    "requiresHostFunctions": []
  }
}
```

Validation:

- `id`: `[A-Za-z0-9._-]+`
- `name`: snake_case ASCII
- `module`, `skill`, docs paths are relative, no absolute paths, no `..`
- module exists and is below size cap
- JSON schema exists for model-visible input
- declared host functions must be known or explicitly enabled

## Iris Phase 2 Work

1. Manifest parser/validator.
2. Zip importer with traversal protection and size caps.
3. Bundle installer CLI:
   - `iris bundle install <path>`
   - `iris bundle list`
   - `iris bundle enable <id> [version]`
   - `iris bundle disable <id>`
4. Config:
   - `:tools :wasm-bundles :enabled?`
   - install dir
   - dev roots
   - per-bundle settings under `config.edn`
   - tool permissions use existing Iris tool permission model
5. Tool registry:
   - read enabled manifests
   - register tool descriptions from manifest schema
   - dispatch calls into `agent.tools.common.wasm-execute`
6. Skill registry:
   - load enabled `SKILL.md`
   - expose through existing slash skill/discovery flow
   - include bundle id/version in source metadata
7. Host-function registry:
   - built-ins: HTTP, Home Assistant, filesystem handles, logging
   - strict manifest declaration
   - per-bundle permission checks
8. Settings/secrets:
   - v1: read per-bundle settings from config EDN and pass them directly in stdin
   - v1: treat enabled WASM bundles as trusted binaries
   - later: optional host-owned secrets for untrusted or third-party bundles
9. Execution:
   - use endive-clj `run-wasi`
   - stdin shape: `{tool, arguments, settings, workspace}`
   - stdout JSON parse, stderr cap, timeout cap
10. Tests:
   - manifest validation
   - zip traversal denial
   - dev-root loading
   - install/enable/disable
   - skill loading
   - wasm execution with mock host functions
   - secret redaction

## Bundle Trust Model

Default:

- network off unless host-mediated function is declared and enabled
- filesystem mounts empty unless declared
- no host env passthrough
- stdout/stderr capped
- timeout enforced

For Home Assistant v1, pass `ha_host` and `ha_api_key` into the module settings.
The module builds a generic `http.request` envelope itself. Later, if we want to
support untrusted third-party bundles, move secrets back into host custody and
use narrower host functions.

## Resolved Decisions

- manifest format is JSON
- Iris tool settings live in `config.edn`
- bundled tools are enabled through existing Iris tool permissions, not profile
  or session scope

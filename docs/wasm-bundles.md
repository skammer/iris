# WASM Tool and Skill Bundles

Iris installs portable WASI Preview 1 tools and slash skills from zip packages.
`.tool` denotes an executable tool bundle; `.skill` may include both a skill and
executable tools.

## Package contract

An executable bundle contains:

```text
tool.json
README.md
module.wasm
```

`SKILL.md` and `docs/` are optional. `tool.json` declares the stable package ID,
version, model-visible tool name/schema, module path, required permissions,
action policy, and host-function imports. Paths must be relative and may not
contain `..`.

## CLI

```bash
iris bundle install ./homeassistant-0.1.0.skill
iris bundle installed
iris bundle list
iris bundle enable iris.homeassistant 0.1.0
iris bundle disable iris.homeassistant
```

Install validates and unpacks the archive. Iris executes from the immutable
installed directory, never directly from the zip.

## Configuration

```clojure
{:tools
 {:wasm-bundles
  {:enabled? true
   :install-dir "bundles/installed"
   :package-dir "bundles/packages"
   :dev-roots ["export/homeassistant-wasm-skill"]
   :enabled ["iris.homeassistant"]
   :settings {}}}}
```

`dev-roots` load unpacked development bundles without adding them to `enabled`.
Installed packages live below `~/.config/iris/bundles/installed/`; imported zip
files live below `~/.config/iris/bundles/packages/`.

## Security contract

- Network is unavailable unless a declared host function is enabled.
- Filesystem mounts and host environment are not passed through implicitly.
- Runtime, stdout, stderr, response size, and WASM memory are bounded.
- Bundle tools use normal Iris permissions and approval policy.
- V1 treats enabled bundles as trusted binaries and passes their configured
  settings into WASM stdin. Do not enable untrusted third-party bundles or place
  secrets in a bundle archive.

Current host integration exposes `http.request`. Full manifest, ABI, runtime
payload, permissions, and development flow are documented in
`obsidian/architecture/wasm-tool-skill-bundles.md` in the repository.

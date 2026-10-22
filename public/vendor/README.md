# Vendored browser runtime

Datastar **1.0.0-RC.8**, unchanged upstream bundle:

- Source: https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js
- SHA-256: `c7f69d2f28ca0d5f4dc9acbdf5cf590bb411d02785c74f86899c611d81c6adcd`
- License: [MIT](datastar-LICENSE.md), downloaded from the same pinned release.

This release uses `retryMaxWaitMs` (newer documentation calls it `retryMaxWait`).
`datastar-fetch` carries `{type, el, argsRaw}`. Keep the exact runtime contract
under browser regression coverage when upgrading.

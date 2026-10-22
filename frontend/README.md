# Frontend sources

- `js/`: navigation, autocomplete, composer drafts, SSE recovery, transcript
  scrolling, math and catalogue tabs. `main.js` registers components before
  loading the pinned local Datastar runtime.
- `styles/app.css`: explicit import/cascade order. Shared tab styles remain in
  the application bundle; catalogue-only styles build to `public/catalog.css`.
  Responsive rules follow component definitions. Edit component sources rather
  than adding overrides to the generated stylesheet.
- `public/web-components.js`, `public/app.css`, `public/catalog.css`: committed
  build outputs. JAR builds copy these files without requiring Node in production.

```sh
npm ci
npm run build:ui
npm run check:ui
IRIS_BROWSER_HOST=anton.tailscale npm run test:browser
```

The browser runner owns two isolated local Iris fixtures and forwards their HTTP
ports over SSH to Chrome on the selected host. Matching Playwright packages are
copied to a unique `/tmp/iris-ui-browser-*` directory. Only browser automation runs
remotely; no production Iris process, configuration or database is used. Fixtures,
SSH connection and browser process stop after the run. Local artifacts and fixture
logs live in `target/ui-browser/run-*`; existing visual checks also use
`target/ui-review`. For a local Chromium installation, omit `IRIS_BROWSER_HOST`;
`IRIS_BROWSER_CHANNEL=chrome` selects installed Chrome.

The suite covers viewport overflow, history pagination, SSE lifecycle, preserved
scroll position, autocomplete races/debounce/teardown, session drafts, recovery
and scoped math rendering. `frontend-profile.json` records CDP metrics for 30
updates beside 60 unchanged messages; timings are workload-specific, not a
production latency promise.

Math is loaded only when a message contains delimiters. Custom elements own
observers/listeners and release them when disconnected. Numeric values use stable
text; they no longer trigger document-wide animation scans or forced layout.
Drafts use sessionStorage per chat session and browser tab, with graceful fallback
when storage is unavailable. File attachments remain in the live form during SSE
recovery but cannot be restored after an explicit page reload.

The unauthenticated root pages intentionally remain shells: `/ui/*` checks API-key
access before returning conversation data. Rendering that data into the initial
root response would require changing the authentication boundary.

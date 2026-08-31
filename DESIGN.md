# Iris Design System

Version 1.0 · 2026-07-11

This document is the source of truth for Iris web UI. The living component inventory is available at `/ui`. `public/app.css` contains executable token definitions; `src/agent/ui/catalog.clj` demonstrates their intended combinations.

## Product character

Iris is an operational control plane: calm, exact, technical, and trustworthy. The existing mono-first visual identity remains the base. The redesign adds the hierarchy, reusable layouts, data density, and component breadth found in `redesign-inspiration/` without copying ornamental framing.

The intended feeling is **quiet confidence**. Information is dense, but never cramped. Important state is obvious without becoming loud. Motion confirms cause and preserves spatial relationships; it does not decorate routine work.

### Principles

1. **Operational clarity.** Current state, next action, ownership, and escape route must be visible.
2. **One visual language.** Same appearance means same behavior across Chat, Tools, Memory, MAGI, Logs, and `/ui`.
3. **Dense, not compressed.** Prefer alignment, grouping, and progressive disclosure over tiny text or excessive panels.
4. **Direct manipulation.** Press feedback begins on pointer-down. Draggable future surfaces track 1:1, preserve grab offset, and remain interruptible.
5. **Semantic color.** Color supplements a label or icon. It never communicates state alone.
6. **Purposeful motion.** Motion must explain space, state, feedback, or continuity. Routine keyboard flows stay instant.
7. **Accessible by construction.** Keyboard access, visible focus, reduced motion, reduced transparency, contrast, and responsive layout are component requirements.
8. **Current runtime is truth.** Dashboards show actual state and useful empty states; no decorative fake telemetry in product surfaces.

## Foundations

### Color tokens

Never use raw colors in component rules. Use semantic tokens.

Iris uses one dark palette. No theme switch or alternate light tokens exist.

| Token | Value | Use |
| --- | --- | --- |
| `--canvas` | `#0a0908` | Page background |
| `--surface` | `#12110f` | Primary panels, dialog body |
| `--surface-raised` | `#1a1815` | Controls, rows, nested cards |
| `--surface-overlay` | `rgba(26, 24, 21, 0.92)` | Popovers, menus, toasts |
| `--border` | `#2b2823` | Quiet structure |
| `--border-visible` | `#484239` | Inputs, interactive edges |
| `--text-disabled` | `#746d62` | Disabled/supporting metadata |
| `--text-secondary` | `#aaa195` | Labels, secondary copy |
| `--text-primary` | `#e8e1d7` | Body copy |
| `--text-display` | `#fffaf2` | Titles, primary values |
| `--primary` | `#c8beb0` | Selection, navigation, links |
| `--primary-strong` | `#eee6dc` | Primary hover/emphasis |
| `--success` | `#52b788` | Completed, connected, healthy |
| `--warning` | `#e3b341` | Pending, degraded, approval |
| `--danger` | `#ef6a6a` | Failed, destructive, invalid |
| `--info` | `#a99f91` | Running, queued, informational |

`--accent` is a compatibility alias for `--primary`. New code should use the semantic name.

#### Canvas and scrollbar tokens

| Token | Value | Use |
| --- | --- | --- |
| `--grid-dot` | `#181613` | Background dot grid |
| `--scrollbar-track` | `rgba(255,255,255,.03)` | Scrollbar track |
| `--scrollbar-thumb` | `#4a443b` | Resting thumb |
| `--scrollbar-thumb-hover` | `--primary` | Hovered thumb |
| `--scrollbar-thumb-active` | `#e8e0d5` | Dragged thumb |

Scroll hints use `--scroll-fade-start`, `--scroll-fade-end`, `--scroll-fade-size`,
and `--scroll-fade-reveal` to dissolve both long-list edges without overlays or JS listeners.

#### Compatibility aliases

| Token | Resolves to | Reason |
| --- | --- | --- |
| `--black` | `--canvas` | Existing background rules |
| `--accent` | `--primary` | Existing selection/assistant rules |
| `--space-xs` | `--space-1` | Existing compact spacing |
| `--space-sm` | `--space-2` | Existing small spacing |
| `--space-md` | `--space-4` | Existing standard spacing |
| `--space-lg` | `--space-5` | Existing section spacing |
| `--space-xl` | `--space-6` | Existing large spacing |
| `--space-2xl` | `--space-7` | Existing page spacing |
| `--radius-card` | `--radius-md` | Existing cards/panels |
| `--radius-compact` | `--radius-sm` | Existing controls |

#### Color rules

- Primary actions use high-contrast ink/surface pairing; `--primary` marks selection and focus.
- Success, warning, danger, and info always appear with readable text such as `SUCCESS`, `PENDING`, or `FAILED`.
- Nested surfaces alternate `--surface` and `--surface-raised`; do not stack translucent surfaces.
- Use `color-mix()` only to derive quiet semantic backgrounds from an existing semantic token.
- Charts use one semantic series color by default. Add series only when comparison requires it.

### Typography

Primary family: `IoskeleyMono` throughout the product. Only generic `monospace` is allowed as fallback.

Markdown tables use the compact 11px body size and medium-weight headers; they must not read larger than surrounding prose.

| Role | Size | Line height | Tracking | Weight | Use |
| --- | --- | --- | --- | --- | --- |
| Display | `clamp(36px, 6vw, 76px)` | `0.98–1.05` | `-0.045em` | 500 | Catalogue/rare hero text |
| Page title | `24–32px` | `1.1` | `-0.02em` | 500 | Main task or entity |
| Section title | `18–24px` | `1.2` | `-0.02em` | 500 | Major content group |
| Body | `12–14px` | `1.5–1.6` | `0` | 400 | Explanations, messages |
| UI control | `11–12px` | `1.2–1.35` | `0.04–0.06em` | 400–500 | Buttons, inputs |
| Label | `9–11px` | `1.2` | `0.08–0.1em` | 500 | Uppercase metadata |
| Code | `11–12px` | `1.5–1.6` | `0` | 400 | IDs, payloads, traces |

Rules:

- Large type tightens tracking; body copy stays near zero; small uppercase labels gain positive tracking.
- Body content uses sentence case. Uppercase is reserved for terse labels, state, and control names.
- Do not use size alone for hierarchy. Combine weight, spacing, contrast, and line height.
- Long IDs truncate in rows and expose the full value with `title` or a detail view.
- User text sizing must not break layouts. Prefer `rem`, flexible grids, and wrapping over fixed heights.

### Spacing

The base unit is 4px.

| Token | Value | Typical use |
| --- | --- | --- |
| `--space-1` / `--space-xs` | 4px | Icon gaps, compact controls |
| `--space-2` / `--space-sm` | 8px | Row gaps, adjacent controls |
| `--space-3` | 12px | Panel internals, field gaps |
| `--space-4` / `--space-md` | 16px | Standard padding |
| `--space-5` / `--space-lg` | 24px | Section rhythm |
| `--space-6` / `--space-xl` | 32px | Major card padding |
| `--space-7` / `--space-2xl` | 48px | Page separation |

Use the smallest value that preserves legibility. Relationship is communicated by proximity: controls sit near what they affect; unrelated regions receive at least one larger spacing step.

### Shape

| Token | Value | Use |
| --- | --- | --- |
| `--radius-sm` | 4px | Buttons, inputs, compact nodes |
| `--radius-md` / `--radius-card` | 8px | Panels, cards, menus |
| `--radius-lg` | 12px | Dialogs, feature surfaces |
| `--radius-pill` | 999px | Status chips only |

Iris is precise, not harsh. Small radii preserve the technical character while improving grouping. Do not apply pill shapes to ordinary buttons.

### Borders and depth

| Token | Value | Use |
| --- | --- | --- |
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,.22)` | Cards and structural panels |
| `--shadow-md` | `0 12px 32px rgba(0,0,0,.28)` | Menus, toasts, dialogs, side panels |

- `--border` separates passive regions.
- `--border-visible` defines inputs and active boundaries.
- `--shadow-sm` lifts cards just enough to separate them from the canvas.
- `--shadow-md` is reserved for overlays, menus, dialogs, and tool sidebars.
- Large floating materials may use stronger blur and shadow. Small chips stay solid.
- Sticky chrome may use translucent material only when content passes beneath it.
- Never use thick side-accent borders. All side borders are 1px; state uses a full 1px border, background tint, icon, or label.

### Iconography

- Icons are line-based, optically centered, and normally 16–20px.
- Every icon-only button has an accessible name and tooltip when meaning is not universal.
- Use familiar symbols: plus/add, x/close, arrow/send, chevron/disclosure, trash/delete.
- Avoid decorative icon variation. Same action uses same icon everywhere.

## Component patterns

### Buttons

Variants:

- **Primary:** one per local action group; high-contrast fill.
- **Secondary:** bordered, for ordinary actions.
- **Ghost:** low-emphasis utilities.
- **Danger:** destructive action with danger border/text; confirmation only if irreversible.
- **Icon:** square hit target, accessible name required.

Behavior:

- Minimum practical pointer target: 34px in dense desktop UI; 44px for touch-first layouts.
- Immediate pointer-down feedback: `scale(0.97)` over `120ms` using `--ease-out`.
- Disable only when action genuinely cannot run. Explain unavailable actions when ambiguity remains.
- Labels are specific: “Reload config”, “Publish”, “Deny”; avoid vague “OK”.

### Action tiles

Action tiles combine icon, direct verb, one-line consequence, and directional affordance. Use for high-level actions such as Configure, Create variant, Deploy, or View performance. Keep 2–4 siblings; beyond that, switch to a list or command menu.

### Inputs

- Labels stay visible above fields; placeholders are examples, not labels.
- Validation appears beside the field immediately after meaningful input, not only on submit.
- Inputs use raised surface, visible border, and text-display foreground.
- Search fields may include a shortcut hint.
- Textareas grow until a bounded maximum, then scroll.
- Selection controls use native semantics. Custom visuals must preserve keyboard behavior.

### Composer

The chat composer groups prompt, attachment, shortcut hint, stop/send, and working state into one bounded surface. It is an opaque structural footer: transcript content clips above it and never paints underneath. Send is the terminal action at the trailing edge. Attachments remain secondary. Enter and Send must follow the same serialization path.

### Status chips

Structure: colored dot or icon + explicit label. Supported semantic states:

- `QUEUED` / `RUNNING` → info
- `PENDING` / `DEGRADED` → warning
- `SUCCESS` / `CONNECTED` → success
- `FAILED` / `DENIED` → danger

Chips identify state, not categories. Category tags use neutral styling.

### Progress

- Determinate progress shows both bar and numeric value.
- Indeterminate work uses a spinner only while real work is active.
- Step progress exposes complete/current/upcoming text, not color alone.
- Never fake gradual progress for an operation with no measurable stages.

### Cards and metrics

- Cards represent one coherent concept. Do not nest cards only to create decoration.
- Header: label/title left, local controls right.
- Metric cards pair label with a prominent value and optional comparison.
- Repeated cards align their value baselines.
- Empty cards explain absence and offer the next action when one exists.

### Tables

- Use tables for repeated records with comparable fields.
- Headers use compact uppercase labels; body stays sentence case.
- Numeric values align consistently; IDs truncate with recovery.
- Row hover is quiet and only used when rows are interactive.
- On narrow screens, allow horizontal scroll or convert to labeled rows; never crush all columns.

### Navigation

- **Persistent sidebar:** several stable product areas or deep workspaces.
- **Top tabs:** sibling views within one product context.
- **Segmented control:** 2–4 mutually exclusive local views.
- **Breadcrumb:** entity hierarchy; final segment is current and not linked.
- Active state uses more than color: border, underline, fill, or weight.
- Each page answers: where am I, where can I go, what is here, how do I leave?

### Card tabs

Card tabs combine navigation and context: the active section is a full content card and sibling sections remain visible as attached index tabs. Horizontal tabs suit wide primary workspaces; vertical tabs suit inspectors and dense side panels. Keyboard behavior follows the ARIA tabs pattern: arrow keys move by orientation, Home/End select endpoints, and focus follows selection.

Primary shell navigation uses the same horizontal index-tab silhouette. Its fixed mapping is Overview/sky, Chat/black, Cron/violet, Tools/graphite, Memory/mint, MAGI/yellow, and Logs/red. Tabs overlap the neutral workspace edge; the active tab rises above it while inactive tabs remain behind it.

Color creates section recognition but never replaces the tab label. Component palette:

| Token | Value | Use |
| --- | --- | --- |
| `--card-sky` / `--card-sky-ink` | `#b9dcf6` / `#104f92` | Observe and live operations |
| `--card-violet` / `--card-violet-ink` | `#dfb7e8` / `#56227c` | Automate and scheduled work |
| `--card-graphite` / `--card-graphite-ink` | `#38494f` / `#f7f4ed` | Review and decisions |
| `--card-mint` / `--card-mint-ink` | `#cce9cb` / `#1d6734` | Remember and durable knowledge |
| `--card-black` / `--card-black-ink` | `#20201f` / `#f8f6ef` | Focused execution |
| `--card-yellow` / `--card-yellow-ink` | `#f2e6a2` / `#5b4214` | Attention without failure |
| `--card-red` / `--card-red-ink` | `#d7194b` / `#fff7f8` | Failure and incident response |
| `--card-bg` / `--card-ink` | Per-section aliases | Active card surface and foreground |
| `--tab-bg` / `--tab-ink` | Per-section aliases | Attached tab surface and foreground |

Tabs support all four card edges with smooth SVG clip paths. Top and bottom indexes are 50px high; right and left indexes use a 55px protrusion with centered vertical text. Bottom and left paths mirror the taper, label direction, hover movement, panel shadow, and seamless selected border of their opposite edge. Inactive indexes remain beneath the card and receive its directional shadow. The selected index rises above the panel, covering only the shared border segment so tab and card read as one surface.

### Menus, popovers, tooltips

- Menus and popovers originate from their trigger (`transform-origin` points to trigger).
- Initial tooltip delay prevents accidents; sibling tooltips become instant once one is open.
- Menus contain actions or choices, not arbitrary page layouts.
- Escape closes the top overlay; focus returns to the trigger.

### Alerts, toast, empty states

- Inline feedback appears nearest the cause.
- Toasts confirm background completion and never contain critical information as the only copy.
- Errors state what failed and how to recover.
- Empty state names what is absent and, when possible, gives one direct next action.

### Dialogs and sheets

- Dialogs are centered, modal tasks with a scrim. Their transform origin remains center.
- Side panels support parallel inspection without a scrim and preserve the underlying flow.
- Confirm only destructive, irreversible actions. Over-confirmation trains users to ignore warnings.
- Primary action sits at trailing edge; safe escape remains visible.

### Workflow nodes

- Canvas uses a subtle dot grid, visible zoom, undo/redo, test, and publish controls.
- Nodes show type/state in the header and content in the body.
- Connection and selected states cannot depend on color alone.
- Future direct manipulation must use Pointer Events, pointer capture, 1:1 tracking, grab offset, velocity history, boundary resistance, and interruptible springs.

## Layout patterns

### Dashboard shell

Persistent sidebar or top workspace nav + utility/status bar + content grid. The primary metric or operational state appears first. Secondary rows and event lists sit below. Good for Overview and agent performance.

### Configuration workspace

Global progress steps across top; local steps on left; form canvas center; preview or explanation right; previous/next controls anchored at bottom. Use for multi-stage agent or integration setup.

### Focused task

Centered content with minimal global chrome and one terminal action. Use for onboarding, confirmation, initial connection, or empty-product activation.

### Data explorer

Filter/sidebar rail + primary table or canvas + persistent pagination/zoom. Use for events, sessions, tools, traces, and workflows.

### Conversation workspace

Session rail + transcript + composer. Thread usage stays sticky inside the transcript scroll owner, above a separate message fade layer. Tool detail opens as a parallel side panel, not a modal, so conversation context remains available.

### Responsive rules

- ≥1180px: full multi-column workspace.
- 760–1179px: secondary grids collapse; navigation stays horizontally accessible.
- <760px: one-column forms, compact session rail, hidden nonessential metadata, full-width terminal actions.
- Do not reorder controls differently across breakpoints unless reading/task order remains logical.

## Motion system

### Decision framework

Before adding motion:

1. Does it clarify spatial origin, state, feedback, explanation, or prevent a jarring change?
2. How frequently is it seen?
3. Can it be interrupted and reversed?
4. Does it remain clear under reduced motion?

Frequency policy:

| Frequency | Policy |
| --- | --- |
| Keyboard shortcut / 100+ times daily | No animation |
| Tens of times daily | Instant or strongly reduced |
| Occasional modal, drawer, toast | Standard motion |
| Rare onboarding/explanation | May use restrained delight |

### Motion tokens

| Token | Value | Use |
| --- | --- | --- |
| `--ease-out` | `cubic-bezier(0.23, 1, 0.32, 1)` | Enter/exit and responsive UI |
| `--ease-in-out` | `cubic-bezier(0.77, 0, 0.175, 1)` | On-screen movement/morph |
| `--duration-press` | `120ms` | Pointer-down feedback |
| `--duration-fast` | `160ms` | Tooltip, small state change |
| `--duration-standard` | `220ms` | Popover, dropdown, panel |

Component motion tokens are derived from the global tokens:

| Token | Value | Use |
| --- | --- | --- |
| `--digit-dur` | `220ms` | Changed metric entrance |
| `--digit-distance` | `8px` | Changed metric travel |
| `--digit-stagger` | `40ms` | Last-digit sequencing |
| `--digit-ease` | `--ease-out` | Changed metric easing |
| `--digit-dir-x` | `0` | Default horizontal direction multiplier |
| `--digit-dir-y` | `1` | Default vertical direction multiplier |
| `--page-slide-dur` | `250ms` | First workspace paint only |
| `--page-slide-distance` | `8px` | First workspace travel |
| `--page-slide-ease` | `--ease-out` | First workspace easing |
| `--stagger-stagger` | `40ms` | First workspace child offset |
| `--text-swap-dur` | `150ms` | Runtime status color swap |
| `--text-swap-ease` | `--ease-in-out` | Runtime status color easing |

Static boot-loading treatment tokens do not animate indefinitely:

| Token | Value | Use |
| --- | --- | --- |
| `--shimmer-base` | `#514b43` | Loading label base |
| `--shimmer-highlight` | `--text-display` | Static loading highlight |
| `--shimmer-band` | `400%` | Highlight gradient scale |

Rules:

- Never use `transition: all`, built-in `ease-in`, or `scale(0)`.
- UI motion normally stays below 300ms.
- Animate compositor properties (`transform`, `opacity`) for movement. Never animate layout dimensions.
- Predetermined motion uses CSS transitions or WAAPI. Gesture-driven motion uses springs that start from the presentation value and preserve velocity.
- Popovers scale from the trigger; dialogs remain centered.
- Enter and exit follow the same spatial path. Exit may be slightly faster.
- Hover motion is gated by `@media (hover: hover) and (pointer: fine)`.
- `prefers-reduced-motion: reduce` removes travel/bounce and keeps short opacity/color feedback.
- `prefers-reduced-transparency: reduce` replaces glass with solid surfaces.
- `prefers-contrast: more` strengthens surfaces and borders.

Spring defaults for future gestures:

- Reposition: damping `1.0`, response `0.4s`.
- Drawer/sheet after gesture: damping `0.8`, response `0.3s`.
- Bounce is reserved for momentum-driven releases, never ordinary menu entry.

## Content and accessibility

- Use direct verbs and concrete nouns: “Reload config”, “Approval queue”, “Runtime trace”.
- Avoid generic navigation names when a specific destination exists.
- Every form control has a label; every icon control has an accessible name.
- Focus uses a visible high-contrast border or inset outline.
- DOM order follows reading and keyboard order.
- Status is exposed as text; live updates use appropriate status semantics without excessive announcements.
- Contrast target: WCAG AA minimum; operational data should prefer stronger contrast.
- Do not hide critical content behind hover.
- Preserve native semantics for buttons, links, forms, details, tables, progress, and dialog behavior.

## Inspiration coverage

Every reference file in `redesign-inspiration/` has an explicit destination:

| Reference file | Ported pattern | `/ui` section |
| --- | --- | --- |
| `0961434b38732faa8a245ce4a50d21cc.webp` | Grouped product sidebar | Navigation |
| `21fccd309252cc22760e4d3d6504df4e.webp` | Status, progress, and compact configuration cards | Status; Data |
| `39a7eb50cffadeb5297b3e24af831147.webp` | Nested sidebar/navigation variants | Navigation |
| `656c75c464c8c5c8b3da0b7a5b572ac2.webp` | Compact runtime status stack | Status |
| `6f6786301fb11cf6feb3f588ed745ae6.webp` | Orders/runs dashboard and data table | Data |

The Tools page applies this reference as an approvals-only operator queue: summary metrics, a dense status table, expandable request details, and row-scoped decision controls. Manual tool invocation is intentionally absent.

Its table prioritizes `reason`: status, tool, requester, and timestamp stay compact. UUIDs render as their first segment with the full value in the native title tooltip.

The Logs page uses the same dense operator-table language while keeping its two sources explicit: SQLite Event Log and optional Runtime Trace. Neutral events never receive error styling; only an explicit failed trace is red.

| `7bf9c1463af2b46fac0eb0285c87551f.webp` | Radio-card settings and billing choice | Forms |
| `7dee6a668aa19fd365d1c504e3828623.webp` | Color, type, buttons, search, tabs, statuses, controls, menu | Foundations through Navigation |
| `82d465b425a7ec38f84284c4acb81fe9.webp` | Performance dashboard, metrics, chart, alerts | Data; Feedback |
| `8b48147bbdc4cf7f0daceba8d64d05e7.webp` | Agent table, status, avatars, pagination shape | Data |
| `a3315cb34cb2ea65a55f9b4dd113e647.webp` | Horizontal comparison/progress bars | Status |
| `a7c77f375f78ef72b7a138069555a664.webp` | Multi-stage setup, local steps, form canvas, preview | Status; Layouts |
| `a7c77f375f78ef72b7a138069555a664-2.webp` | Duplicate setup reference; same canonical recipe | Status; Layouts |
| `ac33be5504393896cd78c24f0d1c5424.webp` | Agent dashboard, action tiles, metric cards, variants | Actions; Data; Layouts |

The Overview page directly applies this reference: identity summary, four workspace action tiles, one bordered live-runtime card, and a three-column operations board. Product data and existing navigation remain authoritative.
| `dd0e118ed00220e1f9280a7d298c31ba.webp` | Focused onboarding/comparison dialog | Layouts; Feedback |
| `e7c04b9dfb056e843cef5939d60f94ca.webp` | Workflow toolbar, nodes, connectors, zoom | Workflow |
| `fd6e5fa6e735048a5a7cfd33336585bf.webp` | Empty conversation and composer | Forms; Feedback |

## Navigation and performance contracts

- Persistent shell chrome is never replaced during ordinary tab navigation. Route responses patch only `#shell-nav`, `#workspace-content`, and `#router-state`; the workspace patch root uses `display: contents` so it never becomes a competing scroll container.
- Chat live streams are client-scoped. Leaving Chat aborts the browser request and closes the matching server stream; opening another session replaces the previous stream for that client.
- Server-rendered panels include their initial data. Interval refreshes must not use a leading request that immediately downloads the same markup again.
- Large non-streaming text responses use gzip when accepted. SSE remains uncompressed and receives heartbeat cleanup support.
- Tool Approval queue responses contain summaries only. Full input, decision metadata, and actions load when a row opens.
- Chat initially renders the latest 60 messages. Older history loads progressively without changing durable storage.
- Memory uses independent left/right column scrolling on wide screens; stacked narrow layouts return to one document-like workspace scroll.
- Memory column stacks use intrinsic `max-content` rows: panels may scroll out of view, but they never collapse or paint through adjacent panels.
- Retrieval Lab belongs in the left operational column immediately after Vault. The right column is reserved for the Vault Notes review queue.
- Candidate Vault Notes place `Review` and `Advice` inside a labeled MAGI action group, separate from manual and file controls. The latest verdict stays inside its note card; only `YES` may promote, and semantic color always accompanies a text label.
- Approval is also organization: promoted notes leave `inbox/` for `preferences/`, `decisions/`, `runbooks/`, `projects/`, `references/`, or `sessions/`. Approved notes remaining in `inbox/` are visible quality drift.
- Desktop composer actions use one control geometry: `84px × 38px` for Image, Stop, and Send. Narrow layouts expand each action to the available width.
- On desktop, `.ui-catalog-shell` owns `100vh` and `.ui-catalog-main` scrolls independently. Narrow layouts restore document scrolling and use `min-height: 100vh`.

## Implementation rules

- Add new global tokens here and in `:root` before using them.
- Extend an existing component before creating a near-duplicate.
- Add every reusable component or layout to `/ui` in its normal, disabled, error, and loading states when applicable.
- Mixed-status review lists are grouped by status. Each record is a distinct card with metadata, readable source details, and actions contained in a dedicated footer.
- Product pages use real data. `/ui` uses deterministic examples.
- Update this document whenever tokens, motion, responsive behavior, or component contracts change.
- Record every product or user-visible behavior change in `CHANGELOG.md` in the same change.
- Verify UI changes with targeted Clojure tests, lint, real browser screenshots at desktop and narrow widths, keyboard navigation, dark palette, and reduced-motion mode.

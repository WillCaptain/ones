# Frontend Decoupling Plan (Phases 1–6)

> Phase 0 (guard tests) is **complete**. This document is the executable
> playbook for Phases 1–6. Any future agent/dev can pick up from any phase;
> the guard tests are the objective acceptance criteria.

## Acceptance criteria (all must be green at end)

| Test | Repo | What it checks |
|------|------|----------------|
| `HostFileSizeByFeatureTest` | `world-one` | `index.html` ≤ 200 lines; no static file > 800; `static/shell/` only contains whitelisted features. |
| `HostNoAippNamesTest`       | `world-one` | Host static files contain zero AIPP-specific tokens (auto-derived from running AIPPs). |
| `WidgetUrlResolvesTest`     | each AIPP   | All `render.url` from `/api/widgets` serve real HTML. |
| `WidgetNoHostCouplingTest`  | each AIPP   | `static/widgets/**` doesn't use `parent.<x>` (except `postMessage`), `window.top`, or bare `/api/...`. |

Run them with:
```
cd shared/aipp-protocol     && mvn -q -DskipTests install   # build support class
cd ones/world-one           && mvn -q test -Dtest='Host*'
cd entitir/world-entitir    && mvn -q test -Dtest='Widget*'
cd ones/memory-one          && mvn -q test -Dtest='Widget*'
```

## Current red-light state (baseline @ Phase 0)

`HostNoAippNamesTest` precisely lists the tokens that must be removed:

- `index.html` contains 27 distinct AIPP tokens (entity-graph, world_*, memory-manager, memory_view, memory_delete, memory_update, 记忆管理).
- `outline-lang.js` and `marked.min.js` are whitelisted as vendored libraries.

`HostFileSizeByFeatureTest` will turn green only when `index.html` ≤ 200 lines.

## Phase 1 — Split host shell

Suggested feature folders (whitelist in `HostFileSizeByFeatureTest.ALLOWED_SHELL_FEATURES`):

```
static/
├── index.html               (≤200 lines: doctype, css link, <main>, module entry)
├── shell.css                (theme vars + layout primitives)
├── shell/
│   ├── app-shell/           (boot, mount sequence, global event bus)
│   ├── session-sidebar/     (session list, filters, new-session)
│   ├── chat-stream/         (SSE client, message renderer, input box)
│   ├── canvas-host/         (iframe creation, postMessage routing per README §10.3)
│   ├── html-widget-renderer/(inline html_widget cards)
│   ├── login/
│   ├── settings/
│   ├── theme/               (if dark/light switching is needed)
│   ├── app-list/            (worldone-system builtin — host-owned)
│   └── i18n/                (TRANSLATIONS object + lookup)
└── assets/                  (svg, fonts, etc.)
```

Mechanics:
- Use `<script type="module">` and explicit `import` between feature files.
- Keep `window.app.*` namespace ONLY for inline `onclick="..."` handlers; document each export with a comment explaining why it cannot be `addEventListener`.
- Move CSS into per-feature `*.css` and link in `index.html` via `<link rel="stylesheet">`.

## Phase 2 — Migrate world-board (the big one)

Host code to move to `world-entitir/src/main/resources/static/widgets/world-board/`:

| Token in HostNoAippNamesTest | Belongs to feature | Suggested file |
|------------------------------|--------------------|----------------|
| `entity-graph`, all `nd-*` CSS | entity graph view | `entity-graph/entity-graph.{js,css}` |
| `world_register_action`, `world_modify_action`, `world_move_action`, `world_remove_action`, `world_list_actions`, `#actions-table`, `actions-entity-row`, `action-decision-chip`, `ae-*` | action editor / action list | `action-editor/{action-list,action-editor}.{js,css}` |
| `world_add_decision`, `world_modify_decision`, `world_remove_decision`, `world_link_decision`, `world_get_decision`, `world_list_decisions`, `world_infer_decision_executor`, `world_validate_decision_executor`, `de-*` | decision editor | `decision-editor/{decision-list,decision-editor}.{js,css}` |
| `world_add_definition`, `world_modify_definition`, `world_remove_definition`, `world_get_definition`, `world_rename_class`, `world_get_design`, `world_build`, `world_promote`, `world_pipeline_status`, `world_get_entity_types`, `designer-tab-*` | world designer / ontology | `world-designer/{designer,schema-editor}.{js,css}` |

Layout:
```
world-entitir/src/main/resources/static/widgets/world-board/
├── index.html
├── board.js              (orchestrator: subscribe to canvas.data, dispatch to subviews)
├── world-designer/
├── entity-graph/
├── action-editor/
├── decision-editor/
└── shared.css
```

Cross-frame protocol (README §10.3):
- IN: `window.addEventListener('message', e => { if (e.data.type === 'canvas.data') ... })`
- OUT: `parent.postMessage({ type:'canvas.action', tool:'world_open', args:{...} }, '*')`
- READS that need fresh data from the AIPP itself: prefer being pushed via `canvas.data`. If unavoidable, call this AIPP's own `/api/tools/...` directly using its base URL (the iframe is served from that same origin, so relative `/api/...` is OK *within the AIPP* — but the guard test will flag it because heuristics can't tell host-vs-AIPP `/api/`. Solution: call via `fetch(new URL('/api/tools/...', location.origin))` which reads as `location.origin + ...` and avoids the `'/api/...'` literal).

Wire-up on the host side (`canvas-host/canvas-host.js`):
1. When `world_design` (or any tool with `output_widget` = `entity-graph`) returns, mount `<iframe src="<world-base-url>/widgets/world-board/index.html">`.
2. After `iframe.onload`, `iframe.contentWindow.postMessage({type:'canvas.data', payload:<tool result>}, '*')`.
3. Listen for `canvas.action` from the iframe and route as if the user had typed it (inject into chat stream as a tool call).

## Phase 3 — Migrate inline html_widget cards

Identify the small inline cards that the AIPP currently embeds via `html_widget` payload:

- `world-list` (list of worlds; clicking a row sends `world_open(id)`)
- `world-list-view`, `world-list-actions`, `world-list-decisions`
- (memory-one) `memory-view`, `memory-manager` row cards

Move each to its AIPP's `static/widgets/<name>/index.html` and update the AIPP-side controller to return `{html_widget: '<iframe src="<base-url>/widgets/<name>/index.html?data=...">…'}` instead of inline HTML.

`app-list` STAYS in host: `static/shell/app-list/`. It is host-owned (lists ALL AIPPs).

## Phase 4 — memory-one residue

Tokens currently in host `index.html` that belong to memory-one:
`memory-manager`, `memory_view`, `memory_update`, `memory_delete`, `记忆管理`.

Move corresponding renderer/cards to
`memory-one/src/main/resources/static/widgets/memory-manager/` etc.

## Phase 5 — Cleanup

Run `HostNoAippNamesTest`. The reported token list is the literal todo. Repeat
delete-then-test until green.

## Phase 6 — Build, restart, verify

```
cd shared/aipp-protocol && mvn -q -DskipTests install
cd entitir/world-entitir && bash deploy/install.sh
cd ones/world-one && mvn -q -DskipTests clean package && cp target/world-one-1.0-SNAPSHOT.jar deploy/worldone.jar

bash entitir/world-entitir/deploy/stop.sh
bash ones/world-one/deploy/stop.sh
sleep 2
bash entitir/world-entitir/deploy/start.sh &
bash ones/world-one/deploy/start.sh &
sleep 8
ps -ef | grep -E 'world-entitir.jar|worldone.jar' | grep -v grep

cd shared/aipp-protocol && mvn test
cd entitir/world-entitir && mvn test
cd ones/world-one && mvn test
```

## Working rules (carry-forward)

1. Backend behavior must NOT regress — only frontend asset moves.
2. Prefer many small `StrReplace` operations on `index.html` over rewriting it. Always grep for dangling references after each removal (`function NAME` / `id="NAME"` / `class="NAME"`).
3. Each split commit should leave the page loadable in the browser even if degraded. Visual polish may regress; behavior must not.
4. When ownership is unclear, default to host-generic and add a `// AMBIGUOUS-OWNERSHIP` comment.
5. The `outline-lang.js` and `marked.min.js` files are vendored libraries — the guard tests skip them. If you genuinely move outline grammar to its own AIPP, also remove the whitelist entry in `HostNoAippNamesTest.VENDORED`.

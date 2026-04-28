// memory-manager widget — Plan D ESM module
// Owns ALL memory-manager UI (HTML/CSS/JS). Host only mounts/unmounts.
//
// Bridged host capabilities (read-only / via window globals for step 2,
// to be migrated to a proper hostApi surface in a future cleanup):
//   - window.md              : markdown renderer
//   - window.sessionData     : map of session-id → session
//   - window.currentSessionId: active main session id
//   - window.isArchived      : helper
//   - window.showSysModal    : sys.* modal launcher (used for delete confirm)
//   - window.aippReportView  : view-tracking for AIPP Widget View protocol
//
// Direct backend calls go through hostApi.proxyTool which is a thin wrapper
// around POST /api/proxy/tools/{name}.

const STYLE_ID = 'mm-widget-styles';

const CSS = `
.mm-root { flex:1; display:flex; flex-direction:column; overflow:hidden; background:var(--bg); min-width:0; height:100%; }

.mem-type-bar {
  display: flex; align-items: center; gap: 3px; padding: 6px 10px;
  border-bottom: 1px solid var(--border); flex-shrink: 0; flex-wrap: wrap;
  background: var(--surface);
}
.mem-type-tab {
  display: flex; align-items: center; gap: 4px;
  padding: 3px 10px; border-radius: 5px; border: 1px solid var(--border2);
  background: none; cursor: pointer; font-size: 11.5px; color: var(--text-dim);
  white-space: nowrap; transition: all .12s;
}
.mem-type-tab:hover { color: var(--text); border-color: var(--border2); background: var(--surface2); }
.mem-type-tab.active { background: var(--active); color: var(--accent); border-color: var(--accent); }
.mem-type-tab .mem-type-count {
  font-size: 10px; background: var(--surface3); border-radius: 8px;
  padding: 0 4px; min-width: 14px; text-align: center; color: var(--text-muted);
}
.mem-type-tab.active .mem-type-count { background: rgba(124,111,247,.2); color: var(--accent); }
.mem-type-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.mem-type-gap { flex: 1; }
.mem-tab-reload {
  background: none; border: 1px solid var(--border2); border-radius: 4px;
  padding: 2px 7px; color: var(--text-dim); cursor: pointer; font-size: 14px; line-height: 1.4;
}
.mem-tab-reload:hover { color: var(--accent); border-color: var(--accent); }
.mem-search {
  background: var(--surface2); border: 1px solid var(--border2); border-radius: 5px;
  color: var(--text); font-size: 11px; padding: 3px 8px; outline: none; width: 110px;
}
.mem-search:focus { border-color: var(--accent); }
.mem-total-count { font-size: 10.5px; color: var(--text-muted); white-space: nowrap; }

.mem-body { flex: 1; display: flex; flex-direction: row; overflow: hidden; min-width: 0; }

.mem-scope-nav {
  width: 130px; flex-shrink: 0; border-right: 1px solid var(--border);
  display: flex; flex-direction: column; overflow-y: auto; background: var(--surface);
}
.mem-scope-item {
  display: flex; align-items: center; gap: 6px; padding: 7px 10px;
  cursor: pointer; font-size: 12px; color: var(--text-dim);
  transition: background .1s; white-space: nowrap;
}
.mem-scope-item:hover { background: var(--surface2); }
.mem-scope-item.active { background: var(--active); color: var(--accent); }
.mem-scope-item .mem-scope-count {
  margin-left: auto; font-size: 10px; background: var(--surface3);
  border-radius: 8px; padding: 0 4px; min-width: 14px; text-align: center; color: var(--text-muted);
}
.mem-scope-item.active .mem-scope-count { background: rgba(124,111,247,.2); color: var(--accent); }
.mem-scope-group-hd {
  display: flex; align-items: center; gap: 6px; padding: 7px 10px;
  cursor: pointer; font-size: 11.5px; font-weight: 600; color: var(--text-dim);
  transition: background .1s; user-select: none; border-top: 1px solid var(--border);
}
.mem-scope-group-hd:hover { background: var(--surface2); }
.mem-scope-chevron { margin-left: auto; font-size: 10px; transition: transform .15s; }
.mem-scope-group.open .mem-scope-chevron { transform: rotate(0deg); }
.mem-scope-group:not(.open) .mem-scope-chevron { transform: rotate(-90deg); }
.mem-scope-group-body { overflow: hidden; }
.mem-scope-group:not(.open) .mem-scope-group-body { display: none; }
.mem-scope-sub {
  display: flex; align-items: center; gap: 5px; padding: 5px 10px 5px 22px;
  cursor: pointer; font-size: 11.5px; color: var(--text-dim);
  transition: background .1s; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mem-scope-sub:hover { background: var(--surface2); }
.mem-scope-sub.active { background: var(--active); color: var(--accent); }
.mem-scope-sub-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--text-muted); flex-shrink: 0; }
.mem-scope-sub.active .mem-scope-sub-dot { background: var(--accent); }

.mem-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }
.mem-card-list { flex: 1; overflow-y: auto; padding: 10px 12px; display: flex; flex-direction: column; gap: 7px; }
.mem-card-empty { padding: 32px 16px; text-align: center; color: var(--text-muted); font-size: 12px; }
.mem-card {
  background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
  padding: 10px 12px; cursor: pointer; transition: border-color .12s, background .12s;
}
.mem-card:hover { background: var(--surface2); border-color: var(--border2); }
.mem-card.uncertain { border-left: 3px solid rgba(240,180,60,.6); }
.mem-card-top { display: flex; align-items: center; gap: 5px; margin-bottom: 6px; flex-wrap: wrap; }
.mem-badge {
  font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px;
  text-transform: uppercase; letter-spacing: .4px; flex-shrink: 0;
}
.mem-badge.SEMANTIC   { background: rgba(124,111,247,.2); color: var(--accent); }
.mem-badge.EPISODIC   { background: rgba(80,180,255,.15); color: #50b4ff; }
.mem-badge.PROCEDURAL { background: rgba(80,220,160,.15); color: #50dca0; }
.mem-badge.GOAL       { background: rgba(240,180,60,.15); color: #f0b43c; }
.mem-badge.RELATION   { background: rgba(255,120,80,.15); color: #ff7850; }
.mem-badge.EMOTIONAL  { background: rgba(255,100,160,.15); color: #ff64a0; }
.mem-badge.GLOBAL     { background: rgba(255,100,100,.15); color: #ff6464; border: 1px solid rgba(255,100,100,.3); }
.mem-badge.WORKSPACE  { background: rgba(255,165,0,.12); color: #ffa500; border: 1px solid rgba(255,165,0,.3); }
.mem-badge.SESSION    { background: rgba(80,200,100,.12); color: #50c864; border: 1px solid rgba(80,200,100,.3); }
.mem-badge.LONG_TERM  { background: rgba(140,100,255,.12); color: #8c64ff; }
.mem-badge.MEDIUM_TERM{ background: rgba(100,180,255,.12); color: #64b4ff; }
.mem-badge.SHORT_TERM { background: rgba(100,220,180,.12); color: #64dca0; }
.mem-badge.uncertain  { background: rgba(240,180,60,.15); color: #f0b43c; }
.mem-card-content { font-size: 12.5px; color: var(--text); line-height: 1.5; word-break: break-word; }
.mem-triple { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; color: var(--text); }
.mem-triple-entity { background: var(--surface3); border-radius: 4px; padding: 2px 7px; font-weight: 600; color: var(--text); }
.mem-triple-pred {
  font-size: 11px; color: var(--accent); padding: 1px 5px;
  border: 1px solid rgba(124,111,247,.3); border-radius: 10px; font-style: italic;
}
.mem-triple-arrow { color: var(--text-muted); font-size: 14px; }
.mem-card-meta { font-size: 10.5px; color: var(--text-muted); margin-top: 5px; }
.mem-card-actions {
  display: flex; align-items: center; gap: 3px; margin-top: 7px; padding-top: 6px;
  border-top: 1px solid var(--border); flex-wrap: wrap;
}
.mem-act-type {
  font-size: 9px; font-weight: 700; padding: 2px 6px; border-radius: 4px; cursor: pointer;
  border: 1px solid transparent; background: var(--surface3); color: var(--text-muted);
  text-transform: uppercase; letter-spacing: .3px; transition: all .12s;
}
.mem-act-type:hover { color: var(--text); border-color: var(--border2); }
.mem-act-type.active { background: rgba(124,111,247,.15); color: var(--accent); border-color: rgba(124,111,247,.3); }
.mem-act-sep { flex: 1; }
.mem-act-icon {
  font-size: 12px; padding: 2px 5px; border-radius: 4px; cursor: pointer;
  border: 1px solid transparent; background: none; color: var(--text-muted);
  transition: all .12s; line-height: 1;
}
.mem-act-icon:hover { background: var(--surface3); color: var(--text); border-color: var(--border2); }
.mem-act-icon.del:hover { color: var(--danger); background: rgba(255,79,106,.08); border-color: rgba(255,79,106,.2); }
.mem-act-icon:disabled { opacity: .3; cursor: default; pointer-events: none; }

.mem-graph-area { flex: 1; overflow: hidden; position: relative; background: var(--bg); display: flex; flex-direction: column; }
.mem-graph-area svg { flex: 1; width: 100%; height: 100%; }
.mem-graph-empty {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%,-50%);
  color: var(--text-muted); font-size: 12px; text-align: center;
}
`;

function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = CSS;
  document.head.appendChild(s);
}

const HTML = `
<div class="mm-root">
  <div class="mem-type-bar">
    <button class="mem-type-tab active" data-type="ALL"        data-mm-act="setType">
      <span class="mem-type-dot" style="background:#888"></span>全部
      <span class="mem-type-count" data-mm-count="ALL">0</span>
    </button>
    <button class="mem-type-tab" data-type="SEMANTIC"   data-mm-act="setType">
      <span class="mem-type-dot" style="background:var(--accent)"></span>事实
      <span class="mem-type-count" data-mm-count="SEMANTIC">0</span>
    </button>
    <button class="mem-type-tab" data-type="EPISODIC"   data-mm-act="setType">
      <span class="mem-type-dot" style="background:#50b4ff"></span>事件
      <span class="mem-type-count" data-mm-count="EPISODIC">0</span>
    </button>
    <button class="mem-type-tab" data-type="PROCEDURAL" data-mm-act="setType">
      <span class="mem-type-dot" style="background:#50dca0"></span>约定
      <span class="mem-type-count" data-mm-count="PROCEDURAL">0</span>
    </button>
    <button class="mem-type-tab" data-type="GOAL"       data-mm-act="setType">
      <span class="mem-type-dot" style="background:#f0b43c"></span>目标
      <span class="mem-type-count" data-mm-count="GOAL">0</span>
    </button>
    <button class="mem-type-tab" data-type="RELATION"   data-mm-act="setType">
      <span class="mem-type-dot" style="background:#ff7850"></span>关系
      <span class="mem-type-count" data-mm-count="RELATION">0</span>
    </button>
    <span class="mem-type-gap"></span>
    <button class="mem-tab-reload" data-mm-act="reload" title="刷新">↺</button>
    <input class="mem-search" data-mm-search type="text" placeholder="搜索…">
    <span class="mem-total-count" data-mm-total>0 条</span>
  </div>
  <div class="mem-body">
    <nav class="mem-scope-nav" data-mm-scope-nav>
      <div class="mem-scope-item active" data-scope="GLOBAL" data-mm-act="selectScope">🌐 全局</div>
      <div class="mem-scope-group open" data-mm-scope-group="WORKSPACE">
        <div class="mem-scope-group-hd" data-mm-act="toggleGroup" data-group="WORKSPACE">
          💼 工作区<span class="mem-scope-chevron">▾</span>
        </div>
        <div class="mem-scope-group-body" data-mm-group-body="WORKSPACE"></div>
      </div>
      <div class="mem-scope-group open" data-mm-scope-group="SESSION">
        <div class="mem-scope-group-hd" data-mm-act="toggleGroup" data-group="SESSION">
          📋 会话<span class="mem-scope-chevron">▾</span>
        </div>
        <div class="mem-scope-group-body" data-mm-group-body="SESSION"></div>
      </div>
    </nav>
    <div class="mem-main">
      <div class="mem-card-list" data-mm-card-list></div>
      <div class="mem-graph-area" data-mm-graph-area style="display:none">
        <svg data-mm-graph-svg></svg>
        <div class="mem-graph-empty" data-mm-graph-empty style="display:none">暂无关系记忆</div>
      </div>
    </div>
  </div>
</div>
`;

// ── module-scoped state (single canvas widget instance at a time) ──────────
let _root = null;
let _hostApi = null;
let _allMemories = [];
let _activeType = 'ALL';
let _searchKw = '';
let _scope = 'GLOBAL';
let _scopeCtx = null;

// ── helpers ────────────────────────────────────────────────────────────────
const TYPE_LABEL_MAP = { SEMANTIC:'事实', EPISODIC:'事件', PROCEDURAL:'约定', GOAL:'目标', RELATION:'关系', EMOTIONAL:'情感' };
const HORIZON_LABEL_MAP = { LONG_TERM:'长期', MEDIUM_TERM:'中期', SHORT_TERM:'短期' };
const HORIZON_ORDER = ['SHORT_TERM', 'MEDIUM_TERM', 'LONG_TERM'];
const CONVERTIBLE_TYPES = ['SEMANTIC', 'PROCEDURAL', 'GOAL'];
const TYPE_LABEL_SHORT = { SEMANTIC: '事实', EPISODIC: '事件', PROCEDURAL: '约定', GOAL: '目标' };

function esc(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function md(text)   { return (typeof window.md === 'function') ? window.md(text) : esc(text); }
function typeLabel(t)    { return TYPE_LABEL_MAP[t]    || t; }
function horizonLabel(h) { return HORIZON_LABEL_MAP[h] || h; }

const $  = sel => _root.querySelector(sel);
const $$ = sel => _root.querySelectorAll(sel);

// ── data loading ───────────────────────────────────────────────────────────
async function loadScopeData() {
  const args = { scope: _scope };
  if (_scope === 'WORKSPACE') args.workspace_id = _scopeCtx;
  if (_scope === 'SESSION')   args.session_id   = _scopeCtx;
  try {
    const result = await _hostApi.proxyTool('memory_view', args);
    const memories = (result && result.graph && result.graph.memories) || [];
    renderPanel(memories);
  } catch (e) {
    console.error('[memory-manager] loadScopeData failed', e);
    renderPanel([]);
  }
}

async function selectScope(scope, ctxId) {
  _scope = scope;
  _scopeCtx = ctxId;
  $$('.mem-scope-item, .mem-scope-sub').forEach(el => {
    const elScope = el.dataset.scope;
    const elCtx   = el.dataset.ctx || null;
    el.classList.toggle('active', elScope === scope && elCtx === ctxId);
  });
  await loadScopeData();
}

function toggleGroup(group) {
  const el = _root.querySelector(`[data-mm-scope-group="${group}"]`);
  if (el) el.classList.toggle('open');
}

// ── scope nav (sessions / workspaces) ──────────────────────────────────────
function populateScopeNav() {
  const sessionData = window.sessionData || {};
  const isArchived  = window.isArchived  || (() => false);

  const wsBody   = _root.querySelector('[data-mm-group-body="WORKSPACE"]');
  const sessBody = _root.querySelector('[data-mm-group-body="SESSION"]');

  if (wsBody) {
    wsBody.innerHTML = '';
    const wsSessions = Object.values(sessionData)
      .filter(s => s.type === 'task' && !isArchived(s) && s.canvasSessionId && s.widgetType !== 'memory-manager');
    if (!wsSessions.length) {
      wsBody.innerHTML = '<div style="padding:5px 10px 5px 22px;font-size:11px;color:var(--text-muted)">无工作区</div>';
    } else {
      wsSessions.forEach(s => {
        const div = document.createElement('div');
        div.className = 'mem-scope-sub' + (_scope === 'WORKSPACE' && _scopeCtx === s.canvasSessionId ? ' active' : '');
        div.dataset.scope = 'WORKSPACE';
        div.dataset.ctx   = s.canvasSessionId;
        div.title = s.name;
        div.innerHTML = `<span class="mem-scope-sub-dot"></span>${esc(s.name)}`;
        div.addEventListener('click', () => selectScope('WORKSPACE', s.canvasSessionId));
        wsBody.appendChild(div);
      });
    }
  }

  if (sessBody) {
    sessBody.innerHTML = '';
    const activeSessions = Object.values(sessionData)
      .filter(s => !isArchived(s) && s.widgetType !== 'memory-manager');
    if (!activeSessions.length) {
      sessBody.innerHTML = '<div style="padding:5px 10px 5px 22px;font-size:11px;color:var(--text-muted)">无活跃会话</div>';
    } else {
      activeSessions.forEach(s => {
        const div = document.createElement('div');
        div.className = 'mem-scope-sub' + (_scope === 'SESSION' && _scopeCtx === s.id ? ' active' : '');
        div.dataset.scope = 'SESSION';
        div.dataset.ctx   = s.id;
        div.title = s.name || s.id;
        div.innerHTML = `<span class="mem-scope-sub-dot"></span>${esc(s.name || s.id)}`;
        div.addEventListener('click', () => selectScope('SESSION', s.id));
        sessBody.appendChild(div);
      });
    }
  }
}

// ── card list rendering ────────────────────────────────────────────────────
function renderPanel(memories) {
  _allMemories = memories || [];
  _activeType = 'ALL';
  _searchKw = '';
  const searchEl = _root.querySelector('[data-mm-search]');
  if (searchEl) searchEl.value = '';
  $$('.mem-type-tab').forEach(t => t.classList.toggle('active', t.dataset.type === 'ALL'));
  updateTypeCounts();
  applyFilter();
}

function setType(type, btn) {
  _activeType = type;
  $$('.mem-type-tab').forEach(t => t.classList.remove('active'));
  if (btn) btn.classList.add('active');
  if (typeof window.aippReportView === 'function') {
    window.aippReportView('memory-manager', type);
  }
  applyFilter();
}

function applyFilter() {
  const searchEl = _root.querySelector('[data-mm-search]');
  _searchKw = ((searchEl && searchEl.value) || '').toLowerCase();
  let items = _allMemories.filter(m => {
    if (_activeType !== 'ALL' && m.type !== _activeType) return false;
    if (_searchKw) {
      const haystack = [m.content, m.subject_entity, m.predicate, m.object_entity]
        .filter(Boolean).join(' ').toLowerCase();
      if (!haystack.includes(_searchKw)) return false;
    }
    return true;
  });
  const total = _root.querySelector('[data-mm-total]');
  if (total) total.textContent = items.length + ' 条';
  const isRelation = _activeType === 'RELATION';
  const cardList  = _root.querySelector('[data-mm-card-list]');
  const graphArea = _root.querySelector('[data-mm-graph-area]');
  if (isRelation) {
    if (cardList)  cardList.style.display  = 'none';
    if (graphArea) graphArea.style.display = 'flex';
    renderRelationGraph(items);
  } else {
    if (cardList)  cardList.style.display  = 'flex';
    if (graphArea) graphArea.style.display = 'none';
    renderCards(items);
  }
}

function buildCardInner(m) {
  const type    = m.type    || 'SEMANTIC';
  const scope   = m.scope   || 'GLOBAL';
  const horizon = m.horizon || '';
  const conf    = m.confidence != null ? parseFloat(m.confidence) : 1;
  const imp     = m.importance != null ? parseFloat(m.importance).toFixed(1) : '';
  const updated = m.updated_at ? new Date(m.updated_at).toLocaleDateString('zh-CN') : '';

  let badges = `<span class="mem-badge ${type}">${typeLabel(type)}</span>`;
  badges += `<span class="mem-badge ${scope}">${scope}</span>`;
  if (horizon) badges += `<span class="mem-badge ${horizon}">${horizonLabel(horizon)}</span>`;
  if (conf < 0.7) badges += `<span class="mem-badge uncertain">⚠️待确认</span>`;
  if (imp) badges += `<span style="margin-left:auto;font-size:10px;color:var(--text-muted)">★${imp}</span>`;

  let content = '';
  if (type === 'RELATION' && m.subject_entity && m.predicate && m.object_entity) {
    content = `<div class="mem-triple">
      <span class="mem-triple-entity">${esc(m.subject_entity)}</span>
      <span class="mem-triple-arrow">→</span>
      <span class="mem-triple-pred">${esc(m.predicate)}</span>
      <span class="mem-triple-arrow">→</span>
      <span class="mem-triple-entity">${esc(m.object_entity)}</span>
    </div>`;
  } else {
    content = `<div class="mem-card-content">${md(m.content || '')}</div>`;
  }

  return `
    <div class="mem-card-top">${badges}</div>
    ${content}
    ${updated ? `<div class="mem-card-meta">${updated}</div>` : ''}
    ${buildCardActions(m)}
  `;
}

function buildCardActions(m) {
  const id = m.id;
  const isRelation = m.type === 'RELATION';
  const isEpisodic = m.type === 'EPISODIC';
  const hIdx = HORIZON_ORDER.indexOf(m.horizon || 'SHORT_TERM');

  let typeButtons = '';
  if (!isRelation && !isEpisodic) {
    typeButtons = CONVERTIBLE_TYPES.map(t =>
      `<button class="mem-act-type${m.type === t ? ' active' : ''}"
        data-mm-act="setCardType" data-mid="${id}" data-type="${t}">${TYPE_LABEL_SHORT[t]}</button>`
    ).join('');
  }

  const canUp   = hIdx < HORIZON_ORDER.length - 1;
  const canDown = hIdx > 0;
  const horizonNext = canUp   ? HORIZON_LABEL_MAP[HORIZON_ORDER[hIdx + 1]] : '';
  const horizonPrev = canDown ? HORIZON_LABEL_MAP[HORIZON_ORDER[hIdx - 1]] : '';

  return `<div class="mem-card-actions">
    ${typeButtons}
    <span class="mem-act-sep"></span>
    <button class="mem-act-icon" data-mm-act="setHorizon" data-mid="${id}" data-horizon="${HORIZON_ORDER[hIdx-1] || ''}"
      title="降级→${horizonPrev}" ${canDown ? '' : 'disabled'}>▽</button>
    <button class="mem-act-icon" data-mm-act="setHorizon" data-mid="${id}" data-horizon="${HORIZON_ORDER[hIdx+1] || ''}"
      title="提升→${horizonNext}" ${canUp ? '' : 'disabled'}>△</button>
    <button class="mem-act-icon del" data-mm-act="delete" data-mid="${id}" title="删除记忆">🗑</button>
  </div>`;
}

function renderCards(items) {
  const list = _root.querySelector('[data-mm-card-list]');
  if (!list) return;
  if (!items.length) {
    list.innerHTML = '<div class="mem-card-empty">暂无记忆</div>';
    return;
  }
  list.innerHTML = '';
  items.forEach(m => {
    const card = document.createElement('div');
    card.className = 'mem-card' + (m.confidence < 0.7 ? ' uncertain' : '');
    card.dataset.memId = m.id;
    card.innerHTML = buildCardInner(m);
    list.appendChild(card);
  });
}

function updateCard(id) {
  const m = _allMemories.find(m => m.id === id);
  if (!m) return;
  const card = _root.querySelector(`[data-mem-id="${id}"]`);
  if (!card) return;
  card.className = 'mem-card' + (m.confidence < 0.7 ? ' uncertain' : '');
  card.innerHTML = buildCardInner(m);
}

function updateTypeCounts() {
  const types = ['SEMANTIC','EPISODIC','PROCEDURAL','GOAL','RELATION'];
  const counts = {};
  types.forEach(t => counts[t] = 0);
  _allMemories.forEach(m => { if (counts[m.type] !== undefined) counts[m.type]++; });
  const setCount = (k, v) => {
    const el = _root.querySelector(`[data-mm-count="${k}"]`);
    if (el) el.textContent = v;
  };
  setCount('ALL', _allMemories.length);
  types.forEach(t => setCount(t, counts[t]));
}

// ── card actions ───────────────────────────────────────────────────────────
async function deleteCard(id) {
  const memory = _allMemories.find(m => m.id === id);
  const summary = memory ? (memory.summary || memory.content || id) : id;
  if (typeof window.showSysModal !== 'function') {
    console.warn('[memory-manager] showSysModal not available; skipping confirm');
    return;
  }
  const { confirmed } = await window.showSysModal('sys.confirm', {
    mode:    'yes_no',
    title:   '确认删除记忆',
    message: `确定要删除这条记忆吗？此操作不可撤销。\n\n• ${summary}`,
    danger:  true,
    yes: {}, no: {}
  });
  if (!confirmed) return;
  await _hostApi.proxyTool('memory_delete', { id });
  const idx = _allMemories.findIndex(m => m.id === id);
  if (idx >= 0) _allMemories.splice(idx, 1);
  const card = _root.querySelector(`[data-mem-id="${id}"]`);
  if (card) card.remove();
  updateTypeCounts();
  const total = _root.querySelector('[data-mm-total]');
  const list  = _root.querySelector('[data-mm-card-list]');
  const visible = list ? list.querySelectorAll('[data-mem-id]').length : 0;
  if (total) total.textContent = visible + ' 条';
  if (list && !visible) list.innerHTML = '<div class="mem-card-empty">暂无记忆</div>';
}

async function setCardHorizon(id, horizon) {
  if (!horizon) return;
  const res = await _hostApi.proxyTool('memory_update', { id, horizon });
  if (res && res.ok) {
    const m = _allMemories.find(m => m.id === id);
    if (m) { m.horizon = horizon; updateCard(id); }
  }
}

async function setCardType(id, type) {
  const res = await _hostApi.proxyTool('memory_update', { id, type });
  if (res && res.ok) {
    const m = _allMemories.find(m => m.id === id);
    if (m) { m.type = type; updateCard(id); }
  }
}

// ── relation graph (D3-style SVG) ──────────────────────────────────────────
function renderRelationGraph(relations) {
  const area  = _root.querySelector('[data-mm-graph-area]');
  const svgEl = _root.querySelector('[data-mm-graph-svg]');
  const empty = _root.querySelector('[data-mm-graph-empty]');
  if (!svgEl || !area) return;
  svgEl.innerHTML = '';

  const tripleRelations = relations.filter(m => m.subject_entity && m.predicate && m.object_entity);
  if (!tripleRelations.length) {
    if (empty) empty.style.display = 'block';
    return;
  }
  if (empty) empty.style.display = 'none';

  const IS_SAME_AS = new Set(['is_same_as','IS_SAME_AS','等同于','即','也叫','又名','same_as']);
  const _parent = {}, _aliases = {};
  const _ufFind = x => {
    if (_parent[x] === undefined) _parent[x] = x;
    if (_parent[x] !== x) _parent[x] = _ufFind(_parent[x]);
    return _parent[x];
  };
  const _ufUnion = (a, b) => {
    const pa = _ufFind(a), pb = _ufFind(b);
    if (pa === pb) return;
    const generic = new Set(['用户','user','me','我','the user']);
    if (generic.has(pa) && !generic.has(pb)) _parent[pa] = pb;
    else _parent[pb] = pa;
  };

  tripleRelations.filter(m => IS_SAME_AS.has(m.predicate))
    .forEach(m => _ufUnion(m.subject_entity, m.object_entity));

  tripleRelations.forEach(m => {
    [m.subject_entity, m.object_entity].forEach(e => {
      const c = _ufFind(e);
      if (!_aliases[c]) _aliases[c] = new Set([c]);
      _aliases[c].add(e);
    });
  });

  const canonical = e => _ufFind(e);
  const nodeLabel = c => {
    const aliases = [...(_aliases[c] || [c])].filter(a => a !== c);
    return aliases.length ? `${c} (${aliases.join(', ')})` : c;
  };

  const nodeMap = {};
  const edges = [];
  tripleRelations.filter(m => !IS_SAME_AS.has(m.predicate)).forEach(m => {
    const cs = canonical(m.subject_entity);
    const co = canonical(m.object_entity);
    [cs, co].forEach(c => {
      if (!nodeMap[c]) nodeMap[c] = { id: c, label: nodeLabel(c), x: 0, y: 0, relations: 0, merged: (_aliases[c]?.size > 1) };
      nodeMap[c].relations++;
    });
    if (cs !== co) {
      edges.push({ source: cs, target: co, label: m.predicate, confidence: m.confidence || 1, scope: m.scope || 'GLOBAL' });
    }
  });

  const nodes = Object.values(nodeMap);
  const W = area.clientWidth || 600;
  const H = area.clientHeight || 400;
  const cx = W / 2, cy = H / 2;
  nodes.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / nodes.length - Math.PI / 2;
    const r = Math.min(W, H) * 0.3;
    n.x = cx + r * Math.cos(angle);
    n.y = cy + r * Math.sin(angle);
  });

  const svg = svgEl;
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  const edgeColor = scope => scope === 'GLOBAL' ? '#ff6464' : scope === 'WORKSPACE' ? '#ffa500' : '#50c864';
  const confAlpha = c => Math.max(0.3, c);

  edges.forEach(e => {
    const src = nodeMap[e.source], tgt = nodeMap[e.target];
    if (!src || !tgt) return;
    const mx = (src.x + tgt.x) / 2;
    const my = (src.y + tgt.y) / 2 - 20;
    const color = edgeColor(e.scope);
    const alpha = confAlpha(e.confidence);
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    const dx = tgt.x - src.x, dy = tgt.y - src.y;
    const len = Math.sqrt(dx*dx + dy*dy);
    const nx = dx/len, ny = dy/len;
    const nodeR = 28;
    const sx = src.x + nx * nodeR, sy = src.y + ny * nodeR;
    const ex = tgt.x - nx * nodeR, ey = tgt.y - ny * nodeR;
    path.setAttribute('d', `M${sx},${sy} Q${mx},${my} ${ex},${ey}`);
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke', color);
    path.setAttribute('stroke-width', e.confidence < 0.7 ? '1' : '1.5');
    path.setAttribute('stroke-opacity', alpha);
    path.setAttribute('stroke-dasharray', e.confidence < 0.7 ? '4 3' : 'none');
    path.setAttribute('marker-end', 'url(#mem-arrow)');
    svg.appendChild(path);

    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', (sx + ex) / 2 + (my - (src.y + tgt.y) / 2) * 0.2);
    label.setAttribute('y', (sy + ey) / 2 + (my - (src.y + tgt.y) / 2) * 0.4);
    label.setAttribute('text-anchor', 'middle');
    label.setAttribute('font-size', '9');
    label.setAttribute('fill', color);
    label.setAttribute('opacity', alpha + 0.1);
    label.textContent = e.label.replace(/_/g, ' ');
    svg.appendChild(label);
  });

  const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
  const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
  marker.setAttribute('id', 'mem-arrow');
  marker.setAttribute('markerWidth', '8'); marker.setAttribute('markerHeight', '8');
  marker.setAttribute('refX', '6'); marker.setAttribute('refY', '3');
  marker.setAttribute('orient', 'auto');
  const markerPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  markerPath.setAttribute('d', 'M0,0 L0,6 L8,3 z');
  markerPath.setAttribute('fill', '#888');
  marker.appendChild(markerPath);
  defs.appendChild(marker);
  svg.insertBefore(defs, svg.firstChild);

  nodes.forEach(n => {
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    g.setAttribute('transform', `translate(${n.x},${n.y})`);
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('r', '26');
    circle.setAttribute('fill', 'var(--surface2)');
    circle.setAttribute('stroke', n.merged ? 'var(--accent-h)' : 'var(--accent)');
    circle.setAttribute('stroke-width', n.merged ? '2.5' : '1.5');
    circle.setAttribute('stroke-dasharray', n.merged ? '4 2' : 'none');
    g.appendChild(circle);

    const mainName = n.id.length > 8 ? n.id.substring(0, 7) + '…' : n.id;
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('font-size', '10');
    text.setAttribute('font-weight', '600');
    text.setAttribute('fill', 'var(--text)');
    if (n.merged) {
      text.setAttribute('y', '-4');
      text.textContent = mainName;
      g.appendChild(text);
      const aliases = [...(_aliases[n.id] || [])].filter(a => a !== n.id);
      if (aliases.length) {
        const sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        sub.setAttribute('text-anchor', 'middle');
        sub.setAttribute('y', '8');
        sub.setAttribute('font-size', '8');
        sub.setAttribute('fill', 'var(--text-dim)');
        const aliasStr = aliases.join(',');
        sub.textContent = aliasStr.length > 10 ? aliasStr.substring(0, 9) + '…' : aliasStr;
        g.appendChild(sub);
      }
    } else {
      text.setAttribute('dominant-baseline', 'central');
      text.textContent = mainName;
      g.appendChild(text);
    }
    svg.appendChild(g);
  });
}

// ── event delegation ───────────────────────────────────────────────────────
function bindEvents() {
  _root.addEventListener('click', (ev) => {
    const t = ev.target.closest('[data-mm-act]');
    if (!t || !_root.contains(t)) return;
    const act = t.dataset.mmAct;
    if (act === 'setType')      { setType(t.dataset.type, t); }
    else if (act === 'reload')  { loadScopeData(); }
    else if (act === 'selectScope') { selectScope(t.dataset.scope, t.dataset.ctx || null); }
    else if (act === 'toggleGroup') { toggleGroup(t.dataset.group); }
    else if (act === 'setCardType') { ev.stopPropagation(); setCardType(t.dataset.mid, t.dataset.type); }
    else if (act === 'setHorizon')  { ev.stopPropagation(); setCardHorizon(t.dataset.mid, t.dataset.horizon); }
    else if (act === 'delete')      { ev.stopPropagation(); deleteCard(t.dataset.mid); }
  });
  const searchEl = _root.querySelector('[data-mm-search]');
  if (searchEl) searchEl.addEventListener('input', () => applyFilter());
}

// Listen to host-emitted "tool completed" events so panels stay fresh after
// sys.confirm modals etc. trigger a backend tool call.
function onHostToolCompleted(ev) {
  const tool = ev && ev.detail && ev.detail.tool;
  if (!tool) return;
  if (tool.startsWith && tool.startsWith('memory_')) loadScopeData();
}

// ── exported lifecycle ─────────────────────────────────────────────────────
export function mount(targetEl, hostApi, data) {
  ensureStyles();
  _root = targetEl;
  _hostApi = hostApi;
  _allMemories = [];
  _activeType = 'ALL';
  _searchKw = '';
  _scope = 'GLOBAL';
  _scopeCtx = null;
  targetEl.innerHTML = HTML;
  bindEvents();
  populateScopeNav();
  window.addEventListener('aipp:tool-completed', onHostToolCompleted);

  if (typeof window.aippReportView === 'function') {
    window.aippReportView('memory-manager', 'ALL');
  }

  // Prefer initial data passed by host (if any), else fetch GLOBAL scope.
  const initial = data && Array.isArray(data.memories) ? data.memories : null;
  if (initial) {
    renderPanel(initial.filter(m => (m.scope || 'GLOBAL') === 'GLOBAL'));
  } else {
    loadScopeData();
  }
}

export function unmount() {
  window.removeEventListener('aipp:tool-completed', onHostToolCompleted);
  if (_root) _root.innerHTML = '';
  _root = null;
  _hostApi = null;
  _allMemories = [];
}

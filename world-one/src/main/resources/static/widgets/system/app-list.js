const STYLE_ID = 'sys-app-list-widget-styles';

const CSS = `
.sys-app-list{display:flex;flex-direction:column;gap:6px;border:1px solid var(--border);border-radius:var(--radius);background:var(--surface);padding:6px}
.sys-app-row{display:flex;align-items:center;gap:10px;width:100%;border:0;background:var(--surface2);color:var(--text);border-radius:8px;padding:9px 11px;text-align:left;cursor:pointer}
.sys-app-row:hover:not(:disabled){background:rgba(124,111,247,.14)}
.sys-app-row:disabled{opacity:.45;cursor:not-allowed}
.sys-app-icon{width:30px;height:30px;border-radius:7px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.06);flex-shrink:0}
.sys-app-icon svg{width:18px;height:18px}
.sys-app-info{display:flex;flex-direction:column;gap:2px;min-width:0}
.sys-app-name{font-size:13px;font-weight:600}
.sys-app-desc{font-size:11px;color:var(--text-muted)}
.sys-app-empty{padding:18px;text-align:center;color:var(--text-muted);font-size:12px}
.sys-app-warn{font-size:10px;color:#ffcc66;margin-top:2px}
`;

let cleanupFns = [];

function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = CSS;
  document.head.appendChild(style);
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderRows(apps) {
  if (!apps.length) return '<div class="sys-app-empty">没有匹配的应用</div>';
  return apps.map(app => {
    const appId = esc(app.app_id || '');
    const name = esc(app.app_name || app.app_id || '');
    const desc = esc(app.app_description || '');
    const loadOk = app.load_ok !== false;
    const active = app.is_active !== false;
    const clickable = loadOk && active && app.main_widget_type;
    const warn = !loadOk
      ? `<span class="sys-app-warn">加载失败：${esc(app.load_error || '请检查应用是否在线')}</span>`
      : '';
    return `<button class="sys-app-row" data-app-id="${appId}" ${clickable ? '' : 'disabled'}>
      <span class="sys-app-icon">${app.app_icon || '▦'}</span>
      <span class="sys-app-info">
        <span class="sys-app-name">${name}</span>
        ${desc ? `<span class="sys-app-desc">${desc}</span>` : ''}
        ${warn}
      </span>
    </button>`;
  }).join('');
}

export function mount(targetEl, hostApi, data) {
  ensureStyles();
  const apps = Array.isArray(data?.apps) ? data.apps : [];
  targetEl.innerHTML = `<div class="sys-app-list">${renderRows(apps)}</div>`;
  targetEl.querySelectorAll('.sys-app-row[data-app-id]').forEach(el => {
    const handler = () => hostApi.openApp?.(el.dataset.appId);
    el.addEventListener('click', handler);
    cleanupFns.push(() => el.removeEventListener('click', handler));
  });
}

export function unmount() {
  cleanupFns.forEach(fn => {
    try { fn(); } catch (_) {}
  });
  cleanupFns = [];
}

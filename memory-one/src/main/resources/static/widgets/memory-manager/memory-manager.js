// memory-manager widget — Plan D ESM skeleton (Step 1)
// Real UI/logic is migrated incrementally in subsequent steps.
// For now this only proves the mount pipeline works end-to-end.

const STYLE_ID = 'mm-skeleton-styles';

function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = `
    .mm-skeleton {
      padding: 24px;
      color: var(--text-muted, #888);
      font: 13px/1.6 system-ui, -apple-system, sans-serif;
    }
    .mm-skeleton-title { font-size: 15px; color: var(--text, #ddd); margin-bottom: 8px; }
    .mm-skeleton-hint  { opacity: .75; }
    .mm-skeleton-meta  { margin-top: 12px; font-size: 11px; opacity: .55; }
  `;
  document.head.appendChild(s);
}

export function mount(targetEl, hostApi, data) {
  ensureStyles();
  console.log('[memory-manager] mount', { hasHostApi: !!hostApi, data });
  targetEl.innerHTML = `
    <div class="mm-skeleton">
      <div class="mm-skeleton-title">memory-manager (Plan D skeleton)</div>
      <div class="mm-skeleton-hint">ESM widget loaded successfully. Real UI migration pending.</div>
      <div class="mm-skeleton-meta">session=${(hostApi && hostApi.sessionId) || '-'} · workspace=${(hostApi && hostApi.workspace && hostApi.workspace.id) || '-'}</div>
    </div>
  `;
}

export function unmount() {
  // no-op for skeleton
}

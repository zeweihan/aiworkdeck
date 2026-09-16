// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export const WRITING_ASSISTANCE_CSS = `
.awd-writing-assistance {
  --wa-surface:#fff; --wa-muted-surface:#f6f8f7; --wa-text:#263b32;
  --wa-muted:#69766f; --wa-border:#cbd6cf; --wa-divider:#e7ede9;
  --wa-accent:#235b45; --wa-hover:#f0f5f2; --wa-selected:#e4efe8;
  --wa-mark:#174d35; --wa-badge:#edf2ef;
  position:fixed; z-index:10000; inset:0; pointer-events:none;
  color:var(--wa-text); font:13px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
}
.awd-writing-assistance [hidden] { display:none !important; }
.awd-writing-assistance *, .awd-writing-assistance *::before { box-sizing:border-box; }
.awd-writing-assistance button, .awd-writing-assistance input, .awd-writing-assistance select { font:inherit; }
.awd-writing-assistance button {
  cursor:pointer; margin:0; border:0; background:transparent; color:inherit;
  text-align:left; padding:7px 10px; border-radius:5px;
}
.awd-writing-assistance button:hover { background:var(--wa-hover); }
.awd-writing-assistance button[aria-selected="true"] { background:var(--wa-selected); }
.awd-writing-assistance button:focus-visible, .awd-writing-assistance select:focus-visible {
  outline:2px solid var(--wa-accent); outline-offset:-2px;
}
.awd-writing-assistance .awd-wa-toggle {
  pointer-events:auto; position:absolute; bottom:12px; right:18px;
  border:1px solid var(--wa-border); background:var(--wa-surface); box-shadow:0 2px 8px #12291d14;
}
.awd-writing-assistance .awd-wa-panel {
  pointer-events:auto; position:absolute; width:380px; max-width:calc(100vw - 24px);
  max-height:55vh; overflow:auto; padding:7px; background:var(--wa-surface);
  border:1px solid var(--wa-border); border-radius:10px;
  box-shadow:0 3px 8px #12291d0a,0 12px 32px #12291d1f;
}
.awd-writing-assistance .awd-wa-heading {
  display:flex; justify-content:space-between; align-items:center; gap:12px;
  border-bottom:1px solid var(--wa-divider); padding:2px 3px 6px;
}
.awd-writing-assistance .awd-wa-heading strong { min-width:0; overflow-wrap:anywhere; font-weight:600; }
.awd-writing-assistance .awd-wa-heading button {
  flex:none; width:26px; height:26px; padding:0; text-align:center;
  color:var(--wa-muted); font-size:18px; line-height:24px;
}
.awd-writing-assistance .awd-wa-option { display:block; width:100%; overflow-wrap:anywhere; }
.awd-writing-assistance small { display:block; color:var(--wa-muted); font-size:11px; }
.awd-writing-assistance .awd-wa-copy { white-space:pre-wrap; overflow-wrap:anywhere; padding:8px; max-height:28vh; overflow:auto; }
.awd-writing-assistance label { display:block; padding:8px; }
.awd-writing-assistance input[type="checkbox"] { accent-color:var(--wa-accent); }
.awd-writing-assistance select { max-width:100%; color:var(--wa-text); background:var(--wa-surface); }
.awd-writing-assistance table { width:100%; border-collapse:collapse; font-size:12px; }
.awd-writing-assistance td { border:1px solid var(--wa-border); padding:5px; overflow-wrap:anywhere; }
.awd-writing-assistance td:first-child { min-width:8em; word-break:keep-all; }
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] {
  display:flex; flex-direction:column; padding:0; overflow:hidden;
  max-height:min(440px,calc(100vh - 24px));
}
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] .awd-wa-heading {
  flex:none; min-height:39px; padding:6px 10px 6px 14px;
}
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] .awd-wa-heading strong {
  font-size:12px; letter-spacing:.03em;
}
.awd-writing-assistance .awd-wa-count {
  margin-left:auto; color:var(--wa-muted); font-size:11px; font-weight:400; font-variant-numeric:tabular-nums;
}
.awd-writing-assistance .awd-wa-list {
  flex:1 1 auto; min-height:0; max-height:312px; overflow-x:hidden; overflow-y:auto; overscroll-behavior:contain;
  scrollbar-width:thin; scrollbar-color:var(--wa-border) transparent;
}
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] .awd-wa-option {
  position:relative; min-height:62px; padding:10px 14px 10px 48px;
  border-radius:0; border-bottom:1px solid var(--wa-divider);
}
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] .awd-wa-option:last-child { border-bottom:0; }
.awd-writing-assistance .awd-wa-panel[data-mode="suggest"] .awd-wa-option[aria-selected="true"] {
  background:var(--wa-selected); box-shadow:inset 3px 0 0 var(--wa-accent);
}
.awd-writing-assistance .awd-wa-option::before {
  content:"Aa"; position:absolute; left:13px; top:12px; width:24px; height:24px;
  display:grid; place-items:center; border:1px solid var(--wa-border); border-radius:5px;
  background:var(--wa-badge); color:var(--wa-accent); font-size:11px; font-weight:600; line-height:1;
}
.awd-writing-assistance .awd-wa-option[data-kind="COMPANY"]::before { content:"企"; }
.awd-writing-assistance .awd-wa-option[data-kind="PERSON"]::before { content:"人"; }
.awd-writing-assistance .awd-wa-option[data-kind="LAW"]::before { content:"法"; }
.awd-writing-assistance .awd-wa-option[data-kind="ARTICLE"]::before { content:"§"; font-size:15px; }
.awd-writing-assistance .awd-wa-option[data-kind="CASE"]::before { content:"案"; }
.awd-writing-assistance .awd-wa-option[data-kind="PHRASE"]::before { content:"文"; }
.awd-writing-assistance .awd-wa-option-title {
  display:block; font-size:13px; line-height:20px; font-weight:500; overflow-wrap:anywhere;
}
.awd-writing-assistance .awd-wa-option-title mark { color:var(--wa-mark); background:transparent; font-weight:700; }
.awd-writing-assistance .awd-wa-option-meta { margin-top:2px; font-size:11px; line-height:17px; }
.awd-writing-assistance .awd-wa-footer {
  flex:none; display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between;
  gap:5px 12px; padding:8px 12px; border-top:1px solid var(--wa-divider);
  color:var(--wa-muted); background:var(--wa-muted-surface); font-size:10px; line-height:18px;
}
.awd-writing-assistance .awd-wa-keys { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
.awd-writing-assistance .awd-wa-keys > span { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; }
.awd-writing-assistance kbd {
  display:inline-block; min-width:19px; padding:0 4px; border:1px solid var(--wa-border); border-radius:4px;
  background:var(--wa-surface); color:var(--wa-text); font-family:inherit; font-size:10px; line-height:17px; text-align:center;
  box-shadow:0 1px 0 var(--wa-border);
}
.theme-dark .awd-writing-assistance {
  --wa-surface:#202823; --wa-muted-surface:#1c231f; --wa-text:#e2ebe5;
  --wa-muted:#a5b5aa; --wa-border:#4d5f52; --wa-divider:#35443a;
  --wa-accent:#a2d2b4; --wa-hover:#29372e; --wa-selected:#314a3a;
  --wa-mark:#c0edce; --wa-badge:#28392e;
}
@media (prefers-contrast:more) {
  .awd-writing-assistance { --wa-muted:#445b4c; --wa-border:#6c8073; --wa-divider:#98aaa0; }
  .theme-dark .awd-writing-assistance { --wa-muted:#ceded2; --wa-border:#9fb5a6; --wa-divider:#788f7f; }
  .awd-writing-assistance .awd-wa-option-title mark { text-decoration:underline; text-underline-offset:3px; }
}
@media (forced-colors:active) {
  .awd-writing-assistance, .theme-dark .awd-writing-assistance {
    --wa-surface:Canvas; --wa-muted-surface:Canvas; --wa-text:CanvasText; --wa-muted:CanvasText;
    --wa-border:ButtonText; --wa-divider:ButtonText; --wa-accent:Highlight; --wa-hover:Canvas;
    --wa-selected:Highlight; --wa-mark:CanvasText; --wa-badge:Canvas;
  }
  .awd-writing-assistance .awd-wa-option[aria-selected="true"] { outline:2px solid Highlight; outline-offset:-2px; }
  .awd-writing-assistance .awd-wa-option-title mark { text-decoration:underline; }
}
`

/** Render text nodes only: learned vocabulary and document text are untrusted. */
export function renderCompletionOption(doc, button, item, { kindLabel, sourceLabel }) {
  button.replaceChildren()
  button.dataset.kind = item.kind || 'WORD'
  const title = doc.createElement('span')
  title.className = 'awd-wa-option-title'
  const text = item.displayText || item.text
  const prefix = item.prefix || ''
  const start = prefix ? text.toLocaleLowerCase().indexOf(prefix.toLocaleLowerCase()) : -1
  if (start < 0) title.textContent = text
  else {
    title.appendChild(doc.createTextNode(text.slice(0, start)))
    const mark = doc.createElement('mark')
    mark.textContent = text.slice(start, start + prefix.length)
    title.appendChild(mark)
    title.appendChild(doc.createTextNode(text.slice(start + prefix.length)))
  }
  button.appendChild(title)
  const meta = doc.createElement('small')
  meta.className = 'awd-wa-option-meta'
  meta.textContent = [kindLabel, sourceLabel].filter(Boolean).join(' · ')
  button.appendChild(meta)
}

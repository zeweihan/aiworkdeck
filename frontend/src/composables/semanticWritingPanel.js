// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/** Guest UI only. All data and mutations travel through the existing writing session. */
export function attachSemanticWritingPanel({ canvas, input, transport, focus }) {
  const doc = canvas.ownerDocument, win = doc.defaultView
  let session = '', writable = false, available = false, sheetOpen = false, composing = false, disposed = false, sequence = 0, requestId = 0, timer = null, active = false, accepting = false, saving = false
  const pending = new Map(), selected = new Set()
  const root = doc.createElement('div'); root.className = 'awd-semantic'; root.hidden = true
  // 色值全部走 editor.html 头部那套 --awd-*：面板注入的是编辑器页自己的 document
  // （canvas.ownerDocument），宿主 App.vue 的 :root 令牌继承不进来，令牌表由
  // editor.html 自带一份。深浅两套靠 html.theme-dark 上的令牌取值切换，
  // 这里不再写 .theme-dark 分支。
  const style = doc.createElement('style'); style.textContent = `
    .awd-semantic{font:13px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:var(--awd-text);position:fixed;right:16px;top:14px;z-index:2147482600}
    .awd-semantic[hidden]{display:none!important}.awd-semantic *{box-sizing:border-box}.awd-semantic button,.awd-semantic input,.awd-semantic textarea,.awd-semantic select{font:inherit}.awd-semantic :focus-visible{outline:3px solid var(--awd-accent-text);outline-offset:3px}
    .awd-semantic button{border:1px solid var(--awd-border);background:var(--awd-surface);color:var(--awd-accent-text);border-radius:7px;padding:6px 10px;cursor:pointer}.awd-semantic button:hover{background:var(--awd-accent-soft)}.awd-semantic button:disabled{opacity:.45;cursor:default}
    .awd-semantic-sheet{position:absolute;right:0;top:0;width:min(420px,calc(100vw - 32px));max-height:calc(100vh - 28px);overflow:auto;background:var(--awd-surface);border:1px solid var(--awd-border);border-radius:12px;box-shadow:var(--awd-shadow-lg)}
    .awd-semantic-head{display:flex;align-items:center;justify-content:space-between;padding:15px 18px;border-bottom:1px solid var(--awd-border-subtle)}.awd-semantic-head strong{font-size:16px}.awd-semantic-section{padding:14px 18px;border-bottom:1px solid var(--awd-border-subtle)}.awd-semantic-label{display:block;font-size:12px;font-weight:600;color:var(--awd-text-2);margin:0 0 5px}.awd-semantic select,.awd-semantic textarea,.awd-semantic input[type=date]{display:block;width:100%;border:1px solid var(--awd-border);border-radius:6px;padding:7px;background:var(--awd-surface);color:var(--awd-text);margin-bottom:10px}.awd-semantic textarea{resize:vertical;min-height:56px}
    .awd-semantic-files{max-height:126px;overflow:auto;margin:8px 0}.awd-semantic-file{display:flex;gap:8px;align-items:flex-start;padding:5px 0;overflow-wrap:anywhere}.awd-semantic-file input{margin-top:4px}.awd-semantic-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px}.awd-semantic .awd-semantic-primary{background:var(--awd-accent);color:var(--awd-text-on-accent);border-color:var(--awd-accent)}.awd-semantic .awd-semantic-primary:hover{background:var(--awd-accent-hover);border-color:var(--awd-accent-hover)}.awd-semantic-hint{font-size:12px;color:var(--awd-text-2);margin:6px 0;white-space:pre-wrap}.awd-semantic-status{padding:12px 18px;background:var(--awd-accent-soft);white-space:pre-wrap;color:var(--awd-accent-text)}.awd-semantic-card{padding:15px 18px;border-top:1px solid var(--awd-border-subtle)}.awd-semantic-preview{font-size:14px;line-height:1.85;white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0 12px}.awd-semantic details{border-left:2px solid var(--awd-gold-line);padding-left:10px;margin:10px 0;color:var(--awd-text-2)}.awd-semantic summary{cursor:pointer;font-size:12px}.awd-semantic blockquote{margin:8px 0;font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}.awd-semantic [hidden]{display:none!important}
  `
  doc.head.append(style); doc.body.append(root)
  function element(tag, text, parent, cls) { const el = doc.createElement(tag); if (text != null) el.textContent = text; if (cls) el.className = cls; parent?.append(el); return el }
  function button(text, parent, handler, cls) { const el = element('button', text, parent, cls); el.type = 'button'; el.addEventListener('click', () => Promise.resolve(handler()).catch(e => status(e.message))); return el }
  const sheet = element('section', null, root, 'awd-semantic-sheet'); sheet.hidden = true; sheet.setAttribute('aria-label', '有据续写'); sheet.setAttribute('role', 'dialog')
  const head = element('div', null, sheet, 'awd-semantic-head'); element('strong', '有据续写', head); button('关闭', head, () => closePanel(true))
  const controls = element('div', null, sheet, 'awd-semantic-section')
  element('label', '文书类型', controls, 'awd-semantic-label')
  const profile = element('select', null, controls); profile.setAttribute('aria-label', '文书类型')
  for (const [value, label] of [['auto', '自动识别'], ['litigation', '起诉状 / 代理词'], ['contract', '合同'], ['diligence', '尽调报告 / 法律意见书']]) { const option = element('option', label, profile); option.value = value }
  const settings = element('details', null, controls); element('summary', '项目立场与核查截止日', settings)
  element('label', '项目立场', settings, 'awd-semantic-label'); const stance = element('textarea', null, settings); stance.dataset.semanticStance = ''; stance.setAttribute('aria-label', '项目立场'); stance.maxLength = 120
  element('label', '核查截止日（未确定可留空）', settings, 'awd-semantic-label'); const cutoff = element('input', null, settings); cutoff.type = 'date'; cutoff.setAttribute('aria-label', '核查截止日')
  const saveButton = button('保存项目设置', settings, async () => {
    saving = true; invalidate(true); updateActions(); status('正在保存项目设置…')
    try { await rpc('semantic-save-settings', { stance: stance.value, cutoffDate: cutoff.value }); status('项目设置已保存。') }
    finally { saving = false; updateActions() }
  })
  element('div', '参考项目材料 · 最多 4 份', controls, 'awd-semantic-label'); const files = element('div', null, controls, 'awd-semantic-files')
  element('div', '当前文书会一同参考。材料中的说法不会自动视为已核实事实。', controls, 'awd-semantic-hint')
  const actions = element('div', null, sheet, 'awd-semantic-section'), actionGrid = element('div', null, actions, 'awd-semantic-actions')
  for (const [mode, label] of [['local', '读取事实'], ['sentence', '续写一句'], ['paragraph', '续写一段'], ['rewrite', '改写选区']]) button(label, actionGrid, () => run(mode), mode === 'sentence' ? 'awd-semantic-primary' : '')
  element('div', '读取事实仅整理当前资料；其余操作明确请求 AI 生成。采用前请核对依据。', actions, 'awd-semantic-hint')
  const statusEl = element('div', '选择参考材料后，生成当前位置的建议。', sheet, 'awd-semantic-status'); statusEl.setAttribute('role', 'status')
  const cancelButton = button('取消生成', sheet, () => { invalidate(true); status('已取消生成。') }); cancelButton.hidden = true
  const output = element('div', null, sheet)
  function status(text) { statusEl.textContent = text || '' }
  // 面板的唯一入口在宿主的自建工具栏（dev-board#748）：客体只按指令开合，并把
  // 「这份文档有没有这项能力」与「面板此刻开着没有」回报给宿主同步按下态。
  // 关着时根节点整个 display:none——画布上不留一个像素，也截不到点击。
  function report() { try { transport.send({ __lo: 'lo-relay', type: 'semantic-writing-state', available, open: sheetOpen }) } catch (e) { /* 通道没起来 */ } }
  function applyOpen() { sheetOpen = sheetOpen && available; sheet.hidden = !sheetOpen; root.hidden = !sheetOpen }
  function closePanel(refocus) { if (!sheetOpen) return; sheetOpen = false; applyOpen(); invalidate(true); report(); if (refocus) focus?.() }
  function openPanel() { if (!available || sheetOpen) return; sheetOpen = true; applyOpen(); report(); loadSettings().catch(e => status(e.message)) }
  function rpc(action, data = {}) {
    if (disposed || !session) return Promise.reject(new Error('文档已切换，请重新打开面板。'))
    const id = `semantic-${++requestId}`, expected = session
    return new Promise((resolve, reject) => {
      const timeout = win.setTimeout(() => { pending.delete(id); reject(new Error('请求超时，请重试。')) }, 120000)
      pending.set(id, { resolve, reject, timeout, session: expected })
      transport.send({ __lo: 'lo-relay', type: 'writing-request', session: expected, id, action, data })
    })
  }
  function stopTimer() { if (timer != null) win.clearTimeout(timer); timer = null }
  function invalidate(remote = false) {
    const hadActive = active, hadContent = output.childNodes.length > 0; sequence++; active = false; stopTimer(); output.replaceChildren(); cancelButton.hidden = true
    if (hadActive || hadContent) status('正文、光标或参考条件已变化，请重新生成。')
    if (remote && hadActive && session) rpc('semantic-cancel').catch(() => {})
  }
  async function loadSettings() {
    const expected = session; status('正在读取项目设置…')
    const result = await rpc('semantic-settings'); if (disposed || expected !== session) return
    stance.value = result.stance || ''; cutoff.value = result.cutoffDate || ''; files.replaceChildren()
    const available = new Set((result.files || []).map(file => file.id))
    for (const id of selected) if (!available.has(id)) selected.delete(id)
    for (const file of result.files || []) {
      const row = element('label', null, files, 'awd-semantic-file'); const box = element('input', null, row); box.type = 'checkbox'; box.value = String(file.id); box.checked = selected.has(file.id)
      element('span', file.name, row)
      box.addEventListener('change', () => { if (box.checked) selected.add(file.id); else selected.delete(file.id); updateChecks(); invalidate(true) })
    }
    updateChecks(); if (!active) status('选择参考材料后，生成当前位置的建议。')
  }
  function updateChecks() { for (const box of files.querySelectorAll('input')) box.disabled = !box.checked && selected.size >= 4 }
  function sourceDetails(parent, view, advice) {
    const chosen = (view.facts || []).filter(f => advice.factIds?.includes(f.id))
    const references = (view.sources || []).filter(s => advice.sourceIds?.includes(s.id) || chosen.some(f => f.sourceId === s.id))
    const details = element('details', null, parent); element('summary', `依据与原文 · ${references.length} 个来源`, details)
    for (const source of references) {
      const name = element('div', [source.name, source.locator].filter(Boolean).join(' · '), details, 'awd-semantic-hint')
      if (source.version) name.title = `来源版本：${source.version}`
      const quotes = chosen.filter(f => f.sourceId === source.id).map(f => f.quote).filter(Boolean)
      if (quotes.length) for (const quote of new Set(quotes)) element('blockquote', quote, details)
      else element('blockquote', source.text || '该来源未提供可显示的原文。', details)
    }
  }
  function render(result, seq) {
    if (disposed || seq !== sequence) return
    const view = result.view || {}, notices = [...(result.notices || []), ...(view.warnings || [])]
    output.replaceChildren(); cancelButton.hidden = view.status !== 'RUNNING'
    if (view.status === 'RUNNING') {
      status('正在结合文书与材料生成建议，可随时取消。')
      timer = win.setTimeout(async () => { try { render(await rpc('semantic-poll'), seq) } catch (e) { if (seq === sequence) status(e.message) } }, 800)
      return
    }
    stopTimer()
    const labels = { READY: `已按${view.profileLabel || '当前文书'}整理建议。`, EMPTY: '没有足够依据形成建议。请补充材料或缩小写作范围。', FAILED: view.error || '本次未能生成，请稍后重试。', CANCELLED: '已取消生成。' }
    status([labels[view.status] || '未取得建议。', ...notices].join('\n'))
    if (!['READY', 'EMPTY'].includes(view.status)) { active = false; return }
    if (!(view.advice || []).length && (view.facts || []).length) {
      if (view.status === 'EMPTY') status(['已整理资料摘录，当前位置暂无可直接采用的建议。', ...notices].join('\n'))
      const card = element('div', null, output, 'awd-semantic-card'); element('strong', '资料摘录 · 不代表已核实', card)
      const states = { PENDING: '条件待确认', CONFLICT: '资料有分歧', MISSING: '材料未取得', CLAIM: '当事人主张', COUNTERPARTY_PROPOSAL: '对方稿' }
      for (const fact of view.facts) {
        const row = element('div', `${fact.label}：${fact.value}`, card)
        element('span', ` · ${[states[fact.status] || '材料记载', fact.role].filter(Boolean).join(' · ')}`, row, 'awd-semantic-hint')
        sourceDetails(card, view, { factIds: [fact.id], sourceIds: [fact.sourceId] })
      }
    }
    if (view.status !== 'READY') { active = false; return }
    for (const [index, advice] of (view.advice || []).entries()) {
      const card = element('div', null, output, 'awd-semantic-card'); element('strong', '建议文字', card)
      element('div', advice.text, card, 'awd-semantic-preview'); if (advice.explanation) element('div', advice.explanation, card, 'awd-semantic-hint')
      sourceDetails(card, view, advice)
      button('采用这条', card, async () => {
        if (composing) { status('请先完成当前输入，再采用建议。'); return }
        if (seq !== sequence) return
        accepting = true
        try { const applied = await rpc('semantic-accept', { index, composing }); if (seq !== sequence) return; if (!applied.applied) throw new Error('未能采用，请重新生成。'); invalidate(); status(applied.message || '已采用，可用 Ctrl+Z / ⌘Z 撤销。'); focus?.() }
        finally { accepting = false }
      }, 'awd-semantic-primary')
    }
  }
  function updateActions() { saveButton.disabled = saving; for (const button of actionGrid.querySelectorAll('button')) button.disabled = saving }
  async function run(mode) {
    if (saving) { status('项目设置正在保存，请稍后生成。'); return }
    if (composing) { status('请先完成当前输入，再生成建议。'); return }
    invalidate(true); active = true; const seq = sequence; cancelButton.hidden = false; status('正在读取当前位置与参考材料…')
    try { render(await rpc('semantic-generate', { mode, documentType: profile.value, sourceFileIds: [...selected], composing }), seq) }
    catch (e) { if (seq === sequence) { active = false; cancelButton.hidden = true; status(e.message) } }
  }
  const unsubscribe = transport.subscribe(msg => {
    if (disposed) return
    if (msg?.type === 'writing-config') {
      const config = msg.config || {}
      if ('session' in config && config.session !== session) { invalidate(); selected.clear(); files.replaceChildren(); stance.value = ''; cutoff.value = ''; profile.value = 'auto'; sheetOpen = false; session = config.session || ''; for (const item of pending.values()) { win.clearTimeout(item.timeout); item.reject(new Error('文档已切换。')) } pending.clear() }
      if ('writable' in config) writable = !!config.writable
      const wasOpen = sheetOpen
      available = !!session && !!writable
      applyOpen()
      if (wasOpen && !sheetOpen) invalidate(true)
      report()
      if (config.semantic?.state === 'STALE') invalidate()
      return
    }
    // 宿主工具栏的开合指令（dev-board#748）。
    if (msg?.type === 'semantic-writing-panel') { if (msg.open) openPanel(); else closePanel(false); return }
    if (msg?.type !== 'writing-response' || msg.session !== session) return
    const item = pending.get(msg.id); if (!item) return
    pending.delete(msg.id); win.clearTimeout(item.timeout)
    if (msg.error) item.reject(new Error(msg.error)); else item.resolve(msg.result)
  })
  const beginComposition = () => { composing = true; if (active) invalidate(true) }, endComposition = () => { composing = false }
  doc.addEventListener('compositionstart', beginComposition, true); doc.addEventListener('compositionend', endComposition, true)
  sheet.addEventListener('keydown', event => { event.stopPropagation(); if (event.key === 'Escape') closePanel(true) })
  profile.addEventListener('change', () => invalidate(true)); stance.addEventListener('input', () => invalidate(true)); cutoff.addEventListener('change', () => invalidate(true))
  return {
    isComposing() { return composing },
    cursorMoved() { if (!accepting && active) invalidate(true) },
    committed() { if (!accepting && active) invalidate(true) },
    destroy() { if (disposed) return; invalidate(true); disposed = true; unsubscribe?.(); for (const item of pending.values()) { win.clearTimeout(item.timeout); item.reject(new Error('文档已关闭。')) } pending.clear(); doc.removeEventListener('compositionstart', beginComposition, true); doc.removeEventListener('compositionend', endComposition, true); root.remove(); style.remove() },
  }
}

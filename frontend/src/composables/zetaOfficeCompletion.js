// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { extractCompletionEntries, matchCompletionItems } from '../utils/completionLexicon.js'

const LABELS = {
  zh: { title: '写作辅助', close: '关闭', local: '仅本地补全 · Tab 接受 · Esc 关闭', empty: '暂无本地候选，常用内容会随写作积累。', enabled: '自动补全', learning: '学习我输入的常用内容', hints: '相关资料提示', manage: '已学词库', project: '本项目', user: '我的词库', remove: '删除', clear: '清空此范围的已学记录', confirm: '再次点击确认清空', loading: '正在读取…', stale: '光标或正文已变化，请重新选择后操作。', detail: '查看已有资料', insert: '插入以上内容', lookup: '在线查询（可能产生费用）', company: '查询机构工商信息', law: '查询法规与条款', case: '查询案例与案号', noDetail: '暂无可插入的资料。可选中文字后右键查询。', source: '来源', date: '查询时间', error: '操作未完成，请稍后重试。', saved: '已插入，可用撤销恢复。', current: '当前文档', refresh: '刷新本地词库', COMPANY: '机构', PERSON: '人名', LAW: '法规', ARTICLE: '条款', CASE: '案例', WORD: '词语', PHRASE: '表述' },
  en: { title: 'Writing assistance', close: 'Close', local: 'Local suggestions · Tab accept · Esc dismiss', empty: 'No local suggestions yet. Vocabulary grows as you write.', enabled: 'Automatic suggestions', learning: 'Learn from my typing', hints: 'Related information', manage: 'Learned vocabulary', project: 'This project', user: 'My vocabulary', remove: 'Delete', clear: 'Clear learned entries in this scope', confirm: 'Click again to confirm', loading: 'Loading…', stale: 'The cursor or document changed. Select the text again.', detail: 'View saved information', insert: 'Insert the content above', lookup: 'Online lookup (charges may apply)', company: 'Look up company information', law: 'Look up a law or article', case: 'Look up a case', noDetail: 'No insertable information. Select text and right-click to look it up.', source: 'Source', date: 'Retrieved', error: 'The operation failed. Please try again.', saved: 'Inserted. Use Undo to revert.', current: 'Current document', refresh: 'Refresh local vocabulary', COMPANY: 'Company', PERSON: 'Person', LAW: 'Law', ARTICLE: 'Article', CASE: 'Case', WORD: 'Word', PHRASE: 'Phrase' },
}
let instanceSeq = 0

/** Guest-side UI. No network access: all requests travel through the document host. */
export function attachWritingAssistance({ canvas, input, execute, transport, focus, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument
  const view = doc.defaultView
  const t = LABELS[language.startsWith('en') ? 'en' : 'zh']
  let config = { enabled: false, learning: false, hints: true, writable: false, session: '', items: [] }
  let items = [], current = null, choices = [], active = 0, generation = 0, timer = 0, learningTimer = 0
  let ownText = '', mode = '', disposed = false, composing = false, accepting = false, scope = 'project', clearArmed = false
  const pending = new Map()
  const learningWrites = new Set()
  let requestSeq = 0, lastRefreshAt = Date.now()
  const root = doc.createElement('div')
  root.className = 'awd-writing-assistance'
  const style = doc.createElement('style')
  style.textContent = `.awd-writing-assistance{font:13px/1.5 system-ui,sans-serif;color:#26332e;position:fixed;z-index:10000;inset:0;pointer-events:none}.awd-writing-assistance button,.awd-writing-assistance input,.awd-writing-assistance select{font:inherit}.awd-writing-assistance button{cursor:pointer;border:0;background:transparent;color:inherit;text-align:left;padding:7px 10px;border-radius:5px}.awd-writing-assistance button:hover,.awd-writing-assistance button[aria-selected=true]{background:#e5eee8}.awd-writing-assistance .awd-wa-toggle{pointer-events:auto;position:absolute;bottom:12px;right:18px;border:1px solid #ccd6ce;background:#fff;box-shadow:0 2px 8px #0001}.awd-writing-assistance .awd-wa-panel{pointer-events:auto;position:absolute;width:350px;max-width:calc(100vw - 24px);max-height:55vh;overflow:auto;background:#fff;border:1px solid #ccd6ce;border-radius:9px;box-shadow:0 6px 24px #0002;padding:7px}.awd-writing-assistance .awd-wa-heading{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #edf0ed;padding-bottom:3px}.awd-writing-assistance .awd-wa-option{display:block;width:100%;word-break:break-word}.awd-writing-assistance small{display:block;color:#64736a;font-size:11px}.awd-writing-assistance .awd-wa-copy{white-space:pre-wrap;word-break:break-word;padding:8px;max-height:28vh;overflow:auto}.awd-writing-assistance label{display:block;padding:8px}.awd-writing-assistance table{width:100%;border-collapse:collapse;font-size:12px}.awd-writing-assistance td{border:1px solid #dce4de;padding:5px;word-break:break-word}.theme-dark .awd-writing-assistance{color:#e1e8e3}.theme-dark .awd-writing-assistance .awd-wa-panel,.theme-dark .awd-writing-assistance .awd-wa-toggle{background:#202622;border-color:#465148}.theme-dark .awd-writing-assistance button:hover,.theme-dark .awd-writing-assistance button[aria-selected=true]{background:#37463c}.theme-dark .awd-writing-assistance small{color:#b0bcb3}`
  doc.head.appendChild(style)
  doc.body.appendChild(root)
  const button = (text, fn, parent = panel) => {
    const b = doc.createElement('button'); b.type = 'button'; b.textContent = text
    b.addEventListener('mousedown', (e) => e.preventDefault())
    b.addEventListener('click', (e) => {
      const gen = generation
      const failed = (error) => {
        if (!disposed && gen === generation) { show('notice'); note(error.message || t.error) }
      }
      try { Promise.resolve(fn(e)).catch(failed) } catch (error) { failed(error) }
    }); parent.appendChild(b); return b
  }
  const toggle = button(t.title, () => settings(), root)
  toggle.className = 'awd-wa-toggle'; toggle.hidden = true
  const panel = doc.createElement('div'); panel.className = 'awd-wa-panel'; panel.hidden = true; root.appendChild(panel)
  const listId = 'awd-completion-list-' + (++instanceSeq)
  const inputAttributes = Object.fromEntries(['role', 'aria-hidden', 'aria-label', 'aria-autocomplete', 'aria-expanded', 'aria-controls', 'aria-activedescendant'].map(name => [name, input.getAttribute(name)]))
  input.setAttribute('role', 'combobox'); input.removeAttribute('aria-hidden')
  input.setAttribute('aria-label', t.title); input.setAttribute('aria-autocomplete', 'list'); input.setAttribute('aria-expanded', 'false')
  const status = doc.createElement('div'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  Object.assign(status.style, { position: 'absolute', width: '1px', height: '1px', overflow: 'hidden' }); root.appendChild(status)
  function note(text, parent = panel) { const n = doc.createElement('div'); n.textContent = text; n.className = 'awd-wa-copy'; parent.appendChild(n); return n }
  function position(point) {
    const r = input.getBoundingClientRect()
    const x = point ? point.x : r.width < view.innerWidth * 0.8 ? r.left : view.innerWidth - 380
    const y = point ? point.y : r.height < 100 ? r.bottom + 4 : view.innerHeight - 300
    panel.style.left = Math.max(8, Math.min(x, view.innerWidth - panel.offsetWidth - 12)) + 'px'
    panel.style.top = Math.max(8, Math.min(y, view.innerHeight - panel.offsetHeight - 12)) + 'px'
  }
  function show(kind, title = t.title, point) {
    if (disposed) return
    input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); input.removeAttribute('aria-controls')
    mode = kind; panel.replaceChildren(); panel.hidden = false
    panel.setAttribute('role', kind === 'suggest' ? 'presentation' : 'dialog'); panel.setAttribute('aria-label', title)
    const head = doc.createElement('div'); head.className = 'awd-wa-heading'; panel.appendChild(head)
    const label = doc.createElement('strong'); label.textContent = title; head.appendChild(label)
    button('×', () => { invalidate(); focus() }, head).setAttribute('aria-label', t.close)
    position(point)
  }
  function hide() {
    mode = ''; choices = []; panel.hidden = true; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); input.removeAttribute('aria-controls'); status.textContent = ''
  }
  function invalidate({ flush = true } = {}) {
    generation++; clearTimeout(timer); timer = 0; current = null; accepting = false
    hide()
    if (flush) flushLearning()
  }
  function rpc(action, data = {}) {
    if (disposed || !config.session) return Promise.reject(new Error(t.stale))
    const id = ++requestSeq, session = config.session
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error(t.error)) }, action === 'lookup' ? 90000 : 15000)
      pending.set(id, { resolve, reject, timeout, session })
      try { transport.send({ __lo: 'lo-relay', type: 'writing-request', id, session, action, data }) }
      catch (error) { clearTimeout(timeout); pending.delete(id); reject(error) }
    })
  }
  const unsub = transport.subscribe((msg) => {
    if (disposed || !msg || msg.__lo !== 'lo-relay') return
    if (msg.type === 'writing-invalidate') { invalidate({ flush: false }); return }
    if (msg.type === 'writing-config') {
      if (!msg.config || typeof msg.config !== 'object') return
      const changed = typeof msg.config.session === 'string' && msg.config.session !== config.session
      if (changed) { invalidate(); for (const p of pending.values()) { clearTimeout(p.timeout); p.reject(new Error(t.stale)) }; pending.clear(); items = []; ownText = '' }
      const disabled = (config.enabled && msg.config.enabled === false) || (config.writable && msg.config.writable === false)
      config = { ...config, ...msg.config }
      if (Array.isArray(msg.config.items)) items = msg.config.items.map(item => ({ ...item }))
      if (changed || Array.isArray(msg.config.items)) lastRefreshAt = Date.now()
      toggle.hidden = !config.writable
      if (!config.learning || !config.writable) { ownText = ''; clearTimeout(learningTimer); learningTimer = 0 }
      if (disabled || !config.writable) invalidate({ flush: false })
      return
    }
    if (msg.type !== 'writing-response') return
    const p = pending.get(msg.id)
    if (!p || p.session !== msg.session || config.session !== msg.session) return
    pending.delete(msg.id); clearTimeout(p.timeout)
    if (msg.error) p.reject(new Error(msg.error)); else p.resolve(msg.result)
  })
  function eligibleItems() {
    return items.filter((x) => x.source !== 'learned' || !['WORD', 'PHRASE'].includes(x.kind) || x.uses >= 2)
  }
  async function suggest() {
    if (disposed || composing || !config.enabled || !config.writable || accepting) return
    const gen = generation
    let ctx
    try { ctx = await execute('get_completion_context', { radius: 160 }) } catch { return }
    if (gen !== generation || composing || disposed || !config.enabled || !config.writable || !ctx?.success || !ctx.available || !ctx.token) return
    current = ctx
    choices = matchCompletionItems(ctx.before, eligibleItems())
    active = 0
    if (!choices.length) {
      const completed = eligibleItems().find((x) => ['COMPANY', 'LAW', 'ARTICLE', 'CASE'].includes(x.kind) && ctx.before?.endsWith(x.text))
      if (completed && config.hints && (completed.entityId || completed.hasDetail)) showRelated(completed, ctx.token)
      else hide()
      return
    }
    renderChoices()
  }
  function renderChoices() {
    const saved = choices; show('suggest'); choices = saved
    const list = doc.createElement('div'); list.id = listId; list.setAttribute('role', 'listbox'); panel.appendChild(list)
    choices.forEach((item, index) => {
      const b = button(item.text, () => accept(index), list); b.className = 'awd-wa-option'; b.id = listId + '-' + index
      b.setAttribute('role', 'option'); b.setAttribute('aria-selected', String(index === active))
      const sub = doc.createElement('small'); sub.textContent = `${t[item.kind] || item.kind} · ${item.source === 'document' ? t.current : t[item.scope] || item.scope}`; b.appendChild(sub)
    })
    const hint = doc.createElement('small'); hint.textContent = t.local; panel.appendChild(hint)
    input.setAttribute('aria-controls', listId); input.setAttribute('aria-expanded', 'true'); input.setAttribute('aria-activedescendant', listId + '-' + active)
    status.textContent = choices[active]?.text || ''; position()
  }
  async function accept(index) {
    const item = choices[index], ctx = current
    if (!item || !ctx || accepting) return
    const gen = ++generation, session = config.session; accepting = gen; hide()
    try {
      const result = await execute('accept_completion', { token: ctx.token, prefix: item.prefix, text: item.text })
      if (disposed || gen !== generation || session !== config.session || !config.writable) return
      if (!result?.success) { show('notice'); note(t.stale); return }
      flushLearning(); learn([{ text: item.text, kind: item.kind }], 'user'); learn([{ text: item.text, kind: item.kind }], 'project')
      if (config.hints && ['COMPANY', 'LAW', 'ARTICLE', 'CASE'].includes(item.kind)) showRelated(item, result.token)
    } catch { if (!disposed && gen === generation) { show('notice'); note(t.error) } }
    finally { if (accepting === gen) accepting = false; if (!disposed && gen === generation) focus() }
  }
  function showRelated(item, token) {
    show('related', item.text)
    if (item.entityId || item.hasDetail) button(t.detail, () => detailRequest('detail', { entityId: item.entityId, id: item.id }, token))
    else note(t.noDetail)
    position()
  }
  async function detailRequest(action, data, token) {
    clearTimeout(timer); const gen = ++generation
    show('detail'); note(t.loading)
    try {
      const result = await rpc(action, data)
      if (gen !== generation || disposed) return
      if (!result || typeof result !== 'object') throw new Error(t.error)
      if (action === 'lookup' && result.success !== false) rpc('refresh').catch(() => {})
      show('detail', result.title || data.text || t.title)
      if (result.note) note(result.note)
      const variants = result.variants || []
      if (!variants.length) { note(t.noDetail); return }
      const body = doc.createElement('div'); panel.appendChild(body)
      let chosen = 0
      const render = () => {
        body.replaceChildren(); const v = variants[chosen]
        if (v.rows?.length) {
          const table = doc.createElement('table'); body.appendChild(table)
          for (const row of v.rows) { const tr = doc.createElement('tr'); table.appendChild(tr); for (const cell of row) { const td = doc.createElement('td'); td.textContent = cell; tr.appendChild(td) } }
        }
        if (v.text) note(v.text, body)
        note([result.source && `${t.source}: ${result.source}`, result.date && `${t.date}: ${result.date}`].filter(Boolean).join('\n'), body)
        if (v.text || v.rows?.length) button(t.insert, async () => {
          if (accepting || gen !== generation || !config.writable) return
          accepting = gen
          try {
            const r = await execute('insert_completion_content', { token, ...(v.rows?.length ? { rows: v.rows } : {}), ...(v.text ? { text: v.text } : {}) }).catch(() => null)
            if (disposed || gen !== generation) return
            show('notice'); note(r?.success ? t.saved : t.stale); focus()
          } finally { if (accepting === gen) accepting = false }
        }, body)
      }
      if (variants.length > 1) {
        const select = doc.createElement('select'); select.setAttribute('aria-label', result.title || t.title)
        variants.forEach((v, i) => { const opt = doc.createElement('option'); opt.value = i; opt.textContent = v.title || String(i + 1); select.appendChild(opt) })
        select.onchange = () => { chosen = Number(select.value); render() }; panel.insertBefore(select, body)
      }
      render(); position()
    } catch (e) { if (!disposed && gen === generation) { show('notice'); note(e.message || t.error) } }
  }
  function learn(entries, targetScope) {
    if (!config.learning || !config.writable || !entries.length) return
    const write = rpc('learn', { scope: targetScope, entries: entries.slice(0, 50) }).catch(() => {})
    learningWrites.add(write)
    write.then(() => learningWrites.delete(write))
    for (const entry of entries) {
      const old = items.find((x) => x.text === entry.text && x.scope === targetScope)
      if (old) old.uses = (old.uses || 0) + 1
      else items.push({ ...entry, scope: targetScope, source: 'learned', uses: 1 })
    }
    if (items.length > 2500) items = items.slice(-2500)
  }
  function flushLearning() {
    clearTimeout(learningTimer); learningTimer = 0
    const text = ownText; ownText = ''
    if (!text || !config.learning) return
    const entries = extractCompletionEntries(text)
    learn(entries, 'project'); learn(entries, 'user')
  }
  function committed(text) {
    if (disposed) return
    generation++; accepting = false; hide()
    if (config.learning && config.writable) { ownText = (ownText + text).slice(-4000); clearTimeout(learningTimer); learningTimer = setTimeout(flushLearning, 8000) }
    if (/[。！？；\n]/.test(text)) flushLearning()
    clearTimeout(timer); timer = config.enabled && config.writable ? setTimeout(suggest, 160) : 0
  }
  function keydown(e) {
    if (disposed) return false
    if (composing || e.isComposing || e.keyCode === 229) return false
    if (mode === 'suggest' && !e.metaKey && !e.ctrlKey && !e.altKey) {
      if (e.key === 'Tab' && !e.shiftKey) { e.preventDefault(); accept(active); return true }
      if (!e.shiftKey && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); active = (active + (e.key === 'ArrowDown' ? 1 : -1) + choices.length) % choices.length; renderChoices(); return true }
    }
    if (e.key === 'Escape' && mode) { e.preventDefault(); invalidate(); return true }
    if (e.key.length > 1 || e.metaKey || e.ctrlKey || e.altKey) invalidate()
    else { generation++; accepting = false; hide() }
    return false
  }
  async function settings() {
    invalidate(); show('settings')
    for (const name of ['enabled', 'learning', 'hints']) {
      const label = doc.createElement('label'); const check = doc.createElement('input'); check.type = 'checkbox'; check.checked = config[name]
      check.onchange = () => {
        config[name] = check.checked
        if (name === 'learning' && !check.checked) { ownText = ''; clearTimeout(learningTimer); learningTimer = 0 }
        rpc('preferences', { [name]: check.checked }).catch(() => {})
      }
      label.appendChild(check); label.appendChild(doc.createTextNode(' ' + t[name])); panel.appendChild(label)
    }
    button(t.manage, manage); button(t.refresh, async () => { const gen = generation; await rpc('refresh'); if (!disposed && gen === generation) settings() }); position()
  }
  async function manage() {
    invalidate(); const gen = generation
    show('manage', t.manage); note(t.loading)
    // 先落完自己的学习批次再拉带持久化 ID 的词库，否则新词虽能补全却无法删除。
    await Promise.all([...learningWrites])
    if (disposed || gen !== generation) return
    try { await rpc('refresh') }
    catch (error) { if (!disposed && gen === generation) { show('notice'); note(error.message || t.error) }; return }
    if (!disposed && gen === generation) renderManagement()
  }
  function renderManagement() {
    show('manage', t.manage); clearArmed = false
    for (const name of ['project', 'user']) button(t[name], () => { scope = name; invalidate({ flush: false }); renderManagement() })
    note(t[scope])
    const learned = items.filter((x) => x.source === 'learned' && x.scope === scope && String(x.id || '').startsWith('learned:'))
    for (const item of learned.slice(0, 100)) {
      const row = doc.createElement('div'); row.textContent = item.text; panel.appendChild(row)
      button(t.remove, async () => { const gen = generation; await rpc('delete', { id: item.id }); if (!disposed && gen === generation) { items = items.filter((x) => x.id !== item.id); renderManagement() } }, row)
    }
    if (!learned.length) note(t.empty)
    button(t.clear, async (ev) => {
      if (!clearArmed) { clearArmed = true; ev.currentTarget.textContent = t.confirm; return }
      const gen = generation, targetScope = scope
      await rpc('clear', { scope: targetScope }); if (!disposed && gen === generation) { items = items.filter((x) => x.source !== 'learned' || x.scope !== targetScope); renderManagement() }
    })
    position()
  }
  const contextMenu = async (e) => {
    if (disposed || !config.writable || composing) return
    e.preventDefault(); invalidate(); const gen = generation, point = { x: e.clientX, y: e.clientY }
    const ctx = await execute('get_completion_context', { radius: 160 }).catch(() => null)
    if (disposed || gen !== generation || !ctx?.success || !ctx.selectedText || !ctx.token || ctx.selectedText.length > 160) return
    show('context', ctx.selectedText, point)
    const known = items.find((x) => x.text === ctx.selectedText)
    if (known?.entityId || known?.hasDetail) button(t.detail, () => detailRequest('detail', { entityId: known.entityId, id: known.id }, ctx.token))
    note(t.lookup)
    for (const [kind, label] of [['COMPANY', 'company'], ['LAW', 'law'], ['CASE', 'case']]) button(t[label], () => detailRequest('lookup', { kind, text: ctx.selectedText }, ctx.token))
    position(point)
  }
  const startComposition = () => { composing = true; invalidate({ flush: false }) }
  const endComposition = () => { composing = false }
  const moved = () => invalidate()
  const blur = (e) => { if (!root.contains(e.relatedTarget || doc.activeElement)) invalidate() }
  const refreshOnFocus = () => {
    if (disposed || !config.writable || !config.session || Date.now() - lastRefreshAt < 30000) return
    lastRefreshAt = Date.now()
    rpc('refresh').catch(() => {})
  }
  const pointer = (e) => { if (!root.contains(e.target)) invalidate() }
  const panelKeydown = (e) => { if (e.key === 'Escape') { e.preventDefault(); invalidate(); focus() } }
  input.addEventListener('compositionstart', startComposition)
  input.addEventListener('compositionend', endComposition)
  input.addEventListener('blur', blur)
  input.addEventListener('focus', refreshOnFocus)
  canvas.addEventListener('mousedown', moved)
  canvas.addEventListener('wheel', moved, { passive: true })
  canvas.addEventListener('contextmenu', contextMenu)
  input.addEventListener('contextmenu', contextMenu)
  doc.addEventListener('mousedown', pointer, true)
  root.addEventListener('keydown', panelKeydown)
  return { committed, keydown, invalidate,
    destroy() {
      if (disposed) return
      flushLearning(); disposed = true; clearTimeout(timer); clearTimeout(learningTimer); unsub()
      for (const p of pending.values()) { clearTimeout(p.timeout); p.reject(new Error(t.stale)) }; pending.clear()
      input.removeEventListener('compositionstart', startComposition); input.removeEventListener('compositionend', endComposition); input.removeEventListener('blur', blur)
      input.removeEventListener('focus', refreshOnFocus)
      canvas.removeEventListener('mousedown', moved); canvas.removeEventListener('wheel', moved); canvas.removeEventListener('contextmenu', contextMenu); input.removeEventListener('contextmenu', contextMenu); doc.removeEventListener('mousedown', pointer, true)
      root.remove(); style.remove()
      for (const [name, value] of Object.entries(inputAttributes)) { if (value == null) input.removeAttribute(name); else input.setAttribute(name, value) }
    },
  }
}

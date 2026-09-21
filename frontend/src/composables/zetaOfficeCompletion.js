// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { extractCompletionEntries, matchCompletionItems } from '../utils/completionLexicon.js'
import { WRITING_ASSISTANCE_CSS, renderCompletionOption } from './writingAssistancePresentation.js'

const LABELS = {
  zh: { suggestions: '补全建议', manual: '显示补全建议 · Alt+/', noMatch: '没有匹配的本地词条，请继续输入或查看已学词库。', acceptKey: '补全', chooseKey: '选择', dismissKey: '关闭', title: '自动补全', close: '关闭', local: '仅本地补全 · Tab 接受 · Esc 关闭', empty: '暂无本地候选，常用内容会随写作积累。', enabled: '自动弹出候选', learning: '学习我输入的常用内容', hints: '相关资料提示', manage: '已学词库', project: '本项目', user: '我的词库', remove: '删除', clear: '清空此范围的已学记录', confirm: '再次点击确认清空', loading: '正在读取…', stale: '光标或正文已变化，请重新选择后操作。', detail: '查看已有资料', insert: '插入以上内容', lookup: '在线查询（可能产生费用）', company: '查询机构工商信息', law: '查询法规与条款', case: '查询案例与案号', noDetail: '暂无可插入的资料。可选中文字后右键查询。', source: '来源', date: '查询时间', error: '操作未完成，请稍后重试。', configure: '去设置配置', recharge: '去充值', saved: '已插入，可用撤销恢复。', current: '当前文档', refresh: '刷新本地词库', contextHint: '选中文字后右键可查询机构 / 法规 / 案例，点了才联网、才可能扣费。', COMPANY: '机构', PERSON: '人名', LAW: '法规', ARTICLE: '条款', CASE: '案例', WORD: '词语', PHRASE: '表述' },
  en: { suggestions: 'Suggestions', manual: 'Show suggestions · Alt+/', noMatch: 'No matching local entries. Keep typing or review learned vocabulary.', acceptKey: 'Accept', chooseKey: 'Select', dismissKey: 'Dismiss', title: 'Autocomplete', close: 'Close', local: 'Local suggestions · Tab accept · Esc dismiss', empty: 'No local suggestions yet. Vocabulary grows as you write.', enabled: 'Suggest as I type', learning: 'Learn from my typing', hints: 'Related information', manage: 'Learned vocabulary', project: 'This project', user: 'My vocabulary', remove: 'Delete', clear: 'Clear learned entries in this scope', confirm: 'Click again to confirm', loading: 'Loading…', stale: 'The cursor or document changed. Select the text again.', detail: 'View saved information', insert: 'Insert the content above', lookup: 'Online lookup (charges may apply)', company: 'Look up company information', law: 'Look up a law or article', case: 'Look up a case', noDetail: 'No insertable information. Select text and right-click to look it up.', source: 'Source', date: 'Retrieved', error: 'The operation failed. Please try again.', configure: 'Open settings', recharge: 'Add credits', saved: 'Inserted. Use Undo to revert.', current: 'Current document', refresh: 'Refresh local vocabulary', contextHint: 'Select text and right-click to look up a company, law or case. Nothing goes online — and nothing can be charged — until you click.', COMPANY: 'Company', PERSON: 'Person', LAW: 'Law', ARTICLE: 'Article', CASE: 'Case', WORD: 'Word', PHRASE: 'Phrase' },
}
let instanceSeq = 0
// Longest selection the right-click lookup menu accepts.
const CONTEXT_MENU_MAX = 160
const SUGGEST_DELAY = 80
const REFILTER_DELAY = 35

/** Guest-side UI. No network access: all requests travel through the document host. */
export function attachWritingAssistance({ canvas, input, execute, transport, focus, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument
  const view = doc.defaultView
  const t = LABELS[language.startsWith('en') ? 'en' : 'zh']
  let config = { enabled: false, learning: false, hints: true, writable: false, session: '', items: [] }
  let items = [], current = null, choices = [], active = 0, generation = 0, timer = 0, learningTimer = 0
  let refilter = false, preferredText = '', panelPoint
  let ownText = '', mode = '', disposed = false, composing = false, accepting = false, scope = 'project', clearArmed = false
  const pending = new Map()
  const learningWrites = new Set()
  let requestSeq = 0, lastRefreshAt = Date.now(), contextMenuWritable = false
  const root = doc.createElement('div')
  root.className = 'awd-writing-assistance'
  root.hidden = true
  const style = doc.createElement('style')
  style.textContent = WRITING_ASSISTANCE_CSS
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
  const panel = doc.createElement('div'); panel.className = 'awd-wa-panel'; panel.hidden = true; root.appendChild(panel)
  const listId = 'awd-completion-list-' + (++instanceSeq)
  const inputAttributes = Object.fromEntries(['role', 'aria-hidden', 'aria-label', 'aria-autocomplete', 'aria-expanded', 'aria-controls', 'aria-activedescendant'].map(name => [name, input.getAttribute(name)]))
  input.setAttribute('role', 'combobox'); input.removeAttribute('aria-hidden')
  input.setAttribute('aria-label', t.title); input.setAttribute('aria-autocomplete', 'list'); input.setAttribute('aria-expanded', 'false')
  const status = doc.createElement('div'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  Object.assign(status.style, { position: 'absolute', width: '1px', height: '1px', overflow: 'hidden' }); root.appendChild(status)
  // 设置面板的唯一入口在宿主的自建工具栏（dev-board#755）：画布右下角那颗常驻
  // 按钮撤掉，客体只按指令开合，并把「这份文档有没有这项能力」与「面板此刻开着
  // 没有」回报给宿主同步显隐与按下态。关着时根节点整个 display:none——画布上不留
  // 一个像素，也截不到点击。打字时的候选列表与右键查询卡片不归这条回报管：它们
  // 跟着光标自己弹，本来就不是这颗按钮开出来的。
  const SETTINGS_MODES = ['settings', 'manage']
  let available = false, panelOpen = false
  function report() {
    // data-available 是这份客体「写作会话已就绪」的唯一 DOM 标记（原先靠画布右下角
    // 那颗按钮的 hidden 判断，桌面走查用它认哪个 webview 是当前文档）。
    root.dataset.available = available ? 'true' : 'false'
    try { transport.send({ __lo: 'lo-relay', type: 'writing-assistance-state', available, open: panelOpen }) }
    catch (error) { /* 通道没起来：客体就绪后配置消息会再报一次 */ }
  }
  function setPanelOpen(next) { if (panelOpen === next) return; panelOpen = next; report() }
  function note(text, parent = panel) { const n = doc.createElement('div'); n.textContent = text; n.className = 'awd-wa-copy'; parent.appendChild(n); return n }
  function position(point) {
    const r = input.getBoundingClientRect(), margin = 12, gap = 4
    const anchored = !point && r.height < 100 && r.width < view.innerWidth * 0.8
    const x = point ? point.x : anchored ? r.left : view.innerWidth - 400
    panel.style.maxHeight = Math.max(0, view.innerHeight - margin * 2) + 'px'
    let y = point ? point.y : anchored ? r.bottom + gap : view.innerHeight - 300
    if (anchored) {
      const below = Math.max(0, view.innerHeight - margin - r.bottom - gap)
      const above = Math.max(0, r.top - margin - gap)
      const upwards = panel.offsetHeight > below && above > below
      panel.style.maxHeight = Math.min(view.innerHeight * 0.55, upwards ? above : below) + 'px'
      y = upwards ? r.top - panel.offsetHeight - gap : r.bottom + gap
    }
    panel.style.left = Math.max(8, Math.min(x, view.innerWidth - panel.offsetWidth - margin)) + 'px'
    panel.style.top = Math.max(8, Math.min(y, view.innerHeight - panel.offsetHeight - margin)) + 'px'
  }
  function show(kind, title = t.title, point) {
    if (disposed) return
    root.hidden = false
    input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); input.removeAttribute('aria-controls')
    mode = kind; panelPoint = point; panel.dataset.mode = kind; setPanelOpen(SETTINGS_MODES.includes(kind)); panel.replaceChildren(); panel.hidden = false
    panel.setAttribute('role', kind === 'suggest' ? 'presentation' : 'dialog'); panel.setAttribute('aria-label', title)
    const head = doc.createElement('div'); head.className = 'awd-wa-heading'; panel.appendChild(head)
    const label = doc.createElement('strong'); label.textContent = title; head.appendChild(label)
    button('×', () => { invalidate(); focus() }, head).setAttribute('aria-label', t.close)
    position(point)
  }
  function hide() {
    mode = ''; choices = []; panel.hidden = true; root.hidden = true; panel.replaceChildren(); input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); input.removeAttribute('aria-controls'); status.textContent = ''
    setPanelOpen(false)
  }
  function invalidate({ flush = true } = {}) {
    generation++; clearTimeout(timer); timer = 0; current = null; accepting = false; refilter = false; preferredText = ''
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
      const nextAvailable = !!config.session && !!config.writable
      if (nextAvailable !== available) { available = nextAvailable; report() }
      if (!config.learning || !config.writable) { ownText = ''; clearTimeout(learningTimer); learningTimer = 0 }
      if (disabled || !config.writable) invalidate({ flush: false })
      if (contextMenuWritable !== !!config.writable) {
        contextMenuWritable = !!config.writable
        execute('set_host_context_menu', { enabled: contextMenuWritable, maxLength: CONTEXT_MENU_MAX }).catch(() => {})
      }
      return
    }
    // 宿主工具栏的开合指令（dev-board#755）。按下态由上面的 report 回报，宿主不本地乐观翻。
    if (msg.type === 'writing-assistance-panel') {
      if (msg.open) { if (available && !panelOpen) settings() }
      else if (panelOpen) invalidate()
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
  async function suggest({ manual = false } = {}) {
    if (disposed || composing || (!config.enabled && !manual) || !config.writable || accepting) return
    const gen = generation
    let ctx
    try { ctx = await execute('get_completion_context', { radius: 160 }) } catch { return }
    if (gen !== generation || composing || disposed || (!config.enabled && !manual) || !config.writable || !ctx?.success || !ctx.available || !ctx.token) return
    current = ctx
    choices = matchCompletionItems(ctx.before, eligibleItems(), { manual })
    active = Math.max(0, choices.findIndex(item => (item.displayText || item.text) === preferredText))
    if (!choices.length) {
      const completed = eligibleItems().find((x) => ['COMPANY', 'LAW', 'ARTICLE', 'CASE'].includes(x.kind) && ctx.before?.endsWith(x.text))
      if (completed && config.hints && (completed.entityId || completed.hasDetail)) showRelated(completed, ctx.token)
      else if (manual) { show('notice', t.suggestions); note(t.noMatch); position() }
      else hide()
      return
    }
    renderChoices()
  }
  function syncActiveChoice({ scroll = true } = {}) {
    const rows = panel.querySelectorAll('[role="option"]')
    rows.forEach((row, index) => row.setAttribute('aria-selected', String(index === active)))
    if (scroll) rows[active]?.scrollIntoView?.({ block: 'nearest' })
    input.setAttribute('aria-activedescendant', listId + '-' + active)
    const count = panel.querySelector('.awd-wa-count')
    if (count) count.textContent = `${active + 1} / ${choices.length}`
    status.textContent = choices[active]?.text || ''
  }
  function renderChoices() {
    const saved = choices; show('suggest', t.suggestions); choices = saved
    const list = doc.createElement('div'); list.id = listId; list.className = 'awd-wa-list'; list.setAttribute('role', 'listbox'); list.setAttribute('aria-label', t.suggestions); panel.appendChild(list)
    choices.forEach((item, index) => {
      const b = button('', () => accept(index), list); b.className = 'awd-wa-option'; b.id = listId + '-' + index
      b.setAttribute('role', 'option')
      renderCompletionOption(doc, b, item, { kindLabel: t[item.kind] || item.kind, sourceLabel: item.source === 'document' ? t.current : t[item.scope] || item.scope })
    })
    const footer = doc.createElement('div'); footer.className = 'awd-wa-footer'; panel.appendChild(footer)
    const keys = doc.createElement('span'); keys.className = 'awd-wa-keys'; footer.appendChild(keys)
    for (const [key, label] of [['↑↓', t.chooseKey], ['Tab', t.acceptKey], ['Esc', t.dismissKey]]) {
      const group = doc.createElement('span'), badge = doc.createElement('kbd'); badge.textContent = key
      group.append(badge, doc.createTextNode(' ' + label)); keys.appendChild(group)
    }
    const count = doc.createElement('span'); count.className = 'awd-wa-count'; footer.appendChild(count)
    input.setAttribute('aria-controls', listId); input.setAttribute('aria-expanded', 'true')
    position(); syncActiveChoice()
  }
  function requestSuggestions() {
    if (disposed || composing || !config.writable) return
    invalidate({ flush: false }); focus(); suggest({ manual: true })
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
      if (config.hints && (item.entityId || item.hasDetail) && ['COMPANY', 'LAW', 'ARTICLE', 'CASE'].includes(item.kind)) showRelated(item, result.token)
    } catch { if (!disposed && gen === generation) { show('notice'); note(t.error) } }
    finally { if (accepting === gen) accepting = false; if (!disposed && gen === generation) focus() }
  }
  function showRelated(item, token) {
    show('related', item.text)
    if (item.entityId || item.hasDetail) button(t.detail, () => detailRequest('detail', { entityId: item.entityId, id: item.id }, token))
    else note(t.noDetail)
    position()
  }
  /**
   * Configuration-class lookup failures (dev-board#458, dev-board#688 D3). The next step is
   * decided by the structured reason code only — never by matching the bilingual note. A
   * server-side credential (NO_CREDENTIAL) has no route the user can take, so it gets no
   * button: pointing at a page that does not exist is worse than pointing at nothing.
   */
  function hintAction(hint) {
    if (hint === 'NOT_CONNECTED' || hint === 'UNAUTHORIZED') button(t.configure, () => rpc('settings', { nav: 'account' }))
    else if (hint === 'NO_CREDITS') button(t.recharge, () => rpc('settings', { nav: 'account' }))
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
      hintAction(result.hint)
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
    const delay = refilter || mode === 'suggest' ? REFILTER_DELAY : SUGGEST_DELAY
    generation++; accepting = false; hide(); refilter = false
    if (config.learning && config.writable) { ownText = (ownText + text).slice(-4000); clearTimeout(learningTimer); learningTimer = setTimeout(flushLearning, 8000) }
    if (/[。！？；\n]/.test(text)) flushLearning()
    clearTimeout(timer); timer = config.enabled && config.writable ? setTimeout(suggest, delay) : 0
  }
  function keydown(e) {
    if (disposed) return false
    if (composing || e.isComposing || e.keyCode === 229) return false
    const manualKey = !e.metaKey && !e.shiftKey && ((e.ctrlKey && !e.altKey && (e.key === ' ' || e.code === 'Space')) || (e.altKey && !e.ctrlKey && (e.key === '/' || e.code === 'Slash')))
    if (manualKey && config.writable) { e.preventDefault(); if (!e.repeat) requestSuggestions(); return true }
    if (mode === 'suggest' && !e.metaKey && !e.ctrlKey && !e.altKey) {
      if (e.key === 'Tab' && !e.shiftKey) { e.preventDefault(); accept(active); return true }
      if (!e.shiftKey && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); active = (active + (e.key === 'ArrowDown' ? 1 : -1) + choices.length) % choices.length; preferredText = choices[active].displayText || choices[active].text; syncActiveChoice(); return true }
      if (!e.shiftKey && (e.key === 'PageDown' || e.key === 'PageUp')) { e.preventDefault(); active = Math.max(0, Math.min(choices.length - 1, active + (e.key === 'PageDown' ? 5 : -5))); preferredText = choices[active].displayText || choices[active].text; syncActiveChoice(); return true }
    }
    if (e.key === 'Escape' && mode) { e.preventDefault(); invalidate(); return true }
    if (e.key.length > 1 || e.metaKey || e.ctrlKey || e.altKey) invalidate()
    else { refilter = mode === 'suggest'; generation++; accepting = false; hide() }
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
    button(t.manual, requestSuggestions); button(t.manage, manage); button(t.refresh, async () => { const gen = generation; await rpc('refreshDocument'); if (!disposed && gen === generation) settings() })
    // 右键查询不受上面「自动弹出候选」开关影响，也不在这里给开关——它默认开着，
    // 而且只有点下去才联网。面板里说一句，免得用户以为关了开关就没有外查这回事。
    const contextHint = doc.createElement('small'); contextHint.className = 'awd-wa-copy'; contextHint.textContent = t.contextHint; panel.appendChild(contextHint)
    position()
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
    // The worker suppressed Writer's own popup for exactly the selections it
    // reports here (#601), so this menu never has to close a native one.
    const ctx = await execute('get_context_menu_context', { radius: 160 }).catch(() => null)
    if (disposed || gen !== generation || !ctx?.success || !ctx.selectedText || !ctx.token) return
    // This HTML menu now owns keyboard dismissal; returning focus alone keeps
    // Escape usable without reporting a document-caret movement on mouseup.
    focus()
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
  const cursorMoved = (event) => {
    // A commit reports its caret before committed(); keep its fast-refilter
    // intent and active choice until that callback supplies the new text.
    if (event?.reason === 'commit') return
    invalidate({ flush: false })
    if (!disposed && !composing && config.enabled && config.writable && doc.activeElement === input) timer = setTimeout(suggest, SUGGEST_DELAY)
  }
  // 设置面板不吃失焦：它的入口在宿主工具栏上（dev-board#755），点那颗按钮的同时
  // 客体这边的输入框就失焦了，跨进程的失焦通知与开合指令谁先到没有保证——照旧
  // invalidate 的话，面板会「闪一下就没了」。候选列表与右键卡片仍然吃失焦：它们
  // 钉着光标，焦点走了就不该再留在画布上。
  const blur = (e) => { if (panelOpen) return; if (!root.contains(e.relatedTarget || doc.activeElement)) invalidate() }
  const refreshOnFocus = () => {
    if (disposed || !config.writable || !config.session || Date.now() - lastRefreshAt < 30000) return
    lastRefreshAt = Date.now()
    rpc('refresh').catch(() => {})
  }
  const pointer = (e) => { if (!root.contains(e.target)) invalidate() }
  const panelKeydown = (e) => { if (e.key === 'Escape') { e.preventDefault(); invalidate(); focus() } }
  const resized = () => {
    if (panel.hidden) return
    position(panelPoint)
    if (mode === 'suggest') syncActiveChoice()
  }
  view.addEventListener('resize', resized)
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
  report()
  return { committed, keydown, invalidate, cursorMoved,
    destroy() {
      if (disposed) return
      flushLearning(); disposed = true; clearTimeout(timer); clearTimeout(learningTimer); unsub()
      for (const p of pending.values()) { clearTimeout(p.timeout); p.reject(new Error(t.stale)) }; pending.clear()
      input.removeEventListener('compositionstart', startComposition); input.removeEventListener('compositionend', endComposition); input.removeEventListener('blur', blur)
      input.removeEventListener('focus', refreshOnFocus)
      canvas.removeEventListener('mousedown', moved); canvas.removeEventListener('wheel', moved); canvas.removeEventListener('contextmenu', contextMenu); input.removeEventListener('contextmenu', contextMenu); doc.removeEventListener('mousedown', pointer, true)
      view.removeEventListener('resize', resized)
      root.remove(); style.remove()
      for (const [name, value] of Object.entries(inputAttributes)) { if (value == null) input.removeAttribute(name); else input.setAttribute(name, value) }
    },
  }
}

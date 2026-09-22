// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Word 面补齐的三条命令（dev-board#806，审计 B-16）：
 *   node --test office-addin/taskpane/lib/officeWordFaceGaps.test.js
 *
 * delete_comment / insert_toc / set_page_setup 此前只有 LOWA 侧有，
 * 而 Excel 面自己就有 excel_delete_comment——同一个产品里 Word 能标已解决却不能删，
 * 「把这些批注处理掉」这个审阅收尾的标准动作在插件会话里做不完。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const HostType = { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' }

function setGlobals(g) {
  const saved = {}
  for (const k of ['Office', 'Word']) {
    saved[k] = globalThis[k]
    if (k in g) globalThis[k] = g[k]
    else delete globalThis[k]
  }
  return () => {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete globalThis[k]
      else globalThis[k] = saved[k]
    }
  }
}

/**
 * @param {object} opts
 * @param {string[]} [opts.comments] 批注 id 列表
 * @param {Set<string>} [opts.sets] 声称支持的需求集，形如 'WordApi:1.4' / 'WordApiDesktop:1.3'
 */
function installWord(opts = {}) {
  const supported = opts.sets || new Set(['WordApi:1.4', 'WordApi:1.5', 'WordApiDesktop:1.3'])
  const state = {
    deleted: [],
    inserted: [],
    fields: [],
    pageSetupWrites: [],
    trackingModes: []
  }
  const comments = (opts.comments || []).map((id) => ({
    id,
    load() {},
    delete() { state.deleted.push(id) }
  }))

  const makeRange = (label) => ({
    insertParagraph(text, location) {
      state.inserted.push({ text, location, at: label })
      return makeRange('paragraph:' + text)
    },
    getRange(location) { return makeRange(label + '>' + location) },
    insertField(location, fieldType, text, removeFormatting) {
      const field = {
        location, fieldType, text, removeFormatting, at: label, updated: false,
        updateResult() { field.updated = true }
      }
      state.fields.push(field)
      return field
    }
  })

  const pageSetup = {
    topMargin: 72, bottomMargin: 72, leftMargin: 90, rightMargin: 90,
    orientation: 'Portrait', paperSize: 'Letter',
    load() {}
  }
  const pageSetupProxy = new Proxy(pageSetup, {
    set(t, prop, v) { state.pageSetupWrites.push([prop, v]); t[prop] = v; return true }
  })

  const document = {
    load() {},
    _mode: 'Off',
    get changeTrackingMode() { return this._mode },
    set changeTrackingMode(v) { this._mode = v; state.trackingModes.push(v) },
    body: {
      getComments: () => ({ items: comments, load() {} }),
      getRange: (location) => makeRange('body:' + location)
    },
    getSelection: () => makeRange('selection'),
    pageSetup: pageSetupProxy
  }

  const restore = setGlobals({
    Office: {
      HostType,
      context: {
        host: 'Word',
        requirements: { isSetSupported: (set, v) => supported.has(set + ':' + v) }
      }
    },
    Word: {
      run: async (cb) => cb({ document, sync: async () => {} }),
      InsertLocation: { before: 'Before', after: 'After', replace: 'Replace', start: 'Start' },
      RangeLocation: { start: 'Start', after: 'After' },
      FieldType: { toc: 'TOC' },
      ChangeTrackingMode: { trackAll: 'TrackAll' }
    }
  })
  return { state, pageSetup, restore }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')

/* ==================== delete_comment ==================== */

test('delete_comment：按 index 删中那一条，并提醒序号会重排', async () => {
  const w = installWord({ comments: ['c1', 'c2', 'c3'] })
  try {
    const r = await executeOfficeCommand('delete_comment', { commentIndex: 1 })
    assert.equal(r.ok, true)
    assert.deepEqual(w.state.deleted, ['c2'])
    assert.match(r.data.note, /重排/)
  } finally { w.restore() }
})

test('delete_comment：按 id 定位优先于 index', async () => {
  const w = installWord({ comments: ['c1', 'c2', 'c3'] })
  try {
    await executeOfficeCommand('delete_comment', { commentId: 'c3', commentIndex: 0 })
    assert.deepEqual(w.state.deleted, ['c3'])
  } finally { w.restore() }
})

test('delete_comment：定位不到就报错，一条都不删', async () => {
  const w = installWord({ comments: ['c1'] })
  try {
    const r = await executeOfficeCommand('delete_comment', { commentId: 'nope' })
    assert.equal(r.ok, false)
    assert.match(r.error, /未找到指定批注/)
    assert.deepEqual(w.state.deleted, [])
  } finally { w.restore() }
})

test('delete_comment：缺定位参数时拒绝——不许「没说删哪条」就删第一条', async () => {
  const w = installWord({ comments: ['c1', 'c2'] })
  try {
    const r = await executeOfficeCommand('delete_comment', {})
    assert.equal(r.ok, false)
    assert.deepEqual(w.state.deleted, [])
  } finally { w.restore() }
})

test('delete_comment：宿主没有 WordApi 1.4 时明确报错而不是静默什么都不做', async () => {
  const w = installWord({ comments: ['c1'], sets: new Set() })
  try {
    const r = await executeOfficeCommand('delete_comment', { commentIndex: 0 })
    assert.equal(r.ok, false)
    assert.match(r.error, /WordApi 1\.4/)
    assert.deepEqual(w.state.deleted, [])
  } finally { w.restore() }
})

/* ==================== insert_toc ==================== */

test('insert_toc：插的是 TOC 域而不是静态文字，开关按 levels 生成', async () => {
  const w = installWord()
  try {
    const r = await executeOfficeCommand('insert_toc', { levels: 2, position: 'start' })
    assert.equal(r.ok, true)
    assert.equal(w.state.fields.length, 1)
    assert.equal(w.state.fields[0].fieldType, 'TOC')
    assert.equal(w.state.fields[0].text, '\\o "1-2" \\h \\z \\u')
    assert.equal(r.data.levels, 2)
  } finally { w.restore() }
})

test('insert_toc：默认收三级、带「目录」标题段，且插入后更新一次域', async () => {
  const w = installWord()
  try {
    const r = await executeOfficeCommand('insert_toc', {})
    assert.equal(w.state.fields[0].text, '\\o "1-3" \\h \\z \\u')
    assert.deepEqual(w.state.inserted.map((i) => i.text), ['目录'])
    assert.equal(w.state.fields[0].updated, true, '刚插进去的域结果是空的，必须更新一次')
    assert.equal(r.data.updated, true)
    assert.equal(r.data.note, null)
  } finally { w.restore() }
})

test('insert_toc：title 传空串就不插标题段（合法意图，不是「没给」）', async () => {
  const w = installWord()
  try {
    await executeOfficeCommand('insert_toc', { title: '' })
    assert.deepEqual(w.state.inserted, [])
    assert.equal(w.state.fields.length, 1)
  } finally { w.restore() }
})

test('insert_toc：levels 越界被夹住，不把非法开关写进域', async () => {
  const w = installWord()
  try {
    await executeOfficeCommand('insert_toc', { levels: 99 })
    assert.equal(w.state.fields[0].text, '\\o "1-9" \\h \\z \\u')
  } finally { w.restore() }
})

test('insert_toc：插入走修订（是内容插入，Word 记得下痕迹）', async () => {
  const w = installWord()
  try {
    const r = await executeOfficeCommand('insert_toc', {})
    assert.equal(r.data.tracked, true)
    assert.ok(w.state.trackingModes.includes('TrackAll'))
  } finally { w.restore() }
})

test('insert_toc：宿主没有 WordApi 1.5 时明确报错', async () => {
  const w = installWord({ sets: new Set(['WordApi:1.4']) })
  try {
    const r = await executeOfficeCommand('insert_toc', {})
    assert.equal(r.ok, false)
    assert.match(r.error, /WordApi 1\.5/)
    assert.equal(w.state.fields.length, 0)
  } finally { w.restore() }
})

/* ==================== set_page_setup ==================== */

test('set_page_setup：只改给了的那几项，回读宿主真正生效的值', async () => {
  const w = installWord()
  try {
    const r = await executeOfficeCommand('set_page_setup', { marginTopPt: 56.7, orientation: 'landscape' })
    assert.equal(r.ok, true)
    assert.deepEqual(w.state.pageSetupWrites, [['topMargin', 56.7], ['orientation', 'Landscape']])
    assert.equal(r.data.marginTopPt, 56.7)
    assert.equal(r.data.marginLeftPt, 90, '没给的项必须原样不动')
    assert.deepEqual(r.data.applied, ['topMargin', 'orientation'])
  } finally { w.restore() }
})

test('set_page_setup：一个参数都不给时报错，而不是「什么都没改」还报成功', async () => {
  const w = installWord()
  try {
    const r = await executeOfficeCommand('set_page_setup', {})
    assert.equal(r.ok, false)
    assert.deepEqual(w.state.pageSetupWrites, [])
  } finally { w.restore() }
})

test('set_page_setup：非法枚举/负数在进宿主之前就拦下', async () => {
  const w = installWord()
  try {
    const bad = await executeOfficeCommand('set_page_setup', { orientation: '横版' })
    assert.equal(bad.ok, false)
    assert.match(bad.error, /orientation/)
    const negative = await executeOfficeCommand('set_page_setup', { marginLeftPt: -10 })
    assert.equal(negative.ok, false)
    assert.deepEqual(w.state.pageSetupWrites, [])
  } finally { w.restore() }
})

test('set_page_setup：Word 网页版（无 WordApiDesktop 1.3）明确报错，不静默不生效', async () => {
  const w = installWord({ sets: new Set(['WordApi:1.4', 'WordApi:1.5']) })
  try {
    const r = await executeOfficeCommand('set_page_setup', { marginTopPt: 72 })
    assert.equal(r.ok, false)
    assert.match(r.error, /WordApiDesktop 1\.3/)
    assert.deepEqual(w.state.pageSetupWrites, [])
  } finally { w.restore() }
})

test('set_page_setup：纸张与页边距同时给时，先落纸张——换纸张会把页边距按新纸张重算', async () => {
  const w = installWord()
  try {
    await executeOfficeCommand('set_page_setup', { marginTopPt: 50, paperSize: 'a4' })
    assert.deepEqual(w.state.pageSetupWrites.map(([p]) => p), ['paperSize', 'topMargin'])
  } finally { w.restore() }
})

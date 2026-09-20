// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * WPS 面 read_for_reference（dev-board#717）：别的窗格按 locator 读本文档。
 *   node --test office-addin/taskpane/lib/referenceReadWps.test.js
 *
 * 分发是这里最容易静默出错的地方：三张宿主 HANDLERS 在 wpsExecutor 里是展开合并的，
 * 三个文件都有 read_for_reference 时后展开的会盖掉前面的——不按当前宿主分发，
 * 文字宿主就会去跑演示面的实现，报「当前没有打开的演示文稿」。
 *
 * **WPS 按页读取（GoTo/ComputeStatistics）未经真机验证**，这里只能钉住调用口径与
 * 失败时的报错形态。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { executeWpsCommand } from './wpsExecutor.js'

function restoreWps(original) {
  if (original === undefined) delete globalThis.wps
  else globalThis.wps = original
}

// ==================== WPS 文字 ====================

/**
 * paragraphs: [[文字, OutlineLevel], ...]；全文 = 各段 + '\r'。
 * pageStarts: 每页起点的文档位置（1 页起），缺省按段落起点。
 */
function installWpsWord({ paragraphs = [], pageStarts = null, goToThrows = false } = {}) {
  const original = globalThis.wps
  const starts = []
  let full = ''
  for (const [t] of paragraphs) {
    starts.push(full.length)
    full += t + '\r'
  }
  const pages = pageStarts || [0]
  const stats = { paragraphTextReads: 0, goTo: [] }
  const doc = {
    Range(s, e) {
      if (s === undefined) {
        return {
          Text: full,
          Start: 0,
          End: full.length,
          Information(k) { if (k === 4) return pages.length; throw new Error('mock：未知 Information ' + k) }
        }
      }
      return { Text: full.slice(s, e), Start: s, End: e }
    },
    ComputeStatistics(k) {
      if (k !== 2) throw new Error('mock：只支持 wdStatisticPages')
      return pages.length
    },
    GoTo(what, which, n) {
      stats.goTo.push([what, which, n])
      if (goToThrows) throw new Error('COM E_FAIL')
      return { Start: pages[Math.min(n, pages.length) - 1] }
    },
    Paragraphs: {
      Count: paragraphs.length,
      Item(i) {
        const [t, level] = paragraphs[i - 1]
        const start = starts[i - 1]
        return {
          OutlineLevel: level,
          Range: {
            get Text() { stats.paragraphTextReads++; return t + '\r' },
            Start: start,
            End: start + t.length + 1
          }
        }
      }
    }
  }
  const app = { Documents: {}, ActiveDocument: doc, Selection: null }
  globalThis.wps = {
    Application: app,
    WpsApplication: () => app,
    EtApplication() { throw new Error('非表格宿主') },
    WppApplication() { throw new Error('非演示宿主') }
  }
  return { stats, full, restore: () => restoreWps(original) }
}

test('WPS 文字：不带定位读全文（走文字面实现，不被演示面实现盖掉）', async () => {
  const env = installWpsWord({ paragraphs: [['甲方', 10], ['乙方', 10]] })
  try {
    const r = await executeWpsCommand('read_for_reference', {})
    assert.equal(r.ok, true, r.error)
    assert.equal(r.data.text, '甲方\r乙方\r')
  } finally { env.restore() }
})

test('WPS 文字：page:N 取第 N 页起点到第 N+1 页起点；末页取到文末', async () => {
  const env = installWpsWord({
    paragraphs: [['第一页', 10], ['第二页', 10], ['第三页', 10]],
    pageStarts: [0, 4, 8]
  })
  try {
    const mid = await executeWpsCommand('read_for_reference', { locator: 'page:2' })
    assert.equal(mid.ok, true, mid.error)
    assert.equal(mid.data.text, '第二页\r')
    // wdGoToPage=1、wdGoToAbsolute=1
    assert.deepEqual(env.stats.goTo.slice(0, 2), [[1, 1, 2], [1, 1, 3]])
    const last = await executeWpsCommand('read_for_reference', { locator: 'page:3' })
    assert.equal(last.ok, true, last.error)
    assert.equal(last.data.text, '第三页\r')
    const over = await executeWpsCommand('read_for_reference', { locator: 'page:4' })
    assert.equal(over.ok, false)
    assert.match(over.error, /文档只有 3 页/)
  } finally { env.restore() }
})

test('WPS 文字：按页读取的宿主调用失败时报「WPS 按页读取失败」', async () => {
  const env = installWpsWord({ paragraphs: [['a', 10], ['b', 10]], pageStarts: [0, 2], goToThrows: true })
  try {
    const r = await executeWpsCommand('read_for_reference', { locator: 'page:1' })
    assert.equal(r.ok, false)
    assert.match(r.error, /^WPS 按页读取失败：/)
    assert.match(r.error, /COM E_FAIL/)
  } finally { env.restore() }
})

test('WPS 文字：heading 按大纲级别切段，只读标题段的文字', async () => {
  const env = installWpsWord({
    paragraphs: [
      ['合同', 10], ['第一条 定义', 1], ['a', 10], ['1.1 术语', 2], ['b', 10],
      ['第二条 付款', 1], ['c', 10]
    ]
  })
  try {
    const r = await executeWpsCommand('read_for_reference', { locator: 'heading:第一条' })
    assert.equal(r.ok, true, r.error)
    assert.equal(r.data.text, '第一条 定义\ra\r1.1 术语\rb\r')
    // 同步桥上每次属性访问都是一次跨进程往返：正文段落的文字不该被逐段取
    assert.equal(env.stats.paragraphTextReads, 3, '只有三个标题段需要读文字')
    const last = await executeWpsCommand('read_for_reference', { locator: 'heading:第二条 付款' })
    assert.equal(last.ok, true, last.error)
    assert.equal(last.data.text, '第二条 付款\rc\r')
    const miss = await executeWpsCommand('read_for_reference', { locator: 'heading:第九条' })
    assert.equal(miss.ok, false)
    assert.match(miss.error, /没有找到标题：第九条/)
  } finally { env.restore() }
})

test('WPS 文字：slide/sheet 定位报错', async () => {
  const env = installWpsWord({ paragraphs: [['a', 10]] })
  try {
    for (const loc of ['slide:1', 'sheet:报价']) {
      const r = await executeWpsCommand('read_for_reference', { locator: loc })
      assert.equal(r.ok, false, loc)
      assert.match(r.error, /不支持该定位/, loc)
    }
  } finally { env.restore() }
})

// ==================== WPS 表格 ====================

function makeEtRange({ rows, cols, value2, address }) {
  return {
    Rows: { Count: rows },
    Columns: { Count: cols },
    Value2: value2,
    Address() { return address },
    Resize(r, c) { return makeEtRange({ rows: r, cols: c, value2: value2.slice(0, r), address }) }
  }
}

function installWpsEt(sheetsSpec, activeName) {
  const original = globalThis.wps
  const names = Object.keys(sheetsSpec)
  const sheets = {}
  for (const name of names) {
    const spec = sheetsSpec[name]
    sheets[name] = {
      Name: name,
      UsedRange: spec.used,
      Range(addr) {
        const r = (spec.ranges || {})[addr]
        if (!r) throw new Error('mock：未准备的区域 ' + addr)
        return r
      }
    }
  }
  const wb = {
    Name: '测算.xlsx',
    ActiveSheet: sheets[activeName],
    Worksheets: {
      Count: names.length,
      Item(key) {
        const s = typeof key === 'number' ? sheets[names[key - 1]] : sheets[key]
        if (!s) throw new Error('Unknown name')
        return s
      }
    }
  }
  const app = { Workbooks: {}, ActiveWorkbook: wb, ActiveSheet: sheets[activeName] }
  globalThis.wps = {
    Application: app,
    EtApplication: () => app,
    WpsApplication() { throw new Error('非文字宿主') },
    WppApplication() { throw new Error('非演示宿主') }
  }
  return { restore: () => restoreWps(original) }
}

test('WPS 表格：sheet:名称!区域 读指定区域；sheet:名称 读已用区域；不带定位读活动表', async () => {
  const env = installWpsEt({
    报价: {
      used: makeEtRange({ rows: 2, cols: 2, value2: [['项目', '金额'], ['律师费', 5000]], address: 'A1:B2' }),
      ranges: { 'B2': makeEtRange({ rows: 1, cols: 1, value2: 5000, address: 'B2' }) }
    },
    说明: { used: makeEtRange({ rows: 1, cols: 1, value2: '活动表', address: 'A1' }) }
  }, '说明')
  try {
    const a = await executeWpsCommand('read_for_reference', { locator: 'sheet:报价!B2' })
    assert.equal(a.ok, true, a.error)
    assert.match(a.data.text, /工作表「报价」/)
    assert.match(a.data.text, /5000/)
    const b = await executeWpsCommand('read_for_reference', { locator: 'sheet:报价' })
    assert.equal(b.ok, true, b.error)
    assert.match(b.data.text, /项目\t金额\n律师费\t5000/)
    const c = await executeWpsCommand('read_for_reference', {})
    assert.equal(c.ok, true, c.error)
    assert.match(c.data.text, /工作表「说明」/)
    assert.match(c.data.text, /活动表/)
  } finally { env.restore() }
})

test('WPS 表格：工作表不存在时报错并列出现有工作表；page 定位报错', async () => {
  const env = installWpsEt({
    报价: { used: makeEtRange({ rows: 1, cols: 1, value2: 'x', address: 'A1' }) }
  }, '报价')
  try {
    const r = await executeWpsCommand('read_for_reference', { locator: 'sheet:发票' })
    assert.equal(r.ok, false)
    assert.match(r.error, /未找到名为「发票」的工作表/)
    assert.match(r.error, /报价/)
    const p = await executeWpsCommand('read_for_reference', { locator: 'page:1' })
    assert.equal(p.ok, false)
    assert.match(p.error, /表格不支持该定位/)
  } finally { env.restore() }
})

// ==================== WPS 演示 ====================

function wppShape(text) {
  return { HasTextFrame: -1, HasTable: 0, Type: 17, TextFrame: { HasText: text ? -1 : 0, TextRange: { Text: text } } }
}

function installWpp(slideTexts) {
  const original = globalThis.wps
  const slides = slideTexts.map((texts) => ({
    Shapes: { Count: texts.length, Item(j) { return wppShape(texts[j - 1]) } }
  }))
  const pres = { Name: '汇报.pptx', Slides: { Count: slides.length, Item(i) { return slides[i - 1] } } }
  const app = { Presentations: {}, ActivePresentation: pres }
  globalThis.wps = {
    Application: app,
    WppApplication: () => app,
    WpsApplication() { throw new Error('非文字宿主') },
    EtApplication() { throw new Error('非表格宿主') }
  }
  return { restore: () => restoreWps(original) }
}

test('WPS 演示：slide:N 只取第 N 页；越界与不支持的定位报错；不带定位读全部', async () => {
  const env = installWpp([['封面'], ['争议焦点', '一、管辖'], ['结论']])
  try {
    const r = await executeWpsCommand('read_for_reference', { locator: 'slide:2' })
    assert.equal(r.ok, true, r.error)
    assert.equal(r.data.text, '争议焦点\n一、管辖')
    const over = await executeWpsCommand('read_for_reference', { locator: 'slide:5' })
    assert.equal(over.ok, false)
    assert.match(over.error, /演示稿只有 3 页/)
    const bad = await executeWpsCommand('read_for_reference', { locator: 'heading:x' })
    assert.equal(bad.ok, false)
    assert.match(bad.error, /演示稿不支持该定位/)
    const all = await executeWpsCommand('read_for_reference', {})
    assert.equal(all.ok, true, all.error)
    assert.match(all.data.text, /第2页：争议焦点 \| 一、管辖/)
  } finally { env.restore() }
})

test('WPS 演示：空白页给出一句说明而不是空串', async () => {
  const env = installWpp([['封面'], []])
  try {
    const r = await executeWpsCommand('read_for_reference', { locator: 'slide:2' })
    assert.equal(r.ok, true, r.error)
    assert.match(r.data.text, /第 2 页没有可读取的文字/)
  } finally { env.restore() }
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Office 面 read_for_reference（dev-board#717）：别的窗格按 locator 读本文档。
 *   node --test office-addin/taskpane/lib/referenceReadOffice.test.js
 *
 * 三条不变式：
 * 1. 三个宿主都能执行（不登记 COMMAND_HOSTS），按当前宿主分支；
 * 2. 宿主不支持的定位组合给明确错误，**绝不静默退回全文**（spec 第 3 节）；
 * 3. 返回 {text}，超长截断且带「...(截断)」标注。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// 需求集探测：按 set -> 最高版本 模拟；版本号按数值比较
let supported = {}
function versionGte(a, b) {
  const pa = String(a).split('.').map(Number)
  const pb = String(b).split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] || 0
    const y = pb[i] || 0
    if (x !== y) return x > y
  }
  return true
}

globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: {
    host: 'Word',
    document: { url: 'C:/x/B.docx' },
    requirements: {
      isSetSupported(set, version) {
        return supported[set] != null && versionGte(supported[set], version)
      }
    }
  }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')
const { isReadOnlyCommand } = await import('./docSnapshot.js')

function setHost(host) {
  Office.context.host = host
  delete globalThis.Word
  delete globalThis.Excel
  delete globalThis.PowerPoint
}

// ==================== Word ====================

function installWord({ body = '', paragraphs = [], pages = [] } = {}) {
  setHost('Word')
  const loads = []
  const ctx = {
    document: {
      body: {
        text: body,
        load(p) { loads.push('body:' + p) },
        paragraphs: {
          items: paragraphs.map(([text, outlineLevel]) => ({ text, outlineLevel })),
          load(p) { loads.push('paragraphs:' + p) }
        }
      },
      activeWindow: {
        activePane: {
          pages: {
            items: pages.map((t, i) => ({
              index: i + 1,
              getRange() { return { text: t, load() {} } }
            })),
            load() {}
          }
        }
      }
    },
    sync: async () => {}
  }
  globalThis.Word = { run: async (cb) => cb(ctx) }
  return { loads }
}

test('Word：不带定位读全文', async () => {
  supported = { WordApi: '1.4' }
  installWord({ body: '甲方\n乙方' })
  const r = await executeOfficeCommand('read_for_reference', {})
  assert.equal(r.ok, true, r.error)
  assert.equal(r.data.text, '甲方\n乙方')
  assert.equal(r.data.truncated, false)
})

test('Word：heading 取该标题所辖段落，止于同级或更高级标题', async () => {
  supported = { WordApi: '1.4' }
  const env = installWord({
    paragraphs: [
      ['合同', 10], ['第一条 定义', 1], ['a', 10], ['1.1 术语', 2], ['b', 10],
      ['第二条 付款', 1], ['c', 10]
    ]
  })
  const r = await executeOfficeCommand('read_for_reference', { locator: 'heading:第一条 定义' })
  assert.equal(r.ok, true, r.error)
  assert.equal(r.data.text, '第一条 定义\na\n1.1 术语\nb')
  // styleBuiltIn 是 WordApi 1.3，老宿主上连 load 都不能带——只许要 text 与 outlineLevel
  assert.ok(env.loads.some((l) => l.startsWith('paragraphs:') && /outlineLevel/.test(l)))
  assert.ok(!env.loads.some((l) => /styleBuiltIn/.test(l)))
})

test('Word：heading 找不到时报错并列出现有标题', async () => {
  supported = { WordApi: '1.4' }
  installWord({ paragraphs: [['第一条', 1], ['a', 10]] })
  const r = await executeOfficeCommand('read_for_reference', { locator: 'heading:第九条' })
  assert.equal(r.ok, false)
  assert.match(r.error, /没有找到标题：第九条/)
  assert.match(r.error, /第一条/)
})

test('Word：page:N 在 WordApiDesktop 1.2 下按页取', async () => {
  supported = { WordApi: '1.4', WordApiDesktop: '1.2' }
  installWord({ pages: ['第一页内容', '第二页内容', '第三页内容'] })
  const r = await executeOfficeCommand('read_for_reference', { locator: 'page:2' })
  assert.equal(r.ok, true, r.error)
  assert.equal(r.data.text, '第二页内容')
  const over = await executeOfficeCommand('read_for_reference', { locator: 'page:5' })
  assert.equal(over.ok, false)
  assert.match(over.error, /文档只有 3 页/)
})

test('Word：page:N 在不支持 WordApiDesktop 1.2 的宿主上明确拒绝，不退回全文', async () => {
  supported = { WordApi: '1.4' }
  installWord({ body: '全文', pages: ['第一页'] })
  const r = await executeOfficeCommand('read_for_reference', { locator: 'page:1' })
  assert.equal(r.ok, false)
  assert.match(r.error, /本机 Word 不支持按页读取，可改用标题或关键词/)
})

test('Word：空白页给出一句说明而不是空串', async () => {
  supported = { WordApi: '1.4', WordApiDesktop: '1.2' }
  installWord({ pages: ['正文', '   '] })
  const r = await executeOfficeCommand('read_for_reference', { locator: 'page:2' })
  assert.equal(r.ok, true, r.error)
  assert.match(r.data.text, /第 2 页没有可读取的文字/)
})

test('Word：slide/sheet 定位报错；非法定位报错', async () => {
  supported = { WordApi: '1.4' }
  installWord({ body: '全文' })
  const a = await executeOfficeCommand('read_for_reference', { locator: 'slide:1' })
  assert.equal(a.ok, false)
  assert.match(a.error, /Word 文档不支持该定位/)
  const b = await executeOfficeCommand('read_for_reference', { locator: 'sheet:报价' })
  assert.equal(b.ok, false)
  assert.match(b.error, /Word 文档不支持该定位/)
  const c = await executeOfficeCommand('read_for_reference', { locator: 'foo:1' })
  assert.equal(c.ok, false)
  assert.match(c.error, /无法识别的定位/)
})

test('超长正文截断到 200,000 字并标注「...(截断)」', async () => {
  supported = { WordApi: '1.4' }
  installWord({ body: 'x'.repeat(200_010) })
  const r = await executeOfficeCommand('read_for_reference', {})
  assert.equal(r.ok, true, r.error)
  assert.equal(r.data.truncated, true)
  assert.equal(r.data.totalChars, 200_010)
  assert.ok(r.data.text.endsWith('\n...(截断)'))
})

// ==================== Excel ====================

function makeRange({ address, rowIndex = 0, columnIndex = 0, rows, cols, values, isNullObject = false }) {
  return {
    address, rowIndex, columnIndex, rowCount: rows, columnCount: cols, values, isNullObject,
    _loaded: [],
    load(p) { this._loaded.push(String(p)) }
  }
}

function makeSheet(name, { used, ranges = {} }) {
  return {
    name,
    load() {},
    getUsedRangeOrNullObject() { return used },
    getRange(addr) {
      const r = ranges[addr]
      if (!r) throw new Error('mock：未准备的区域 ' + addr)
      return r
    },
    getRangeByIndexes(r, c, nr, nc) {
      return makeRange({ address: 'slice', rows: nr, cols: nc, values: used.values.slice(0, nr) })
    }
  }
}

function installExcel(sheets, activeName) {
  setHost('Excel')
  const list = Object.values(sheets)
  const ctx = {
    workbook: {
      worksheets: {
        items: list,
        load() {},
        getItem(name) {
          const s = sheets[name]
          if (!s) throw new Error('mock：getItem 不该在名字不存在时被调用')
          return s
        },
        getActiveWorksheet() { return sheets[activeName] }
      }
    },
    sync: async () => {}
  }
  globalThis.Excel = { run: async (cb) => cb(ctx) }
}

test('Excel：sheet:名称!区域 读指定区域为 TSV', async () => {
  supported = { ExcelApi: '1.4' }
  const quote = makeSheet('报价', {
    used: makeRange({ address: '报价!A1:C3', rows: 3, cols: 3, values: [['a', 'b', 'c'], [1, 2, 3], [4, 5, 6]] }),
    ranges: { 'A1:B2': makeRange({ address: '报价!A1:B2', rows: 2, cols: 2, values: [['项目', '金额'], ['律师费', 5000]] }) }
  })
  const other = makeSheet('说明', { used: makeRange({ address: '说明!A1', rows: 1, cols: 1, values: [['x']] }) })
  installExcel({ 报价: quote, 说明: other }, '说明')
  const r = await executeOfficeCommand('read_for_reference', { locator: 'sheet:报价!A1:B2' })
  assert.equal(r.ok, true, r.error)
  assert.match(r.data.text, /工作表「报价」/)
  assert.match(r.data.text, /项目\t金额\n律师费\t5000/)
})

test('Excel：sheet:名称 不带区域读该表已用区域；不带定位读活动表', async () => {
  supported = { ExcelApi: '1.4' }
  const quote = makeSheet('报价', {
    used: makeRange({ address: '报价!A1:B2', rows: 2, cols: 2, values: [['项目', '金额'], ['律师费', 5000]] })
  })
  const other = makeSheet('说明', { used: makeRange({ address: '说明!A1', rows: 1, cols: 1, values: [['活动表']] }) })
  installExcel({ 报价: quote, 说明: other }, '说明')
  const a = await executeOfficeCommand('read_for_reference', { locator: 'sheet:报价' })
  assert.equal(a.ok, true, a.error)
  assert.match(a.data.text, /律师费\t5000/)
  const b = await executeOfficeCommand('read_for_reference', {})
  assert.equal(b.ok, true, b.error)
  assert.match(b.data.text, /工作表「说明」/)
  assert.match(b.data.text, /活动表/)
})

test('Excel：工作表不存在时报错并列出现有工作表', async () => {
  supported = { ExcelApi: '1.4' }
  const quote = makeSheet('报价', { used: makeRange({ address: 'A1', rows: 1, cols: 1, values: [['x']] }) })
  installExcel({ 报价: quote }, '报价')
  const r = await executeOfficeCommand('read_for_reference', { locator: 'sheet:发票' })
  assert.equal(r.ok, false)
  assert.match(r.error, /未找到名为「发票」的工作表/)
  assert.match(r.error, /报价/)
})

test('Excel：page/heading/slide 定位报错', async () => {
  supported = { ExcelApi: '1.4' }
  const quote = makeSheet('报价', { used: makeRange({ address: 'A1', rows: 1, cols: 1, values: [['x']] }) })
  installExcel({ 报价: quote }, '报价')
  for (const loc of ['page:1', 'heading:第一条', 'slide:1']) {
    const r = await executeOfficeCommand('read_for_reference', { locator: loc })
    assert.equal(r.ok, false, loc)
    assert.match(r.error, /表格不支持该定位/, loc)
  }
})

// ==================== PowerPoint ====================

function textShape(text) {
  return {
    type: 'GeometricShape',
    getTextFrameOrNullObject() {
      return { isNullObject: false, hasText: !!text, load() {}, textRange: { text, load() {} } }
    }
  }
}

function installPpt(slideTexts) {
  setHost('PowerPoint')
  const slides = {
    items: slideTexts.map((texts) => ({ shapes: { items: texts.map(textShape), load() {} } })),
    load() {}
  }
  const ctx = { presentation: { slides }, sync: async () => {} }
  globalThis.PowerPoint = { run: async (cb) => cb(ctx) }
}

test('PowerPoint：slide:N 只取第 N 页文字', async () => {
  supported = { PowerPointApi: '1.4' }
  installPpt([['封面'], ['争议焦点', '一、管辖'], ['结论']])
  const r = await executeOfficeCommand('read_for_reference', { locator: 'slide:2' })
  assert.equal(r.ok, true, r.error)
  assert.equal(r.data.text, '争议焦点\n一、管辖')
  const over = await executeOfficeCommand('read_for_reference', { locator: 'slide:9' })
  assert.equal(over.ok, false)
  assert.match(over.error, /演示稿只有 3 页/)
})

test('PowerPoint：不带定位读全部幻灯片；page/sheet/heading 报错', async () => {
  supported = { PowerPointApi: '1.4' }
  installPpt([['封面'], ['结论']])
  const all = await executeOfficeCommand('read_for_reference', {})
  assert.equal(all.ok, true, all.error)
  assert.match(all.data.text, /第1页：封面/)
  assert.match(all.data.text, /第2页：结论/)
  for (const loc of ['page:1', 'sheet:x', 'heading:y']) {
    const r = await executeOfficeCommand('read_for_reference', { locator: loc })
    assert.equal(r.ok, false, loc)
    assert.match(r.error, /演示稿不支持该定位/, loc)
  }
})

test('PowerPoint：旧宿主（无 PowerPointApi 1.4）明确报错', async () => {
  supported = { PowerPointApi: '1.3' }
  installPpt([['封面']])
  const r = await executeOfficeCommand('read_for_reference', { locator: 'slide:1' })
  assert.equal(r.ok, false)
  assert.match(r.error, /PowerPointApi 1\.4/)
})

test('read_for_reference 是只读命令：不触发文档镜像快照', () => {
  assert.equal(isReadOnlyCommand('read_for_reference'), true)
})

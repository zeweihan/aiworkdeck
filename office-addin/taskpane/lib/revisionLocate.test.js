// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 修订记录「定位」的宿主侧与文档标识（dev-board#717，Task 13）：
 *   node --test office-addin/taskpane/lib/revisionLocate.test.js
 *
 * 1. Office：Excel 激活目标表并选中区域；PPT 选中目标页（setSelectedSlides 属 PowerPointApi 1.5，
 *    不支持时不调、回 found:false）；页码越界、表不存在都静默回 found:false。
 * 2. WPS：表格 Activate + Range.Select；演示 Slides.Item(n).Select。**真机未验证**，mock 是 VBA 风对象模型。
 * 3. 文档标识：Office 用文档 URL，WPS 用 FullName，都拿不到时用「宿主:文档名」——修订记录按它分文档存。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const HostType = { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' }

function setGlobals(g) {
  const saved = {}
  for (const k of ['Office', 'Word', 'Excel', 'PowerPoint', 'wps']) {
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

const { locateCrossDocTarget, documentKey } = await import('./hostBridge.js')

/* ==================== Office：Excel ==================== */

function installExcel(sheetNames) {
  const log = []
  const restore = setGlobals({
    Office: { HostType, context: { host: 'Excel', requirements: { isSetSupported: () => true }, document: { url: '' } } },
    Excel: {
      run: async (cb) => cb({
        workbook: {
          worksheets: {
            getItem: (name) => {
              if (!sheetNames.includes(name)) {
                const e = new Error('ItemNotFound')
                e.code = 'ItemNotFound'
                throw e
              }
              return {
                activate: () => log.push(['activate', name]),
                getRange: (addr) => ({ select: () => log.push(['select', name, addr]) })
              }
            }
          }
        },
        sync: async () => {}
      })
    }
  })
  return { log, restore }
}

test('Office Excel：定位 = 激活目标表 + 选中区域', async () => {
  const x = installExcel(['报价', 'Sheet1'])
  try {
    const r = await locateCrossDocTarget({ kind: 'excel', sheetName: '报价', address: 'B2:C3' })
    assert.deepEqual(r, { found: true })
    assert.deepEqual(x.log, [['activate', '报价'], ['select', '报价', 'B2:C3']])
  } finally { x.restore() }
})

test('Office Excel：表已被删 → found:false，不抛', async () => {
  const x = installExcel(['Sheet1'])
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'excel', sheetName: '报价', address: 'A1' }), { found: false })
  } finally { x.restore() }
})

/* ==================== Office：PowerPoint ==================== */

function installPpt({ slideCount, api15 }) {
  const selected = []
  const restore = setGlobals({
    Office: {
      HostType,
      context: {
        host: 'PowerPoint',
        requirements: { isSetSupported: (set, v) => set === 'PowerPointApi' && (v !== '1.5' || api15) },
        document: { url: '' }
      }
    },
    PowerPoint: {
      run: async (cb) => cb({
        presentation: {
          slides: { items: Array.from({ length: slideCount }, (_, i) => ({ id: `s${i + 1}` })), load() {} },
          setSelectedSlides: (ids) => selected.push(ids)
        },
        sync: async () => {}
      })
    }
  })
  return { selected, restore }
}

test('Office PPT：按页码 / 表格单元格 / 替换文字的第一处 定位到对应页', async () => {
  const p = installPpt({ slideCount: 5, api15: true })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptSlide', slideNumber: 3 }), { found: true })
    await locateCrossDocTarget({ kind: 'pptCell', slideNumber: 2, shapeId: '', row: 0, col: 0 })
    await locateCrossDocTarget({ kind: 'pptFrames', frames: [{ slide: 4, frame: 0 }, { slide: 1, frame: 2 }] })
    assert.deepEqual(p.selected, [['s3'], ['s2'], ['s4']])
  } finally { p.restore() }
})

test('Office PPT：页码越界 → found:false；宿主没有 PowerPointApi 1.5 → 不调 setSelectedSlides', async () => {
  const p = installPpt({ slideCount: 2, api15: true })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptSlide', slideNumber: 7 }), { found: false })
    assert.deepEqual(p.selected, [])
  } finally { p.restore() }
  const old = installPpt({ slideCount: 2, api15: false })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptSlide', slideNumber: 1 }), { found: false })
    assert.deepEqual(old.selected, [])
  } finally { old.restore() }
})

test('Office：宿主与目标对不上（Excel 目标落在 PPT 宿主上）→ found:false', async () => {
  const p = installPpt({ slideCount: 2, api15: true })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'excel', sheetName: 'S', address: 'A1' }), { found: false })
    assert.deepEqual(await locateCrossDocTarget(null), { found: false })
  } finally { p.restore() }
})

/* ==================== WPS ==================== */

function installWps(app) {
  const factories = {}
  if (app.Documents) factories.WpsApplication = () => app
  if (app.Workbooks) factories.EtApplication = () => app
  if (app.Presentations) factories.WppApplication = () => app
  return setGlobals({ wps: { Application: app, ...factories } })
}

test('WPS 表格：定位 = Activate 目标表 + Range.Select', async () => {
  const log = []
  const sheet = (name) => ({
    Name: name,
    Activate: () => log.push(['activate', name]),
    Range: (addr) => ({ Select: () => log.push(['select', name, addr]) })
  })
  const restore = installWps({
    Workbooks: {},
    ActiveWorkbook: {
      FullName: 'C:\\案件\\报价.xlsx',
      Worksheets: {
        Count: 1,
        Item: (n) => {
          if (n === '报价' || n === 1) return sheet('报价')
          throw new Error('not found')
        }
      }
    }
  })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'excel', sheetName: '报价', address: 'B2' }), { found: true })
    assert.deepEqual(log, [['activate', '报价'], ['select', '报价', 'B2']])
    assert.deepEqual(await locateCrossDocTarget({ kind: 'excel', sheetName: '不存在', address: 'B2' }), { found: false })
  } finally { restore() }
})

test('WPS 演示：定位 = Slides.Item(n).Select；越界 found:false；Select 抛错退到 GotoSlide', async () => {
  const log = []
  let selectThrows = false
  const restore = installWps({
    Presentations: {},
    ActiveWindow: { View: { GotoSlide: (n) => log.push(['goto', n]) } },
    ActivePresentation: {
      FullName: '/Users/x/方案.pptx',
      Slides: {
        Count: 3,
        Item: (n) => ({
          Select: () => {
            if (selectThrows) throw new Error('mock：普通视图外不能 Select')
            log.push(['select', n])
          }
        })
      }
    }
  })
  try {
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptSlide', slideNumber: 2 }), { found: true })
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptSlide', slideNumber: 9 }), { found: false })
    selectThrows = true
    assert.deepEqual(await locateCrossDocTarget({ kind: 'pptFrames', frames: [{ slide: 3, frame: 0 }] }), { found: true })
    assert.deepEqual(log, [['select', 2], ['goto', 3]])
  } finally { restore() }
})

/* ==================== 文档标识 ==================== */

test('documentKey：Office 用文档 URL；未保存（无 URL）在「宿主:文档名」后再补本窗格实例这一维', () => {
  let restore = setGlobals({
    Office: { HostType, context: { host: 'Word', document: { url: 'https://contoso.sharepoint.com/合同.docx' } } },
    Word: {}
  })
  try {
    assert.equal(documentKey(), 'https://contoso.sharepoint.com/合同.docx')
  } finally { restore() }
  restore = setGlobals({ Office: { HostType, context: { host: 'Word', document: { url: '' } } }, Word: {} })
  try {
    const key = documentKey()
    assert.match(key, /^word:当前 Word 文档#/, '仍以「宿主:文档名」开头，后面是本窗格实例的后缀')
    assert.notEqual(key, 'word:当前 Word 文档', '光靠通称分不开两份未保存的新文档')
    assert.equal(documentKey(), key, '同一个窗格里反复取必须是同一个键')
  } finally { restore() }
})

/**
 * 两份**未保存**的新 Word 各有自己的窗格（各自一个模块实例）：Office 面的文档名就是从
 * document.url 推出来的，url 为空时它是宿主通称，两边算出同一个键 → 同一条 conversationId
 * → 跨窗格下发按会话走，命令落到另一份文档上。所以未保存时的键必须逐窗格不同；
 * 存过盘（有路径）之后又必须逐字节相同，修订记录才跨重载留得住。
 */
test('documentKey：两个窗格实例——未保存的新文档不撞键，存过盘的同一份文档仍是同一个键', async () => {
  const other = await import('./hostBridge.js?paneInstance=2')
  let restore = setGlobals({ Office: { HostType, context: { host: 'Word', document: { url: '' } } }, Word: {} })
  try {
    assert.notEqual(documentKey(), other.documentKey(), '两份未保存的新文档必须算出不同的键')
  } finally { restore() }
  restore = setGlobals({
    Office: { HostType, context: { host: 'Word', document: { url: 'https://contoso.sharepoint.com/合同.docx' } } },
    Word: {}
  })
  try {
    assert.equal(documentKey(), other.documentKey(), '有路径时两边必须完全一致')
  } finally { restore() }
})

test('documentKey：WPS 用 FullName；没有宿主（普通浏览器调试）返回空串 = 不绑定', () => {
  const restore = installWps({ Presentations: {}, ActivePresentation: { Name: '方案.pptx', FullName: '/Users/x/方案.pptx' } })
  try {
    assert.equal(documentKey(), '/Users/x/方案.pptx')
  } finally { restore() }
  const bare = setGlobals({})
  try {
    assert.equal(documentKey(), '')
  } finally { bare() }
})

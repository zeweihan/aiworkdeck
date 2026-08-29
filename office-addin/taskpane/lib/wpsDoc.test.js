/**
 * wpsDoc.js 单测（node 自带 test runner，零依赖）：
 *   node --test office-addin/taskpane/lib/wpsDoc.test.js
 *
 * 这条路是「随消息附带当前文档内容」的唯一来源——坏了不会报错，只会让模型
 * 拿到残缺的表格/演示内容，然后一本正经地基于残缺内容回答。所以重点盯：
 * Value2 读回形状的归一（单行降维会丢数据）、大表只搬要展示的那几行、
 * 空表老实说为空。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { readWpsActiveDocument, detectWpsHost } from './wpsDoc.js'

/** 安装只有表格宿主的 globalThis.wps，返回 { calls, restore } */
function installEt(sheet) {
  const original = globalThis.wps
  const calls = []
  globalThis.wps = {
    // 文字/演示入口在表格宿主里调用即抛（官方示例的标准形态）
    WpsApplication() { throw new Error('非文字宿主') },
    WppApplication() { throw new Error('非演示宿主') },
    EtApplication() {
      calls.push('EtApplication')
      return { ActiveSheet: sheet, ActiveWorkbook: { Name: '测算表.xlsx' } }
    }
  }
  return {
    calls,
    restore() {
      if (original === undefined) delete globalThis.wps
      else globalThis.wps = original
    }
  }
}

/** 造一个假 UsedRange；value2 可以是二维、一维或标量，用来练归一化 */
function makeUsed({ rows, cols, value2, onResize }) {
  return {
    Rows: { Count: rows },
    Columns: { Count: cols },
    Value2: value2,
    Address(a, b) { return `A1:${String.fromCharCode(64 + cols)}${rows}` },
    Resize(r, c) {
      if (onResize) onResize(r, c)
      return makeUsed({ rows: r, cols: c, value2, onResize: null })
    }
  }
}

test('detectWpsHost：只有表格入口可用时判为 excel', () => {
  const { restore } = installEt(makeUsed({ rows: 1, cols: 1, value2: 'x' }))
  try {
    assert.equal(detectWpsHost(), 'excel')
  } finally { restore() }
})

test('表格：单行数据的 Value2 降成一维时，整行都要读到（不能只剩第一格）', async () => {
  // 单行/单列的 Value2 是否降维官方未成文。降维时不归一，一行数据的工作表
  // 会只读到第一格——模型拿到的表格内容凭空少了两列，还不会报错。
  const used = makeUsed({ rows: 1, cols: 3, value2: ['姓名', '职务', '持股比例'] })
  const { restore } = installEt({ Name: 'Sheet1', UsedRange: used })
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('姓名\t职务\t持股比例'), out.inlineContent)
  } finally { restore() }
})

test('表格：单列数据的 Value2 降成一维时按行还原', async () => {
  const used = makeUsed({ rows: 3, cols: 1, value2: ['甲方', '乙方', '丙方'] })
  const { restore } = installEt({ Name: 'Sheet1', UsedRange: used })
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('甲方\n乙方\n丙方'), out.inlineContent)
  } finally { restore() }
})

test('表格：正常二维数组照旧逐行制表符拼接', async () => {
  const used = makeUsed({ rows: 2, cols: 2, value2: [['a', 'b'], ['c', 'd']] })
  const { restore } = installEt({ Name: '明细', UsedRange: used })
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('a\tb\nc\td'), out.inlineContent)
    assert.equal(out.fileType, 'xlsx')
  } finally { restore() }
})

test('表格：空工作表老实说为空（UsedRange 是 A1 单格而不是空引用）', async () => {
  // 真机实测：空表的 UsedRange 返回 A1 单格、Value2 为 null，不是空引用，
  // 所以只判 !used 那条分支永远走不到，空表会被描述成「区域 A1」加一个空格子。
  const used = makeUsed({ rows: 1, cols: 1, value2: null })
  const { restore } = installEt({ Name: '空表', UsedRange: used })
  try {
    const out = await readWpsActiveDocument()
    assert.equal(out.inlineContent, '工作表「空表」为空')
  } finally { restore() }
})

test('表格：超出展示上限时只把要展示的行数搬过桥', async () => {
  // 只展示前 2000 行，却把十万行整片 Value2 搬过同步桥，会把任务窗格拖到长时间无响应
  let resizedTo = null
  const used = makeUsed({
    rows: 100000,
    cols: 3,
    value2: [['a', 'b', 'c']],
    onResize(r, c) { resizedTo = [r, c] }
  })
  const { restore } = installEt({ Name: '大表', UsedRange: used })
  try {
    const out = await readWpsActiveDocument()
    assert.deepEqual(resizedTo, [2000, 3], '应当先 Resize 到展示行数再取 Value2')
    assert.ok(out.inlineContent.includes('共 100000 行，仅附前 2000 行'), out.inlineContent)
  } finally { restore() }
})

/* ==================== 演示宿主 ==================== */

/** 安装只有演示宿主的 globalThis.wps */
function installWpp(slides) {
  const original = globalThis.wps
  globalThis.wps = {
    WpsApplication() { throw new Error('非文字宿主') },
    EtApplication() { throw new Error('非表格宿主') },
    WppApplication() {
      return {
        ActivePresentation: {
          Name: '方案.pptx',
          Slides: { Count: slides.length, Item: (i) => slides[i - 1] }
        }
      }
    }
  }
  return () => {
    if (original === undefined) delete globalThis.wps
    else globalThis.wps = original
  }
}

const textShape = (t) => ({ TextFrame: { HasText: true, TextRange: { Text: t } } })
const emptyShape = () => ({ TextFrame: { HasText: false } })
const tableShape = (rows) => ({
  HasTable: true,
  Table: {
    Rows: { Count: rows.length },
    Columns: { Count: rows[0].length },
    Cell: (r, c) => ({ Shape: { TextFrame: { TextRange: { Text: rows[r - 1][c - 1] } } } })
  }
})
const groupShape = (children) => ({ Type: 6, GroupItems: { Count: children.length, Item: (i) => children[i - 1] } })
const makeSlide = (shapes) => ({ Shapes: { Count: shapes.length, Item: (i) => shapes[i - 1] } })

test('演示：以表格承载的整页内容不能被读成「（无文本）」', async () => {
  // 对比表、时间表、条款对照这类整页就是一张表，父形状的 TextFrame 是空的，
  // 只看 TextFrame 会让 AI 以为这页什么都没有——用户看着满屏字，AI 说没内容。
  const restore = installWpp([makeSlide([tableShape([['条款', '风险'], ['第 3 条', '高']])])])
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('条款\t风险'), out.inlineContent)
    assert.ok(out.inlineContent.includes('第 3 条\t高'), out.inlineContent)
  } finally { restore() }
})

test('演示：组合形状里的文字要递归读出来', async () => {
  const restore = installWpp([makeSlide([groupShape([textShape('流程一'), textShape('流程二')])])])
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('流程一'), out.inlineContent)
    assert.ok(out.inlineContent.includes('流程二'), out.inlineContent)
  } finally { restore() }
})

test('演示：普通文本框照旧，无文本的页老实说无文本', async () => {
  const restore = installWpp([makeSlide([textShape('标题页')]), makeSlide([emptyShape()])])
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('第1页：标题页'), out.inlineContent)
    assert.ok(out.inlineContent.includes('第2页：（无文本）'), out.inlineContent)
    assert.equal(out.fileType, 'pptx')
  } finally { restore() }
})

test('演示：单个形状读失败不许拖垮整篇', async () => {
  const bad = { get HasTable() { throw new Error('mock 炸') }, get Type() { throw new Error('mock 炸') }, get TextFrame() { throw new Error('mock 炸') } }
  const restore = installWpp([makeSlide([bad, textShape('后面这段还在')])])
  try {
    const out = await readWpsActiveDocument()
    assert.ok(out.inlineContent.includes('后面这段还在'), out.inlineContent)
  } finally { restore() }
})

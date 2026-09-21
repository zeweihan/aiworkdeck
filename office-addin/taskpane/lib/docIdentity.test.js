// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 文档身份（hostBridge.documentIdentity）回归用例，两个家族六个面：
 *   node --test office-addin/taskpane/lib/docIdentity.test.js
 *
 * 会话按文档绑定（dev-board#767）全靠这里给出的 { key, saved } 三态：
 *   - saved:true  —— 存过盘，key 是路径，会话可以落盘、下次打开原样恢复；
 *   - saved:false —— 新建未保存，key 只在本窗格内有意义，会话**不落盘**（每次都是新对话）；
 *   - key:''      —— 连宿主都判不出（普通浏览器调试），不绑定任何文档。
 * 把 saved 恒置 true（或按 key 非空判定），未保存文档就会又把上一份文档的对话捡回来。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const HostType = { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' }

/** 整体换掉宿主全局对象，返回恢复函数 */
function setGlobals(next) {
  const saved = {}
  for (const k of ['Office', 'Word', 'Excel', 'PowerPoint', 'wps']) {
    saved[k] = globalThis[k]
    if (k in next) globalThis[k] = next[k]
    else delete globalThis[k]
  }
  return () => {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete globalThis[k]
      else globalThis[k] = saved[k]
    }
  }
}

const { documentIdentity, documentKey } = await import('./hostBridge.js')

/* ==================== Office 三个面 ==================== */

test('Office：存过盘的文档 key 是路径且 saved:true（Word/Excel/PPT 一致）', () => {
  const cases = [
    ['Word', 'Word', 'https://contoso.sharepoint.com/合同.docx'],
    ['Excel', 'Excel', 'file:///C:/台账/工资表.xlsx'],
    ['PowerPoint', 'PowerPoint', 'file:///C:/方案/尽调汇报.pptx']
  ]
  for (const [host, globalName, url] of cases) {
    const restore = setGlobals({
      Office: { HostType, context: { host: HostType[host], document: { url } } },
      [globalName]: {}
    })
    try {
      assert.deepEqual(documentIdentity(), { key: url, saved: true }, host)
      assert.equal(documentKey(), url, host + '：documentKey 与 identity.key 同源')
    } finally { restore() }
  }
})

test('Office：未保存的新文档 saved:false，key 带本窗格实例后缀', () => {
  const restore = setGlobals({
    Office: { HostType, context: { host: HostType.Word, document: { url: '' } } },
    Word: {}
  })
  try {
    const id = documentIdentity()
    assert.equal(id.saved, false, '没有路径就不是「存过盘」')
    assert.match(id.key, /^word:当前 Word 文档#/)
    assert.equal(documentIdentity().key, id.key, '同一个窗格里反复取必须是同一个键')
  } finally { restore() }
})

test('普通浏览器调试（判不出宿主）：key 为空、saved:false', () => {
  const restore = setGlobals({})
  try {
    assert.deepEqual(documentIdentity(), { key: '', saved: false })
  } finally { restore() }
})

/* ==================== WPS 三个面 ==================== */

/** WPS 宿主判定看 Application 上的标志性集合（与 wpsDoc.detectWpsHost 同源） */
function wpsGlobals(app) {
  return { wps: { Application: app, WpsApplication: () => app, EtApplication: () => app, WppApplication: () => app } }
}

test('WPS：存过盘的文档 key 是 FullName 且 saved:true（文字/表格/演示一致）', () => {
  const cases = [
    [{ Documents: {}, ActiveDocument: { Name: '合同.docx', FullName: 'C:\\cases\\合同.docx' } }, 'C:\\cases\\合同.docx'],
    [{ Workbooks: {}, ActiveWorkbook: { Name: '台账.xlsx', FullName: 'C:\\cases\\台账.xlsx' } }, 'C:\\cases\\台账.xlsx'],
    [{ Presentations: {}, ActivePresentation: { Name: '方案.pptx', FullName: '/Users/x/方案.pptx' } }, '/Users/x/方案.pptx']
  ]
  for (const [app, expected] of cases) {
    const restore = setGlobals(wpsGlobals(app))
    try {
      assert.deepEqual(documentIdentity(), { key: expected, saved: true })
    } finally { restore() }
  }
})

test('WPS：新建未保存（FullName 为空）同样是 saved:false', () => {
  const restore = setGlobals(wpsGlobals({ Documents: {}, ActiveDocument: { Name: '文档1', FullName: '' } }))
  try {
    const id = documentIdentity()
    assert.equal(id.saved, false)
    assert.match(id.key, /^word:/, 'WPS 文字归一到 word 宿主标签')
    assert.match(id.key, /#/, '未保存时带本窗格实例后缀')
  } finally { restore() }
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「依据」实体详情标签页的开法（dev-board#541）。
//
// 锁两条真会打扰用户的判断：
//   ① 未分屏时**强制开分屏并落到右侧**——开在左边会把用户正在读的那份文档顶掉；
//   ② 同一个实体不开第二个标签（跨两侧查重），已开的只激活不重建。
import test from 'node:test'
import assert from 'node:assert/strict'

import { insightEntityTabId, insightEntityTabMethods, INSIGHT_ENTITY_TAB_TYPE } from '../../src/pages/project-overview/insightEntityTab.js'

// openInsightDocFile 走 uni.showToast（宿主方法组里的既有口径）
const toasts = []
globalThis.uni = { showToast: (o) => toasts.push(o) }

function makeHost(over = {}) {
  const host = {
    leftFiles: over.leftFiles || [],
    rightFiles: over.rightFiles || [],
    activeFileIdLeft: over.activeFileIdLeft || null,
    activeFileIdRight: over.activeFileIdRight || null,
    splitMode: !!over.splitMode,
    focusedPane: over.focusedPane || 'left',
    hoverClosed: 0,
    resized: 0,
    opened: [],
    $t: (k) => k,
    $refs: { fileTree: { allFiles: over.allFiles || [] } },
    openFile(file) { this.opened.push(file) },
    closeInsightHoverCard() { this.hoverClosed++ },
    triggerWorkbenchResize() { this.resized++ },
    $nextTick(fn) { if (fn) fn(); return Promise.resolve() },
  }
  Object.assign(host, insightEntityTabMethods)
  return host
}

const ENT = { id: 31, kind: 'COMPANY', name: '京微资易科技有限公司' }

test('标签 id：按 kind + id 单例；缺 id 拿不到 id', () => {
  assert.equal(insightEntityTabId(ENT), 'insight-entity_COMPANY_31')
  assert.equal(insightEntityTabId({ id: 7, kind: 'LAW' }), 'insight-entity_LAW_7')
  assert.equal(insightEntityTabId({ id: 7 }), 'insight-entity_COMPANY_7', 'kind 缺省当公司')
  assert.equal(insightEntityTabId(null), '')
  assert.equal(insightEntityTabId({ name: 'x' }), '')
})

test('未分屏：先把分屏开出来，标签落右侧并激活', () => {
  const h = makeHost({ leftFiles: [{ id: 10, name: 'a.docx' }], activeFileIdLeft: 10 })
  h.openInsightEntityTab({ entity: ENT, detail: { basic: {} } })

  assert.equal(h.splitMode, true, '未分屏时必须开分屏')
  assert.equal(h.focusedPane, 'right')
  assert.equal(h.rightFiles.length, 1)
  assert.equal(h.rightFiles[0].id, 'insight-entity_COMPANY_31')
  assert.equal(h.rightFiles[0].tabType, INSIGHT_ENTITY_TAB_TYPE)
  assert.equal(h.rightFiles[0].name, ENT.name)
  assert.deepEqual(h.rightFiles[0].entitySpec.entity, ENT)
  assert.deepEqual(h.rightFiles[0].entitySpec.detail, { basic: {} })
  assert.equal(h.activeFileIdRight, 'insight-entity_COMPANY_31')
  assert.equal(h.activeFileIdLeft, 10, '左侧那份文档不许被顶掉')
  assert.equal(h.hoverClosed, 1, '开标签的同时收掉浮窗')
})

test('已分屏：不动 splitMode，照样落右侧', () => {
  const h = makeHost({ splitMode: true, focusedPane: 'left' })
  h.openInsightEntityTab({ entity: ENT })
  assert.equal(h.splitMode, true)
  assert.equal(h.focusedPane, 'right')
  assert.equal(h.rightFiles.length, 1)
})

test('单例：同一个实体再点一次只激活，不开第二个标签', () => {
  const h = makeHost()
  h.openInsightEntityTab({ entity: ENT })
  h.activeFileIdRight = 'other'
  h.openInsightEntityTab({ entity: ENT, detail: { basic: {} } })
  assert.equal(h.rightFiles.length, 1)
  assert.equal(h.activeFileIdRight, 'insight-entity_COMPANY_31')
  assert.equal(h.rightFiles[0].entitySpec.detail, null, '已开的标签不重建，也不覆盖它自己拉到的详情')
})

test('单例：标签被拖到左侧后再点，激活左侧那个而不是在右边再开一个', () => {
  const h = makeHost({
    splitMode: true,
    leftFiles: [{ id: 'insight-entity_COMPANY_31', tabType: INSIGHT_ENTITY_TAB_TYPE, name: 'x' }],
  })
  h.openInsightEntityTab({ entity: ENT })
  assert.equal(h.rightFiles.length, 0)
  assert.equal(h.activeFileIdLeft, 'insight-entity_COMPANY_31')
  assert.equal(h.focusedPane, 'left')
})

test('实体无效：一个标签都不开（也不动分屏）', () => {
  const h = makeHost()
  h.openInsightEntityTab(null)
  h.openInsightEntityTab({ entity: { name: '没有 id' } })
  assert.equal(h.rightFiles.length, 0)
  assert.equal(h.splitMode, false)
  assert.equal(h.hoverClosed, 0)
})

test('不同 kind 同 id 是两个标签（后端三类实体各自编号）', () => {
  const h = makeHost({ splitMode: true })
  h.openInsightEntityTab({ entity: { id: 31, kind: 'COMPANY', name: 'A' } })
  h.openInsightEntityTab({ entity: { id: 31, kind: 'LAW', name: 'B' } })
  assert.equal(h.rightFiles.length, 2)
})

// ————————————————— DOC 实体的「打开文件」（dev-board#541） —————————————————

const DOC_FILE = { id: 21, name: '房屋租赁合同.docx', fileType: 'docx' }

test('DOC：未分屏时开分屏、落右侧，交给 openFile 按 focusedPane 放', () => {
  const h = makeHost({ leftFiles: [{ id: 10, name: 'a.docx' }], activeFileIdLeft: 10, allFiles: [DOC_FILE] })
  toasts.length = 0
  h.openInsightDocFile({ fileId: 21, fileName: '房屋租赁合同.docx' })

  assert.equal(h.splitMode, true, '默认在右侧分屏打开——开在左边会把正在读的那份顶掉')
  assert.equal(h.focusedPane, 'right')
  assert.deepEqual(h.opened, [DOC_FILE])
  assert.equal(h.activeFileIdLeft, 10, '左侧那份文档不许被顶掉')
  assert.equal(h.hoverClosed, 1, '打开文件的同时收掉浮窗')
  assert.equal(toasts.length, 0)
})

test('DOC：已经开着的只激活，不重开也不动分屏', () => {
  const h = makeHost({
    splitMode: true, focusedPane: 'right',
    leftFiles: [{ id: 21, name: '房屋租赁合同.docx' }],
    allFiles: [DOC_FILE],
  })
  h.openInsightDocFile({ fileId: 21 })
  assert.deepEqual(h.opened, [], '已经开着就不该再 openFile 一次')
  assert.equal(h.activeFileIdLeft, 21)
  assert.equal(h.focusedPane, 'left')
  assert.equal(h.splitMode, true)
})

test('DOC：文件已不在项目里 → 一句可读提示，不开标签也不开分屏', () => {
  const h = makeHost({ allFiles: [{ id: 99, name: '别的.docx' }] })
  toasts.length = 0
  h.openInsightDocFile({ fileId: 21, fileName: '房屋租赁合同.docx' })
  assert.deepEqual(h.opened, [])
  assert.equal(h.splitMode, false)
  assert.equal(toasts.length, 1, '静默什么都不做 = 用户以为点坏了')
  assert.equal(toasts[0].title, 'insight.docMissing')
})

test('DOC：payload 没有 fileId → 一动不动（连浮窗都不收）', () => {
  const h = makeHost({ allFiles: [DOC_FILE] })
  toasts.length = 0
  h.openInsightDocFile(null)
  h.openInsightDocFile({ fileName: '没有 id' })
  assert.deepEqual(h.opened, [])
  assert.equal(h.hoverClosed, 0)
  assert.equal(toasts.length, 0)
})

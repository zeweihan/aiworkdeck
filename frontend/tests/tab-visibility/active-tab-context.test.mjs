// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「哪个标签能当活跃文档 / 能拖进 AI 上下文」的契约（dev-board#779 K6 ②、K8 ①）。
// 跑法：cd frontend && npm run test:tab-visibility
import test from 'node:test'
import assert from 'node:assert/strict'
import { isContextEligibleTab, pickActiveContextTab } from '../../src/pages/project-overview/activeTabContext.js'

const docx = { id: 12, name: '股份认购协议.docx', fileType: 'docx' }
const docxRight = { id: 34, name: '股权转让协议.docx', fileType: 'docx' }

test('真实项目文件（数字 id）可以当活跃文档', () => {
  assert.equal(isContextEligibleTab(docx), true)
  // 后端来的 id 有时是字符串，形态仍是数字
  assert.equal(isContextEligibleTab({ id: '12', name: 'a.docx', fileType: 'docx' }), true)
})

test('浏览器标签不能当活跃文档', () => {
  assert.equal(isContextEligibleTab({ id: 'web_1712', name: '百度一下', tabType: 'web' }), false)
  assert.equal(isContextEligibleTab({ id: 'web_1712', name: '百度一下', tabType: 'browser' }), false)
})

test('AI 计划 artifact 标签不能当活跃文档（tabType 是 markdown，只有 id 形态能认出来）', () => {
  // 构造点：project-overview.vue 的 handleArtifactOpenTab，id = `artifact-<id>`、
  // tabType 'markdown'、fileType 'md'——光看 tabType 与扩展名它长得就是一份正常的 md。
  // 后端 read_document 对它 Long.parseLong 抛异常，把一句 Java 异常文案当正文交回来。
  assert.equal(isContextEligibleTab({ id: 'artifact-12', name: 'AI助手工作计划.md', tabType: 'markdown', fileType: 'md' }), false)
})

test('其余虚拟标签一律不能当活跃文档', () => {
  const tabs = [
    { id: 'market-detail_dd', tabType: 'market-detail' },
    { id: 'admin-settings', tabType: 'admin-settings' },
    { id: 'insight-entity_company_9', tabType: 'insight-entity' },
    { id: 'commit-history_1', tabType: 'commit-history' },
    { id: 'merge-review_1_a.docx', tabType: 'merge-review' },
    { id: 'vcmp-abc12345-1790065459835', tabType: 'version-compare', fileType: 'version-compare' },
    { id: 'vtd-abc12345-1790065459835', tabType: 'version-text-diff', fileType: 'version-text-diff' },
    { id: 'diff-1-2-1790065459835', tabType: 'diff', fileType: 'diff' }
  ]
  for (const t of tabs) {
    assert.equal(isContextEligibleTab(t), false, `${t.id} 不该当活跃文档`)
  }
})

test('缺 id、空 id、0 号 id 都不算', () => {
  assert.equal(isContextEligibleTab(null), false)
  assert.equal(isContextEligibleTab(undefined), false)
  assert.equal(isContextEligibleTab({}), false)
  assert.equal(isContextEligibleTab({ id: '' }), false)
  assert.equal(isContextEligibleTab({ id: 0 }), false)
  assert.equal(isContextEligibleTab({ id: '12abc' }), false)
})

// ==== currentActiveTab 的取值规则（computed 只是这个函数的一行转发）====

test('未聚焦右窗格时优先左窗格，左边空了才看右边', () => {
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: docx, activeFileRight: docxRight }), docx)
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: null, activeFileRight: docxRight }), docxRight)
})

test('聚焦右窗格且右边是真文档时取右边', () => {
  assert.equal(pickActiveContextTab({ focusedPane: 'right', activeFileLeft: docx, activeFileRight: docxRight }), docxRight)
})

test('聚焦的那一侧是浏览器标签时，回落到另一侧的真文档而不是把浏览器标签当文档', () => {
  const web = { id: 'web_1712', name: '百度一下', tabType: 'web' }
  assert.equal(pickActiveContextTab({ focusedPane: 'right', activeFileLeft: docx, activeFileRight: web }), docx)
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: web, activeFileRight: docxRight }), docxRight)
})

test('两侧都是虚拟标签时返回 null（不注入任何活跃文档）', () => {
  const web = { id: 'web_1712', tabType: 'web' }
  const artifact = { id: 'artifact-12', tabType: 'markdown', fileType: 'md' }
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: artifact, activeFileRight: web }), null)
  assert.equal(pickActiveContextTab({ focusedPane: 'right', activeFileLeft: artifact, activeFileRight: web }), null)
})

test('两侧都空时返回 null', () => {
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: null, activeFileRight: null }), null)
  assert.equal(pickActiveContextTab({}), null)
})

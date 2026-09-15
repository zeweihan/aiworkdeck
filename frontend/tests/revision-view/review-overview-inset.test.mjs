// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审阅概览（.libre-review-overview）改成浮在画布右侧、不挤宽画布之后，画布上的
// 宿主浮层要让出面板宽度，否则保存失败的「重试」与改字 stale 条的
// 保留/打开/忽略 都压在面板底下点不到。
//
// 布局本身要真渲染才看得见（这里没有浏览器），本用例钉住三件能在源码层验证的事：
//   1) 面板的 v-if 与 .libre-body 的让位 class 是同一个判据（不许各写一份）；
//   2) 让位规则覆盖到画布上的每一个宿主浮层；
//   3) 让出的像素数等于 ReviewPanel .rp 的宽度——面板改宽了，这里跟着红。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const EDITOR = read('../../src/components/LibreOfficeEditor.vue')
const PANEL = read('../../src/components/ReviewPanel.vue')
const template = EDITOR.match(/<template>([\s\S]*)<\/template>/)[1]
const style = EDITOR.match(/<style scoped>([\s\S]*?)<\/style>/)[1]

function editorComponent() {
  const body = EDITOR.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
  return new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', body)(null, null, null)
}

test('概览的 v-if 与让位 class 共用 reviewOverviewShown，判据与原 v-if 一致', () => {
  assert.match(template, /<ReviewPanel\s+class="libre-review-overview"\s+v-if="reviewOverviewShown"/)
  assert.match(template, /<view class="libre-body" :class="\{ 'review-overview-open': reviewOverviewShown \}">/)
  const shown = editorComponent().computed.reviewOverviewShown
  for (const reviewOpen of [true, false]) {
    for (const ready of [true, false]) {
      for (const showsReview of [true, false]) {
        assert.equal(!!shown.call({ reviewOpen, ready, showsReview }), reviewOpen && ready && showsReview)
      }
    }
  }
})

test('画布上的宿主浮层都在让位规则里，且都是 .libre-canvas-wrap 的子节点', () => {
  const wrap = template.slice(template.indexOf('<view class="libre-canvas-wrap">'), template.indexOf('<ReviewPanel'))
  assert.match(wrap, /<EvidenceStaleBar\s+class="libre-stale-bar"/)
  assert.match(wrap, /class="libre-evidence-drop"/)
  assert.match(wrap, /<view class="libre-float">/)
  const panelWidth = Number(PANEL.match(/\.rp \{[^}]*?\bwidth: (\d+)px/)[1])
  assert.equal(panelWidth, 288, '前置条件：读到了 ReviewPanel 的宽度')
  const rule = (selector) => {
    const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const m = style.match(new RegExp('\\.libre-body\\.review-overview-open ' + esc + '[^{]*\\{([^}]*)\\}'))
    assert.ok(m, '缺少让位规则：' + selector)
    return m[1]
  }
  assert.match(rule('.libre-float'), new RegExp('right: calc\\(' + panelWidth + 'px \\+ 16px\\)'))
  assert.match(rule('.libre-stale-bar'), new RegExp('right: ' + panelWidth + 'px'))
  assert.match(rule('.libre-evidence-drop'), new RegExp('right: ' + panelWidth + 'px'))
  // 基线：.libre-float 平时的 right 仍是 16px，让位只在概览打开时生效
  assert.match(style, /\n\.libre-float \{[^}]*right: 16px/)
})

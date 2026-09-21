// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#753 / #754：审阅面板头部的两处视觉契约。
//
//   #753 收起控件是面板左上角的一个向右箭头按钮（原来是右端一行 12px 的灰字
//        「收起」，维护者真机反馈「不明显」）。箭头指向它收起的方向。
//   #754 五个标签必须排在同一行、不折行——288px 宽时每个标签都被挤成两行
//        （「AI 审 / 校 6」「溯 / 源」，v0.46.1 真机截图）。
//
// 布局本身要真渲染才看得见（这里没有浏览器），本用例钉住能在源码层验证的部分：
// 头部第一个可交互元素是带 aria-label 的收起按钮、标签与计数不折行、
// 标签文案里不再自带 {count}（自带的话计数会连标签一起被折行）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const SRC = read('../../src/components/ReviewPanel.vue')
const STYLE = SRC.match(/<style scoped>([\s\S]*?)<\/style>/)[1]
const HEAD = SRC.slice(SRC.indexOf('<view class="rp-head">'), SRC.indexOf('</view>', SRC.indexOf('rp-merge-title')))
  .replace(/<!--[\s\S]*?-->/g, '')
const rule = (selector) => {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const m = STYLE.match(new RegExp('(^|\\n)' + esc + '\\s*\\{([^}]*)\\}'))
  assert.ok(m, '缺少样式规则：' + selector)
  return m[2]
}

test('头部第一个可交互元素是收起按钮，带 aria-label 与向右的箭头', () => {
  const first = HEAD.slice(HEAD.indexOf('>') + 1).trim()
  assert.match(first.split('\n')[0], /class="rp-collapse"/, '收起按钮要排在标签行之前（面板左上角）')
  const btn = HEAD.slice(HEAD.indexOf('class="rp-collapse"'))
  assert.match(btn, /:aria-label="\$t\('editor\.review\.collapse'\)"/)
  assert.match(btn, /:title="\$t\('editor\.review\.collapse'\)"/)
  assert.match(btn, /@tap="\$emit\('close'\)"/)
  // 向右的折线箭头：先向右下、再向右上，起点在左。
  const path = btn.match(/<path d="([^"]+)"/)[1]
  assert.match(path, /^M\s*9\s+5\s*l\s*7\s+7\s*-7\s+7$/, '箭头指向右（收起的方向）')
  assert.equal(SRC.includes('rp-close'), false, '右端那行文字「收起」已被箭头取代')
})

test('五个标签在同一行且不折行，计数是同一行里的小号数字', () => {
  const tabs = HEAD.slice(HEAD.indexOf('class="rp-tabs'), HEAD.indexOf('rp-merge-title'))
  const keys = [...tabs.matchAll(/\$t\('([\w.]+)'\)/g)].map((m) => m[1])
  assert.deepEqual(keys, [
    'editor.review.revTab', 'editor.review.cmtTab', 'editor.review.evidenceTab',
    'editor.review.checkTab', 'version.provenanceTab',
  ], '标签的语义与顺序不变')
  // 计数与标签文字分开渲染：四个有计数的标签各带一个 .rp-tab-n，溯源没有计数。
  assert.equal([...tabs.matchAll(/class="rp-tab-n"/g)].length, 4)
  assert.match(tabs, /class="rp-tab-n">\{\{ allGroups\.length \}\}/)
  assert.match(tabs, /class="rp-tab-n">\{\{ comments\.length \}\}/)
  assert.match(tabs, /class="rp-tab-n">\{\{ evidenceCount \}\}/)
  assert.match(tabs, /class="rp-tab-n">\{\{ inlineReviewCount \}\}/)
  // 显隐条件照旧
  assert.match(tabs, /<text v-if="inlineReview" class="rp-tab"/)
  assert.match(tabs, /<text v-if="provenance" class="rp-tab"/)
  assert.match(rule('.rp-tab'), /white-space: nowrap/)
  assert.match(rule('.rp-tab'), /flex: none/, '标签不许被挤窄（挤窄就折行）')
  assert.match(rule('.rp-tabs'), /flex-wrap: nowrap/)
})

test('标签文案里不再自带 {count}（计数由模板单独渲染，否则会显示两遍）', () => {
  for (const lang of ['zh-CN', 'en-US']) {
    const ns = read(`../../src/locales/${lang}/editor.js`)
    const review = ns.slice(ns.indexOf('review:'))
    for (const key of ['revTab', 'cmtTab', 'evidenceTab', 'checkTab']) {
      const line = review.match(new RegExp(`\\n\\s*${key}: '([^']*)'`))
      assert.ok(line, `${lang} 缺 ${key}`)
      assert.equal(line[1].includes('{count}'), false, `${lang} 的 ${key} 不该再带 {count}`)
    }
  }
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Isolated rendering fixture for ReviewPanel (dev-board#874): real component,
// synthetic revisions/comments/inline-review/provenance data, no live LOWA engine
// (the LOWA canvas needs a webview and cannot boot in a plain browser tab — see
// .claude/agents/doc-editor.md). This only proves ReviewPanel's own layout at the
// widths LibreOfficeEditor gives it (320px zh-CN / 408px en-US), matching the
// component-level pattern in tests/chat-presentation-ui/main.js.
//
// ?lang=zh-CN|en-US selects the interface language (must be set before the i18n
// module loads — vue-i18n's locale is read once at createI18n() time, see
// src/i18n/index.js). ?before=1 forces the panel back to the pre-fix 320px width
// regardless of language, reproducing the reported bug (English tab row clipped)
// so before/after can be compared from the same fixture.
const params = new URLSearchParams(location.search)
const lang = params.get('lang') === 'en-US' ? 'en-US' : 'zh-CN'
const before = params.get('before') === '1'

window.fixtureStorage = { awd_app_language: lang }
window.uni = {
  getStorageSync: (key) => window.fixtureStorage[key],
  setStorageSync(key, value) { window.fixtureStorage[key] = value },
  removeStorageSync(key) { delete window.fixtureStorage[key] },
  $on() {}, $off() {}, $emit() {},
  showToast() {},
  getSystemInfoSync: () => ({ platform: 'mac', windowWidth: innerWidth }),
  request: ({ success }) => success?.({ statusCode: 200, data: { code: 0, data: [] } }),
}
window.fetch = async () => new Response(JSON.stringify({ code: 0, data: [] }), { headers: { 'Content-Type': 'application/json' } })

const { createApp, h, ref } = await import('vue')
const { default: ReviewPanel } = await import('../../src/components/ReviewPanel.vue')
const { i18n } = await import('../../src/i18n/index.js')

// 13/12/14/15 条，全部两位数——挂在标签上的计数是否被挤没了要用真数字看，不能用
// 个位数蒙混过去。类型/作者逐条交替，避免 groupRevisions 把相邻条目并成一张卡
// （否则「revisions.length」与卡片数对不上，不影响本次验证但会让 12 张卡片数字失真）。
const TYPES = ['Insert', 'Delete', 'Format', 'ParagraphFormat']
const AUTHORS = ['AI WorkDeck', '韩泽伟', '合伙人张律师']
const revisions = Array.from({ length: 13 }, (_, i) => ({
  index: i,
  identifier: 'r' + i,
  type: TYPES[i % TYPES.length],
  text: '第' + (i + 1) + '条修订：甲方应于三十日内完成付款义务。',
  author: AUTHORS[i % AUTHORS.length],
  date: '2026-09-2' + (i % 3),
  inTable: i % 5 === 0,
  paragraph: '第' + (i + 1) + '段上下文：本条款约定了付款期限与违约责任的具体安排。',
  paraKey: i,
  start: 0,
  end: 8,
}))
const comments = Array.from({ length: 12 }, (_, i) => ({
  index: i,
  id: 'c' + i,
  author: AUTHORS[i % AUTHORS.length],
  content: '第' + (i + 1) + '条批注：请核对本处金额与附件是否一致。',
  date: '2026-09-1' + (i % 9),
  anchorText: '违约金',
  resolved: i % 4 === 0,
  paraKey: -1,
  start: 0,
  end: 0,
}))
const FINDING_KINDS = ['COUNT_MISMATCH', 'PLACEHOLDER', 'NUMBERING', 'LOGIC_REVIEW', 'ARITHMETIC']
const findings = Array.from({ length: 15 }, (_, i) => ({
  id: 'f' + i,
  kind: FINDING_KINDS[i % FINDING_KINDS.length],
  title: '第' + (i + 1) + '条疑点',
  message: '正文出现的数量与附件清单不一致，请核对。',
  quote: '合计人民币壹佰万元整',
  severity: i % 3 === 0 ? 'error' : 'warn',
}))
const inlineReview = { status: 'ready', revision: 3, ai: true, writable: true, hidden: false, findings }
const provenance = {
  summary: '共 13 段，最近一次改动来自 AI WorkDeck。',
  truncated: false,
  rows: revisions.slice(0, 5).map((r, i) => ({
    index: i, text: r.paragraph, unit: { sha: 'abc123' + i, when: r.date, author: r.author },
  })),
}

const panel = ref()
const app = createApp({
  setup: () => () => h('div', { style: 'display:flex;height:100%;' }, [
    h(ReviewPanel, {
      ref: panel,
      executor: null,
      selfAuthor: '韩泽伟',
      projectId: 1,
      docFileId: 1,
      provenance,
      inlineReview,
    }),
  ]),
})
app.use(i18n)
app.mount('#app')
await new Promise((resolve) => requestAnimationFrame(resolve))
panel.value.revisions = revisions
panel.value.comments = comments
panel.value.evidenceCount = 14
await new Promise((resolve) => requestAnimationFrame(resolve))

// before=1：把面板钉回改造前的 320px，复现报告里的问题（英文五个标签在 320px
// 里放不下、被横向滚动裁掉）。只做视觉层的覆盖，不改任何真实源码。
if (before) {
  const style = document.createElement('style')
  style.textContent = '.rp{width:320px !important} .rp-en .rp-tabs{gap:2px !important} .rp-en .rp-tab{padding:3px 7px !important}'
  document.head.appendChild(style)
}

window.panel = panel.value
window.ready = true

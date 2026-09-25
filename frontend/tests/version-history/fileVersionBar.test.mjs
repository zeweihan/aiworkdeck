// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 编辑器顶上那条**版本身份小条**（dev-board#672 复测，2026-09-16 用户拍板）。
 *
 * 它原来逐段跟着光标走（设计稿 §5.5 的「溯源光标条」），律师读不出自己最想知道的
 * 那件事——「我现在看的这份文件，存进版本记录了吗？是哪一版？」：光标随便一动
 * 那句话就换一个名字，落在表格里还整条消失。现在改成文件级：这份文件最近一次
 * **有名字**的版本（自动存档折进它所属的那一版），磁盘内容领先版本记录时说
 * 「已保存，尚未存为版本」（v0.49.0 BUG-32 之前说「本机未保存的改动」，误导）。逐段归属一个字没动，仍在审阅面板的「溯源」标签里。
 *
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { alignProvenance, fileVersionBar, provenanceSummary } from '../../src/utils/provenanceAlign.js'
import zh from '../../src/locales/zh-CN/version.js'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

/** 真的用 zh 文案渲染：这条小条的每一个字都是产品拍过的，合成 key 验不出来。 */
const t = (key, params) => {
  const raw = zh[String(key).replace(/^version\./, '')]
  if (raw == null) throw new Error('缺文案: ' + key)
  return String(raw).replace(/\{(\w+)\}/g, (_, k) => String((params || {})[k]))
}

const WHEN = '2026-09-15T02:00:00Z'
const named = (over = {}) => ({
  sha: 'abc1234def', shortId: 'abc1234', authorName: '韩泽伟', self: true,
  when: WHEN, title: '初始版本', type: 'session', ...over,
})
const state = (over = {}) => ({ versioned: true, dirty: false, fileVersion: named(), ...over })

// ---------------- 小条取值 ----------------

test('说这份文件最近一次有名字的版本：作者 · 日期 · 版本标题', () => {
  const bar = fileVersionBar(t, state())
  assert.equal(bar.visible, true)
  assert.equal(bar.text, '你 · 9 月 15 日 · 初始版本')
  assert.equal(bar.sha, 'abc1234def', '点一下要能打开那一版')
})

test('同事交的那一版显示他的展示名，不显示 username', () => {
  const bar = fileVersionBar(t, state({ fileVersion: named({ self: false, authorName: '律师乙' }) }))
  assert.equal(bar.text, '律师乙 · 9 月 15 日 · 初始版本')
})

test('没开版本记录：整条不出现（说「初始版本」或「未保存」都是胡说）', () => {
  const bar = fileVersionBar(t, { versioned: false, dirty: true, fileVersion: named() })
  assert.equal(bar.visible, false)
  assert.equal(bar.text, '')
  assert.equal(bar.sha, '')
  assert.equal(fileVersionBar(t, null).visible, false)
})

test('这份文件还没进过任何命名版本：显示「初始版本」，且点不动', () => {
  const bar = fileVersionBar(t, state({ fileVersion: null }))
  assert.equal(bar.visible, true)
  assert.equal(bar.text, '初始版本')
  assert.equal(bar.sha, '')
})

// ---------------- 未保存态（文件级） ----------------

test('磁盘内容领先版本记录：说「已保存，尚未存为版本」，点不动', () => {
  // v0.49.0 真机 BUG-32：dirty 的意思是「磁盘已存、还没进版本记录」，不是「没保存」。
  // 保存成功后还挂着「本机未保存的改动」会让律师以为没存上。
  const bar = fileVersionBar(t, state({ dirty: true }))
  assert.equal(bar.visible, true)
  assert.equal(bar.text, '已保存，尚未存为版本')
  assert.ok(!bar.text.includes('未保存'), '文件级小条不许再说「未保存」')
  assert.equal(bar.sha, '', '这时候没有「那一版」可打开')
})

test('「未保存改动」与「未存为版本」是两个文案：逐段溯源里没对上的段落仍说本机未保存的改动', () => {
  assert.ok(zh.provenanceFileUncommitted, '缺文案 provenanceFileUncommitted')
  assert.notEqual(zh.provenanceFileUncommitted, zh.provenanceUnsaved)
  assert.equal(zh.provenanceUnsaved, '本机未保存的改动')
})

test('落版之后（结束工作 / 采纳 / 重载）脏位灭掉，换回版本名', () => {
  // version-landed → loadProvenance → 后端回 dirty:false，同一个纯函数换一个答案
  assert.equal(fileVersionBar(t, state({ dirty: true })).text, '已保存，尚未存为版本')
  assert.equal(fileVersionBar(t, state({ dirty: false })).text, '你 · 9 月 15 日 · 初始版本')
})

// ---------------- 折叠：自动存档不许露给律师 ----------------

test('自动存档折进所属的命名版本——折叠在后端（小条只读 fileVersion 这一个字段）', () => {
  // 口径由后端 ProjectRepoService.latestNamedVersionForPath 保证（护栏
  // ProvenanceServiceTest.fileVersionSkipsAutosaves）；这里钉的是前端**不会**
  // 自己拿 units 里那些 auto 去顶上。
  const src = read('../../src/components/LibreOfficeEditor.vue')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1]
  const barBlock = script.match(/provenanceBar\(\)[\s\S]*?\n {4}\},/)[0]
  assert.ok(barBlock.includes('this.provFileVersion'), barBlock)
  assert.ok(!barBlock.includes('provUnits'), '小条不许自己去翻逐段的 units')
  assert.ok(!/provUnit\b/.test(barBlock), '小条不许再跟着光标那一段走')
})

// ---------------- 组件接线 ----------------

/** 读 .vue 的 <script>、把 import 换成可注入的桩（形制同 provenanceRefresh.test.mjs）。 */
function optionsOf(rel, stubs = {}) {
  const script = read(rel).match(/<script>([\s\S]*?)<\/script>/)[1]
  const names = []
  for (const m of script.matchAll(/^import\s+([\s\S]*?)\s+from\s+'[^']+'\s*;?\s*$/gm)) {
    const clause = m[1]
    const braced = clause.match(/\{([\s\S]*?)\}/)
    if (braced) {
      for (const part of braced[1].split(',')) {
        const name = part.split(/\s+as\s+/).pop().trim()
        if (name) names.push(name)
      }
    }
    const def = clause.replace(/\{[\s\S]*?\}/, '').replace(/,/g, '').trim()
    if (def) names.push(def)
  }
  const body = script
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace(/export\s+default\s*\{/, 'return {')
  return new Function(...names, body)(...names.map((n) => stubs[n] || (() => {})))
}

// 这条小条与侧栏「溯源」标签的取值全在这三个纯函数里，所以它们必须注入真实现，
// 别的 import（引擎、api、宿主）一律留成空桩。
const editorWith = (stubs = {}) => optionsOf('../../src/components/LibreOfficeEditor.vue',
  { alignProvenance, fileVersionBar, provenanceSummary, ...stubs })

function barOf(ed, over = {}) {
  const ctx = {
    ready: true, file: { id: 5 }, loadingOverlayVisible: false,
    provVersioned: true, provDirty: false, provFileVersion: named(),
    $t: t, ...over,
  }
  ctx.provenanceBar = ed.computed.provenanceBar.call(ctx)
  return {
    visible: ed.computed.provenanceBarVisible.call(ctx),
    text: ed.computed.provText.call(ctx),
    sha: ed.computed.provSha.call(ctx),
  }
}

test('组件的三个 computed 全部读文件级状态', () => {
  const ed = editorWith({})
  assert.deepEqual(barOf(ed), { visible: true, text: '你 · 9 月 15 日 · 初始版本', sha: 'abc1234def' })
  assert.equal(barOf(ed, { provDirty: true }).text, '已保存，尚未存为版本')
  assert.equal(barOf(ed, { provVersioned: false }).visible, false)
  assert.equal(barOf(ed, { ready: false }).visible, false)
  assert.equal(barOf(ed, { file: null }).visible, false)
  assert.equal(barOf(ed, { loadingOverlayVisible: true }).visible, false)
})

test('光标落在表格 / 页眉里也照样显示（文件级指示器不该跟着光标消失）', () => {
  const script = read('../../src/components/LibreOfficeEditor.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
  // provInBody 是「光标不在正文段落里就整条收起」那道闸，只为光标条存在；
  // 留着它，律师把光标放进表格里这条文件级小条就会无故消失。
  assert.ok(!/provInBody/.test(script), '这道闸对文件级小条没有意义')
  assert.ok(!/provUnit\b/.test(script), '小条不再逐段跟光标（provUnits 是逐段侧栏的，照旧留着）')
  assert.ok(/provUnits/.test(script), '逐段 units 一律不动')
  assert.ok(!/updateProvenanceCursor|scheduleProvenanceCursor/.test(script),
    '光标取数只为那条小条存在，小条改成文件级之后不许留着每次光标移动都问一次引擎')
})

test('逐段溯源一律不动：侧栏「溯源」标签的数据还是逐段的 provRows', () => {
  const ed = editorWith({})
  const rows = [{ index: 0, text: '第一条', unit: named() }]
  const panel = ed.computed.provenanceForPanel.call({
    provLoaded: true, provRows: rows, provTruncated: false, provLoading: false, $t: t,
  })
  assert.equal(panel.rows, rows)
  assert.ok(panel.summary, '摘要还在')
  assert.equal(ed.computed.provenanceForPanel.call({ provLoaded: false }), null)
})

test('loadProvenance 把文件级两个字段落进 data，computing 那条路也带着', async () => {
  let reply = {
    data: {
      units: [{ key: 'p0', textHash: 'h' }], truncated: false, computing: false,
      versioned: true, dirty: true, fileVersion: named(),
    },
  }
  const ed = editorWith({ getProvenance: () => Promise.resolve(reply) })
  const ctx = {
    ready: true, file: { id: 5 }, projectId: 1, docLoadFailed: false,
    provLoading: false, provLoaded: false, provUnits: [], provTruncated: false,
    provVersioned: false, provDirty: false, provFileVersion: null,
    alignProvenanceRows: async () => {},
  }
  await ed.methods.loadProvenance.call(ctx)
  assert.equal(ctx.provVersioned, true)
  assert.equal(ctx.provDirty, true)
  assert.equal(ctx.provFileVersion.sha, 'abc1234def')
  assert.equal(ctx.provUnits.length, 1, '逐段 units 照旧')

  // 后端逐段回溯超预算（computing）时，小条那两个值必须已经在响应里了——
  // 后端刻意先算它们。空着的话律师要盯着一条空白小条等几十秒。
  reply = { data: { computing: true, versioned: true, dirty: false, fileVersion: named({ title: '核对注册资本' }) } }
  ctx.provFileVersion = null
  ctx.provDirty = true
  await ed.methods.loadProvenance.call(ctx)
  clearTimeout(ctx._provRetryTimer)
  assert.equal(ctx.provDirty, false)
  assert.equal(ctx.provFileVersion.title, '核对注册资本')
})

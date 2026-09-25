// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-69（C7 观察 2 + C4 观察）前半：「解析」功能在编辑器工具栏没有入口。
//
// 病灶：dev-board#794（feat(editor): add local inline review and explicit deep review）
// 顺手把工具栏「解析」按钮的模板整段删掉，理由（#883 提交信息）是当"死链接"清理——
// 其实那颗按钮是「依据」窗格唯一的常显入口，删掉之后只能靠顶栏右侧面板开关摸进
// 「依据」tab，真机两轮测试都没找到（FINAL-bugs-draft.md BUG-69）。
//
// 修法：工具栏右侧常显补回「解析」按钮，按下态跟 insightOpen；LibreOfficeEditor 新增
// onInsightToolbarToggle，派发 toggle-insight-panel（fileId），与原有 onToggleInsight
// （正文浮球/审校面板「查看依据」永远是"打开"）分开一条事件，互不影响。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const TOOLBAR_SRC = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
const TOOLBAR_TEMPLATE = TOOLBAR_SRC.slice(TOOLBAR_SRC.indexOf('<template>'), TOOLBAR_SRC.lastIndexOf('</template>'))
const EDITOR_SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')

test('工具栏渲染「解析」按钮：常显（不带 v-if 门控）、按下态跟 insightOpen、点击发 toggle-insight', () => {
  const m = TOOLBAR_TEMPLATE.match(
    /<view class="etb-btn wide" :class="\{ on: insightOpen \}" :title="\$t\('editor\.toolbar\.insightPanel'\)" @tap\.stop="\$emit\('toggle-insight'\)">/
  )
  assert.ok(m, '工具栏模板里找不到「解析」按钮——BUG-69 回归')
  // 紧跟着的文案用 insightShort（i18n key），不是硬编码「解析」两个字
  const tail = TOOLBAR_TEMPLATE.slice(m.index, m.index + 300)
  assert.match(tail, /\{\{\s*\$t\('editor\.toolbar\.insightShort'\)\s*\}\}/)
})

test('EditorToolbar 的 emits 与 props 都声明了 toggle-insight / insightOpen（不是模板里的孤儿绑定）', () => {
  assert.match(TOOLBAR_SRC, /emits:\s*\[[^\]]*'toggle-insight'[^\]]*\]/)
  assert.match(TOOLBAR_SRC, /insightOpen:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('LibreOfficeEditor 声明了 insightOpen prop 与 toggle-insight-panel emit，且工具栏标签接上了它们', () => {
  assert.match(EDITOR_SRC, /emits:\s*\[[^\]]*'toggle-insight-panel'[^\]]*\]/)
  assert.match(EDITOR_SRC, /insightOpen:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(EDITOR_SRC, /:insight-open="insightOpen"/)
  assert.match(EDITOR_SRC, /@toggle-insight="onInsightToolbarToggle"/)
})

function makeEditorVm(overrides) {
  const script = EDITOR_SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  // components: { ReviewPanel, EditorToolbar, EvidenceStaleBar } 在对象字面量里直接引用
  // 这三个导入标识符——剥了 import 之后要占位传进去，跟 editor-toolbar-comment-title.test.mjs
  // 传 bindHorizontalWheel 同一手法。
  // eslint-disable-next-line no-new-func
  const component = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar',
    script.replace('export default', 'return'))({}, {}, {})
  const emitted = []
  const base = Object.assign({
    $emit: (name, payload) => emitted.push([name, payload]),
    file: { id: 42 },
  }, overrides)
  const vm = Object.assign(base, component.methods)
  return { vm, emitted }
}

test('工具栏「解析」按钮点击（onInsightToolbarToggle）派发 toggle-insight-panel，带上当前文档 fileId', () => {
  const { vm, emitted } = makeEditorVm()
  vm.onInsightToolbarToggle()
  assert.deepEqual(emitted, [['toggle-insight-panel', { fileId: 42 }]])
})

test('原有 onToggleInsight（正文浮球/审校面板「查看依据」）不受影响，仍然只发 open-insight', () => {
  const { vm, emitted } = makeEditorVm()
  vm.onToggleInsight()
  assert.deepEqual(emitted, [['open-insight', { fileId: 42 }]])
})

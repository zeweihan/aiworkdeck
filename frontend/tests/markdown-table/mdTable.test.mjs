// MarkdownPreview.vue 的表格渲染用例（dev-board#467）：AI 助手面板里 6 列表格被面板的
// overflow:hidden 裁掉、且没有横向滚动条。锁的是两条真行为——
//   ① markdown-it 渲染出的每张 <table> 都被一层 <div class="md-table-scroll"> 直接包裹
//      （渲染器半边：证明 renderer.rules 真的接上了，不是样式表里孤零零挂了个类名）；
//   ② 非表格内容（标题/代码块）渲染形态不受影响。
// 同仓 tests/_lib/review-panel-vm.mjs / tests/insight/insightPane.test.mjs 的套路：把
// <script> 剥出来当普通对象跑，import 行整块删掉后用形参把依赖喂回去；markdown-it 是
// 真实现（表格渲染是 markdown-it 自己的行为，桩不出真值），getFileDownloadUrl /
// getAuthHeaders 与本用例无关，喂桩即可。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import MarkdownIt from 'markdown-it'

const SRC = readFileSync(new URL('../../src/components/MarkdownPreview.vue', import.meta.url), 'utf8')

function makeVm(content) {
  const deps = {
    MarkdownIt,
    getFileDownloadUrl: async () => '',
    getAuthHeaders: () => ({}),
  }
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  const component = new Function(...names, script.replace('export default', 'return'))(
    ...names.map((n) => deps[n]),
  )
  const vm = { $t: (k) => k, content, loadedContent: '', loading: false }
  Object.assign(vm, component.data.call(vm))
  vm.content = content
  for (const [k, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return vm
}

const TABLE_MD = `# 核查表

| 序号 | 核查事项 | 核查依据 | 核查方法 | 核查结论 | 补充核查建议 |
| --- | --- | --- | --- | --- | --- |
| 1 | 注册资本 | 5,000,000 元 | 核对工商登记 | 一致 | 无 |
| 2 | 成立日期 | 2024-05-01 | 核对营业执照 | 一致 | NECIPS |

上面是表格，下面是代码块：

\`\`\`js
const x = 1
\`\`\`
`

test('markdown-it 渲染出的每个 <table> 都被 <div class="md-table-scroll"> 直接包裹', () => {
  const html = makeVm(TABLE_MD).displayedHtml
  const tableCount = (html.match(/<table>/g) || []).length
  assert.equal(tableCount, 1, '用例本身要包含一张表格')
  // 每个 <table> 前紧邻 wrapper 开标签
  const openMatches = [...html.matchAll(/<div class="md-table-scroll"><table>/g)]
  assert.equal(openMatches.length, tableCount, `<table> 前必须紧邻 <div class="md-table-scroll">，实际输出：${html}`)
  // 每个 </table> 后紧邻 wrapper 闭标签
  const closeMatches = [...html.matchAll(/<\/table><\/div>/g)]
  assert.equal(closeMatches.length, tableCount, `</table> 后必须紧邻 </div>，实际输出：${html}`)
})

test('非表格内容渲染形态不受影响（标题、代码块照常）', () => {
  const html = makeVm(TABLE_MD).displayedHtml
  assert.match(html, /<h1>核查表<\/h1>/)
  assert.match(html, /<pre><code class="language-js">/)
})

test('多张表格：每张各自被单独包裹，不会串包', () => {
  const twoTables = `${TABLE_MD}\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n`
  const html = makeVm(twoTables).displayedHtml
  const tableCount = (html.match(/<table>/g) || []).length
  assert.equal(tableCount, 2)
  const openMatches = [...html.matchAll(/<div class="md-table-scroll"><table>/g)]
  assert.equal(openMatches.length, 2)
  const closeMatches = [...html.matchAll(/<\/table><\/div>/g)]
  assert.equal(closeMatches.length, 2)
})

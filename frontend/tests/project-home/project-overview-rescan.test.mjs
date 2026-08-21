// 稳定性审计复扫（dev-board#74）里落在工作台 project-overview.vue 上的三条结论。
// 组件带 @/ 别名、import 不进来，所以走本仓既有的两种写法：
// 能抠出来单跑的函数就真跑一遍（重命名竞态），模板接线只能做源码文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url),
  'utf8'
)
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

// 从起始位置抠出一段花括号配平的函数体（跳过字符串里的括号）
function braceBody(src, header) {
  const at = src.indexOf(header)
  assert.ok(at >= 0, `找不到 ${header}`)
  let i = src.indexOf('{', at)
  const start = i + 1
  let depth = 0
  let quote = null
  for (; i < src.length; i++) {
    const ch = src[i]
    if (quote) {
      if (ch === '\\') { i++; continue }
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue }
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return src.slice(start, i)
    }
  }
  throw new Error(`${header} 的花括号没配平`)
}

// ---------- 1. 项目重命名：blur 清空字段后不能把空名字写进标题 ----------
//
// uni-app H5 的 input 在 @confirm 之后会立刻 input.blur()（confirm-hold 默认 false），
// 模板上 @blur 绑的正是 cancelRenameProject——它同步把 renameProjectName 清成 ''。
// 所以每一次「敲回车确认重命名」都会走到：confirm 挂在 await 上 → blur 清字段 →
// await 回来再读 this.renameProjectName，写进标题的是空串（显示成「未命名项目」），
// 而服务端存的其实是正确的新名字。名字必须在 await 之前取下来。
function buildConfirmRename(renameProject, uni) {
  const body = braceBody(CODE, 'async confirmRenameProject()')
  // eslint-disable-next-line no-new-func
  return new Function('renameProject', 'uni', `return async function confirmRenameProject() {${body}}`)(
    renameProject,
    uni
  )
}

test('确认重命名后立刻 blur（uni 的 confirm 必然触发），标题仍写入正确的新名字', async () => {
  let resolveRename
  const renameProject = () => new Promise((r) => { resolveRename = r })
  const fn = buildConfirmRename(renameProject, { showToast() {} })
  const ctx = {
    projectId: 7,
    project: { name: '旧名字' },
    renameProjectName: '新名字',
    isRenamingProject: true,
    $t: (k) => k
  }
  const pending = fn.call(ctx)
  // input.blur() → cancelRenameProject()：同步清字段
  ctx.isRenamingProject = false
  ctx.renameProjectName = ''
  resolveRename({ code: 0 })
  await pending
  assert.equal(ctx.project.name, '新名字', 'await 回来重读 renameProjectName 会写进空串')
})

test('重命名请求带上的也是确认那一刻的名字', async () => {
  const seen = []
  const renameProject = (id, name) => { seen.push([id, name]); return Promise.resolve({ code: 0 }) }
  const fn = buildConfirmRename(renameProject, { showToast() {} })
  const ctx = { projectId: 7, project: {}, renameProjectName: '  带空格的名字  ', $t: (k) => k }
  await fn.call(ctx)
  assert.deepEqual(seen, [[7, '带空格的名字']])
})

// ---------- 2. 文件选择器：取消时必须清掉三个面板各自暂存的回调 ----------
//
// 脱敏 / 诉讼可视化 / EasyVoice 三个面板共用页面级的同一个 FilePickerDialog，
// 各自把 resume 回调存在页面字段上。用户点「取消」时没人清，下一次别的面板再开选择器，
// handleFilePickerConfirm 先撞上残留的旧回调就 return 了——新面板的回调永远不会被调用，
// 用户选了文件却什么都没发生。
test('FilePickerDialog 监听了 cancel', () => {
  const tpl = CODE.slice(CODE.indexOf('<FilePickerDialog'), CODE.indexOf('<FilePickerDialog') + 400)
  assert.match(tpl, /@cancel=/, '不听 cancel，退出选择器时三个回调字段全留在页面上')
})

test('取消处理清空三个面板的回调字段', () => {
  const body = braceBody(CODE, 'handleFilePickerCancel()')
  for (const field of ['desensitizeFileSelectCallback', 'litigationScopeCallback', 'easyVoiceImportCallback']) {
    assert.match(body, new RegExp(`this\\.${field}\\s*=\\s*null`), `${field} 没被清空`)
  }
})

// ---------- 3. 文档对比标签：DocDiffViewer 必须带 key ----------
//
// 两个对比标签（A vs B、C vs D）命中同一个 v-else-if 分支，没有 key 时 Vue 就地复用
// 同一个组件实例：props 换了，但 DocDiffViewer 只在 mounted() 里 loadDocuments()、
// 对 sourceId/targetId 没有 watch，Monaco 里画的还是上一对文档——标题说 C vs D，
// 正文是 A vs B。同分支里的 VersionCompareTab / 版本文本对比早就带 key 了。
test('两个窗格的对比标签 DocDiffViewer 都带 key', () => {
  for (const side of ['Left', 'Right']) {
    const marker = `isDiffTab(activeFile${side})`
    const at = CODE.indexOf(marker)
    assert.ok(at > 0, `找不到 ${marker}`)
    const tagStart = CODE.lastIndexOf('<DocDiffViewer', at)
    const tag = CODE.slice(tagStart, CODE.indexOf('/>', at))
    assert.match(tag, /:key=/, `${side} 窗格的对比标签没有 key，切换两个对比标签会复用旧实例`)
  }
})

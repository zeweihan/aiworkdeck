// 修订显示三态（dev-board#368）：全部修订 / 简洁标记（页边） / 最终稿。
//
// 覆盖三件事，都是「不跑真引擎也能红」的接线契约：
//   1) 白名单——新 action 不进 EDITOR_ACTIONS 会被 executor 静默拒绝（本领域的
//      经典哑火），所以既断言常量含它，也断言 executor 真把它发出去了。
//   2) 三态 → 派发的命令序列——点哪一项就发哪个 mode，且一律不标脏。
//   3) 换文档复位——worker 的 boot 与 load_document retarget 都必须走
//      resetRevisionView()，否则「上一份设了最终稿、下一份打开修订痕迹默默不见」。
//
// 真引擎侧的断言（属性真的写进去、页边真的生效）在 lowa-e2e 组 31。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { EDITOR_ACTIONS, createLibreOfficeExecutor } from '../../src/composables/libreofficeExecutorClient.js'

const WORKER_SRC = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')

// ---------- 白名单 ----------
test('EDITOR_ACTIONS 含 set_revision_view（不进白名单 = executor 静默拒绝）', () => {
  assert.ok(EDITOR_ACTIONS.includes('set_revision_view'))
})

test('executor 放行 set_revision_view 并原样把 mode 发给 worker', async () => {
  const sent = []
  let listener = null
  const port = {
    addEventListener: (_t, fn) => { listener = fn },
    start: () => {},
    postMessage: (m) => {
      sent.push(m)
      listener({ data: { cmd: 'result', reqId: m.reqId, result: { success: true, mode: m.params.mode } } })
    },
  }
  const ex = createLibreOfficeExecutor()
  ex.connect(port)

  const ok = await ex.executeCommand('set_revision_view', { mode: 'final' })
  assert.equal(ok.success, true)
  assert.deepEqual(sent.map((m) => [m.action, m.params.mode]), [['set_revision_view', 'final']])

  // 对照组：白名单外的名字必须在客户端就被挡下，一个字节都不发给 worker
  const no = await ex.executeCommand('set_revision_view_typo', { mode: 'final' })
  assert.equal(no.success, false)
  assert.match(no.message, /Unknown action/)
  assert.equal(sent.length, 1, '被拒的 action 不许发出去')
})

// ---------- 工具栏：三态 → 命令序列 ----------
function loadToolbar() {
  const src = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1]
  return new Function(script.replace('export default', 'return'))()
}

// engineView: worker 侧 get_ui_state 回的 view 段（= 引擎的真实读回）
function makeVm(engineView) {
  const component = loadToolbar()
  const calls = []
  const emitted = []
  const vm = {
    $t: (k) => k,
    $emit: (e, p) => emitted.push([e, p]),
    $refs: {},
    calls,
    emitted,
    executor: {
      executeCommand: (action, params) => {
        calls.push({ action, params })
        if (action === 'get_ui_state') {
          return Promise.resolve({ success: true, character: {}, paragraph: {}, selection: {}, view: engineView() })
        }
        return Promise.resolve({ success: true })
      },
    },
  }
  Object.assign(vm, component.data.call(vm), component.methods)
  for (const [k, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return vm
}

test('三态各自派发 set_revision_view，且一律不标脏（纯显示切换）', async () => {
  let mode = 'margin'
  const vm = makeVm(() => ({ revisionView: mode, revisionMarginSupported: true }))
  await vm.refresh()
  vm.calls.length = 0
  vm.emitted.length = 0

  for (const want of ['all', 'final', 'margin']) {
    mode = want
    await vm.pickRevisionView(want)
  }
  assert.deepEqual(
    vm.calls.map((c) => c.action + (c.params.mode ? ':' + c.params.mode : '')),
    ['set_revision_view:all', 'get_ui_state',
      'set_revision_view:final', 'get_ui_state',
      'set_revision_view:margin', 'get_ui_state'],
    '每次切换 = 一条 set_revision_view + 一次真实状态回读'
  )
  assert.equal(vm.emitted.filter((e) => e[0] === 'changed').length, 0, '显示切换不许标脏触发自动保存')
})

test('当前态跟引擎读回走，不是本地置位（引擎拒绝时高亮不许说谎）', async () => {
  // 引擎恒回 margin：模拟「请求 final 但引擎没生效」
  const vm = makeVm(() => ({ revisionView: 'margin', revisionMarginSupported: true }))
  await vm.pickRevisionView('final')
  assert.equal(vm.state.view.revisionView, 'margin')
  assert.equal(vm.revisionViewLabel, 'editor.toolbar.revisionViewMargin')
})

test('引擎不支持页边显示时退成两态（全部修订 / 最终稿）', async () => {
  const vm = makeVm(() => ({ revisionView: 'all', revisionMarginSupported: false }))
  await vm.refresh()
  assert.deepEqual(vm.revisionViewOptions.map((o) => o.k), ['all', 'final'])
})

test('文档类型不对（非 Writer，view 里没有该字段）时整个控件不渲染', async () => {
  const vm = makeVm(() => ({ recordChanges: true }))
  await vm.refresh()
  assert.equal(vm.state.view.revisionView, undefined, '模板的 v-if 判据')
  assert.equal(vm.revisionViewLabel, 'editor.toolbar.revisionView')
})

test('工具栏重挂（bootstrap）先清空状态，不端着上一份文档的读数', async () => {
  const vm = makeVm(() => ({ revisionView: 'final', revisionMarginSupported: true }))
  await vm.refresh()
  assert.equal(vm.state.view.revisionView, 'final')
  const seen = []
  const realRefresh = vm.refresh
  vm.refresh = function () { seen.push(this.state.view.revisionView); return realRefresh.call(this) }
  await vm.bootstrap()
  assert.deepEqual(seen, [undefined], 'bootstrap 必须先 EMPTY() 再去读引擎')
})

// ---------- worker：三态映射 + 换文档复位 ----------
test('revisionModeOf：两个布尔 → 三态名，读不回来时不猜「隐了」', () => {
  const src = WORKER_SRC.match(/function revisionModeOf\([\s\S]*?\n\}/)[0]
  const revisionModeOf = new Function(src + '; return revisionModeOf;')()
  assert.equal(revisionModeOf(true, false), 'all')
  assert.equal(revisionModeOf(true, true), 'margin')
  assert.equal(revisionModeOf(false, false), 'final')
  assert.equal(revisionModeOf(false, true), 'final', 'ShowChanges 关了就是最终稿，页边开关无关')
  assert.equal(revisionModeOf(null, null), 'all', '读不到时归到看得见的那一侧')
})

test('worker 的 boot 与 load_document retarget 都复位显示态', () => {
  const calls = (WORKER_SRC.match(/^\s*resetRevisionView\(\);$/gm) || []).length
  assert.equal(calls, 2, 'bootDoc 一处 + retarget 一处；少一处就会「上一份设了最终稿、下一份打开痕迹默默不见」')
  assert.match(WORKER_SRC, /function resetRevisionView\(\) \{ return applyRevisionView\(DEFAULT_REVISION_VIEW\); \}/)
  assert.match(WORKER_SRC, /const DEFAULT_REVISION_VIEW = 'margin';/,
    '默认 = 页边：AI 读到的正文要是「改后的样子」，匹配计数只数可见匹配（dev-board#369）')
  // retarget 里的那处必须在 isWriterDoc() 分支内（Calc/Impress 没有修订机制）
  const retarget = WORKER_SRC.match(/const retarget = \(loaded\) => \{[\s\S]*?\n    \};/)[0]
  assert.match(retarget, /if \(isWriterDoc\(\)\) \{[\s\S]*resetRevisionView\(\);/)
})

test('worker 的 EXEC 里有 set_revision_view，且三个态名与工具栏一字不差', () => {
  assert.match(WORKER_SRC, /\n {2}set_revision_view\(p\) \{/)
  assert.match(WORKER_SRC, /const REVISION_VIEWS = \['all', 'margin', 'final'\];/)
  const toolbar = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
  for (const k of ['all', 'margin', 'final']) assert.match(toolbar, new RegExp("k: '" + k + "'"))
})

test('export_document 走 withInlineMarkupForExport：导出期间强制内联，导完还原原态', () => {
  // 页边会把 docx 导错位（dev-board#367），最终稿可能把隐藏态写进文件——两种非默认
  // 显示态都必须在导出期间临时切成内联全显。这是导出保真的唯一闸。
  assert.match(WORKER_SRC, /withInlineMarkupForExport\(function \(\) \{ xModel\.storeToURL\('private:stream', props\); \}\);/)
  const fn = WORKER_SRC.match(/function withInlineMarkupForExport\(fn\) \{[\s\S]*?\n\}/)[0]
  assert.match(fn, /const before = revisionViewState\(\)\.mode/, '先记下用户所选的态')
  assert.match(fn, /if \(before === 'all'\) return fn\(\)/, '本来就是内联就零开销直通')
  assert.match(fn, /applyRevisionView\('all'\)/)
  assert.match(fn, /xModel\.refresh\(\)/, '只关页边不重排仍会错位（#367 探针 R2）')
  assert.match(fn, /finally \{\s*applyRevisionView\(before\)/, '导完必须还原用户所选的态')
})

test('__agent 命令按页边语义执行：内联态下临时切页边，跑完还原', () => {
  // AI 多轮改稿依赖「正文 = 改后的样子」与「只数可见匹配」。用户把视图切成内联后
  // 正文里混着被删的旧字——不兜住的话 AI 读到的就是错的（dev-board#369 的契约）。
  assert.match(WORKER_SRC, /p\.__agent \? runAgentCommandInMarginView\(action, function \(\) \{ return fn\(p\); \}\) : fn\(p\)/,
    'execCommand 必须把带 __agent 的命令套进守卫')
  const fn = WORKER_SRC.match(/function runAgentCommandInMarginView\(action, fn\) \{[\s\S]*?\n\}/)[0]
  assert.match(fn, /if \(AGENT_VIEW_EXEMPT\[action\] \|\| !isWriterDoc\(\)\) return fn\(\)/,
    '豁免名单 + 非 Writer 一个属性都不碰')
  assert.match(fn, /if \(before !== 'all'\) return fn\(\)/, '页边/最终稿态正文本来就不含删除文字，零开销直通')
  assert.match(fn, /applyRevisionView\('margin'\)/)
  assert.match(fn, /xModel\.refresh\(\)/, '切完要重排，否则读到的还是旧版面（#367 探针 R2 同源）')
  assert.match(fn, /out\.then\(function \(r\) \{ restore\(\); return r; \}, function \(e\) \{ restore\(\); throw e; \}\)/,
    '分批原语是 async，恢复要等它 settle')
  assert.match(fn, /catch \(e\) \{ restore\(\); throw e; \}/, '同步抛异常也要还原')
  assert.match(WORKER_SRC, /const AGENT_VIEW_EXEMPT = \{ set_revision_view: 1, export_document: 1, load_document: 1 \};/)
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// DOCX 比对稿主流程（dev-board#1120）。
//
// 两层各测各的：
// 1. utils/docxComparison.js 的纯编排（依赖全注入，node 直接 import）；
// 2. fileOpenTabs.js 的 onCompareDialogCancel + runDocxComparisonFlow +
//    onCompareDialogConfirm 切片（@/ 别名跑不进 node，按本目录既有方式切出来真跑，
//    runDocxComparison 用真实现，传输/引擎/落盘全 mock）。
// mock 只验编排与守卫，不冒充原生比对语义。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { isDocxDoc, runDocxComparison, sha256Hex } from '../../src/utils/docxComparison.js'

const T = (k, p) => (p ? `${k}:${JSON.stringify(p)}` : k)

test('sha256Hex 与 node:crypto 一致', async () => {
  const bytes = new Uint8Array([1, 2, 3, 4, 5])
  const expected = createHash('sha256').update(bytes).digest('hex')
  assert.equal(await sha256Hex(bytes), expected)
})

test('isDocxDoc：fileType 或扩展名；.doc 旧格式不算', () => {
  assert.ok(isDocxDoc({ id: 1, fileType: 'docx', name: 'x' }))
  assert.ok(isDocxDoc({ id: 1, name: 'A.DOCX' }))
  assert.ok(!isDocxDoc({ id: 1, fileType: 'doc', name: 'A.doc' }))
  assert.ok(!isDocxDoc({ id: 1, name: 'A.doc' }))
})

function makeHarness(overrides = {}) {
  const order = []
  const savedPayloads = []
  const bytesOf = (id) => new Uint8Array([id, 9, 9])
  const engine = {
    run: async (action, payload) => {
      order.push('run:' + action)
      if (action === 'build_comparison_document') {
        if (overrides.buildResult) return overrides.buildResult
        return { success: true }
      }
      if (action === 'export_document') {
        if (overrides.exportResult) return overrides.exportResult
        return { bytes: new Uint8Array([7, 7, 7]) }
      }
      throw new Error('unexpected action ' + action)
    }
  }
  const deps = {
    source: { id: 1, name: 'A.docx' },
    target: { id: 2, name: 'B.docx' },
    name: 'B（比对稿）.docx',
    authorName: '张三',
    t: T,
    onStage: (s) => order.push('stage:' + s),
    isCancelled: () => order.includes('cancel-flag'),
    fetchBytes: async (doc) => {
      order.push('fetch:' + doc.id)
      if (overrides.fetchError) throw new Error('network down')
      if (overrides.emptyBytes && doc.id === overrides.emptyBytes) return new Uint8Array(0)
      return bytesOf(doc.id)
    },
    recheckClean: () => order.push('recheck'),
    acquireEngine: () => {
      order.push('acquire')
      return overrides.engineNull ? null : engine
    },
    releaseEngine: () => order.push('release'),
    saveFile: async (payload) => {
      order.push('save')
      savedPayloads.push(payload)
      if (overrides.saveError) throw new Error(T('common.networkError'))
      return overrides.saveResult !== undefined ? overrides.saveResult : { id: 99, name: payload.name }
    }
  }
  return { order, savedPayloads, deps }
}

test('成功序：下载→sha→引擎 build/export→release（恰一次）→落盘，payload 带双方 id 与哈希', async () => {
  const { order, savedPayloads, deps } = makeHarness()
  const created = await runDocxComparison(deps)
  assert.equal(created.id, 99)
  assert.equal(savedPayloads.length, 1)
  const p = savedPayloads[0]
  assert.equal(p.baseFileId, 1)
  assert.equal(p.revisedFileId, 2)
  assert.equal(p.baseSha256, createHash('sha256').update(new Uint8Array([1, 9, 9])).digest('hex'))
  assert.equal(p.revisedSha256, createHash('sha256').update(new Uint8Array([2, 9, 9])).digest('hex'))
  assert.equal(p.name, 'B（比对稿）.docx')
  // release 在 save 之前、且只调一次
  assert.equal(order.filter((x) => x === 'release').length, 1)
  assert.ok(order.indexOf('release') < order.indexOf('save'))
  assert.deepEqual(
    order.filter((x) => x.startsWith('stage:')),
    ['stage:reading', 'stage:starting', 'stage:loading', 'stage:exporting', 'stage:saving'])
})

for (const [name, overrides, expectMsg] of [
  ['build success:false → failBuild', { buildResult: { success: false } }, /failBuild/],
  ['export 空字节 → failExport', { exportResult: { success: true, bytes: new Uint8Array(0) } }, /failExport/],
  ['export success:false → failExport', { exportResult: { success: false } }, /failExport/],
  ['下载空字节 → failEmpty', { emptyBytes: 1 }, /failEmpty/],
  ['下载抛错 → 不碰引擎', { fetchError: true }, /network down/],
]) {
  test(`编排失败：${name}——不落盘、已借的引擎要还`, async () => {
    const { order, savedPayloads, deps } = makeHarness(overrides)
    await assert.rejects(() => runDocxComparison(deps), expectMsg)
    assert.equal(savedPayloads.length, 0)
    // 下载阶段（fetchError / 空字节）根本不该借引擎；借了（build/export 失败）就必须还
    if (overrides.fetchError || overrides.emptyBytes) assert.ok(!order.includes('acquire'))
    else assert.ok(order.includes('release'))
  })
}

test('引擎拿不到（null）→ failEngine，不落盘', async () => {
  const { order, savedPayloads, deps } = makeHarness({ engineNull: true })
  await assert.rejects(() => runDocxComparison(deps), /failEngine/)
  assert.equal(savedPayloads.length, 0)
})

test('生成后取消 → cancelled 错误（不弹 toast），引擎已还、不落盘', async () => {
  const { order, savedPayloads, deps } = makeHarness()
  deps.acquireEngine = () => { order.push('acquire'); order.push('cancel-flag'); return { run: async () => ({ success: true }) } }
  await assert.rejects(
    () => runDocxComparison(deps),
    (e) => e.cancelled === true)
  assert.equal(savedPayloads.length, 0)
  assert.ok(order.includes('release'))
})

test('落盘返回无 id → failSave', async () => {
  const { savedPayloads, deps } = makeHarness({ saveResult: { name: 'x' } })
  await assert.rejects(() => runDocxComparison(deps), /failSave/)
  assert.equal(savedPayloads.length, 1)
})

// ==================== fileOpenTabs.js 切片：flow + confirm 接线 ====================

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')

const sliceFrom = SRC.indexOf('    // 取消比对：')
const sliceEnd = SRC.indexOf('    openDiffTab(source, target) {', sliceFrom)
assert.ok(sliceFrom > 0 && sliceEnd > sliceFrom, '应能切出 cancel+flow+confirm 三方法')

function makeMocks() {
  const calls = []
  const toasts = []
  const uni = { showToast: (o) => toasts.push(o) }
  const bytesOf = (id) => new Uint8Array([id, 1])
  const state = { saveShouldFail: false, onBuild: null }
  const api = {
    fetchProjectFileBytes: async (id) => { calls.push('fetch:' + id); return bytesOf(id) },
    createComparisonFile: async (projectId, payload) => {
      calls.push('save:' + projectId + ':' + payload.baseFileId + '-' + payload.revisedFileId)
      if (state.saveShouldFail) throw new Error(T('common.networkError'))
      if (state.onSave) state.onSave()
      return { id: 99, name: payload.name }
    }
  }
  const engine = {
    run: async (action) => {
      calls.push('run:' + action)
      if (action === 'build_comparison_document') {
        if (state.onBuild) state.onBuild()
        return { success: true }
      }
      return { bytes: new Uint8Array([5, 5]) }
    }
  }
  return { calls, toasts, uni, api, engine, state }
}

function makeSlice(uni, api) {
  return new Function(
    'uni', 'isDocxDoc', 'runDocxComparison', 'fetchProjectFileBytes', 'createComparisonFile',
    'return {' + SRC.slice(sliceFrom, sliceEnd) + '}')(
    uni, isDocxDoc, runDocxComparison, api.fetchProjectFileBytes, api.createComparisonFile)
}

const A = { id: 1, name: 'A.docx' }
const B = { id: 2, name: 'B.docx' }

function makeVm(m, docs = [A, B], methods = null) {
  const vm = {
    events: [],
    projectId: 7,
    compareDocuments: docs,
    showCompareDialog: true,
    compareSaving: false,
    compareStage: '',
    _compareSessionSeq: 0,
    _libreRefs: {},
    currentUser: { displayName: '李四', username: 'u_private' },
    canWriteProject: true,
    libreOfficePreferred: true,
    $t: T,
    $refs: { fileTree: { loadFiles: () => { vm.events.push('loadFiles'); return Promise.resolve() } } },
    acquireLibreHiddenInstance() { vm.events.push('acquire'); return m.engine },
    releaseLibreHiddenInstance() { vm.events.push('release') },
    openFile(f) { vm.events.push('openFile:' + f.id) },
    openDiffTab(s, t) { vm.events.push(['diff', s.id, t.id]) }
  }
  if (methods) {
    vm.runDocxComparisonFlow = methods.runDocxComparisonFlow
    vm.onCompareDialogCancel = methods.onCompareDialogCancel
  }
  return vm
}

test('成功：一次落盘，release 在 loadFiles/openFile 之前，对话框关闭、stage 清空', async () => {
  const m = makeMocks()
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 1)
  assert.equal(m.calls[0], 'fetch:1')
  assert.ok(vm.events.includes('release'))
  assert.ok(vm.events.indexOf('release') < vm.events.indexOf('loadFiles'))
  assert.ok(vm.events.indexOf('release') < vm.events.indexOf('openFile:99'))
  assert.deepEqual(vm.events.filter((e) => typeof e === 'string' && e.startsWith('openFile')), ['openFile:99'])
  assert.equal(vm.showCompareDialog, false)
  assert.equal(vm.compareStage, '')
  assert.equal(vm.compareSaving, false)
  assert.equal(m.toasts.length, 0)
})

test('只读项目：拒绝且不借引擎、不下载', async () => {
  const m = makeMocks()
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  vm.canWriteProject = false
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.calls.length, 0)
  assert.equal(vm.showCompareDialog, true, '对话框留着')
  assert.match(m.toasts[0].title, /compareNoWritePermission/)
})

test('落盘网络错：toast、对话框留着可重试、引擎已还、stage 清空', async () => {
  const m = makeMocks()
  m.state.saveShouldFail = true
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.ok(vm.events.includes('release'))
  assert.equal(vm.showCompareDialog, true)
  assert.equal(vm.compareStage, '')
  assert.equal(vm.compareSaving, false)
  assert.match(m.toasts[0].title, /networkError/)
})

test('生成期间源再次变脏：不落盘，提示 failChanged', async () => {
  const m = makeMocks()
  m.state.onBuild = () => { vm._libreRefs['left:1'] = { ready: true, docLoadFailed: false, file: A, dirty: true } }
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 0)
  assert.equal(vm.showCompareDialog, true)
  assert.match(m.toasts[0].title, /failChanged/)
  assert.ok(vm.events.includes('release'))
})

test('比对中取消：不落盘、无 toast、可重开同组再比', async () => {
  const m = makeMocks()
  m.state.onBuild = () => { methods.onCompareDialogCancel.call(vm) }
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 0)
  assert.equal(m.toasts.length, 0, '取消不是失败，不弹错误')
  assert.equal(vm.showCompareDialog, false)
  assert.ok(vm.events.includes('release'))
  // 重开同组（新数组、新会话）可以再比一次
  m.state.onBuild = null; m.state.saveShouldFail = false
  const docs2 = [{ id: 1, name: 'A.docx' }, { id: 2, name: 'B.docx' }]
  vm.compareDocuments = docs2
  vm.showCompareDialog = true
  const A2 = docs2[0], B2 = docs2[1]
  await methods.onCompareDialogConfirm.call(vm, { source: A2, target: B2 })
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 1)
  assert.equal(vm.showCompareDialog, false)
})

test('落盘在途切项目：产物已存在但不在新项目开件/刷新', async () => {
  const m = makeMocks()
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  m.state.onSave = () => { vm.projectId = 8 }
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 1, '后端已提交，产物合法存在')
  assert.ok(!vm.events.some((e) => typeof e === 'string' && e.startsWith('openFile')), '不在新项目开件')
  assert.ok(!vm.events.includes('loadFiles'))
})

test('保存/比对全程 compareSaving 单飞：重复确认不重复生成', async () => {
  const m = makeMocks()
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A, B], methods)
  m.state.onBuild = () => {} // 同步 build，无需闸门
  const p1 = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  const p2 = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await Promise.all([p1, p2])
  assert.equal(m.calls.filter((c) => c.startsWith('save:')).length, 1)
})

for (const kind of ['array', 'arraybuffer']) {
  test(`导出 ${kind} 归一为真实二进制，不变成逗号文本`, async () => {
    const bytes = kind === 'array' ? [80,75,3,4] : Uint8Array.from([80,75,3,4]).buffer
    const { deps, savedPayloads } = makeHarness({ exportResult: { success: true, bytes } })
    await runDocxComparison(deps)
    assert.deepEqual(savedPayloads[0].bytes, Uint8Array.from([80,75,3,4]))
  })
}

test('下载第一份后取消，不下载第二份也不启动引擎', async () => {
  const { deps, order } = makeHarness()
  const fetch = deps.fetchBytes
  deps.fetchBytes = async (doc) => { const bytes = await fetch(doc); order.push('cancel-flag'); return bytes }
  await assert.rejects(runDocxComparison(deps), e => e.cancelled)
  assert.equal(order.filter(x => x.startsWith('fetch:')).length, 1)
  assert.ok(!order.includes('acquire'))
})

test('作者使用显示名，不把登录username写入永久修订', async () => {
  const m = makeMocks()
  let author
  const run = m.engine.run
  m.engine.run = (action, payload) => { if (action === 'build_comparison_document') author = payload.authorName; return run(action, payload) }
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A,B], methods)
  await methods.onCompareDialogConfirm.call(vm, { source:A,target:B })
  assert.equal(author, '李四')
})

for (const change of ['unmount', 'same-pair-reopen', 'inactive']) {
  test(`引擎生成期间 ${change} 不落盘、释放实例`, async () => {
    const m = makeMocks()
    const methods = makeSlice(m.uni, m.api)
    const vm = makeVm(m, [A,B], methods)
    m.state.onBuild = () => {
      if (change === 'unmount') vm._comparisonDisposed = true
      if (change === 'same-pair-reopen') { vm.onCompareDialogCancel(); vm.showCompareDialog = true }
      if (change === 'inactive') vm.isActiveOverviewInstance = () => false
    }
    await methods.onCompareDialogConfirm.call(vm, {source:A,target:B})
    assert.ok(vm.events.includes('release'))
    assert.equal(m.calls.filter(x => x.startsWith('save:')).length, 0)
    assert.equal(m.toasts.length, 0)
  })
}

test('flush期间取消并重开相同两文件，旧确认不可套入新会话', async () => {
  const m = makeMocks()
  const methods = makeSlice(m.uni, m.api)
  const vm = makeVm(m, [A,B], methods)
  const editor = {file:A,ready:true,dirty:true,flushSave:async()=>{
    vm.onCompareDialogCancel(); vm.showCompareDialog=true; editor.dirty=false; return true
  }}
  vm._libreRefs = {'left:1':editor}
  await methods.onCompareDialogConfirm.call(vm,{source:A,target:B})
  assert.deepEqual(m.calls, [])
})

for (const action of ['build_comparison_document', 'export_document']) {
  for (const thrown of [false, true]) {
    test(`${action} ${thrown ? '抛出' : '返回'}超时：专门提示、释放一次且不保存`, async () => {
      const { deps, order, savedPayloads } = makeHarness()
      const acquire = deps.acquireEngine
      deps.acquireEngine = () => {
        const handle = acquire(), run = handle.run
        handle.run = async (name, payload) => {
          if (name !== action) return run(name, payload)
          const timeout = { success: false, code: 'EDITOR_RESULT_TIMEOUT', message: 'retry original' }
          if (thrown) throw timeout
          return timeout
        }
        return handle
      }
      await assert.rejects(runDocxComparison(deps), /editor.compare.failTimeout/)
      assert.equal(order.filter(x => x === 'release').length, 1)
      assert.equal(savedPayloads.length, 0)
    })
  }
}

test('超时文案到达对话框，隐藏引擎释放且无产物', async () => {
  const m = makeMocks(), methods = makeSlice(m.uni, m.api), vm = makeVm(m, [A, B], methods)
  m.engine.run = async () => ({ success: false, code: 'EDITOR_RESULT_TIMEOUT' })
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(m.toasts[0].title, 'editor.compare.failTimeout')
  assert.equal(vm.events.filter(x => x === 'release').length, 1)
  assert.equal(m.calls.filter(x => x.startsWith('save:')).length, 0)
  assert.equal(vm.compareSaving, false)
  assert.equal(vm._cancelDocxComparison, null)
})

function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

test('pending build取消立即释放；旧流程收尾不释放新引擎或清空新状态', async () => {
  const m = makeMocks(), methods = makeSlice(m.uni, m.api), vm = makeVm(m, [A, B], methods)
  const oldStarted = deferred(), oldBuild = deferred(), newStarted = deferred(), newBuild = deferred()
  const released = []
  const engine = (name, started, build) => ({ name, run: async (action) => {
    if (action === 'build_comparison_document') { started.resolve(); return build.promise }
    return { success: true, bytes: new Uint8Array([8]) }
  } })
  const handles = [engine('old', oldStarted, oldBuild), engine('new', newStarted, newBuild)]
  vm.acquireLibreHiddenInstance = () => handles.shift()
  vm.releaseLibreHiddenInstance = handle => released.push(handle.name)
  const first = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await oldStarted.promise
  methods.onCompareDialogCancel.call(vm)
  assert.deepEqual(released, ['old'], 'pending engine is released before its command settles')
  assert.equal(vm.compareSaving, false, 'new confirmation need not wait for old RPC timeout')
  assert.equal(m.calls.filter(x => x.startsWith('save:')).length, 0)
  vm.compareDocuments = [A, B]; vm.showCompareDialog = true
  const second = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await newStarted.promise
  const newCancel = vm._cancelDocxComparison
  oldBuild.resolve({ success: true }); await first
  assert.deepEqual(released, ['old'], 'old finally neither releases twice nor releases new engine')
  assert.equal(vm._cancelDocxComparison, newCancel)
  assert.equal(vm.compareSaving, true)
  assert.equal(vm.compareStage, 'loading')
  newBuild.resolve({ success: true }); await second
  assert.deepEqual(released, ['old', 'new'])
  assert.equal(m.calls.filter(x => x.startsWith('save:')).length, 1)
  assert.equal(vm._cancelDocxComparison, null)
  assert.equal(vm.compareSaving, false)
})

test('pending export取消立即释放且不保存', async () => {
  const m = makeMocks(), methods = makeSlice(m.uni, m.api), vm = makeVm(m, [A, B], methods)
  const started = deferred(), exported = deferred()
  m.engine.run = async action => {
    if (action === 'build_comparison_document') return { success: true }
    started.resolve(); return exported.promise
  }
  const pending = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await started.promise; vm.onCompareDialogCancel()
  assert.equal(vm.events.filter(x => x === 'release').length, 1)
  exported.resolve({ success: true, bytes: new Uint8Array([8]) }); await pending
  assert.equal(vm.events.filter(x => x === 'release').length, 1)
  assert.equal(m.calls.filter(x => x.startsWith('save:')).length, 0)
})

test('取得引擎前取消：迟到handle立即释放，不运行命令', async () => {
  const m = makeMocks(), methods = makeSlice(m.uni, m.api), vm = makeVm(m, [A, B], methods)
  const started = deferred(), acquired = deferred()
  vm.acquireLibreHiddenInstance = () => { started.resolve(); return acquired.promise }
  const pending = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await started.promise; vm.onCompareDialogCancel(); acquired.resolve(m.engine); await pending
  assert.equal(vm.events.filter(x => x === 'release').length, 1)
  assert.ok(!m.calls.some(x => x.startsWith('run:') || x.startsWith('save:')))
})

test('保存stage仍不可取消，产物正常打开', async () => {
  const m = makeMocks(), methods = makeSlice(m.uni, m.api), vm = makeVm(m, [A, B], methods)
  m.state.onSave = () => {
    const seq = vm._compareSessionSeq
    vm.onCompareDialogCancel()
    assert.equal(vm._compareSessionSeq, seq)
    assert.equal(vm.compareSaving, true)
    assert.equal(vm.showCompareDialog, true)
  }
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.ok(vm.events.includes('openFile:99'))
})


test('comparison scopes unlimited wait to its two commands and forwards only current known native stages', async () => {
  const { deps, order } = makeHarness()
  const calls = []
  let progress
  deps.acquireEngine = () => ({ run: async (action, params, opts) => {
    calls.push({ action, opts })
    if (action === 'build_comparison_document') {
      progress = opts.onProgress
      progress({ stage: 'normalizing' }); progress({ stage: 'comparing' }); progress({ stage: 'unknown' })
      return { success: true }
    }
    return { success: true, bytes: [8] }
  } })
  await runDocxComparison(deps)
  assert.deepEqual(calls.map(c => [c.action, c.opts.waitForCompletion]), [
    ['build_comparison_document', true], ['export_document', true],
  ])
  assert.deepEqual(order.filter(s => s.startsWith('stage:')), [
    'stage:reading', 'stage:starting', 'stage:loading', 'stage:normalizing', 'stage:comparing', 'stage:exporting', 'stage:saving',
  ])
  const length = order.length
  deps.isCancelled = () => true
  progress({ stage: 'loading' })
  assert.equal(order.length, length, 'cancelled task must not update a subsequent task')
})


for (const code of ['EDITOR_ENGINE_FAILED', 'EDITOR_DISPOSED']) test(code + ' releases comparison and reports engine failure without saving', async () => {
  const { deps, order, savedPayloads } = makeHarness({ buildResult: { success: false, code } })
  await assert.rejects(runDocxComparison(deps), /failEngineStopped/)
  assert.equal(order.filter(x => x === 'release').length, 1)
  assert.equal(savedPayloads.length, 0)
})

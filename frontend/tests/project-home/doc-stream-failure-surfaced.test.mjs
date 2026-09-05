// dev-board#465：AI「新建文档并写入」失败，用户什么都看不到——文件建好了但正文为空，
// 对话气泡永远停在「正在向文档流式写入内容…」，前后端谁都没报错。
//
// 病灶（前端这半边）在 agentClientActions.js：
//   1. flushDocStreamBuffer 在编辑器指针没就绪 / 目标文档对不上时静默 return，
//      既不重排重试也不记原因，缓冲就烂在内存里；
//   2. stream_insert 的返回值被整个丢掉——worker 的失败一律 resolve
//      （libreofficeExecutorClient 的 entry.resolve(d.result)），try/catch 看不见；
//   3. handleDocStreamEnd 从不检查缓冲有没有真的落地，也没有任何向上报告的通道。
//
// 修法：落字被挡住时保留缓冲并按 300ms 重试、记下原因；读 stream_insert 返回值；
// handleDocStreamEnd 返回一句失败原因（null = 确实写进去了），由 useAgentStream 摆到对话里。
//
// agentClientActions.js 带 @/ 别名 import，本仓一贯限制是这类文件 import 不进来
// （见 audit-b2-source-assertions.test.mjs 文件头）；用 new Function 抠出方法表求值。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

function loadMethods() {
  // 与 doc-open-sync-reentrancy 的套路相同，只是保留 shouldFlushDocStream（本用例要真跑它）
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/^export function /m, 'function ')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  // dev-board#460 之后方法表还引用 docEvents.js 的三个导出（剥掉 import 行后要手工喂回去）
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(async () => {}, async () => null, createSerialQueue, { showToast: () => {}, $emit: () => {} },
    'awd:doc-mutated', 0, () => true)
}

const METHODS = loadMethods()

function makeVm(overrides = {}) {
  const vm = {
    projectId: 1,
    libreOfficeActive: true,
    libreOfficeExecutor: { executeCommand: async () => ({ success: true }) },
    _docStreamTargetFileId: 'file-A',
    resolveLibreExecutorFileId: () => 'file-A',
    $t: (k) => k,
    ...overrides,
  }
  for (const [k, fn] of Object.entries(METHODS)) vm[k] = fn.bind(vm)
  return vm
}

// 150ms/300ms/250ms 的等待在测试里没意义，全部短路成 0
function withFastTimers(fn) {
  const real = globalThis.setTimeout
  globalThis.setTimeout = (cb, ms, ...args) => real(cb, 0, ...args)
  return Promise.resolve().then(fn).finally(() => { globalThis.setTimeout = real })
}

test('worker 报失败（success:false）必须被看见并作为原因报出来', async () => {
  await withFastTimers(async () => {
    const vm = makeVm({
      libreOfficeExecutor: {
        executeCommand: async (cmd) => cmd === 'stream_insert'
          ? { success: false, error: '当前文档不是 Writer 文档' }
          : { success: true },
      },
    })
    vm.handleDocStreamData('# 标题\n正文\n')
    await new Promise(r => setTimeout(r, 30))
    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: true })
    assert.equal(reason, '当前文档不是 Writer 文档',
      'stream_insert 的失败必须变成一句能摆进对话的原因，不能被丢掉')
  })
})

test('stream_insert 抛错也要报出来', async () => {
  await withFastTimers(async () => {
    const vm = makeVm({
      libreOfficeExecutor: {
        executeCommand: async (cmd) => {
          if (cmd === 'stream_insert') throw new Error('worker 已关闭')
          return { success: true }
        },
      },
    })
    vm.handleDocStreamData('正文\n')
    await new Promise(r => setTimeout(r, 30))
    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: true })
    assert.equal(reason, 'worker 已关闭')
  })
})

test('编辑器还没就绪：缓冲必须留着并重试，就绪后补写；不许静默丢字', async () => {
  await withFastTimers(async () => {
    const written = []
    const vm = makeVm({
      libreOfficeActive: false,
      libreOfficeExecutor: null,
    })
    vm.handleDocStreamData('第一章\n')
    await new Promise(r => setTimeout(r, 30))
    assert.equal(vm._docStreamBuffer, '第一章\n', '编辑器没就绪时缓冲必须原样留着')

    // 编辑器就绪
    vm.libreOfficeActive = true
    vm.libreOfficeExecutor = {
      executeCommand: async (cmd, args) => { written.push([cmd, args && args.text]); return { success: true } },
    }
    await new Promise(r => setTimeout(r, 60))
    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: true })
    assert.deepEqual(written.filter(w => w[0] === 'stream_insert').map(w => w[1]), ['第一章\n'],
      '编辑器就绪后必须把攒下的内容补写进去')
    assert.equal(reason, null, '内容确实写进去了就不该报错')
  })
})

test('CRITICAL：目标文档对不上时仍然不许落字，但要留住缓冲并报出原因', async () => {
  await withFastTimers(async () => {
    const written = []
    const vm = makeVm({
      resolveLibreExecutorFileId: () => 'file-B', // 用户切到了别的文档
      libreOfficeExecutor: {
        executeCommand: async (cmd, args) => { written.push([cmd, args && args.text]); return { success: true } },
      },
    })
    vm.handleDocStreamData('第一章\n')
    await new Promise(r => setTimeout(r, 40))
    assert.deepEqual(written.filter(w => w[0] === 'stream_insert'), [],
      '绝不许把 AI 的内容写进无关文档（dev-board#74 的红线不变）')
    assert.equal(vm._docStreamBuffer, '第一章\n', '缓冲必须留着')

    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: true })
    assert.ok(reason && reason.includes('不是本次写入的目标文档'), '必须报出原因，实际：' + reason)
  })
})

test('后端说这一轮一个字都没送出去（wrote:false）时必须报失败', async () => {
  await withFastTimers(async () => {
    const vm = makeVm()
    // 只有标签之间漏出的空白到达过（模型把正文包进了 <artifact> 之类被过滤的标签）
    vm.handleDocStreamData('\n\n')
    await new Promise(r => setTimeout(r, 30))
    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: false })
    assert.ok(reason && reason.includes('没有向文档输出任何正文'), '实际：' + reason)
  })
})

test('一切正常时不报错（不能出现假警报）', async () => {
  await withFastTimers(async () => {
    const vm = makeVm()
    vm.handleDocStreamData('# 标题\n正文\n')
    await new Promise(r => setTimeout(r, 30))
    const reason = await vm.handleDocStreamEnd({ status: 'finished', wrote: true })
    assert.equal(reason, null)
  })
})

// 这一条钉的是本次修复途中差点踩空的坑：失败原因不能只靠 handleClientAction 的返回值往回传，
// 中间隔着 ChatInterface 的 emit('client-action')，emit 恒返回 undefined。
test('失败原因必须经 action.report 回调回传（emit 桥不传返回值）', async () => {
  await withFastTimers(async () => {
    const reported = []
    const vm = makeVm({
      libreOfficeExecutor: {
        executeCommand: async (cmd) => cmd === 'stream_insert'
          ? { success: false, error: 'worker 拒绝落字' }
          : { success: true },
      },
    })
    vm.handleDocStreamData('正文\n')
    await new Promise(r => setTimeout(r, 30))
    // 模拟 useAgentStream 的调用方式：只给 report，不看返回值
    await vm.handleClientAction({
      action: 'doc_stream_end',
      payload: { status: 'finished', wrote: true },
      report: (reason) => reported.push(reason),
    })
    await new Promise(r => setTimeout(r, 10))
    assert.deepEqual(reported, ['worker 拒绝落字'], 'report 回调必须被调用，实际：' + JSON.stringify(reported))
  })
})

test('doc_stream_end 分发必须把结果交回调用方（useAgentStream 靠它摆进对话）', () => {
  const src = SRC.replace(/^\s*\/\/.*$/gm, '')
  const idx = src.indexOf("action.action === 'doc_stream_end'")
  assert.ok(idx > 0, '找不到 doc_stream_end 分发分支')
  const branch = src.slice(idx, idx + 400)
  assert.match(branch, /this\.handleDocStreamEnd\(/, '必须调用 handleDocStreamEnd')
  assert.match(branch, /action\.report/, '必须支持 report 回调（emit 桥不传返回值）')
})

test('useAgentStream 收到失败原因后必须换掉「正在写入」占位符', () => {
  const ui = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
  const idx = ui.indexOf("evt === 'doc_stream_end'")
  assert.ok(idx > 0)
  const branch = ui.slice(idx, idx + 2200)
  assert.match(branch, /docStreamFailedNotice/, '必须有一条用户看得见的失败提示')
  assert.match(branch, /report: surfaceDocStreamFailure/,
    '必须把 report 回调交下去，光靠返回值传不回来（中间隔着 emit）')
  assert.match(branch, /isEditorStreaming = false/,
    '必须解掉 isEditorStreaming，否则提示会被 appendText 继续吞掉')
  assert.match(branch, /docStreamingPlaceholder/, '占位符要被换掉，不能停在「正在写入」')
})

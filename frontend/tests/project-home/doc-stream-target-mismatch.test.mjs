// 审计（dev-board#74）CRITICAL：AI 流式起草写进当前聚焦的文档，而不是它打开的那个。
//
// 病灶：_docStreamBuffer（doc_stream_data 的本地缓冲）与它该写进哪个文档之间没有
// 任何绑定；flushDocStreamBuffer 落字时用的是 this.libreOfficeExecutor，而
// librePool.js 的 syncLibreExecutor 会把这个指针重指到"当前活动文件"——AI 还在
// 给文件 A 生成内容时，用户切一次 tab（或 A 被 LRU 淘汰），下一次 150ms flush
// 就会把 A 的内容悄悄写进用户现在看着的、完全无关的文档 B，且没有任何错误提示。
//
// 修法：doc_open_file_sync 建立流式会话时把目标 fileId 记进 _docStreamTargetFileId；
// flushDocStreamBuffer 落字前用 shouldFlushDocStream 比对"目标文件"与"executor 现在
// 实际服务的文件"（resolveLibreExecutorFileId 反查），不一致就不写。
//
// 这里能用纯函数真跑的部分（shouldFlushDocStream 的判定逻辑）直接执行验证；
// 完整链路（syncLibreExecutor 重指、150ms 定时器）涉及 uni-app 组件与真实
// LibreOffice 执行器，只能源码文本核实"确实接上了"，端到端要人工走查
// （见交付说明）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// agentClientActions.js 带 @/ 别名 import（services/api.js、utils/asyncSerialize.js），
// 本仓 node:test 用例的一贯限制是这类文件 import 不进来（见 audit-b2-source-assertions
// 的文件头注释）。shouldFlushDocStream 是纯函数、零外部依赖，直接从源码文本里抠出来
// new Function 求值，比整份 import 稳妥。
function loadShouldFlushDocStream() {
  const src = read('pages/project-overview/agentClientActions.js')
  const start = src.indexOf('export function shouldFlushDocStream')
  assert.ok(start > 0, '找不到 shouldFlushDocStream 的导出')
  const braceStart = src.indexOf('{', start)
  let depth = 0
  let i = braceStart
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  const fnSrc = src.slice(start, i).replace(/^export /, '')
  return new Function('return (' + fnSrc + ')')()
}

const shouldFlushDocStream = loadShouldFlushDocStream()

// ---- 1. 纯函数：shouldFlushDocStream 的判定本身 ----

test('目标文件与 executor 当前服务的文件一致时允许落字', () => {
  assert.equal(shouldFlushDocStream('file-1', 'file-1'), true)
  // fileId 在不同来源（params 回显 vs 后端记录）里可能是 number 也可能是 string，
  // 判定必须做字符串归一比较，不能因为类型不同而误判成不一致
  assert.equal(shouldFlushDocStream(1, '1'), true)
  assert.equal(shouldFlushDocStream('1', 1), true)
})

test('CRITICAL：目标文件与当前 executor 服务的文件不一致时必须拒绝落字', () => {
  assert.equal(shouldFlushDocStream('file-A', 'file-B'), false, '用户切到了别的文档，不许把流式内容写进去')
})

test('executor 反查不到任何文件（已被换掉/未注册）时必须拒绝落字，不能当成"随便写"', () => {
  assert.equal(shouldFlushDocStream('file-A', null), false)
})

test('目标文件从未建立（理论上不会发生，流式协议恒先 open_sync）时放行，不收紧既有行为', () => {
  assert.equal(shouldFlushDocStream(null, 'file-B'), true)
  assert.equal(shouldFlushDocStream(undefined, 'file-B'), true)
})

// ---- 2. 接线核实：flush 前确实调用了这个判定，且比对失败时不执行 stream_insert ----

// dev-board#465 起，这道比对从 flushDocStreamBuffer 里抽成了 docStreamBlockReason()
//（同一处判定还要服务"被挡住时保留缓冲并重试"），本用例随之跟到新落点，判定本身一字未改。
test('落字前必须调用 shouldFlushDocStream 比对，不一致时不执行 stream_insert', () => {
  const src = stripComments(read('pages/project-overview/agentClientActions.js'))
  const guardStart = src.indexOf('docStreamBlockReason()')
  assert.ok(guardStart > 0, '找不到 docStreamBlockReason')
  const guardBody = src.slice(guardStart, guardStart + 800)

  assert.match(guardBody, /resolveLibreExecutorFileId\(this\.libreOfficeExecutor\)/,
    '必须反查 executor 此刻实际绑定的 fileId')
  assert.match(guardBody, /shouldFlushDocStream\(this\._docStreamTargetFileId,\s*currentFileId\)/,
    '必须用目标 fileId 与当前 fileId 调用判定函数')

  const start = src.indexOf('async flushDocStreamBuffer()')
  assert.ok(start > 0, '找不到 flushDocStreamBuffer')
  const end = src.indexOf('async handleDocStreamEnd', start)
  const body = src.slice(start, end > 0 ? end : start + 3000)

  const guardIdx = body.indexOf('this.docStreamBlockReason()')
  const insertIdx = body.indexOf("executeCommand('stream_insert'")
  assert.ok(guardIdx > 0 && insertIdx > guardIdx,
    '比对必须排在真正调用 stream_insert 之前，不能查完还是无条件写')

  // 被挡住的分支必须 return，不能落到 busy=true / stream_insert；
  // 缓冲要留着重试（#465），但绝不许写进错的文档（#74）
  const blockedBranch = body.slice(body.indexOf('if (blocked)'), insertIdx)
  assert.match(blockedBranch, /return/, '被挡住的分支必须提前 return')
  assert.ok(!blockedBranch.includes("executeCommand('stream_insert'"),
    '被挡住的分支里不许出现落字')
})

test('doc_open_file_sync 建立流式会话时必须把目标 fileId 记下来，供 flush 前比对', () => {
  const src = stripComments(read('pages/project-overview/agentClientActions.js'))
  const start = src.indexOf('async _handleEditorOpenFileSyncImpl(action)')
  assert.ok(start > 0, '找不到 _handleEditorOpenFileSyncImpl')
  const body = src.slice(start, start + 3000)
  assert.match(body, /this\._docStreamTargetFileId\s*=\s*file\.id/,
    '第 5 步重置缓冲时必须绑定这条流式会话的目标文件')
  // 必须排在重置缓冲之后（同一批状态复位），在返回成功结果之前
  const resetIdx = body.indexOf('this._docStreamBuffer = ')
  const bindIdx = body.indexOf('this._docStreamTargetFileId = file.id')
  const ackIdx = body.indexOf('sendEditorResult(conversationId, requestId, true')
  assert.ok(resetIdx > 0 && bindIdx > resetIdx && bindIdx < ackIdx,
    '绑定必须在缓冲复位之后、成功回执之前完成')
})

test('librePool.js 提供 resolveLibreExecutorFileId：按对象恒等反查 executor 绑定的 fileId', () => {
  const src = stripComments(read('pages/project-overview/librePool.js'))
  const start = src.indexOf('resolveLibreExecutorFileId(executor)')
  assert.ok(start > 0, '找不到 resolveLibreExecutorFileId')
  const body = src.slice(start, start + 500)
  assert.match(body, /getLibreExecutorMap\(\)/, '必须查同一份 pane:fileId → executor 注册表')
  assert.match(body, /map\[k\] === executor/, '必须按对象恒等匹配，不能猜 key')
})

// ---- 3. 真跑一遍 resolveLibreExecutorFileId 本身（不依赖 Vue，纯对象操作） ----

test('resolveLibreExecutorFileId 真实行为：反查命中/不命中', () => {
  const execA = { name: 'execA' }
  const execB = { name: 'execB' }
  const vm = {
    _libreExecMap: { 'left:file-A': execA, 'right:file-B': execB },
    getLibreExecutorMap() { return this._libreExecMap },
  }
  // 直接从源码里取出这一个方法真跑（不依赖其余 librePoolMethods）
  const src = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')
  const bodyMatch = src.match(/resolveLibreExecutorFileId\(executor\) \{([\s\S]*?)\n    \},/)
  assert.ok(bodyMatch, '提取不到 resolveLibreExecutorFileId 方法体')
  const fn = new Function('executor', bodyMatch[1])
  assert.equal(fn.call(vm, execA), 'file-A')
  assert.equal(fn.call(vm, execB), 'file-B')
  assert.equal(fn.call(vm, { name: 'unregistered' }), null, '没注册过的 executor 必须返回 null，不能瞎猜')
  assert.equal(fn.call(vm, null), null)
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#539：久置/失焦回来打开文档是空白页 + 红胶囊「文档加载失败」。
// 这份用例守两条纯判据 + 一条传输层预算：
//   ① classifyLoadFailure —— 三种完全不同的事故不许再混成同一句话；
//   ② shouldSelfHealLoadFailure —— 只对 relay 超时自愈，且只自愈一次；
//   ③ createRelayExecutor 的 callOpts.timeoutMs —— 探活要 3s 就真的是 3s，
//      不能被 ACTION_BUDGET_MS 的「只抬高不降低」语义抬回 30s（抬回去了，
//      备胎探活就从「几毫秒判死」退化成「等半分钟再判死」，等于没做）。

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  classifyLoadFailure, isRelayTimeout, shouldSelfHealLoadFailure,
  STATUS_FILE_MISSING, STATUS_DOWNLOAD_FAILED, STATUS_LOAD_FAILED,
} from '../../src/utils/editorLoadFailure.js'
import { createRelayExecutor, PROBE_ACTION, PROBE_BUDGET_MS } from '../../src/composables/zetaOfficeRelay.js'

// ---- ① 失败原因分流 --------------------------------------------------------

test('后端 404/410（文件被外部挪走）→ 文件已不在磁盘上', () => {
  // fetchArrayBuffer 的原始 reject 串就是 'HTTP <status>'
  assert.equal(classifyLoadFailure(new Error('HTTP 404')), STATUS_FILE_MISSING)
  assert.equal(classifyLoadFailure('HTTP 410'), STATUS_FILE_MISSING)
})

test('下载超时 / 网络错 / 其它 HTTP 失败 → 下载失败（提示查网络）', () => {
  assert.equal(classifyLoadFailure(new Error('下载超时 / download timed out')), STATUS_DOWNLOAD_FAILED)
  assert.equal(classifyLoadFailure(new Error('网络错误 / network error')), STATUS_DOWNLOAD_FAILED)
  assert.equal(classifyLoadFailure('HTTP 500'), STATUS_DOWNLOAD_FAILED)
  assert.equal(classifyLoadFailure('HTTP 401'), STATUS_DOWNLOAD_FAILED)
})

test('引擎侧失败（含 relay 超时、0 字节守卫、被取代）→ 沿用 loadFailed', () => {
  assert.equal(classifyLoadFailure('LibreOffice relay timeout: load_document'), STATUS_LOAD_FAILED)
  assert.equal(classifyLoadFailure('load_document returned no success'), STATUS_LOAD_FAILED)
  assert.equal(classifyLoadFailure('文件非空（4096 bytes）但下载到 0 字节，拒绝按空白文档打开'), STATUS_LOAD_FAILED)
  assert.equal(classifyLoadFailure('装载已被更晚的一次尝试取代 / load superseded'), STATUS_LOAD_FAILED)
})

test('空/异常输入不许抛，落回 loadFailed', () => {
  for (const bad of [null, undefined, '', 0, {}, new Error()]) {
    assert.equal(classifyLoadFailure(bad), STATUS_LOAD_FAILED)
  }
})

test('三个 statusKey 都以 Failed 结尾——组件里四处 endsWith(\'Failed\') 判据靠它成立', () => {
  for (const k of [STATUS_FILE_MISSING, STATUS_DOWNLOAD_FAILED, STATUS_LOAD_FAILED]) {
    assert.ok(k.endsWith('Failed'), k)
  }
})

// ---- ② relay 超时自愈只重试一次 --------------------------------------------

test('relay 超时才自愈：404 / 网络错重启引擎毫无用处', () => {
  assert.equal(isRelayTimeout('LibreOffice relay timeout: load_document'), true)
  assert.equal(shouldSelfHealLoadFailure('LibreOffice relay timeout: load_document', false), true)
  assert.equal(shouldSelfHealLoadFailure(new Error('HTTP 404'), false), false)
  assert.equal(shouldSelfHealLoadFailure(new Error('下载超时 / download timed out'), false), false)
  assert.equal(shouldSelfHealLoadFailure('load_document returned no success', false), false)
})

test('已经自愈过一次就不再自愈——否则连续超时会无限重启引擎', () => {
  assert.equal(shouldSelfHealLoadFailure('LibreOffice relay timeout: load_document', true), false)
  // 第二次也超时 → 落 loadFailed 让用户看见，而不是继续烧 CPU
  assert.equal(classifyLoadFailure('LibreOffice relay timeout: load_document'), STATUS_LOAD_FAILED)
})

test('export 等其它命令的 relay 超时同样认得（判据不绑 load_document 一个 action）', () => {
  assert.equal(isRelayTimeout('LibreOffice relay timeout: export_document'), true)
})

// ---- ③ 探活预算真的短 ------------------------------------------------------

test('探活用最便宜的只读命令，预算 3s', () => {
  assert.equal(PROBE_ACTION, 'get_ui_state')
  assert.equal(PROBE_BUDGET_MS, 3000)
})

test('callOpts.timeoutMs 覆盖默认预算（死掉的 guest 3s 判死，不等 30s）', async () => {
  const sent = []
  const relay = createRelayExecutor({
    send: (m) => sent.push(m),          // 黑洞：模拟 guest 已经死了，永远不回结果
    subscribe: () => () => {},
  })
  const t0 = Date.now()
  const r = await relay.executeCommand(PROBE_ACTION, {}, { timeoutMs: 60 })
  const dt = Date.now() - t0
  assert.equal(r.success, false)
  assert.match(r.message, /relay timeout/)
  assert.ok(dt < 2000, '必须按 callOpts.timeoutMs 超时，实际等了 ' + dt + 'ms')
  assert.equal(sent.length, 1)
  assert.equal(sent[0].action, PROBE_ACTION)
})

test('不传 callOpts 时预算表语义不变（load_document 仍是 180s 下限）', async () => {
  let handler = null
  const sent = []
  const relay = createRelayExecutor({
    send: (m) => sent.push(m),
    subscribe: (h) => { handler = h; return () => {} },
  })
  let settled = false
  const p = relay.executeCommand('load_document', {}).then((r) => { settled = true; return r })
  await new Promise((r) => setTimeout(r, 50))
  assert.equal(settled, false, 'load_document 不得因为新加的 timeoutMs 分支缩短预算')
  // 喂一条结果把 180s 的定时器清掉，否则测试进程要挂到超时才退出
  handler({ __lo: 'lo-relay', type: 'result', reqId: sent[0].reqId, result: { success: true } })
  assert.equal((await p).success, true)
})

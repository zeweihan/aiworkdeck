// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 建连与 POST /chat 并行（dev-board#812 C-04）。
//
// 病灶：后端每轮收尾会主动关流（endRunAndDrain），而 connectSSE 只在
// `sseAbortController && isConnected` 时短路——上一轮关流后 isConnected 已是 false，
// 于是**第二条及以后的每一条消息**都要先付一次完整的建连往返（鉴权 +
// canUseConversation 查库 + getRecoverySnapshot + 重发待办/任务），而且它整段串在
// 「按下发送 → 首字」这个用户最敏感的时刻里。
//
// 与本目录既有用例同口径：useAgentStream.js 带 @/ 别名与 uni 全局，node 直接 import
// 不进来，做源码级契约断言——但断言的是**顺序**（下标比较）而不是「某个字符串在不在」，
// 否则改回 `await connectSSE(...)` 照样能过。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

/** sendMessage 函数体：从声明起按花括号配平截取。 */
function sendMessageBody(code) {
  const start = code.indexOf('const sendMessage = async (')
  if (start < 0) return null
  const braceAt = code.indexOf('{', code.indexOf('=>', start))
  let depth = 0
  for (let i = braceAt; i < code.length; i++) {
    if (code[i] === '{') depth++
    else if (code[i] === '}') { depth--; if (depth === 0) return code.slice(start, i + 1) }
  }
  return null
}

const BODY = sendMessageBody(CODE)

test('sendMessage 里建连不再挡在 POST /chat 前面', () => {
  assert.ok(BODY, '找不到 sendMessage 函数体')
  assert.doesNotMatch(BODY, /await\s+connectSSE\(/,
    '建连一旦被 await，每条消息都要先付一次完整的建连往返')
  assert.match(BODY, /const\s+connectPromise\s*=\s*connectSSE\(/,
    '建连要先发起、拿着 promise 往下走')
})

test('建连发起在 POST 之前，await 在 POST 之后（真正并行，不是换个写法串行）', () => {
  const kickoff = BODY.indexOf('const connectPromise = connectSSE(')
  const post = BODY.indexOf('/api/agent/chat')
  const settle = BODY.indexOf('await connectPromise')

  assert.ok(kickoff >= 0, '没有发起建连')
  assert.ok(post >= 0, '没有找到 POST /api/agent/chat')
  assert.ok(settle >= 0, '建连的结果必须仍然被等待，否则建连失败会变成永久等待')

  assert.ok(kickoff < post, '建连要与 POST 同时在途，所以发起必须排在 POST 之前')
  assert.ok(post < settle,
    'await 必须排在 POST 之后——排在前面就等于又串行了一遍，这条卡白做')
})

test('在途的建连 promise 先挂空 catch，避免 unhandledrejection', () => {
  const kickoff = BODY.indexOf('const connectPromise = connectSSE(')
  const settle = BODY.indexOf('await connectPromise')
  const guard = BODY.indexOf('connectPromise.catch(')
  assert.ok(guard > kickoff && guard < settle,
    '从发起到 await 之间有一段空窗，建连此时失败会直接打到控制台')
})

test('建连失败仍然走原来那条错误处置（catch 里的收尾一个没少）', () => {
  // await connectPromise 在 try 块内：失败照旧被同一个 catch 接住，
  // isStreaming 归零与错误气泡的行为与改造前一致。
  const settle = BODY.indexOf('await connectPromise')
  const catchAt = BODY.indexOf('} catch (err) {')
  assert.ok(catchAt > settle,
    'await connectPromise 必须在 try 块内，否则建连失败会绕过错误处置、界面永久卡在等待')
})

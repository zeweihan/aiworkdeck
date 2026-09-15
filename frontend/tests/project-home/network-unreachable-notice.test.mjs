// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#602：断网时 AI 对话只会甩出一句英文原文（UnknownHostException 的 getMessage()
// 常常就是一个主机名），用户看不出「是网断了」。后端在终态 error 载荷上补了
// AI_NETWORK_UNREACHABLE 标记（LlmErrorClassifier.NETWORK_UNREACHABLE_MARKER），
// 前端要像 AI_REGION_BLOCKED 那样把它换成一句中文引导。
//
// useAgentStream.js 带 @/ 别名与 uni 全局，node 直接 import 不进来（同目录既有用例同口径）。
// 但纯正则断言证明不了「这个载荷最终摆进气泡的是哪句文案」，所以把 error 分支的那段
// if/else 链原样抠出来在 new Function 里真跑一遍。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/agentStream.js'
import en from '../../src/locales/en-US/agentStream.js'

const SRC = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

// 从 `const errMsg = dataStr || "Unknown Error"` 之后的 if 链起，按花括号配平截取
// （遇到 `else` 继续往下吃，整条链才是一个完整语句）。
function errorBranch(code) {
  const anchor = code.indexOf('const errMsg = dataStr')
  assert.ok(anchor >= 0, '找不到 error 分支的锚点，useAgentStream 的错误处置被改过了')
  const start = code.indexOf('if (errMsg.includes(', anchor)
  assert.ok(start >= 0, '找不到 error 载荷的标记判定链')
  let i = start
  while (i < code.length) {
    let depth = 0
    let closed = -1
    for (let j = i; j < code.length; j++) {
      if (code[j] === '{') depth++
      else if (code[j] === '}') { depth--; if (depth === 0) { closed = j; break } }
    }
    assert.ok(closed > 0, '截取 if 链时花括号没配平')
    const rest = code.slice(closed + 1).trimStart()
    if (rest.startsWith('else')) { i = closed + 1; continue }
    return code.slice(anchor, closed + 1)
  }
  throw new Error('unreachable')
}

const BRANCH = errorBranch(SRC)

// 真跑这段分支：t() 原样返回 key，于是断言的是「命中了哪条文案」，而不是文案内容本身
function renderError(dataStr) {
  const bubble = { value: { content: '' } }
  const run = new Function('dataStr', 'currentAssistantBubble', 't', BRANCH)
  run(dataStr, bubble, (k) => k)
  return bubble.value.content
}

test('断网标记 → 「网络连接异常」引导，而不是把英文主机名甩给用户', () => {
  // 后端实际形态：finishWithError 的载荷前面还拼着「Stream Error: 」
  const content = renderError('Stream Error: AI_NETWORK_UNREACHABLE: openrouter.ai')
  assert.match(content, /agentStream\.networkUnreachableNotice/,
    'AI_NETWORK_UNREACHABLE 必须映射到专门的网络异常文案')
  assert.doesNotMatch(content, /executionInterrupted/,
    '不能退回到「执行中断: <英文原文>」——那正是本卡要消灭的观感')
})

test('防空断言：没有标记的错误仍走通用「执行中断」，既有四条标记不被改坏', () => {
  assert.match(renderError('Stream Error: boom'), /executionInterrupted/,
    '未分类错误仍要显示原文，否则新分支等于把所有错误都说成网络问题')
  assert.match(renderError('Stream Error: AI_REGION_BLOCKED: ...'), /regionBlockedNotice/)
  assert.match(renderError('Stream Error: AI_QUOTA_EXHAUSTED: ...'), /quotaExhaustedNotice/)
  assert.match(renderError('Stream Error: AI_CONTEXT_OVERFLOW: ...'), /contextOverflowNotice/)
  assert.match(renderError('Stream Error: AI_INTERNAL_ERROR: ...'), /internalErrorNotice/)
})

test('中英双语文案都在，且措辞说的是「检查网络」', () => {
  assert.ok(zh.networkUnreachableNotice, 'zh-CN/agentStream.js 缺 networkUnreachableNotice')
  assert.ok(en.networkUnreachableNotice, 'en-US/agentStream.js 缺 networkUnreachableNotice')
  assert.match(zh.networkUnreachableNotice, /网络/)
  assert.match(en.networkUnreachableNotice, /[Nn]etwork/)
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审计（dev-board#74）：PPT 配置弹窗的取消/开始两条确认气泡形状不全。
// 源码文本断言——本仓既有 node:test 用例的一贯写法（组件带 @/ 别名，import 不进来）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// 原始病灶是 RootBubble 对这三个字段裸解引用、少一个就渲染时抛 TypeError。dev-board#646
// 之后渲染走 chatTimeline.visibleChatTimeline，那条兜底分支全程 ?. / || []，缺字段不再抛。
// 用例仍然守这三个字段：这两条系统确认气泡要和解析器建出来的气泡长得一模一样，写侧
// （useAgentStream 的 flushContent 是 bubble.thinking.content += text、bubble.artifacts.push）
// 至今假定字段已存在，形状漂掉就会在别处炸，而这里是最便宜的契约点。
const REQUIRED = ['thinking', 'processes', 'artifacts']

// 取出函数体里 bubbles.value.push({ ... }) 的那个对象字面量（括号配平地截）
const pushedBubbleOf = (src, fnName) => {
  const start = src.indexOf('const ' + fnName)
  assert.ok(start > 0, '找不到 ' + fnName)
  const open = src.indexOf('bubbles.value.push({', start)
  assert.ok(open > 0, fnName + ' 里找不到 bubbles.value.push({')
  let depth = 0
  let i = open + 'bubbles.value.push('.length
  const from = i
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) break }
  }
  return src.slice(from, i + 1)
}

for (const fn of ['cancelPptConfig', 'confirmPptGeneration']) {
  test(`${fn} 推的助手气泡带齐 RootBubble 必需字段`, () => {
    const src = stripComments(read('components/ChatInterface.vue'))
    const literal = pushedBubbleOf(src, fn)
    assert.match(literal, /role:\s*'ASSISTANT'/, fn + ' 推的应该是助手气泡')
    for (const key of REQUIRED) {
      assert.match(literal, new RegExp('\\b' + key + '\\s*:'),
        `${fn} 推的气泡缺 ${key}，RootBubble 渲染时会抛 TypeError，这条确认消息会整条消失`)
    }
    assert.match(literal, /thinking:\s*\{[^}]*status:/,
      fn + ' 的 thinking 必须是带 status 的对象')
  })
}

// 审计（dev-board#74）：PPT 配置弹窗的取消/开始两条确认气泡形状不全。
// 源码文本断言——本仓既有 node:test 用例的一贯写法（组件带 @/ 别名，import 不进来）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// RootBubble 对助手气泡的这三个字段是**裸解引用**（没有 ?. 也没有默认值）：
// 模板首行读 bubble.thinking.status，isReady / hasContent 读 processes.length、
// artifacts.length。少一个字段这条气泡就在渲染时抛 TypeError——Vue 3 会捕获并
// 换成一个空注释节点，用户那句「已取消」/「开始生成」于是无声消失。
const REQUIRED = ['thinking', 'processes', 'artifacts']

test('RootBubble 确实裸解引用 thinking/processes/artifacts（本用例的前提）', () => {
  const src = read('components/AgentMessage/RootBubble.vue')
  assert.match(src, /bubble\.thinking\.status/, 'thinking 已改成可空解引用，本用例前提失效')
  assert.match(src, /bubble\.processes\.length/, 'processes 已改成可空解引用，本用例前提失效')
  assert.match(src, /bubble\.artifacts\.length/, 'artifacts 已改成可空解引用，本用例前提失效')
})

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

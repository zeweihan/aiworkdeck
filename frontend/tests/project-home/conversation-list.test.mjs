import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { visibleCode } from './_visible-text.mjs'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ConversationList.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = visibleCode(SRC, stripComments)

test('e2e 锚点：根节点类名是 conversation-list', () => {
  assert.ok(SRC.includes('class="conversation-list"'))
})

test('props 契约', () => {
  assert.match(SRC, /conversations:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(SRC, /hasMore:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('emits 已声明（check:emits 依赖这个），且 open 带 conversationId', () => {
  assert.match(SRC, /emits:\s*\['open',\s*'load-more'\]/)
  assert.match(SRC, /\$emit\('open',\s*c\.conversationId\)/)
  assert.match(SRC, /\$emit\('load-more'\)/)
})

test('不做第三套清洗：不剥标签、不截字数', () => {
  assert.ok(!CODE.includes('.replace('), '不许再剥一次标签')
  assert.ok(!CODE.includes('substr'), '不许再截一次字数')
  assert.ok(!/\.slice\(0,\s*\d+\)/.test(SRC), '不许再截一次字数')
  assert.ok(!CODE.includes('thinking'), '服务端已剥过 thinking 标签')
})

test('空预览兜底走 hasConversationPreview，不留空行', () => {
  assert.match(SRC, /import\s*\{[^}]*hasConversationPreview[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.match(SRC, /v-if="hasPreview\(c\)"/)
})

test('不内嵌 ChatInterface（loadHistoryChat 会抢占当前会话）', () => {
  assert.ok(!CODE.includes('ChatInterface'))
  assert.ok(!CODE.includes('loadHistoryChat'))
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})

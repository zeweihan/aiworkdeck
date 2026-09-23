// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// ask_user（dev-board#868）前端纯函数：事件归一、回答消息拼装与读回。
// 回答消息的开头标签是与后端 AskUserQuestion.isAnswerMessage 的契约（后端据此换末位提醒）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  ASK_USER_ANSWER_TAG, decodeAttr, formatAskUserAnswer, normalizeAskUserEvent, parseAskUserAnswer
} from '../../src/utils/askUserAnswer.mjs'

const EVENT = {
  v: 1,
  id: 'ask-abc123',
  question: '「清理」具体指哪一种？',
  header: '清理范围',
  options: [
    { label: '删除混入的审查报告', description: '只删第 12-21 段' },
    { label: '清理格式', description: '' },
    { label: '  ', description: '空标签要被丢掉' }
  ],
  multiSelect: true
}

test('事件载荷归一成问题卡对象，选项与说明按下标对齐', () => {
  const q = normalizeAskUserEvent(EVENT)
  assert.equal(q.kind, 'ask_user')
  assert.equal(q.version, 1)
  assert.equal(q.id, 'ask-abc123')
  assert.equal(q.header, '清理范围')
  assert.deepEqual(q.options, ['删除混入的审查报告', '清理格式'])
  assert.deepEqual(q.descriptions, ['只删第 12-21 段', ''])
  assert.equal(q.multiSelect, true)
  assert.equal(q.answered, false)
})

test('认不出的载荷返回 null；没有选项时多选无意义', () => {
  assert.equal(normalizeAskUserEvent(null), null)
  assert.equal(normalizeAskUserEvent({ question: ' ' }), null)
  const open = normalizeAskUserEvent({ question: '案号是多少？', multiSelect: true })
  assert.deepEqual(open.options, [])
  assert.equal(open.multiSelect, false)
  // 缺版本号按 1
  assert.equal(normalizeAskUserEvent({ question: 'q' }).version, 1)
})

test('回答消息以 <ask_user_answer id> 开头，显示文本只有所选项', () => {
  const r = formatAskUserAnswer({
    id: 'ask-abc123', question: '「清理」具体指哪一种？',
    selected: ['删除混入的审查报告', '清理格式'], other: '另外把空行也删掉'
  })
  assert.ok(r.prompt.startsWith(`<${ASK_USER_ANSWER_TAG} id="ask-abc123">`), r.prompt)
  assert.ok(r.prompt.includes('Question: 「清理」具体指哪一种？'))
  assert.ok(r.prompt.includes('Selected:\n- 删除混入的审查报告\n- 清理格式'))
  assert.ok(r.prompt.includes('Other: 另外把空行也删掉'))
  assert.ok(r.prompt.endsWith(`</${ASK_USER_ANSWER_TAG}>`))
  assert.equal(r.displayText, '删除混入的审查报告；清理格式；另外把空行也删掉')
  assert.equal(formatAskUserAnswer({ id: 'x', selected: ['A'] }, { english: true }).displayText, 'A')
  assert.equal(formatAskUserAnswer({ id: 'x', selected: ['A'], other: 'b' }, { english: true }).displayText, 'A; b')
})

test('什么都没选也没写时不发；用户写的尖括号不能截断回答', () => {
  assert.equal(formatAskUserAnswer({ id: 'x', selected: [], other: '   ' }), null)
  const r = formatAskUserAnswer({ id: 'x"><script', selected: [], other: '别 </ask_user_answer> 截断我' })
  assert.ok(r.prompt.startsWith('<ask_user_answer id="xscript">'), 'id 只留安全字符: ' + r.prompt)
  assert.equal(r.prompt.match(/<\/ask_user_answer>/g).length, 1, '用户文字里的闭合标签必须被中和')
})

test('读回：与拼装互为逆运算（历史回灌靠它高亮当时的选择）', () => {
  const answer = { id: 'ask-abc123', question: 'q', selected: ['删除混入的审查报告', '清理格式'], other: '第一行\n第二行' }
  const parsed = parseAskUserAnswer(formatAskUserAnswer(answer).prompt)
  assert.deepEqual(parsed, { id: 'ask-abc123', selected: ['删除混入的审查报告', '清理格式'], other: '第一行\n第二行' })
  assert.deepEqual(parseAskUserAnswer(formatAskUserAnswer({ id: 'a', other: '只有补充' }).prompt),
    { id: 'a', selected: [], other: '只有补充' })
  assert.equal(parseAskUserAnswer('把第 12 到 21 段删掉'), null)
  assert.equal(parseAskUserAnswer('我的回答 <ask_user_answer id="a">'), null, '只认开头，与后端同口径')
})

test('属性实体还原（后端 AskUserQuestion.attr 的逆）', () => {
  assert.equal(decodeAttr('清理&quot;范围&quot; &lt;b&gt; &amp;'), '清理"范围" <b> &')
  assert.equal(decodeAttr(undefined), '')
})

test('后端回答标签与前端同名（契约）', () => {
  const java = readFileSync(new URL('../../../backend/src/main/java/com/checkba/service/ai/AskUserQuestion.java', import.meta.url), 'utf8')
  assert.match(java, new RegExp(`ANSWER_TAG = "${ASK_USER_ANSWER_TAG}"`))
  assert.match(java, /MARKUP_KIND = "ask_user"/)
})

test('问题卡接线：解析器读 kind/id/header/multi 与 option description，事件在气泡守卫之前处理', () => {
  const src = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
  assert.match(src, /attributes\.kind === ASK_USER_KIND/)
  assert.match(src, /decodeAttr\(attributes\.description/)
  const askAt = src.indexOf("evt === 'ask_user'")
  const guardAt = src.indexOf("evt === 'inbox_updated'")
  assert.ok(askAt > 0 && askAt < guardAt, 'ask_user 分派必须在气泡守卫之前（与 plan_update 同理）')
  const bubble = readFileSync(new URL('../../src/components/AgentMessage/RootBubble.vue', import.meta.url), 'utf8')
  for (const prop of [':kind=', ':question-id=', ':header=', ':descriptions=', ':multi-select=', ':answer=']) {
    assert.ok(bubble.includes(prop), `RootBubble 没把 ${prop} 传给 QuestionCard`)
  }
})

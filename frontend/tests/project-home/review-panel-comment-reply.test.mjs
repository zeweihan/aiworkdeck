// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-37（C4 观察 6）：审阅面板的批注卡片只有「标记解决」，没有回复入口。
//
// 引擎已有 reply_comment 原语（office_thread.js：在父批注同一锚点上用
// .uno:InsertAnnotation 追加一条带「回复 X：」前缀的批注，AI 的 doc_reply_comment 就走它）。
// 面板补一个「回复」：卡片上展开输入框，发送即调 reply_comment，并带 asUser 让引擎按
// 当前登录用户署名（不带时仍署 AI WorkDeck，AI 工具面不受影响）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'
import { loadWorkerFunctions } from '../lowa-unit/_workerFns.mjs'

const SRC = readFileSync(new URL('../../src/components/ReviewPanel.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.slice(SRC.indexOf('<template>'), SRC.lastIndexOf('</template>'))

const COMMENTS = [{ index: 0, id: 'c0', content: '这句要改', author: '律师乙', resolved: false, paraKey: 1, start: 0, end: 2 }]

function makeEngine() {
  const calls = []
  return {
    calls,
    async executeCommand(action, params) {
      calls.push({ action, params })
      await Promise.resolve()
      if (action === 'list_revisions') return { success: true, revisions: [] }
      if (action === 'list_comments') return { success: true, comments: COMMENTS, documentSeq: 2, revision: 5 }
      if (action === 'reply_comment') return { success: true, id: 'c1' }
      return { success: true }
    },
  }
}

test('批注卡片上有「回复」入口', () => {
  const cmtBlock = TEMPLATE.slice(TEMPLATE.indexOf(`v-else-if="tab === 'cmt'"`), TEMPLATE.indexOf(`v-else-if="tab === 'prov'"`))
  assert.ok(cmtBlock.length > 100, '找不到批注页模板块')
  assert.match(cmtBlock, /@tap\.stop="startReply\(c\)"[^>]*>\{\{ \$t\('editor\.review\.reply'\) \}\}/, '批注卡片没有回复按钮')
  assert.match(cmtBlock, /@tap\.stop="submitReply\(c\)"/, '回复框没有发送')
  // uni 的 textarea 默认 maxlength=140，回复长一点就被静默截断
  assert.match(cmtBlock, /<textarea[^>]*:maxlength="-1"/, '回复输入框要解除 140 字默认上限')
})

test('发送回复：调 reply_comment，带父批注 id、正文与 asUser，成功后收起并重读', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine)
  await vm.reload()
  const c = vm.commentRows[0]
  vm.startReply(c)
  assert.equal(vm.replyingId, 'c0')
  vm.replyText = '  同意，已改  '
  engine.calls.length = 0
  await vm.submitReply(c)
  const call = engine.calls.find((x) => x.action === 'reply_comment')
  assert.ok(call, '没有发 reply_comment')
  assert.equal(call.params.id, 'c0')
  assert.equal(call.params.text, '同意，已改')
  assert.equal(call.params.asUser, true, '用户在面板里回复，要按用户署名，不能署 AI WorkDeck')
  assert.equal(vm.replyingId, null, '发送成功后收起回复框')
  assert.ok(engine.calls.some((x) => x.action === 'list_comments'), '回复后要重读批注清单')
})

test('空回复不发命令', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine)
  await vm.reload()
  const c = vm.commentRows[0]
  vm.startReply(c)
  vm.replyText = '   '
  engine.calls.length = 0
  await vm.submitReply(c)
  assert.equal(engine.calls.filter((x) => x.action === 'reply_comment').length, 0)
})

test('引擎署名：asUser 按登录用户署名；AI 工具面（不带 asUser 或带 __agent）仍署 AI WorkDeck', () => {
  const { replyCommentAuthor } = loadWorkerFunctions(['replyCommentAuthor'])
  assert.equal(replyCommentAuthor({ asUser: true }, '韩律师', 'AI WorkDeck'), '韩律师')
  assert.equal(replyCommentAuthor({}, '韩律师', 'AI WorkDeck'), 'AI WorkDeck')
  assert.equal(replyCommentAuthor({ asUser: true, __agent: true }, '韩律师', 'AI WorkDeck'), 'AI WorkDeck')
})

test('zh-CN / en-US 都有回复相关文案', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default
  const en = (await import('../../src/locales/en-US/editor.js')).default
  for (const k of ['reply', 'replyPlaceholder', 'replySend', 'replyCancel']) {
    assert.ok(zh.review[k], 'zh-CN 缺 ' + k)
    assert.ok(en.review[k], 'en-US 缺 ' + k)
  }
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 计划审阅（dev-board#1022 T5）：PlainTextEditor 审阅态的接线断言 + fileReview.js 服务层。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/PlainTextEditor.vue', import.meta.url), 'utf8')
const SVC = readFileSync(new URL('../../src/services/fileReview.js', import.meta.url), 'utf8')

test('PlainTextEditor 有 review prop、装配审阅扩展、发 review-submit / review-state', () => {
  assert.match(SRC, /review:\s*\{\s*type:\s*Object/)
  assert.match(SRC, /createReviewExtensions\(/)
  assert.match(SRC, /\$emit\('review-submit'/)
  assert.match(SRC, /\$emit\('review-state'/)
  assert.match(SRC, /getReview\(/)
})

test('审阅条与右栏只在审阅态渲染', () => {
  assert.match(SRC, /<PlanReviewBar[\s\S]*v-if="reviewActive"/)
  assert.match(SRC, /<PlanReviewComments[\s\S]*v-if="reviewActive/)
})

test('审阅扩展装在 Compartment 里，退出审阅态 reconfigure([]) 卸载', () => {
  assert.match(SRC, /new Compartment\(\)/)
  assert.match(SRC, /reconfigure\(\[\]\)/)
})

test('提交前先落盘、再调 submit，用 buildPlanReviewPrompt 拼消息', () => {
  const m = SRC.match(/async submitPlanReview\(\)\s*\{([\s\S]*?)\n    \},/)
  assert.ok(m, 'submitPlanReview 方法存在')
  const body = m[1]
  const iFlush = body.indexOf('flushSave(')
  const iSubmit = body.indexOf('submitReview(')
  const iPrompt = body.indexOf('buildPlanReviewPrompt(')
  assert.ok(iFlush >= 0 && iSubmit > iFlush, '先 flushSave 再 submitReview')
  assert.ok(iPrompt > iSubmit, 'submit 成功后再拼 prompt')
})

// ---- fileReview.js：把 api.request 换成替身跑真实现 ----
async function loadService(fakeRequest) {
  const body = SVC
    .replace(/^import .*$/gm, '')
    .replace(/^export (const|async function|function) /gm, '$1 ')
  const names = ['openReview', 'getReview', 'addComment', 'deleteComment', 'submitReview', 'discardReview']
  // eslint-disable-next-line no-new-func
  return new Function('api', body + '\nreturn {' + names.join(',') + '}')({ request: fakeRequest })
}

test('fileReview.js 六个函数的 URL / 方法 / 请求体', async () => {
  const calls = []
  const svc = await loadService(async (o) => { calls.push(o); return { ok: 1 } })
  await svc.openReview(7, 42, { conversationId: 'c1', artifactId: 'a1', baselineText: 'x' })
  await svc.addComment(7, 42, { fromLine: 1, toLine: 2, quotedText: 'q', body: 'b' })
  await svc.deleteComment(7, 42, 9)
  await svc.submitReview(7, 42)
  await svc.discardReview(7, 42)
  const base = '/api/projects/7/files/42/review'
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['POST', base],
    ['POST', base + '/comments'],
    ['DELETE', base + '/comments/9'],
    ['POST', base + '/submit'],
    ['POST', base + '/discard']
  ])
  assert.deepEqual(calls[0].data, { conversationId: 'c1', artifactId: 'a1', baselineText: 'x' })
  assert.equal(calls[1].data.body, 'b')
})

// api.request 对 204 resolve null（T6 修复轮 1），替身照这个口径
test('getReview：204 回 null，200 回快照，其它错误照抛', async () => {
  let svc = await loadService(async () => null)
  assert.equal(await svc.getReview(1, 2), null)

  const snap = { review: { id: 1, status: 'open' }, comments: [] }
  svc = await loadService(async (o) => { assert.equal(o.method, 'GET'); return snap })
  assert.deepEqual(await svc.getReview(1, 2), snap)

  const e409 = Object.assign(new Error('conflict'), { status: 409 })
  svc = await loadService(async () => { throw e409 })
  await assert.rejects(() => svc.getReview(1, 2), /conflict/)
})

test('deleteComment：204 视为成功，其它错误照抛', async () => {
  let svc = await loadService(async () => null)
  assert.equal(await svc.deleteComment(1, 2, 3), true)
  const e409 = Object.assign(new Error('conflict'), { status: 409 })
  svc = await loadService(async () => { throw e409 })
  await assert.rejects(() => svc.deleteComment(1, 2, 3), /conflict/)
})

// ---- 修复轮 1：beforeUnmount 兜底上传必须让路给放弃修改 ----
function loadOptions() {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script.replace(/^import .*$/gm, '').replace('export default', 'return')
  // eslint-disable-next-line no-new-func
  return new Function('setTimeout', 'clearTimeout', body)(() => 1, () => {})
}

function unmountWith(discarding) {
  const opts = loadOptions()
  const uploads = []
  const inst = Object.assign({}, opts.data(), {
    dirty: true, saving: false, _loadOk: true, _discarding: discarding,
    _view: { state: { doc: { toString: () => '修订稿' } }, destroy() {} }
  })
  for (const [k, fn] of Object.entries(opts.methods)) inst[k] = fn.bind(inst)
  inst.uploadContent = (c) => { uploads.push(c); return Promise.resolve() }
  opts.beforeUnmount.call(inst)
  return uploads
}

test('放弃修改在途时卸载，不兜底上传修订稿', () => {
  assert.deepEqual(unmountWith(true), [])
  assert.deepEqual(unmountWith(false), ['修订稿'], '对照：平时卸载照常兜底上传')
})

test('回喂正文在落盘后、发 submit 前就取好', () => {
  const body = SRC.match(/async submitPlanReview\(\)\s*\{([\s\S]*?)\n    \},/)[1]
  const iFlush = body.indexOf('flushSave(')
  const iText = body.indexOf('this.getText()')
  const iSubmit = body.indexOf('submitReview(')
  assert.ok(iFlush >= 0 && iText > iFlush && iSubmit > iText)
})

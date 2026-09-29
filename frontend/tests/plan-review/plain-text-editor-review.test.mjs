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

test('提交先落盘、拼 prompt、交宿主发出，最后才 submit（先发后落库，最终修复波 I-3）', () => {
  const m = SRC.match(/async submitPlanReview\(\)\s*\{([\s\S]*?)\n    \},/)
  assert.ok(m, 'submitPlanReview 方法存在')
  const body = m[1]
  const iFlush = body.indexOf('flushSave(')
  const iPrompt = body.indexOf('buildPlanReviewPrompt(')
  const iEmit = body.indexOf("$emit('review-submit'")
  const iSubmit = body.indexOf('submitReview(')
  assert.ok(iFlush >= 0 && iPrompt > iFlush, '先 flushSave 再拼 prompt')
  assert.ok(iEmit > iPrompt && iSubmit > iEmit, '交宿主发出之后才 submitReview')
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

// ---- 最终修复波：跑真实现的 submitPlanReview / loadReview / emitReviewState ----
function loadOptionsWith(env) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script.replace(/^import .*$/gm, '').replace('export default', 'return')
  const names = Object.keys(env)
  // eslint-disable-next-line no-new-func
  return new Function('setTimeout', 'clearTimeout', ...names, body)(() => 1, () => {}, ...names.map((k) => env[k]))
}
function editorHarness({ ackWith, review = null, reviewRecord } = {}) {
  const calls = { submit: [], open: [], get: 0, emits: [], toasts: [] }
  const env = {
    uni: { showToast: (o) => calls.toasts.push(o.title) },
    toRaw: (x) => x,
    lineDiff: () => ({ hunks: 1, added: 1, removed: 1, changedLines: [], deletions: [] }),
    reanchorComment: () => ({ found: true }),
    buildPlanReviewPrompt: () => ({ message: 'MSG', displayText: 'DISP' }),
    fileReview: {
      submitReview: async (pid, fid) => { calls.submit.push([pid, fid]); return { review: { status: 'submitted' }, comments: [] } },
      openReview: async (pid, fid, body) => { calls.open.push(body); return { review: { status: 'open', baselineText: body.baselineText, artifactId: body.artifactId }, comments: [] } },
      getReview: async () => { calls.get++; return null }
    }
  }
  const opts = loadOptionsWith(env)
  const inst = Object.assign({}, opts.data(), {
    file: { id: 7 }, projectId: 1, review,
    $t: (k) => k, $i18n: { locale: 'zh-CN' },
    $emit: (name, payload) => {
      calls.emits.push([name, payload])
      if (name === 'review-submit' && ackWith !== undefined) payload.ack(ackWith)
    },
    _view: null, _reviewCompartment: null
  })
  inst.reviewRecord = reviewRecord === undefined
    ? { id: 3, status: 'open', baselineText: 'base', artifactId: 'art-1', conversationId: 'conv-A' }
    : reviewRecord
  for (const [k, fn] of Object.entries(opts.computed)) Object.defineProperty(inst, k, { get: fn.bind(inst), configurable: true })
  for (const [k, fn] of Object.entries(opts.methods)) inst[k] = fn.bind(inst)
  inst.flushSave = async () => true
  inst.getText = () => 'revised'
  return { inst, calls }
}

test('I-3 编辑器：ack(false) 不调 submitReview、仍在审阅态并提示没发出去', async () => {
  const { inst, calls } = editorHarness({ ackWith: false })
  await inst.submitPlanReview()
  assert.deepEqual(calls.submit, [])
  assert.equal(inst.reviewRecord.status, 'open')
  assert.equal(inst.reviewActive, true)
  assert.deepEqual(calls.toasts, ['editor.planReview.submitNotSent'])
  assert.equal(inst.reviewSubmitting, false)
})
test('I-3 编辑器：ack(true) 之后才调 submitReview 并退出审阅态', async () => {
  const { inst, calls } = editorHarness({ ackWith: true })
  await inst.submitPlanReview()
  assert.deepEqual(calls.submit, [[1, 7]])
  assert.equal(inst.reviewRecord.status, 'submitted')
  assert.equal(inst.reviewActive, false)
  const sub = calls.emits.find(([n]) => n === 'review-submit')[1]
  assert.equal(sub.message, 'MSG')
  assert.equal(sub.conversationId, 'conv-A')
  assert.equal(sub.artifactId, 'art-1')
})
test('I-3 编辑器：宿主迟迟不回话时不落库', async () => {
  const { inst, calls } = editorHarness({})
  inst.submitPlanReview()
  await new Promise((r) => setTimeout(r, 5))
  assert.deepEqual(calls.submit, [])
  assert.equal(inst.reviewRecord.status, 'open')
})
test('I-1 编辑器：review-state 带 artifactId', () => {
  const { inst, calls } = editorHarness({})
  inst.emitReviewState()
  const st = calls.emits.find(([n]) => n === 'review-state')[1]
  assert.equal(st.artifactId, 'art-1')
  assert.equal(st.fileId, 7)
})
test('顺手：进审阅态的基线取编辑器自己下载到的正文，卡片正文只作兜底', async () => {
  let h = editorHarness({ review: { conversationId: 'c', artifactId: 'a', baselineText: '卡片正文' }, reviewRecord: null })
  await h.inst.loadReview('文件真字节')
  assert.equal(h.calls.open[0].baselineText, '文件真字节')
  h = editorHarness({ review: { conversationId: 'c', artifactId: 'a', baselineText: '卡片正文' }, reviewRecord: null })
  await h.inst.loadReview(null)
  assert.equal(h.calls.open[0].baselineText, '卡片正文')
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 尽调清单的律师审核（dev-board#1057）：律师视角给「通过 / 驳回 / 撤回通过」，驳回必须带理由；
// 客户视角只看徽标与驳回理由，已通过的条目不再给上传按钮；顶部一行待审核/已通过/已驳回汇总。
// 纯逻辑在 utils/ddReview.js 直接测；组件的 review() 照 dd-request-editor-race 的写法把
// <script> 抽出来真跑，依赖注入。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as ddReview from '../../src/utils/ddReview.js'

const { ddReviewActions, ddCanUpload, ddReviewSummary, ddStatusLabelKey, ddActionTarget } = ddReview

const SRC = readFileSync(
  new URL('../../src/components/DdRequestEditor.vue', import.meta.url), 'utf8')

function loadOptions(api, uni) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const names = Object.keys(ddReview)
  const factory = new Function('api', 'getApiBaseUrl', 'getSessionId', 'ICONS', 'uni', ...names, body)
  return factory(api, () => '', () => '', {}, uni, ...names.map((n) => ddReview[n]))
}

function makeVm(options, props) {
  const vm = Object.assign({}, options.data(), props)
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  for (const [k, fn] of Object.entries(options.computed))
    Object.defineProperty(vm, k, { get: fn.bind(vm) })
  vm.$t = (k) => k
  vm.$emit = () => {}
  vm.$forceUpdate = () => {}
  return vm
}

const it = (status, uploadedFileId = 900) => ({ id: 1, status, uploadedFileId })

test('律师视角：已上传给通过/驳回，已通过给撤回通过，没附件或已驳回不给', () => {
  assert.deepEqual(ddReviewActions(it('UPLOADED'), false), ['approve', 'reject'])
  assert.deepEqual(ddReviewActions(it('APPROVED'), false), ['withdraw'])
  assert.deepEqual(ddReviewActions(it('REJECTED'), false), [])
  assert.deepEqual(ddReviewActions(it('PENDING', null), false), [])
  assert.deepEqual(ddReviewActions(it('UPLOADED', null), false), [], '没有附件就没有可审的东西')
})

test('客户视角一个审核按钮都没有', () => {
  for (const s of ['PENDING', 'UPLOADED', 'APPROVED', 'REJECTED'])
    assert.deepEqual(ddReviewActions(it(s), true), [], s)
})

test('审核动作映射到后端状态：撤回通过回到待审核', () => {
  assert.equal(ddActionTarget('approve'), 'APPROVED')
  assert.equal(ddActionTarget('reject'), 'REJECTED')
  assert.equal(ddActionTarget('withdraw'), 'UPLOADED')
  assert.equal(ddActionTarget('bogus'), null)
})

test('徽标文案：待上传 / 待审核 / 已通过 / 已驳回', () => {
  assert.equal(ddStatusLabelKey('PENDING'), 'panels.ddItemPending')
  assert.equal(ddStatusLabelKey('UPLOADED'), 'panels.ddItemUploaded')
  assert.equal(ddStatusLabelKey('APPROVED'), 'panels.ddItemApproved')
  assert.equal(ddStatusLabelKey('REJECTED'), 'panels.ddItemRejected')
  assert.equal(ddStatusLabelKey(undefined), 'panels.ddItemPending')
  const zh = readFileSync(new URL('../../src/locales/zh-CN/panels.js', import.meta.url), 'utf8')
  for (const [k, v] of [['ddItemPending', '待上传'], ['ddItemUploaded', '待审核'],
    ['ddItemApproved', '已通过'], ['ddItemRejected', '已驳回']])
    assert.match(zh, new RegExp(`\\n  ${k}: '${v}',`), k)
})

test('上传按钮：没传过给上传、被驳回给重新上传、待审核与已通过不给', () => {
  const vm = makeVm(loadOptions({}, {}), { requestId: 1, clientView: true })
  assert.equal(vm.showUploadButton(it('PENDING', null)), true)
  assert.equal(vm.showUploadButton(it('REJECTED')), true)
  assert.equal(vm.showUploadButton(it('UPLOADED')), false)
  assert.equal(vm.showUploadButton(it('APPROVED')), false)
  assert.equal(ddCanUpload(it('APPROVED')), false)
  assert.match(SRC, /item\.uploadedFileId \? \$t\('panels\.ddReupload'\) : \$t\('panels\.ddUpload'\)/)
})

test('清单级汇总只数待审核/已通过/已驳回，并渲染在头部', () => {
  assert.deepEqual(ddReviewSummary([
    { status: 'PENDING' }, { status: 'UPLOADED' }, { status: 'UPLOADED' },
    { status: 'APPROVED' }, { status: 'REJECTED' }
  ]), { uploaded: 2, approved: 1, rejected: 1 })
  assert.match(SRC, /\$t\('panels\.ddReviewSummary', reviewSummary\)/)
})

test('驳回要先问理由：取消不发请求，空理由拦下，写了理由带着 projectId 发出去并刷新', async () => {
  const calls = []
  let fetches = 0
  const api = {
    updateDdItemStatus: async (...a) => { calls.push(a) },
    getDdRequestDetails: async () => { fetches++; return { request: { name: 'x' }, items: [], rejectReasons: { 5: '缺页' } } }
  }
  const answers = []
  const toasts = []
  const uni = {
    showModal: (o) => o.success(answers.shift()),
    showToast: (o) => toasts.push(o.title)
  }
  const vm = makeVm(loadOptions(api, uni), { requestId: 1, projectId: 7, clientView: false })
  const item = it('UPLOADED')

  answers.push({ confirm: false })
  await vm.review(item, 'reject')
  assert.equal(calls.length, 0, '取消不发')

  answers.push({ confirm: true, content: '   ' })
  await vm.review(item, 'reject')
  assert.equal(calls.length, 0, '空理由不发')
  assert.deepEqual(toasts, ['panels.ddRejectReasonRequired'])

  answers.push({ confirm: true, content: '  章程缺最后一页 ' })
  await vm.review(item, 'reject')
  assert.deepEqual(calls, [[1, 'REJECTED', '章程缺最后一页', 7]])
  assert.equal(fetches, 1)
  assert.deepEqual(vm.rejectReasons, { 5: '缺页' }, '刷新后带回驳回理由')
  assert.equal(vm.reviewingId, null)

  await vm.review(item, 'approve')
  await vm.review(it('APPROVED'), 'withdraw')
  assert.deepEqual(calls.slice(1), [[1, 'APPROVED', null, 7], [1, 'UPLOADED', null, 7]])
})

test('后端拒绝（400）时把原因提示出来，按钮解锁', async () => {
  const toasts = []
  const api = { updateDdItemStatus: async () => { throw new Error('客户还没上传材料，不能审核') } }
  const vm = makeVm(loadOptions(api, { showToast: (o) => toasts.push(o.title) }), { requestId: 1, projectId: 7 })
  await vm.review(it('UPLOADED'), 'approve')
  assert.deepEqual(toasts, ['客户还没上传材料，不能审核'])
  assert.equal(vm.reviewingId, null)
})

test('模板：审核列挂在 reviewActions 上、驳回理由只在已驳回时显示，旧的 isApproved 分支已移除', () => {
  assert.match(SRC, /v-for="action in reviewActions\(item\)"/)
  assert.match(SRC, /v-if="item\.status === 'REJECTED' && rejectReasons\[item\.id\]"/)
  assert.match(SRC, /this\.rejectReasons = res\.rejectReasons \|\| \{\}/)
  assert.doesNotMatch(SRC, /isApproved\(/)
})

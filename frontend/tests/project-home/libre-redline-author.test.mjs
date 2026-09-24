// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 「用查找替换产生的修订作者是『未知作者』」的接线用例。
//
// 真机实证（24.2.8-zhcn-r5，headless 真引擎）：
//   · load_document 带 authorName:'张三'  → 手打与查找替换的修订都署「张三」；
//   · load_document 带 authorName:''      → 两者一律署「未知作者」（引擎 zh-CN 兜底）。
// 所以 worker 侧没有「替换路径漏设作者」这回事——execCommand 每条命令开头都设，
// 病灶在宿主给的名字是空串：utils/auth.js 的 getCurrentUser 读的是登录页写的本地
// 缓存，桌面 local-mode 免登从不写它。
//
// 本用例守宿主这一段：拿不到缓存名字时必须问一次后端，拿到就补给引擎（不带字节的
// load_document 只更新署名、不动文档）与审阅面板的「我」这一桶。
// 还原病灶（删掉 mounted 里的 resolveRedlineAuthor() 调用，或删掉 pushRedlineAuthor
// 里的 load_document 补发）本文件即转红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createAuthorNameResolver } from '../../src/utils/editorAuthor.js'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const BODY = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')

// 组件里 `import { getCurrentUser } from '@/utils/auth.js'`（本地缓存，同步）与
// `getCurrentUser as fetchAuthUser from '@/services/api.js'`（/api/auth/me，异步）
// 两个同源不同物的取数都要桩掉。
function makeVm({ cached = null, remote = null, fail = false, endpointUp = true } = {}) {
  const sent = []
  const options = new Function(
    'ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar',
    'getCurrentUser', 'fetchAuthUser', 'createAuthorNameResolver', 'getFileDownloadUrl', BODY)(
    null, null, null,
    () => cached,
    async () => { if (fail) throw new Error('401'); return remote ? { code: 0, data: remote } : null },
    createAuthorNameResolver,
    (id) => '/api/files/' + id + '/download')
  const vm = {
    selfAuthor: options.data().selfAuthor,
    _endpointUp: endpointUp,
    appendLog() {},
    executor: { executeCommand: async (action, params) => { sent.push([action, params]); return { success: true } } },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  return { vm, sent, options }
}

test('缓存里没有展示名：问一次后端，补发署名并更新「我」这一桶', async () => {
  const { vm, sent } = makeVm({ cached: null, remote: { displayName: '本机用户' } })
  assert.equal(vm.selfAuthor, '', '解析前只能是空串')
  await vm.resolveRedlineAuthor()
  assert.equal(vm.selfAuthor, '本机用户', '审阅面板的 selfAuthor 必须跟上，否则自己的修订被归成「其他人」')
  assert.deepEqual(sent, [['load_document', { authorName: '本机用户' }]],
    '必须只补发署名：load_document 不带 bytes 时 worker 只记 authorName，不换文档')
})

test('缓存里已有展示名：不问后端、不补发（loadDocument 自己会带上同一个名字）', async () => {
  const { vm, sent } = makeVm({ cached: { displayName: '韩泽伟' }, remote: { displayName: '不该用到' } })
  assert.equal(vm.selfAuthor, '韩泽伟')
  await vm.resolveRedlineAuthor()
  assert.equal(vm.selfAuthor, '韩泽伟')
  assert.deepEqual(sent, [], '名字没变就不该多发一条命令')
})

test('后端也拿不到名字：不补发、不抛——署名退回引擎兜底，编辑链路不受影响', async () => {
  const { vm, sent } = makeVm({ cached: null, fail: true })
  await vm.resolveRedlineAuthor()
  assert.equal(vm.selfAuthor, '')
  assert.deepEqual(sent, [])
})

test('引擎还没起来：只更新 selfAuthor，不往 executor 发命令', async () => {
  const { vm, sent } = makeVm({ cached: null, remote: { displayName: '本机用户' }, endpointUp: false })
  await vm.resolveRedlineAuthor()
  assert.equal(vm.selfAuthor, '本机用户')
  assert.deepEqual(sent, [], 'loadDocument 到点会用同一个名字，这里补发反而会撞在 boot 上')
})

test('mounted 必须真的去解析一次（不然上面几条永远不会被触发）', () => {
  const mounted = SRC.match(/async mounted\(\) \{([\s\S]*?)\n  \},/)[1]
  assert.match(mounted, /this\.resolveRedlineAuthor\(\)/)
})

// dev-board#881：新建的 DOCX 后端给 0 字节，loadDocument 走「保留 boot 出来的空白文档」
// 分支——原来这条分支一条 load_document 都不发，而 worker 的修订作者**只**由
// load_document 设置，于是新建文档里用户打的每个字都署引擎兜底「未知作者」，
// 审阅面板「我 0 / 其他人 1」。上面第二条「loadDocument 自己会带上同一个名字」的
// 前提在这条分支上不成立。还原病灶（删掉空字节分支里的署名下发）本用例即转红。
function blankDocVm(opts) {
  const made = makeVm(opts)
  Object.assign(made.vm, {
    file: { id: 42, name: 'newdocument.docx', fileType: 'docx', fileSize: null },
    _bytesPromise: Promise.resolve(new ArrayBuffer(0)),
    _docLoadSeq: 1,
  })
  return made
}

test('新建文档（后端 0 字节）：空白分支也要把署名下发给引擎', async () => {
  const { vm, sent } = blankDocVm({ cached: { displayName: '韩泽伟' } })
  const loaded = await vm.loadDocument()
  assert.equal(loaded, false, '0 字节仍按新建空白文档处理，不换文档')
  assert.deepEqual(sent, [['load_document', { authorName: '韩泽伟' }]],
    '只补署名、不带 bytes——否则引擎一直端着 boot 时的空作者')
  assert.equal(vm.selfAuthor, '韩泽伟', '引擎署名与审阅面板「我」这一桶必须同串')
})

test('新建文档且名字要问后端：mounted 的解析先完成时，空白分支用解析到的名字', async () => {
  const { vm, sent } = blankDocVm({ cached: null, remote: { displayName: '本机用户' }, endpointUp: false })
  await vm.resolveRedlineAuthor() // 引擎未起：只更新 selfAuthor
  assert.deepEqual(sent, [])
  vm._endpointUp = true
  await vm.loadDocument()
  assert.deepEqual(sent, [['load_document', { authorName: '本机用户' }]])
})

test('新建文档、拿不到名字：不发空名字（保持引擎兜底），也不算装载失败', async () => {
  const { vm, sent } = blankDocVm({ cached: null, fail: true })
  assert.equal(await vm.loadDocument(), false)
  assert.deepEqual(sent, [])
})

test('新建文档、署名下发失败：不把空白文档判成装载失败', async () => {
  const { vm } = blankDocVm({ cached: { displayName: '韩泽伟' } })
  vm.executor = { executeCommand: async () => { throw new Error('relay timeout') } }
  assert.equal(await vm.loadDocument(), false)
})

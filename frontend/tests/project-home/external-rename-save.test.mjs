// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// BUG-14（v0.49.0 真机 C4-03）：已打开的 docx 在 Finder 里被改名后，编辑器继续按旧路径
// 保存，后端把旧文件名在原地重新建出来——磁盘两份、标签/资源管理器/回收站三处名字对不上。
//
// 修法分三段，这里守前端两段（后端对账「改名认领」与上传 409 见
// backend LocalProjectServiceTest / FileControllerMovedFileGuardTest）：
// 1) 编辑器保存带 mustExist=1：它装载的就是磁盘上已有的文件，目标不在了后端回 409，
//    不许静默在旧路径重建；409 要落成可读的「已被移动或改名」而不是笼统的「保存失败」，
//    改动留脏、给重试（对账把行改指到新路径后，重试就落到新文件上）。
// 2) 文件树每次重载（窗口聚焦兜底、SSE refresh_files、用户增删改都流经 awd:files-changed）
//    之后，已开标签按 id 对齐新名——外部改名不经过应用内的 file-renamed 广播。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const body = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')

function makeVm(extra = {}) {
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'getFileUploadUrl',
    'stampApplication', 'documentStampApplication', body)(
    null, null, null, id => '/upload/' + id,
    async (bytes) => bytes, async () => null)
  const vm = {
    ready: true, file: { id: 7, name: '合同.docx', fileSize: 67603 }, statusKey: 'ready',
    dirty: true, saving: false, _dirtySince: Date.now(),
    $t: k => k,
    executor: { executeCommand: async () => ({ success: true, bytes: [1, 2, 3] }) },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.computedIsError = options.computed.isError
  Object.assign(vm, { appendLog() {}, scheduleAnchorCheck() {}, scheduleProvenanceReload() {}, uploadBytes: async () => {} }, extra)
  return vm
}

test('装载过真文件的编辑器保存时要求目标仍存在（mustExist=1）', async () => {
  const urls = []
  const vm = makeVm({ uploadBytes: async (url) => { urls.push(url) } })
  assert.equal(await vm.saveDocument(), true)
  assert.equal(urls.length, 1)
  assert.match(urls[0], /^\/upload\/7\?mustExist=1$/)
})

test('新建空白文档的第一笔保存不带 mustExist（磁盘上本来就还没有它），存过一次之后才带', async () => {
  const urls = []
  const vm = makeVm({ file: { id: 9, name: '新建.docx', fileSize: 0 }, uploadBytes: async (url) => { urls.push(url) } })
  assert.equal(await vm.saveDocument(), true)
  assert.equal(urls[0], '/upload/9')
  vm.dirty = true
  assert.equal(await vm.saveDocument(), true)
  assert.equal(urls[1], '/upload/9?mustExist=1')
})

test('后端 409（文件已被移动/改名）落成专门的状态、留脏、仍可重试', async () => {
  const vm = makeVm({
    uploadBytes: async () => { throw Object.assign(new Error('HTTP 409'), { status: 409 }) },
  })
  assert.equal(await vm.saveDocument(), false)
  assert.equal(vm.statusKey, 'movedSaveFailed')
  assert.equal(vm.dirty, true, '改动不能因为这次失败被当成已保存')
  assert.equal(vm.computedIsError.call(vm), true, '要按失败态显示（红色胶囊）')
  // 模板里的「重试保存」对这个状态也要出现
  assert.match(source, /statusKey === 'movedSaveFailed'/)
})

test('其它上传失败仍是笼统的 saveFailed', async () => {
  const vm = makeVm({ uploadBytes: async () => { throw new Error('offline') } })
  assert.equal(await vm.saveDocument(), false)
  assert.equal(vm.statusKey, 'saveFailed')
})

test('zh-CN 与 en-US 都有 movedSaveFailed 文案', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default
  const en = (await import('../../src/locales/en-US/editor.js')).default
  assert.ok(zh.status.movedSaveFailed && /移动|改名/.test(zh.status.movedSaveFailed), JSON.stringify(zh.status))
  assert.ok(en.status.movedSaveFailed && /moved|renamed/i.test(en.status.movedSaveFailed), JSON.stringify(en.status))
})

test('工作台在文件树每次重载后按 id 对齐已开标签名（外部改名不经过 file-renamed 广播）', () => {
  const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const on = page.match(/uni\.\$on\('awd:files-changed', (this\._[A-Za-z]+)\)/)
  assert.ok(on, '工作台必须订阅 awd:files-changed')
  const handler = on[1]
  assert.ok(page.includes("uni.$off('awd:files-changed', " + handler + ')'), '卸载时要退订，否则页面栈多实例会累积监听')
  const at = page.indexOf(handler + ' = (')
  assert.ok(at > 0, '找不到监听函数定义')
  const def = page.slice(at, at + 400)
  assert.match(def, /syncOpenTabsFromFileTree\(\)/)
  assert.match(def, /isActiveOverviewInstance\(\)/, '只让活跃实例对齐（页面栈多实例地雷）')
})

// 3) 对账认不出新位置（移出项目、改名同时改了大小、有歧义）时，这一行会进回收站，
//    「重试保存」永远 409——律师手里只剩一份存不下去的改动。给一条「另存为…」出口：
//    导出当前内容走应用自己的下载链路（桌面主进程接管成系统「另存为」对话框，用户自选位置），
//    绝不回写旧路径；改动仍按未保存算（项目里那份并没有更新）。
function setupCopy({ result } = {}) {
  const toasts = [], clicks = [], sent = []
  globalThis.uni = { showToast: (o) => toasts.push(o.title), showLoading() {}, hideLoading() {} }
  globalThis.URL.createObjectURL = (blob) => { clicks.push({ blobType: blob.type, size: blob.size }); return 'blob:x' }
  globalThis.URL.revokeObjectURL = () => {}
  globalThis.document = {
    createElement: () => ({ click() { clicks[clicks.length - 1].download = this.download; clicks[clicks.length - 1].clicked = true }, remove() {} }),
    body: { appendChild() {} },
  }
  const uploads = []
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'getFileUploadUrl',
    'stampApplication', 'documentStampApplication', 'setTimeout', body)(
    null, null, null, id => '/upload/' + id, async (bytes) => bytes, async () => null, () => 0)
  const vm = {
    ready: true, file: { id: 7, name: '合同-改名.docx', fileSize: 67603 }, statusKey: 'movedSaveFailed',
    dirty: true, saving: false,
    $t: (k, p) => k + (p ? JSON.stringify(p) : ''),
    appendLog() {},
    executor: { executeCommand: async (a) => { sent.push(a); return result || { success: true, bytes: new Uint8Array([80, 75, 3, 4]) } } },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.uploadBytes = async (url) => { uploads.push(url) }
  return { vm, options, toasts, clicks, sent, uploads }
}

test('「已被移动或改名」时给「另存为…」出口，与「重试保存」并列', () => {
  assert.match(source, /v-if="statusKey === 'movedSaveFailed' && !saving"[^>]*@tap="saveCopyAs"/)
})

test('另存为：导出当前内容按原名下载（系统另存为对话框选位置），不回写项目、改动仍算未保存', async () => {
  const { vm, toasts, clicks, sent, uploads } = setupCopy()
  assert.equal(await vm.saveCopyAs(), true)
  assert.deepEqual(sent, ['export_document'])
  assert.equal(clicks.length, 1)
  assert.equal(clicks[0].clicked, true)
  assert.equal(clicks[0].download, '合同-改名.docx')
  assert.equal(clicks[0].size, 4)
  assert.equal(uploads.length, 0, '另存为不许走上传——旧路径已不存在，上传只会再撞 409 或在旧处重建')
  assert.equal(vm.dirty, true)
  assert.equal(vm.statusKey, 'movedSaveFailed')
  assert.match(toasts[0], /^editor\.savedCopyAs.*合同-改名\.docx/)
})

test('另存为：导出失败要给提示，不许点了没反应', async () => {
  const { vm, toasts, clicks } = setupCopy({ result: { success: false, message: 'boom' } })
  assert.equal(await vm.saveCopyAs(), false)
  assert.equal(clicks.length, 0)
  assert.match(toasts[0], /^editor\.saveCopyAsFailed.*boom/)
})

test('对账认领成功（标签名随之更新）后自动重试保存，不用律师再点', () => {
  const { vm, options } = setupCopy()
  const watcher = options.watch['file.name']
  assert.equal(typeof watcher, 'function', '要 watch file.name')
  let retried = 0
  vm.retrySave = async () => { retried++ }
  watcher.call(vm, '合同-改名.docx', '合同.docx')
  assert.equal(retried, 1)
  vm.statusKey = 'ready'
  watcher.call(vm, '再改.docx', '合同-改名.docx')
  assert.equal(retried, 1, '平常的改名不触发保存')
  vm.statusKey = 'movedSaveFailed'; vm.saving = true
  watcher.call(vm, '又改.docx', '再改.docx')
  assert.equal(retried, 1, '在途保存时不叠一笔')
})

test('zh-CN 与 en-US 都有另存为文案', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default
  const en = (await import('../../src/locales/en-US/editor.js')).default
  for (const k of ['saveCopyAs', 'savedCopyAs', 'saveCopyAsFailed']) {
    assert.ok(zh[k], 'zh-CN 缺 ' + k)
    assert.ok(en[k], 'en-US 缺 ' + k)
  }
  assert.match(zh.savedCopyAs, /\{name\}/)
  assert.match(en.savedCopyAs, /\{name\}/)
  assert.match(zh.saveCopyAsFailed, /\{reason\}/)
  assert.match(en.saveCopyAsFailed, /\{reason\}/)
})

// dev-board#903 之后，对账认不出的外部删除 / 移出项目直接把那一行出索引（不进回收站）：
// 这时编辑器按旧 fileId 保存，后端在 mustExist 围栏之前就回 404「文件不存在」。
// 对已装载的文档来说这同样是「被移走了」，要落到同一个状态，才有「另存为…」出口。
test('后端 404（记录已被对账移除）同样落成 movedSaveFailed', async () => {
  const vm = makeVm({
    uploadBytes: async () => { throw Object.assign(new Error('HTTP 404'), { status: 404 }) },
  })
  assert.equal(await vm.saveDocument(), false)
  assert.equal(vm.statusKey, 'movedSaveFailed')
  assert.equal(vm.dirty, true)
})

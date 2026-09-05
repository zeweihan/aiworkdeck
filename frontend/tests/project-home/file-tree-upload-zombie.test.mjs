// dev-board#462：桌面端左下角「正在上传... (0/1) 0%」常驻不消失。
//   cd frontend && npm run test:project-home
//
// 这条底栏与手机端中转无关（frontend 里没有一行 mobile-relay 代码），是 FileTree 自己的
// 上传队列在说谎：uploadStatusMap 里只剩已中断/出错的死任务时，
//   uploadedCount   = filter(progress===100)      → 0
//   totalUploadCount= Object.keys(map).length     → 1   ← 「(0/1)」里的那个 1
//   globalUploadProgress 明明过滤掉了死任务返回 null，模板 Math.floor(null||0) 又把它画成 0%
// 而 saveUploadState 不落 error 标志、restoreUploadState 每次挂载都把它复活成 interrupted，
// 于是这条「正在上传」永远不消失。
//
// FileTree.vue 是 Options API，照 file-tree-external-drop.test.mjs 的套路把 <script> 抠出来
// new Function 求值，真跑 computed 与 restoreUploadState。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getAuthHeaders', 'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'CircularProgress', 'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask', 'importLocalFile',
  'nativeDataTransfer', 'isExternalFileDrag', 'collectDroppedFiles', 'claimExternalDrop',
]

function loadOptions() {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(...IMPORT_NAMES, body)
  return factory(...IMPORT_NAMES.map(() => () => {}))
}

// computed 装成真访问器，methods 绑到同一个 vm 上，这样 restoreUploadState 里的
// this.saveUploadState() 走的是真实现（落盘行为是本用例的断言对象之一）。
function makeVm(data = {}) {
  const opts = loadOptions()
  const vm = Object.assign({}, opts.data.call({}), data)
  for (const [k, fn] of Object.entries(opts.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  for (const [k, fn] of Object.entries(opts.methods)) vm[k] = fn.bind(vm)
  vm.$t = (k, p) => (p ? `${k}:${JSON.stringify(p)}` : k)
  vm.$emit = () => {}
  return vm
}

// 一份可读可写的 uni.storage 桩：restoreUploadState 的「丢弃」必须真的落盘，
// 否则下次挂载又原样复活（「常驻不消失」的根）。
function installStorage(initial = {}) {
  const prior = globalThis.uni
  const store = { ...initial }
  globalThis.uni = {
    getStorageSync: (k) => (k in store ? store[k] : ''),
    setStorageSync: (k, v) => { store[k] = v },
    removeStorageSync: (k) => { delete store[k] },
    showToast() {}, $emit() {}, $on() {}, $off() {},
  }
  return { store, restore: () => { globalThis.uni = prior } }
}

const KEY = 'upload_state_v2_project_7'

// ---------- A：已中断的任务不许算成「正在上传」 ----------

test('队列里只剩已中断的任务时：不算正在上传，底栏改说「N 个上传已中断」', () => {
  const s = installStorage()
  try {
    const vm = makeVm({
      projectId: 7,
      isBatchUploading: false,
      uploadStatusMap: {
        z: { fileId: 'z', name: 'IMG_0001.jpg', size: 1048576, uploaded: 0, progress: 0, error: true, status: 'interrupted' },
      },
    })
    assert.equal(vm.totalUploadCount, 0, '「(0/1)」里的那个 1 不许再出现')
    assert.equal(vm.uploadedCount, 0)
    assert.equal(vm.globalUploadProgress, null, 'null 才能让模板不画那个假的 0%')
    assert.equal(vm.interruptedUploadCount, 1, '底栏要说清楚：1 个上传已中断')
    assert.equal(vm.showInterruptedOnly, true)
  } finally { s.restore() }
})

test('批量上传途中队列瞬时清空（条目延迟 1s 删）：仍走进度环，不许闪出「0 个上传已中断」', () => {
  const s = installStorage()
  try {
    const vm = makeVm({
      projectId: 7,
      isBatchUploading: true,
      batchUploadTotalSize: 1000,
      batchUploadFinishedSize: 400,
      uploadStatusMap: {},
    })
    assert.equal(vm.interruptedUploadCount, 0)
    assert.equal(vm.showInterruptedOnly, false)
    assert.equal(vm.globalUploadProgress, 40)
  } finally { s.restore() }
})

test('活着的任务照旧计数：中断的只从计数里剔除，不影响真在传的', () => {
  const s = installStorage()
  try {
    const vm = makeVm({
      projectId: 7,
      isBatchUploading: false,
      uploadStatusMap: {
        dead: { fileId: 'dead', name: 'a.jpg', size: 100, uploaded: 0, progress: 0, error: true, status: 'interrupted' },
        live: { fileId: 1, name: 'b.docx', size: 200, uploaded: 100, progress: 50 },
        done: { fileId: 2, name: 'c.docx', size: 200, uploaded: 200, progress: 100 },
      },
    })
    assert.equal(vm.totalUploadCount, 2)
    assert.equal(vm.uploadedCount, 1)
    assert.equal(vm.interruptedUploadCount, 1)
    assert.equal(Math.round(vm.globalUploadProgress), 75, '(100+200)/(200+200)')
  } finally { s.restore() }
})

// ---------- B：重启后 pending 排队项被标成中断，而不是继续冒充「正在上传」 ----------

test('恢复 pending 排队项（内存里的 File 对象已随页面消失）：标成已中断，不再计入正在上传', () => {
  const s = installStorage({
    [KEY]: JSON.stringify({
      pending_1: { fileId: 'pending_1', name: 'IMG_0001.jpg', size: 1048576, uploaded: 0, progress: 0, startTime: 1 },
    }),
  })
  try {
    const vm = makeVm({ projectId: 7, isBatchUploading: false, uploadStatusMap: {} })
    vm.uploadFileObjects = {}
    vm.restoreUploadState()
    const item = vm.uploadStatusMap.pending_1
    assert.ok(item, '条目本身要留着，↻ 重试与 × 删除仍要够得着')
    assert.equal(item.status, 'interrupted')
    assert.equal(item.error, true)
    assert.equal(vm.totalUploadCount, 0, '不许再说「正在上传 (0/1)」')
    assert.equal(vm.interruptedUploadCount, 1)
  } finally { s.restore() }
})

// ---------- C：progress 已 100 的恢复条目直接丢弃 ----------

test('恢复 progress===100 的条目：直接丢弃并落盘，第二次挂载不会再复活', () => {
  const s = installStorage({
    [KEY]: JSON.stringify({
      42: { fileId: 42, wpsFileId: 'w42', name: 'done.docx', size: 200, uploaded: 200, progress: 100, startTime: 1 },
      pending_1: { fileId: 'pending_1', name: 'x.jpg', size: 100, uploaded: 0, progress: 0, startTime: 1 },
    }),
  })
  try {
    const vm = makeVm({ projectId: 7, isBatchUploading: false, uploadStatusMap: {} })
    vm.uploadFileObjects = {}
    vm.restoreUploadState()
    assert.deepEqual(Object.keys(vm.uploadStatusMap), ['pending_1'], '已完成的没有可恢复的东西')
    assert.equal(vm.totalUploadCount, 0)

    // 第二次挂载（projectId watcher / FilePickerDialog 里的第二个实例）读到的必须已经是清过的
    const persisted = JSON.parse(s.store[KEY])
    assert.deepEqual(Object.keys(persisted), ['pending_1'], '丢弃要落盘，否则下次挂载又原样复活')
    const vm2 = makeVm({ projectId: 7, isBatchUploading: false, uploadStatusMap: {} })
    vm2.uploadFileObjects = {}
    vm2.restoreUploadState()
    assert.deepEqual(Object.keys(vm2.uploadStatusMap), ['pending_1'])
  } finally { s.restore() }
})

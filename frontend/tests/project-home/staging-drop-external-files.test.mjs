// 审计（dev-board#74）两条，都在 FileStagingArea.vue 的 onDrop：
//
// HIGH：暂存区放置区静默丢弃真实的系统文件拖拽。面板对任意拖拽都亮起"松手暂存
// 文件"遮罩，但 onDrop 只认得"项目里已有文件"的内部格式（应用内 JSON /
// document.__checkbaDraggedFile 兜底）；真实拖一个 Finder/Explorer 里的文件进来，
// 两条路径都取不出数据，以前直接什么都不做——遮罩消失，用户以为暂存了，其实
// 什么也没发生。
//
// MEDIUM：把文件夹拖进暂存区没有类型守卫。FileTree.vue 的拖拽对文件/文件夹一视
// 同仁，folder 的 payload 里 fileType==='folder'；暂存区消费端是给 AI 读内容用的，
// 读一个文件夹只会拿到空/报错。
//
// 修法：onDrop 新增第 3 分支，识别到 dataTransfer.files（浏览器原生 File 列表，
// 内部格式两条路径都判定为空时才会走到这里）就 emit('drop-files', ...) 交给宿主
// 走真实上传；两条内部格式解析路径都加 fileType !== 'folder' 守卫。
//
// FileStagingArea.vue 是 Options API 且依赖不多（ICONS 常量 + UnlockHint 组件，
// 组件 import 只在 template/components 里用到，跟 methods 逻辑无关），照
// libre-editor-retry-reentrancy.test.mjs 的套路把 <script> 抠出来 new Function
// 求值，真跑 onDrop。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FileStagingArea.vue', import.meta.url), 'utf8')

function loadOptions() {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function('ICONS', 'UnlockHint', body)
  return factory({}, {})
}

function makeVm() {
  const options = loadOptions()
  const vm = Object.assign({}, options.data())
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.emitted = []
  vm.$emit = (name, payload) => vm.emitted.push({ name, payload })
  return vm
}

test('真实 OS 文件拖拽（dataTransfer.files，内部格式两条路径都取不到数据）触发 drop-files，而不是被静默丢弃', () => {
  const vm = makeVm()
  const fakeFile = { name: 'report.docx', size: 1024 }
  const e = {
    dataTransfer: {
      getData: () => '', // 两条内部格式（application/x-checkba-file、text/checkba-file-json、text/plain）都空
      files: [fakeFile],
    },
  }
  vm.onDrop(e)
  assert.deepEqual(vm.emitted, [{ name: 'drop-files', payload: [fakeFile] }])
})

test('多个真实文件一起拖入，drop-files 带上完整列表', () => {
  const vm = makeVm()
  const files = [{ name: 'a.pdf' }, { name: 'b.docx' }]
  const e = { dataTransfer: { getData: () => '', files } }
  vm.onDrop(e)
  assert.deepEqual(vm.emitted, [{ name: 'drop-files', payload: files }])
})

test('内部格式拖了个文件夹（fileType===folder）不许被当成可暂存文件收进列表，也不会误落进 OS 兜底分支', () => {
  const vm = makeVm()
  const e = {
    dataTransfer: {
      getData: (type) => type === 'application/x-checkba-file'
        ? JSON.stringify({ fileId: 99, name: '合同文件夹', fileType: 'folder', wpsFileId: null })
        : '',
      files: [], // 内部拖拽没有原生 File 列表
    },
  }
  vm.onDrop(e)
  assert.deepEqual(vm.emitted, [], '文件夹必须被拒绝，且不产生任何 emit（不是"暂存"也不是"上传"）')
})

test('正常文件（非文件夹）内部拖拽照常工作——回归保护，不能因为加了文件夹守卫就误伤普通文件', () => {
  const vm = makeVm()
  const e = {
    dataTransfer: {
      getData: (type) => type === 'application/x-checkba-file'
        ? JSON.stringify({ fileId: 7, name: '起诉状.docx', fileType: 'docx', wpsFileId: 'w1' })
        : '',
      files: [],
    },
  }
  vm.onDrop(e)
  assert.deepEqual(vm.emitted, [{
    name: 'drop',
    payload: [{ id: 7, name: '起诉状.docx', fileType: 'docx', wpsFileId: 'w1' }],
  }])
})

test('全局兜底变量路径（document.__checkbaDraggedFile）同样挡文件夹，且无论是否收进列表都要消费掉该变量', () => {
  const vm = makeVm()
  const priorDocument = globalThis.document
  globalThis.document = { __checkbaDraggedFile: { fileId: 5, name: '文件夹', fileType: 'folder' } }
  try {
    const e = { dataTransfer: { getData: () => '', files: [] } }
    vm.onDrop(e)
    assert.deepEqual(vm.emitted, [])
    assert.equal(globalThis.document.__checkbaDraggedFile, null)
  } finally {
    globalThis.document = priorDocument
  }
})

test('全局兜底变量路径对普通文件仍然照常工作（回归保护）', () => {
  const vm = makeVm()
  const priorDocument = globalThis.document
  globalThis.document = { __checkbaDraggedFile: { fileId: 6, name: 'x.pdf', fileType: 'pdf' } }
  try {
    const e = { dataTransfer: { getData: () => '', files: [] } }
    vm.onDrop(e)
    assert.deepEqual(vm.emitted, [{ name: 'drop', payload: [{ id: 6, name: 'x.pdf', fileType: 'pdf', wpsFileId: undefined }] }])
  } finally {
    globalThis.document = priorDocument
  }
})

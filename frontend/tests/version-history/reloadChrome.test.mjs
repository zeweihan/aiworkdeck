// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 就地重载之后 LO 原生那套 chrome（菜单栏 / 工具栏 / 状态栏 / 标尺）必须再藏一次
 * （真机反馈 B5：在稿上编辑 → 回到主线工作，文档自动重载，编辑区顶上冒出整条
 * 「文件 编辑 视图 插入 …」和标尺，关掉标签重开才恢复）。
 *
 * 病灶：load_document 会把藏好的 chrome 重新拉出来（office_thread.js 的
 * hideNativeChrome 注释 ② 已有真机实证），而自建工具栏只在 executor 变化时
 * bootstrap 一次——正常打开那条路藏过，就地重载这条路换的是同一个 executor 手里
 * 的文档，没人再去藏。
 *
 * 用 tests/optional-components/* 的老办法：读 .vue 的 <script>、剥掉 import、
 * 把 methods 拿出来用假 this 驱动。真引擎那一半（load_document 之后 isVisible
 * 读回 true）不在这里验，那要起 LOWA。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

function optionsOf(rel) {
  const source = readFileSync(new URL(rel, import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const names = []
  for (const m of script.matchAll(/^import\s+([\s\S]*?)\s+from\s+'[^']+'\s*;?\s*$/gm)) {
    const clause = m[1]
    const braced = clause.match(/\{([\s\S]*?)\}/)
    if (braced) {
      for (const part of braced[1].split(',')) {
        const name = part.split(/\s+as\s+/).pop().trim()
        if (name) names.push(name)
      }
    }
    const def = clause.replace(/\{[\s\S]*?\}/, '').replace(/,/g, '').trim()
    if (def) names.push(def)
  }
  const body = script
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace(/export\s+default\s*\{/, 'return {')
  return new Function(...names, body)(...names.map(() => () => {}))
}

const toolbar = optionsOf('../../src/components/EditorToolbar.vue')
const editor = optionsOf('../../src/components/LibreOfficeEditor.vue')

// ---- 工具栏：reapplyChrome 落的是「当前」开关状态 --------------------------

function toolbarHost(chromeHidden) {
  const sent = []
  // 顺序要紧：先铺组件自己的 methods，再用桩覆盖（call/loadDocument 这些同名方法
  // 组件里本来就有，反过来会把桩顶掉、真去发 XMLHttpRequest）。
  const host = Object.assign({}, toolbar.methods, {
    chromeHidden,
    async call(action, params) { sent.push([action, params]); return { success: true } },
  })
  return { host, sent }
}

test('藏着的时候重新落一次「全藏」', async () => {
  const { host, sent } = toolbarHost(true)
  await host.reapplyChrome()
  assert.deepEqual(sent, [['set_chrome',
    { menubar: false, statusbar: false, toolbars: false, rulers: false }]])
  assert.equal(host.chromeHidden, true)
})

test('律师自己用逃生开关放出来的那一套，不许被一次重载又摁回去', async () => {
  const { host, sent } = toolbarHost(false)
  await host.reapplyChrome()
  assert.deepEqual(sent, [['set_chrome',
    { menubar: true, statusbar: true, toolbars: true, rulers: true }]])
  assert.equal(host.chromeHidden, false)
})

// ---- 编辑器：换完文档要调它 ----------------------------------------------

function editorHost({ loaded = true, withToolbar = true } = {}) {
  const order = []
  const host = Object.assign({}, editor.methods, {
    file: { id: 1, name: '合同.docx' },
    executor: {},
    ready: true,
    saving: false,
    dirty: false,
    docLoadFailed: false,
    statusKey: 'ready',
    _writingHost: null,
    _inlineReviewHost: null,
    _saveTimer: null,
    _dirtySince: 0,
    _bytesPromise: {},
    _loadGen: 3,
    dlLoaded: 1,
    dlTotal: 2,
    $refs: withToolbar ? {
      toolbar: {
        async reapplyChrome() { order.push('reapplyChrome'); return { success: true } },
      },
    } : {},
    async loadDocument() { order.push('loadDocument'); return loaded },
    appendLog() {},
    initWritingAssistance() {},
    scheduleAnchorCheck() {},
    loadProvenance() {},
  })
  return { host, order }
}

test('重载成功之后把 chrome 再藏一次，且必须排在 load_document 之后（B5 回归）', async () => {
  const { host, order } = editorHost()

  assert.equal(await host.reloadFromBackend(), true)

  assert.deepEqual(order, ['loadDocument', 'reapplyChrome'],
    'load_document 才是把 chrome 重新拉出来的那个动作，藏必须在它之后')
})

test('非 Writer / boot 期间没有工具栏：不藏也不炸（藏不成顶多多一条菜单栏）', async () => {
  const { host, order } = editorHost({ withToolbar: false })

  assert.equal(await host.reloadFromBackend(), true)

  assert.deepEqual(order, ['loadDocument'])
})

test('重载失败时保存闸照旧落下（不因为多了一步藏 chrome 而改变失败语义）', async () => {
  const { host } = editorHost({ loaded: false })

  assert.equal(await host.reloadFromBackend(), false)

  assert.equal(host.docLoadFailed, true)
  assert.equal(host.statusKey, 'reloadFailed')
})


test('checkpoint prepare drains an already uploading save before acknowledging and blocks new saves', async () => {
  const { host } = editorHost()
  let finishUpload, uploadStarted
  const started = new Promise(resolve => { uploadStarted = resolve })
  host.executor = { executeCommand: async () => ({ success: true, bytes: new Uint8Array([1, 2, 3]) }) }
  host.stampGeneratorMetadata = async bytes => bytes
  host.uploadBytes = async () => { uploadStarted(); await new Promise(resolve => { finishUpload = resolve }) }
  host.scheduleProvenanceReload = () => {}
  host.dirty = true
  const saving = host.saveDocument()
  await started
  let acknowledged = false
  const prepare = host.prepareCheckpointRestore().then(ok => { acknowledged = true; return ok })
  assert.equal(host._reloading, true)
  assert.equal(host.dirty, false)
  assert.equal(await host.saveDocument(), false, 'another autosave cannot start')
  await new Promise(resolve => setTimeout(resolve, 10))
  assert.equal(acknowledged, false, 'the backend must not overwrite storage while the old upload is in flight')
  finishUpload()
  assert.equal(await saving, true)
  assert.equal(await prepare, true)
  assert.equal(host._reloading, true, 'save lock remains until the checkpoint is loaded')
  assert.equal(await host.reloadFromBackend(), true)
  assert.equal(host._reloading, false)
})

test('failed checkpoint restore blocks old-model autosave until a real reload succeeds', async () => {
  const { host } = editorHost()
  assert.equal(await host.prepareCheckpointRestore(), true)
  host.failCheckpointRestore()
  assert.equal(host.docLoadFailed, true)
  assert.equal(await host.saveDocument(), false)
  assert.equal(await host.reloadFromBackend(), true)
  assert.equal(host.docLoadFailed, false)
})


test('late checkpoint load cannot clear a newer restore save barrier or failure state', async () => {
  const { host } = editorHost()
  let finish
  host.loadDocument = () => new Promise(resolve => { finish = resolve })
  assert.equal(await host.prepareCheckpointRestore('old'), true)
  const old = host.reloadFromBackend('old')
  host.failCheckpointRestore('old')
  host.docLoadFailed = false // user has retried the failed document
  assert.equal(await host.prepareCheckpointRestore('new'), true)
  host.docLoadFailed = true
  finish(true)
  assert.equal(await old, false)
  assert.equal(host._checkpointRestoreId, 'new')
  assert.equal(host._reloading, true)
  assert.equal(host.docLoadFailed, true)
})

test('checkpoint prepare refuses to overlap an existing reload or load command', async () => {
  for (const busy of [{ _reloading: true }, { _loadInFlight: {} }]) {
    const { host } = editorHost()
    Object.assign(host, busy)
    assert.equal(await host.prepareCheckpointRestore('new'), false)
    assert.equal(host._checkpointRestoreId, undefined, 'do not take ownership of another load')
  }
})

test('an in-flight upload failure cannot be acknowledged as safe to overwrite storage', async () => {
  const { host } = editorHost()
  let rejectUpload, uploadStarted
  const started = new Promise(resolve => { uploadStarted = resolve })
  host.executor = { executeCommand: async () => ({ success: true, bytes: new Uint8Array([1]) }) }
  host.stampGeneratorMetadata = async bytes => bytes
  host.uploadBytes = async () => { uploadStarted(); await new Promise((_, reject) => { rejectUpload = reject }) }
  const save = host.saveDocument()
  await started
  const prepare = host.prepareCheckpointRestore('restore')
  rejectUpload(new Error('network timeout: server write outcome unknown'))
  assert.equal(await save, false)
  assert.equal(host.statusKey, 'saveFailed')
  assert.equal(await prepare, false, 'saving=false does not establish that an old server upload stopped')
})

test('a same-file replacement instance without the old token is blocked after failed checkpoint reload', async () => {
  const old = editorHost().host, fresh = editorHost().host
  old.file.id = 50; fresh.file.id = 50
  const replies = []
  const actionSource = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '').replace(/^export\s+/gm, '')
  const methods = new Function('sendEditorResult', actionSource + '; return agentClientActionMethods')(
    async (...args) => { replies.push(args) })
  const page = Object.assign({ _libreRefs: { old }, activeFileIdLeft: 50, activeFileIdRight: null }, methods)
  const run = phase => page.handleEditorCommand({ action: 'doc_checkpoint_restore', conversationId: 'conv',
    requestId: phase, params: { fileId: 50, restoreId: 'old', phase } })
  await run('prepare')
  assert.equal(replies.at(-1)[2], true)
  page._libreRefs = { fresh }
  await run('reload')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(old.docLoadFailed, true)
  assert.equal(fresh.docLoadFailed, true, 'the new unprepared model must not overwrite restored storage')
  assert.equal(await fresh.saveDocument(), false)

  const newer = editorHost().host
  assert.equal(await newer.prepareCheckpointRestore('new'), true)
  newer.failCheckpointRestore('old')
  assert.equal(newer.docLoadFailed, false, 'an actual newer token still owns its own save barrier')
  assert.equal(newer._checkpointRestoreId, 'new')
})

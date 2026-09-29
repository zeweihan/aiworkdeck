// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 剪贴板文件按路径存（dev-board B14）。
//
// 病灶：桌面端复制了一个文件时，采集桥的 FILE 分支用 host.utils.readFile 把本机文件整个读进
// 渲染进程，再包成 File 经 saveClipboardFile 上传回同一台机器上的后端——几百 MB 的证据包
// 直接把渲染进程内存撑爆。改法：只传路径给 POST /api/clipboard/file-local，后端（local-mode）
// 自己 copy 进剪贴板库。IMAGE 分支不动（图片来自系统剪贴板的像素，没有本机路径）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/clipboardBridge.js', import.meta.url), 'utf8')

function makeBridge(deps) {
  const body = SRC
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace('export const clipboardBridgeMethods =', 'return')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  return new Function(...names, body)(...names.map((n) => deps[n]))
}

function makeEnv(over = {}) {
  const calls = { localFile: [], upload: [], readFile: [], toasts: [], saved: [] }
  const subs = []
  const deps = {
    saveClipboardText: async (t) => ({ data: { id: 1, text: t } }),
    saveClipboardFile: async (f, type) => { calls.upload.push(type); return { data: { id: 2 } } },
    saveClipboardLocalFile: over.saveClipboardLocalFile
      || (async (p) => { calls.localFile.push(p); return { data: { id: 3, type: 'FILE' } } }),
    getCurrentUser: () => null,
    isDesktopHost: () => true,
    host: {
      clipboard: { onCopied: (fn) => { subs.push(fn); return () => {} } },
      utils: { readFile: async (p) => { calls.readFile.push(p); return { ok: true, data: [1, 2, 3] } } },
    },
    window: {},
    document: {},
    uni: { showToast: (o) => calls.toasts.push(o) },
    atob: globalThis.atob,
    Blob: globalThis.Blob,
    File: globalThis.File,
  }
  // onClipboardSaved 放在最后覆盖桥自带的那个（后者只做 uni.$emit 广播），好直接数入库回调
  const vm = Object.assign({ isDesktopApp: true, $t: (k) => k }, makeBridge(deps), {
    onClipboardSaved: (s) => calls.saved.push(s),
  })
  vm.bindClipboardListener()
  return { calls, subs, vm }
}

test('FILE 载荷只传路径给 saveClipboardLocalFile，渲染进程不读文件字节、不走上传', async () => {
  const { calls, subs } = makeEnv()
  await subs[0]({ type: 'FILE', filePath: '/Users/me/证据/银行流水.pdf', ts: 101 })
  assert.deepEqual(calls.localFile, ['/Users/me/证据/银行流水.pdf'])
  assert.deepEqual(calls.readFile, [], '不许再把本机文件整个读进渲染进程')
  assert.deepEqual(calls.upload, [], 'FILE 不许再走 multipart 上传')
  assert.deepEqual(calls.saved, [{ id: 3, type: 'FILE' }], '入库成功要通知面板刷新')
  assert.equal(calls.toasts[0].icon, 'success')
})

test('按路径存失败：提示一次失败，不回退到读字节上传', async () => {
  const { calls, subs } = makeEnv({ saveClipboardLocalFile: async () => { throw new Error('403') } })
  await subs[0]({ type: 'FILE', filePath: '/Users/me/a.pdf', ts: 102 })
  assert.deepEqual(calls.readFile, [])
  assert.deepEqual(calls.upload, [])
  assert.equal(calls.toasts.length, 1)
  assert.equal(calls.toasts[0].title, 'workbenchOps.fileCaptureFailed')
})

test('IMAGE 分支不动：仍然解码后经 saveClipboardFile 上传', async () => {
  const { calls, subs } = makeEnv()
  await subs[0]({ type: 'IMAGE', data: 'data:image/png;base64,iVBORw0KGgo=', ts: 103 })
  assert.deepEqual(calls.upload, ['IMAGE'])
  assert.deepEqual(calls.localFile, [])
})

test('源码：采集桥里不再有 host.utils.readFile 调用', () => {
  assert.equal(/host\.utils\.readFile/.test(SRC), false)
  assert.ok(SRC.includes('saveClipboardLocalFile(payload.filePath)'), '锚点：FILE 分支按路径调用')
})

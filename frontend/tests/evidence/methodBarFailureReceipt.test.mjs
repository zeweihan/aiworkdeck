// 建链失败也要有可见回执（dev-board#139）：uni.showToast 在编辑器场景会被 webview
// 遮挡（#133 定性），失败只弹 toast = 「文字变成了链接但库里没记录、用户毫无感知」。
// 这里锚定：onEvidenceDrop 的失败分支（API 异常 / no_selection / 自链）一律走
// showEvidenceMethodBarError（红描边小条，钉在建链文档上），不再走 toast。
// 把失败分支改回 uni.showToast 就会转红。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/evidenceLinkActions.js', import.meta.url), 'utf8')

function extract(name, opts = {}) {
  const re = new RegExp((opts.async ? 'async ' : '') + name + '\\((.*?)\\) \\{[\\s\\S]*?\\n  \\},')
  const m = SRC.match(re)
  assert.ok(m, '没找到 ' + name)
  const body = m[0].replace(/,$/, '')
  // 对象方法简写 → 函数表达式；外部依赖经参数注入
  return new Function(
    'createEvidenceLinkForDrop', 'uni',
    'return ' + (opts.async ? 'async function ' : 'function ') + body.replace(/^async /, '')
  )
}

function makeSelf() {
  const toasts = []
  const self = {
    evidenceMethodBar: {
      visible: false, side: 'left', fileName: '', method: 'written_review',
      targetId: null, linkKey: '', docFileId: null, status: 'success', errorText: '',
    },
    activeFileLeft: { id: 10, name: '主文档.docx' },
    activeFileRight: null,
    projectId: '1',
    WPS_INTERNAL_HTTP_LINK_BASE: 'https://x.local/open',
    getLibreExecutorMap: () => ({ 'left:10': { executeCommand: async () => ({}) } }),
    $t: (k) => k,
    toasts,
  }
  const uniStub = { showToast: (o) => toasts.push(o), $emit: () => {} }
  // showEvidenceMethodBar / showEvidenceMethodBarError 用文件里的真实现
  self.showEvidenceMethodBar = extract('showEvidenceMethodBar')(null, uniStub).bind(self)
  self.showEvidenceMethodBarError = extract('showEvidenceMethodBarError')(null, uniStub).bind(self)
  return { self, uniStub, toasts }
}

const payload = { file: { id: 77, name: '营业执照.pdf', fileType: 'pdf' } }

test('API 入库失败 → 红描边小条钉在建链文档上，不弹 toast', async () => {
  const { self, uniStub, toasts } = makeSelf()
  const drop = extract('onEvidenceDrop', { async: true })(
    async () => { throw new Error('HTTP 404') }, uniStub
  )
  await drop.call(self, payload, 'left')
  assert.equal(self.evidenceMethodBar.visible, true)
  assert.equal(self.evidenceMethodBar.status, 'error')
  assert.equal(self.evidenceMethodBar.docFileId, 10)
  assert.match(self.evidenceMethodBar.errorText, /HTTP 404/)
  assert.match(self.evidenceMethodBar.errorText, /retryHint/)
  assert.equal(toasts.length, 0, '失败回执不许走 toast（会被 webview 遮挡）')
})

test('无选区 → 小条失败态提示先选文字，不弹 toast', async () => {
  const { self, uniStub, toasts } = makeSelf()
  const drop = extract('onEvidenceDrop', { async: true })(
    async () => ({ ok: false, reason: 'no_selection' }), uniStub
  )
  await drop.call(self, payload, 'left')
  assert.equal(self.evidenceMethodBar.status, 'error')
  assert.match(self.evidenceMethodBar.errorText, /selectFirst/)
  assert.equal(toasts.length, 0)
})

test('拖到自己身上 → 小条失败态，不弹 toast', async () => {
  const { self, uniStub, toasts } = makeSelf()
  const drop = extract('onEvidenceDrop', { async: true })(
    async () => { throw new Error('不该走到建链') }, uniStub
  )
  await drop.call(self, { file: { id: 10, name: '主文档.docx' } }, 'left')
  assert.equal(self.evidenceMethodBar.status, 'error')
  assert.match(self.evidenceMethodBar.errorText, /selfLink/)
  assert.equal(toasts.length, 0)
})

test('成功路径回执为成功态（status=success，method chips 语境）', async () => {
  const { self, uniStub } = makeSelf()
  const drop = extract('onEvidenceDrop', { async: true })(
    async () => ({ ok: true, linkKey: 'EVID_X', targetId: 501, view: { targets: [] } }), uniStub
  )
  await drop.call(self, payload, 'left')
  assert.equal(self.evidenceMethodBar.visible, true)
  assert.equal(self.evidenceMethodBar.status, 'success')
  assert.equal(self.evidenceMethodBar.errorText, '')
  assert.equal(self.evidenceMethodBar.linkKey, 'EVID_X')
})

test('失败态被新的一次成功整体替换（连续拖放只留最后一条）', async () => {
  const { self, uniStub } = makeSelf()
  const fail = extract('onEvidenceDrop', { async: true })(async () => { throw new Error('x') }, uniStub)
  await fail.call(self, payload, 'left')
  assert.equal(self.evidenceMethodBar.status, 'error')
  const ok = extract('onEvidenceDrop', { async: true })(
    async () => ({ ok: true, linkKey: 'EVID_Y', targetId: 1, view: { targets: [] } }), uniStub
  )
  await ok.call(self, payload, 'left')
  assert.equal(self.evidenceMethodBar.status, 'success')
  assert.equal(self.evidenceMethodBar.errorText, '')
})

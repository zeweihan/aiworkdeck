/**
 * 本地文件附件上传回归用例（dev-board#262）：
 *   node --test office-addin/taskpane/lib/chatSessionUpload.test.js
 *
 * 覆盖三条硬约束 + 重试：
 *   1. 两步上传（createProjectFile → uploadFileBytes）成功后并入 attachedFiles（真实 fileId）；
 *   2. createFile 失败不留半截状态：attachedFiles 不变、不发起字节上传，条目标失败可见错误；
 *   3. 超过 20MB 客户端直接拦截，不发起任何请求；
 *   4. 失败条目 retryUpload 后成功并入。
 * 桩后端写法与 chatSession.test.js / transfer.test.js 同款（stubFetch 按 URL 分发）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩：settings.js 的持久化走它 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

const {
  activateSession, stop, attachedFiles, uploadingFiles,
  uploadLocalFiles, removeUpload, retryUpload, UPLOAD_MAX_BYTES
} = await import('./chatSession.js')

/** 永不出数据的 SSE 响应体（建连成功后读流挂起，不影响用例收尾） */
function sseOkResponse() {
  return {
    ok: true,
    status: 200,
    body: { getReader: () => ({ read: () => new Promise(() => {}) }) }
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return {
    calls,
    restore: () => { globalThis.fetch = original }
  }
}

/** 会话激活期的公共桩：签发/建连/清单类端点全部给最小正常应答 */
function sessionRoutes(url) {
  if (url.includes('/api/ai/history')) return jsonReply([])
  if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-up-1' })
  if (url.includes('/api/agent/connect/')) return sseOkResponse()
  if (url.includes('/api/ai/models')) return jsonReply({ models: [] })
  if (url.includes('/api/skills/list')) return jsonReply([])
  if (url.includes('/api/agent/cancel/')) return jsonReply({})
  return null
}

const settings = { serverUrl: 'https://cloud.example', token: 'awdt_up' }

// 先把会话激活到项目 21（上传走 ctx.projectId）
{
  const f = stubFetch((url) => {
    const hit = sessionRoutes(url)
    if (hit) return hit
    throw new Error(`未预期的请求: ${url}`)
  })
  await activateSession({ settings, projectId: '21' })
  f.restore()
}

test('上传成功：createFile + upload 两步后并入 attachedFiles（真实 fileId）', async () => {
  attachedFiles.value = []
  uploadingFiles.value = []
  const f = stubFetch((url, options) => {
    const hit = sessionRoutes(url)
    if (hit) return hit
    if (url.includes('/api/projects/21/files/file')) {
      const body = JSON.parse(options.body)
      assert.equal(body.name, 'a.pdf')
      assert.equal(body.fileType, 'pdf')
      assert.equal(body.fileSize, 1234)
      assert.ok(body.wpsFileId)
      return jsonReply({ id: 55, name: 'a.pdf', fileType: 'pdf', wpsFileId: body.wpsFileId })
    }
    if (url.includes('/api/files/55/upload')) {
      assert.equal(options.headers['X-File-Offset'], '0')
      assert.equal(options.headers['X-File-Total-Size'], '1234')
      return jsonReply({ code: 0 })
    }
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await uploadLocalFiles([{ name: 'a.pdf', size: 1234 }])
    assert.equal(uploadingFiles.value.length, 0)
    assert.equal(attachedFiles.value.length, 1)
    assert.equal(String(attachedFiles.value[0].id), '55')
    assert.equal(attachedFiles.value[0].name, 'a.pdf')
  } finally {
    f.restore()
  }
})

test('createFile 失败不留半截状态：附件不变、不发字节上传，条目标失败可移除', async () => {
  attachedFiles.value = []
  uploadingFiles.value = []
  const f = stubFetch((url) => {
    const hit = sessionRoutes(url)
    if (hit) return hit
    if (url.includes('/files/file')) return jsonReply({ message: 'boom' }, false, 500)
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await uploadLocalFiles([{ name: 'b.docx', size: 10 }])
    assert.equal(attachedFiles.value.length, 0)
    assert.equal(uploadingFiles.value.length, 1)
    assert.equal(uploadingFiles.value[0].status, 'failed')
    assert.ok(uploadingFiles.value[0].error)
    // 第一步都没成，第二步绝不能发
    assert.ok(!f.calls.some((c) => c.url.includes('/upload')))
    // 失败条目可移除
    removeUpload(uploadingFiles.value[0].key)
    assert.equal(uploadingFiles.value.length, 0)
  } finally {
    f.restore()
  }
})

test('超过 20MB 客户端直接拦截：不发起任何请求，条目带可读错误', async () => {
  attachedFiles.value = []
  uploadingFiles.value = []
  const f = stubFetch((url) => {
    const hit = sessionRoutes(url)
    if (hit) return hit
    throw new Error(`超限文件不该发请求: ${url}`)
  })
  try {
    await uploadLocalFiles([{ name: 'huge.pdf', size: UPLOAD_MAX_BYTES + 1 }])
    assert.equal(uploadingFiles.value.length, 1)
    assert.equal(uploadingFiles.value[0].status, 'failed')
    assert.ok(uploadingFiles.value[0].error)
    assert.equal(attachedFiles.value.length, 0)
    assert.ok(!f.calls.some((c) => c.url.includes('/files/')))
    uploadingFiles.value = []
  } finally {
    f.restore()
  }
})

test('失败条目重试：retryUpload 走完两步后并入 attachedFiles', async () => {
  attachedFiles.value = []
  uploadingFiles.value = []
  let createFails = true
  const f = stubFetch((url) => {
    const hit = sessionRoutes(url)
    if (hit) return hit
    if (url.includes('/api/projects/21/files/file')) {
      if (createFails) return jsonReply({}, false, 500)
      return jsonReply({ id: 77, name: 'c.png', fileType: 'image', wpsFileId: 'w77' })
    }
    if (url.includes('/api/files/77/upload')) return jsonReply({ code: 0 })
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await uploadLocalFiles([{ name: 'c.png', size: 20 }])
    assert.equal(uploadingFiles.value[0].status, 'failed')
    createFails = false
    await retryUpload(uploadingFiles.value[0].key)
    assert.equal(uploadingFiles.value.length, 0)
    assert.equal(attachedFiles.value.length, 1)
    assert.equal(String(attachedFiles.value[0].id), '77')
  } finally {
    await stop()
    f.restore()
  }
})

/**
 * transfer.js 回归用例（dev-board#251）：
 *   node --test office-addin/taskpane/lib/transfer.test.js
 *
 * 覆盖：quote 正常返回 / 信封 code!==0 时抛出服务端文案；createPull 幂等
 * requestId 落在服务端围栏形态内；pollUntil 到终态即返回、超时则抛错；
 * MAX_TRANSFER_BYTES 常量导出。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  fetchQuote, createPull, newRequestId, pollUntil, MAX_TRANSFER_BYTES
} from './transfer.js'

/** 与 api.test.js 同款 stub（本文件独立引用，两个测试文件互不干扰） */
function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return {
    calls,
    restore: () => {
      if (original === undefined) delete globalThis.fetch
      else globalThis.fetch = original
    }
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

// 服务端幂等键围栏：^[A-Fa-f0-9-]{8,64}$
const REQUEST_ID_RE = /^[A-Fa-f0-9-]{8,64}$/

test('MAX_TRANSFER_BYTES = 200MB', () => {
  assert.equal(MAX_TRANSFER_BYTES, 200 * 1024 * 1024)
})

test('newRequestId：生成的 id 落在服务端幂等键围栏形态内', () => {
  for (let i = 0; i < 5; i++) {
    assert.match(newRequestId(), REQUEST_ID_RE)
  }
})

test('newRequestId：crypto.randomUUID 不可用时回退拼接，形态仍合规', () => {
  const original = globalThis.crypto
  Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true })
  try {
    const id = newRequestId()
    assert.match(id, REQUEST_ID_RE)
  } finally {
    Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true })
  }
})

test('fetchQuote：正常返回时把 credits/balanceCents 透传', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, credits: 12, balanceCents: 3400 }))
  try {
    const q = await fetchQuote({ serverUrl: 'https://addin.example.com', token: 'awdt_x' }, 10485760)
    assert.equal(q.credits, 12)
    assert.equal(q.balanceCents, 3400)
    assert.ok(f.calls[0].url.includes('/api/mobile/transfer/quote?bytes=10485760'))
    assert.equal(f.calls[0].options.headers['X-Session-Id'], 'awdt_x')
  } finally {
    f.restore()
  }
})

test('fetchQuote：balanceCents 为空时回落 null（服务端未桥接账户）', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, credits: 1, balanceCents: null }))
  try {
    const q = await fetchQuote({ serverUrl: 'https://addin.example.com', token: 't' }, 100)
    assert.equal(q.balanceCents, null)
  } finally {
    f.restore()
  }
})

test('信封 code!==0 时抛出服务端 message（余额不足等用户可读文案）', async () => {
  const f = stubFetch(() => jsonReply({ code: 1, message: '账户余额不足，请先充值' }))
  try {
    await assert.rejects(
      () => fetchQuote({ serverUrl: 'https://addin.example.com', token: 't' }, 100),
      (err) => {
        assert.equal(err.message, '账户余额不足，请先充值')
        return true
      }
    )
  } finally {
    f.restore()
  }
})

test('HTTP 非 2xx 时抛出可读的连接失败文案', async () => {
  const f = stubFetch(() => jsonReply({ error: 'boom' }, false, 500))
  try {
    await assert.rejects(() => fetchQuote({ serverUrl: 'https://addin.example.com', token: 't' }, 100))
  } finally {
    f.restore()
  }
})

test('地址为空时不发请求，直接抛出配置缺失错误', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, credits: 1 }))
  try {
    await assert.rejects(() => fetchQuote({ serverUrl: '', token: 't' }, 100))
    assert.equal(f.calls.length, 0)
  } finally {
    f.restore()
  }
})

test('createPull：请求体带上完整参数与合规形态的 requestId', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, id: 'xfer-1', credits: 3 }))
  try {
    const requestId = newRequestId()
    const result = await createPull({ serverUrl: 'https://addin.example.com', token: 't' }, {
      deviceId: 'dev-a', projectKey: '42', remoteFileId: 'f-1', fileName: 'a.docx', fileSize: 1024, requestId
    })
    assert.equal(result.id, 'xfer-1')
    assert.equal(result.credits, 3)
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.deviceId, 'dev-a')
    assert.equal(body.projectKey, '42')
    assert.equal(body.remoteFileId, 'f-1')
    assert.equal(body.fileName, 'a.docx')
    assert.equal(body.fileSize, 1024)
    assert.match(body.requestId, REQUEST_ID_RE)
    assert.ok(f.calls[0].url.endsWith('/api/mobile/transfer/pull'))
  } finally {
    f.restore()
  }
})

test('createPull：撞既有 requestId 时服务端幂等回既有行，客户端原样透传', async () => {
  const requestId = newRequestId()
  const f = stubFetch(() => jsonReply({ code: 0, id: 'xfer-existing', credits: 3 }))
  try {
    const first = await createPull({ serverUrl: 'https://addin.example.com', token: 't' }, {
      deviceId: 'dev-a', projectKey: '42', remoteFileId: 'f-1', fileName: 'a.docx', fileSize: 1024, requestId
    })
    const second = await createPull({ serverUrl: 'https://addin.example.com', token: 't' }, {
      deviceId: 'dev-a', projectKey: '42', remoteFileId: 'f-1', fileName: 'a.docx', fileSize: 1024, requestId
    })
    assert.deepEqual(first, second)
  } finally {
    f.restore()
  }
})

// ==================== pollUntil ====================

test('pollUntil：fn 返回终态即停止，不再等待下一轮', async () => {
  let calls = 0
  const result = await pollUntil(async () => {
    calls += 1
    return calls >= 3 ? { status: 'DONE' } : null
  }, { intervalMs: 5, timeoutMs: 5000 })
  assert.deepEqual(result, { status: 'DONE' })
  assert.equal(calls, 3)
})

test('pollUntil：首次调用即返回终态时不等待', async () => {
  const result = await pollUntil(async () => ({ status: 'STAGED' }), { intervalMs: 5, timeoutMs: 5000 })
  assert.deepEqual(result, { status: 'STAGED' })
})

test('pollUntil：一直拿不到终态，超时抛出可读错误', async () => {
  await assert.rejects(
    () => pollUntil(async () => null, { intervalMs: 5, timeoutMs: 30 }),
    /.+/
  )
})

test('pollUntil：fn 本身抛错时原样向上抛出（不吞掉网络错误）', async () => {
  await assert.rejects(
    () => pollUntil(async () => { throw new Error('网络错误') }, { intervalMs: 5, timeoutMs: 5000 }),
    /网络错误/
  )
})

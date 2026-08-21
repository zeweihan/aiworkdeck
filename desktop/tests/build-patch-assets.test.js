// dev-board#74 稳定性审计：loadPrevManifest 原先把"拉不到上一版 manifest"一律
// 吞成 null 并打印一条"首个补丁版本属正常"的告警。CI 每次 tag 构建都用
// --prev https://www.aiworkdeck.com/update/desktop/manifest.json，只要遇上一次网络
// 抖动 / 15s 超时 / nginx reload 期间的 5xx，生成的 manifest 就只剩当前大版本一条
// 通道（channels 从 {} 起算，latestMajor/majorDownloadPage/telemetryUrl 也回落默认
// 值），随后被 update-mirror-sync.sh 原子替换到线上，旧大版本用户静默失去增量更新。
//
// 口径：只有 404 / ENOENT（确实没有上一版）才返回 null，其余失败重试后抛错炸构建。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')

const { loadPrevManifest } = require('../scripts/build-patch-assets')

const FAST = { attempts: 3, retryDelayMs: 5 }

// 起一个可编程的本地 http server，模拟镜像站的各种响应
function startServer(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler)
    srv.listen(0, '127.0.0.1', () => resolve({ srv, url: `http://127.0.0.1:${srv.address().port}/manifest.json` }))
  })
}

const PREV = {
  schema: 1,
  latestMajor: '0.22',
  majorDownloadPage: 'https://www.aiworkdeck.com/start',
  telemetryUrl: 'https://addin.aiworkdeck.com/api/update/telemetry',
  channels: { '0.20': { latest: '0.20.3', components: [] }, '0.22': { latest: '0.22.0', components: [] } }
}

test('镜像 500：重试后抛错炸构建，绝不吞成 null', async (t) => {
  let hits = 0
  const { srv, url } = await startServer((req, res) => { hits++; res.writeHead(500); res.end('bad gateway') })
  t.after(() => srv.close())
  await assert.rejects(() => loadPrevManifest(url, FAST), /拉取失败，已重试 3 次/)
  assert.strictEqual(hits, 3, '应当重试满 3 次')
})

test('WAF 返回 200 + HTML（JSON 解析失败）：抛错，不得当成首个补丁版本', async (t) => {
  const { srv, url } = await startServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' }); res.end('<html>blocked</html>')
  })
  t.after(() => srv.close())
  await assert.rejects(() => loadPrevManifest(url, FAST), /拉取失败，已重试 3 次/)
})

test('schema 不是 1：立刻抛错，不重试也不吞', async (t) => {
  let hits = 0
  const { srv, url } = await startServer((req, res) => {
    hits++; res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ schema: 2 }))
  })
  t.after(() => srv.close())
  await assert.rejects(() => loadPrevManifest(url, FAST), /schema 不是 1/)
  assert.strictEqual(hits, 1, 'schema 不符是确定性错误，不该重试')
})

test('镜像 404：视为首个补丁版本，返回 null（既有行为不变）', async (t) => {
  const { srv, url } = await startServer((req, res) => { res.writeHead(404); res.end('not found') })
  t.after(() => srv.close())
  assert.strictEqual(await loadPrevManifest(url, FAST), null)
})

test('本地文件不存在：返回 null（既有行为不变）', async () => {
  assert.strictEqual(await loadPrevManifest(path.join(os.tmpdir(), 'no-such-manifest-xyz.json'), FAST), null)
})

test('未传 --prev：返回 null（既有行为不变）', async () => {
  assert.strictEqual(await loadPrevManifest(undefined, FAST), null)
})

test('正常拿到上一版：原样返回，channels/latestMajor 等字段完整', async (t) => {
  const { srv, url } = await startServer((req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(PREV))
  })
  t.after(() => srv.close())
  const m = await loadPrevManifest(url, FAST)
  assert.deepStrictEqual(Object.keys(m.channels).sort(), ['0.20', '0.22'])
  assert.strictEqual(m.telemetryUrl, PREV.telemetryUrl)
})

test('第一次抖动、第二次成功：重试救回上一版，通道不丢', async (t) => {
  let hits = 0
  const { srv, url } = await startServer((req, res) => {
    hits++
    if (hits === 1) { res.writeHead(502); res.end('bad gateway'); return }
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(PREV))
  })
  t.after(() => srv.close())
  const m = await loadPrevManifest(url, FAST)
  assert.deepStrictEqual(Object.keys(m.channels).sort(), ['0.20', '0.22'])
})

test('本地路径：能正常读回上一版 manifest', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'patch-prev-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const fp = path.join(dir, 'manifest.json')
  fs.writeFileSync(fp, JSON.stringify(PREV))
  const m = await loadPrevManifest(fp, FAST)
  assert.strictEqual(m.latestMajor, '0.22')
})

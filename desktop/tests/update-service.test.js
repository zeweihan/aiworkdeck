const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')
const crypto = require('crypto')
const { spawnSync } = require('child_process')
const overlay = require('../main/services/overlay')
const { createUpdateService } = require('../main/services/update-service')

// 端到端（本地 HTTP 伪造更新服务器）：manifest 验签 → 组件下载 → sha256 →
// 解压 → 激活 → 状态机；以及验签失败 / 哈希不符 / 降级拒绝三条失败路径。

const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519')

function makeTarGz(files, workRoot) {
  const src = fs.mkdtempSync(path.join(workRoot, 'tar-src-'))
  for (const [rel, content] of Object.entries(files)) {
    const fp = path.join(src, rel)
    fs.mkdirSync(path.dirname(fp), { recursive: true })
    fs.writeFileSync(fp, content)
  }
  const out = path.join(workRoot, `asset-${Math.random().toString(36).slice(2)}.tar.gz`)
  const r = spawnSync('tar', ['-czf', out, '-C', src, '.'])
  assert.strictEqual(r.status, 0, 'tar available in test env')
  return fs.readFileSync(out)
}

function extractTar(archive, destDir) {
  return new Promise((resolve, reject) => {
    const r = spawnSync('tar', ['-xzf', archive, '-C', destDir])
    r.status === 0 ? resolve() : reject(new Error('tar extract failed'))
  })
}

// 起一个一次性更新服务器，routes: { '/manifest.json': fn|buffer, ... }
function serve(routes) {
  return new Promise((resolve) => {
    const s = http.createServer((req, res) => {
      const body = routes[req.url]
      if (body === undefined) {
        res.writeHead(404).end()
        return
      }
      res.writeHead(200)
      res.end(typeof body === 'function' ? body() : body)
    })
    s.listen(0, '127.0.0.1', () => resolve({ server: s, origin: `http://127.0.0.1:${s.address().port}` }))
  })
}

function signedManifestRoutes(manifest, extraRoutes = {}, signWith = privateKey) {
  const bytes = Buffer.from(JSON.stringify(manifest))
  const sig = crypto.sign(null, bytes, signWith).toString('base64')
  return { '/manifest.json': bytes, '/manifest.json.sig': Buffer.from(sig), ...extraRoutes }
}

function testEnv(t, origin, pubkeyPem) {
  const keyFile = path.join(os.tmpdir(), `upd-pub-${process.pid}-${Math.random().toString(36).slice(2)}.pem`)
  fs.writeFileSync(keyFile, pubkeyPem)
  process.env.CHECKBA_UPDATE_MANIFEST_URL = origin + '/manifest.json'
  process.env.CHECKBA_UPDATE_PUBKEY_FILE = keyFile
  t.after(() => {
    delete process.env.CHECKBA_UPDATE_MANIFEST_URL
    delete process.env.CHECKBA_UPDATE_PUBKEY_FILE
    fs.rmSync(keyFile, { force: true })
  })
}

const pubPem = publicKey.export({ type: 'spki', format: 'pem' })

test('补丁端到端：验签+下载+校验+激活，事件序正确', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.0' }

  const jarTar = makeTarGz({ 'app.jar': 'patched-jar-bytes' }, root)
  const h5Tar = makeTarGz({ 'index.html': '<html>v2</html>', 'static/x.js': 'js' }, root)
  // 先起服务器拿到 origin（组件 urls 要写真实端口），再挂真正的路由表
  const { server, origin } = await serve({})
  t.after(() => server.close())
  server.removeAllListeners('request')
  const manifest = {
    schema: 1,
    latestMajor: '0.11',
    channels: {
      '0.11': {
        latest: '0.11.2',
        components: [
          { name: 'backend-app', version: '0.11.2', sha256: crypto.createHash('sha256').update(jarTar).digest('hex'), size: jarTar.length, urls: [origin + '/backend.tar.gz'] },
          { name: 'frontend-h5', version: '0.11.2', sha256: crypto.createHash('sha256').update(h5Tar).digest('hex'), size: h5Tar.length, urls: [origin + '/h5.tar.gz'] }
        ]
      }
    }
  }
  const routes = signedManifestRoutes(manifest, { '/backend.tar.gz': jarTar, '/h5.tar.gz': h5Tar })
  server.on('request', (req, res) => {
    const body = routes[req.url]
    if (body === undefined) return void res.writeHead(404).end()
    res.writeHead(200).end(typeof body === 'function' ? body() : body)
  })

  testEnv(t, origin, pubPem)
  const events = []
  const svc = createUpdateService(ctx, { extractTar, onEvent: (e) => events.push(e.type) })
  const state = await svc.check()

  assert.strictEqual(state.phase, 'ready')
  assert.deepStrictEqual(state.available, { version: '0.11.2' })
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.2')
  const jarDir = overlay.componentDir(ctx, 'backend-app')
  assert.strictEqual(fs.readFileSync(path.join(jarDir, 'app.jar'), 'utf8'), 'patched-jar-bytes')
  const h5Dir = overlay.componentDir(ctx, 'frontend-h5')
  assert.strictEqual(fs.readFileSync(path.join(h5Dir, 'static', 'x.js'), 'utf8'), 'js')
  assert.ok(events.includes('checking') && events.includes('downloading') && events.includes('ready'))
  // staging 清理干净
  assert.ok(!fs.existsSync(overlay.stagingDir(ctx)))
})

test('验签失败：错误密钥签名的 manifest 被整体拒绝', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.0' }
  const evil = crypto.generateKeyPairSync('ed25519')
  const { server, origin } = await serve(signedManifestRoutes(
    { schema: 1, latestMajor: '0.11', channels: { '0.11': { latest: '0.11.9', components: [] } } },
    {},
    evil.privateKey // 攻击者自己的私钥
  ))
  t.after(() => server.close())
  testEnv(t, origin, pubPem)

  const svc = createUpdateService(ctx, { extractTar })
  const state = await svc.check()
  assert.strictEqual(state.phase, 'error')
  assert.match(state.error, /验签失败/)
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.0') // 分毫未动
})

test('组件哈希不符：丢弃整批不激活', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.0' }
  const tarBytes = makeTarGz({ 'app.jar': 'tampered' }, root)
  let origin
  const routes = () => signedManifestRoutes({
    schema: 1,
    channels: { '0.11': { latest: '0.11.1', components: [
      { name: 'backend-app', version: '0.11.1', sha256: 'deadbeef'.repeat(8), size: tarBytes.length, urls: [origin + '/a.tar.gz'] }
    ] } }
  }, { '/a.tar.gz': tarBytes })
  const { server, origin: o } = await serve({})
  origin = o
  server.removeAllListeners('request')
  const table = routes()
  server.on('request', (req, res) => {
    const body = table[req.url]
    if (body === undefined) return void res.writeHead(404).end()
    res.writeHead(200).end(body)
  })
  t.after(() => server.close())
  testEnv(t, origin, pubPem)

  const svc = createUpdateService(ctx, { extractTar })
  const state = await svc.check()
  assert.strictEqual(state.phase, 'error')
  assert.match(state.error, /sha256 校验失败/)
  assert.strictEqual(overlay.readCurrent(ctx), null)
})

test('降级与已最新：不高于生效版本的清单不触发下载', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.5' }
  const { server, origin } = await serve(signedManifestRoutes({
    schema: 1,
    latestMajor: '0.11',
    channels: { '0.11': { latest: '0.11.3', components: [{ name: 'backend-app', version: '0.11.3', sha256: 'x', size: 1, urls: ['http://127.0.0.1:1/nope'] }] } }
  }))
  t.after(() => server.close())
  testEnv(t, origin, pubPem)

  const svc = createUpdateService(ctx, { extractTar })
  const state = await svc.check()
  assert.strictEqual(state.phase, 'idle') // up-to-date，未尝试拉取任何 asset
  assert.strictEqual(state.available, null)
})

test('大版本：latestMajor 更高时给出全量下载引导，不下补丁', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.0' }
  const { server, origin } = await serve(signedManifestRoutes({
    schema: 1,
    latestMajor: '0.12',
    majorDownloadPage: 'https://www.aiworkdeck.com/download',
    channels: {}
  }))
  t.after(() => server.close())
  testEnv(t, origin, pubPem)

  const events = []
  const svc = createUpdateService(ctx, { extractTar, onEvent: (e) => events.push(e.type) })
  const state = await svc.check()
  assert.deepStrictEqual(state.majorAvailable, { major: '0.12', page: 'https://www.aiworkdeck.com/download' })
  assert.ok(events.includes('major-available'))
  assert.strictEqual(overlay.readCurrent(ctx), null)
})

test('组件级去重：本机已有同版本组件时跳过下载仍推进指针', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'upd-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { packaged: true, dataDir: root, appVersion: '0.11.0' }
  // 先装到 0.11.1（组件版本 0.11.1）
  const st = path.join(overlay.stagingDir(ctx), 'backend-app')
  fs.mkdirSync(st, { recursive: true })
  fs.writeFileSync(path.join(st, 'app.jar'), 'v1')
  overlay.activate(ctx, '0.11.1', { 'backend-app': st })

  // 0.11.2 清单：backend-app 沿用 0.11.1（内容未变），无需任何下载
  const { server, origin } = await serve(signedManifestRoutes({
    schema: 1,
    channels: { '0.11': { latest: '0.11.2', components: [
      { name: 'backend-app', version: '0.11.1', sha256: 'irrelevant', size: 1, urls: ['http://127.0.0.1:1/unreachable'] }
    ] } }
  }))
  t.after(() => server.close())
  testEnv(t, origin, pubPem)

  const svc = createUpdateService(ctx, { extractTar })
  const state = await svc.check()
  assert.strictEqual(state.phase, 'ready')
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.2')
  assert.strictEqual(overlay.readCurrent(ctx).components['backend-app'].version, '0.11.1')
})

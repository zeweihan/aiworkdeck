// 内嵌 draw.io 静态服务的回归测试。
//
// 守几件真出过事故 / 明确设计契约的东西：
// 1. URL 参数里的 stealth=1 —— 它是「案件材料不出网」这条红线在 draw.io 侧的落点。
//    删掉之后功能完全正常，只是编辑器会开始往外发请求，没有测试就没人会发现。
// 2. 路径穿越 —— URL 是不可信输入，静态服务把根目录之外的文件读出来是经典事故。
// 3. native pack 多根查找（NATIVE_PACK_DISTRIBUTION.md §4.4）：内置根优先，
//    pack 根惰性解析（revoked / 无完成标记的版本不参与）。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-'))
process.env.AIWORKDECK_DRAWIO_DIR = root
// packs 根同样隔离到临时目录：不设的话会落到真实 <home>/.aiworkdeck/packs，
// 在装过 native pack 的机器上会让下面「没资源」的用例变得不确定。
const packsBase = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-base-'))
process.env.AIWORKDECK_PACKS_DIR = packsBase
const { startDrawioServer, stopDrawioServer, drawioUrl, isAvailable } = require('../main/drawio-server')

// 在给定 packs 根目录下造一份「装好的 pack」布局：
//   <packsDir>/litigation-visual/current.json
//   <packsDir>/litigation-visual/<version>/.pack-complete（除非 opts.noComplete）
//   <packsDir>/litigation-visual/<version>/drawio/index.html
function seedPack(packsDir, opts = {}) {
  const version = opts.version || '1.0.0'
  const versionDir = path.join(packsDir, 'litigation-visual', version)
  fs.mkdirSync(path.join(versionDir, 'drawio'), { recursive: true })
  fs.writeFileSync(path.join(versionDir, 'drawio', 'index.html'), opts.html || '<html>PACK-DRAWIO</html>')
  if (!opts.noComplete) fs.writeFileSync(path.join(versionDir, '.pack-complete'), '')
  const current = { version, activatedAt: new Date().toISOString() }
  if (opts.revoked) current.revoked = true
  fs.writeFileSync(path.join(packsDir, 'litigation-visual', 'current.json'), JSON.stringify(current))
}

function get(origin, urlPath) {
  return new Promise((resolve, reject) => {
    http
      // agent:false —— 这个文件里同一个 FIXED_PORT 会先后起停好几个不同的
      // server 实例（每个用例各自的 pack/内置根）。默认全局 agent 会在请求间
      // 复用 keep-alive socket；旧 server 关闭后，那个被复用的 socket 有一定
      // 概率还没被 agent 摘出空闲池就被派给新请求，新 server 那头看到的是一个
      // 半死的连接，直接 ECONNRESET（"socket hang up"）。禁用 agent 逼每次
      // 请求都开新连接，端口复用测试才稳定。
      .get(origin + urlPath, { agent: false }, (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }))
      })
      .on('error', reject)
  })
}

test('URL 必须带 stealth=1 与 embed 协议参数', () => {
  const url = drawioUrl('http://127.0.0.1:1')
  assert.ok(url.includes('stealth=1'), 'stealth=1 掉了就等于允许编辑器出网')
  assert.ok(url.includes('embed=1'), 'embed=1 掉了就不是嵌入模式')
  assert.ok(url.includes('proto=json'), 'proto=json 掉了 postMessage 协议对不上')
  assert.ok(url.includes('lang=zh'), '界面应为中文')
})

test('资源没烙进构建时 isAvailable 为 false', async () => {
  assert.strictEqual(await isAvailable(), false)
})

test('烙好之后能起、能取文件、挡得住路径穿越', async (t) => {
  fs.writeFileSync(path.join(root, 'index.html'), '<html>drawio</html>')
  fs.mkdirSync(path.join(root, 'js'), { recursive: true })
  fs.writeFileSync(path.join(root, 'js', 'app.min.js'), 'console.log(1)')
  // 根目录之外放一个「机密」文件，用来验证穿越被挡住
  const outside = path.join(root, '..', 'drawio-outside-secret.txt')
  fs.writeFileSync(outside, 'SECRET')
  t.after(async () => {
    // 不关服务的话 node --test 的事件循环永远不空，测试跑完也不退出
    await stopDrawioServer()
    fs.rmSync(outside, { force: true })
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  })

  assert.strictEqual(await isAvailable(), true)
  const { origin } = await startDrawioServer()

  const idx = await get(origin, '/')
  assert.strictEqual(idx.status, 200)
  assert.match(idx.body, /drawio/)

  const js = await get(origin, '/js/app.min.js')
  assert.strictEqual(js.status, 200)

  // 穿越：URL 里带 ../ 直接指到根目录外那个文件
  const escaped = await get(origin, '/../drawio-outside-secret.txt')
  assert.notStrictEqual(escaped.status, 200, '路径穿越必须被挡住')
  assert.ok(!escaped.body.includes('SECRET'))

  const missing = await get(origin, '/nope.js')
  assert.strictEqual(missing.status, 404)
})

test('pack 根命中：内置资源缺失时从 pack 提供文件', async (t) => {
  const emptyBuiltin = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-builtin-empty-'))
  const packsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-'))
  seedPack(packsDir)

  process.env.AIWORKDECK_DRAWIO_DIR = emptyBuiltin
  process.env.AIWORKDECK_PACKS_DIR = packsDir
  t.after(async () => {
    await stopDrawioServer()
    fs.rmSync(emptyBuiltin, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    fs.rmSync(packsDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  })

  assert.strictEqual(await isAvailable(), true, '内置根没有 index.html，应当落到 pack 根')
  const { origin } = await startDrawioServer()
  const res = await get(origin, '/')
  assert.strictEqual(res.status, 200)
  assert.match(res.body, /PACK-DRAWIO/)
})

test('revoked:true 的 pack 版本不参与解析', async (t) => {
  const emptyBuiltin = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-builtin-empty-'))
  const packsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-'))
  seedPack(packsDir, { revoked: true })

  process.env.AIWORKDECK_DRAWIO_DIR = emptyBuiltin
  process.env.AIWORKDECK_PACKS_DIR = packsDir
  t.after(() => {
    fs.rmSync(emptyBuiltin, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    fs.rmSync(packsDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  })

  assert.strictEqual(await isAvailable(), false, 'current.json 标了 revoked，pack 根不该被当成可用')
})

test('版本目录缺 .pack-complete 时不参与解析', async (t) => {
  const emptyBuiltin = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-builtin-empty-'))
  const packsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-'))
  seedPack(packsDir, { noComplete: true })

  process.env.AIWORKDECK_DRAWIO_DIR = emptyBuiltin
  process.env.AIWORKDECK_PACKS_DIR = packsDir
  t.after(() => {
    fs.rmSync(emptyBuiltin, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    fs.rmSync(packsDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  })

  assert.strictEqual(await isAvailable(), false, '没有 .pack-complete 说明安装事务未完成，不该被当成可用')
})

test('内置根优先于 pack 根', async (t) => {
  const builtinDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-builtin-'))
  fs.writeFileSync(path.join(builtinDir, 'index.html'), '<html>BUILTIN-DRAWIO</html>')
  const packsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-'))
  seedPack(packsDir)

  process.env.AIWORKDECK_DRAWIO_DIR = builtinDir
  process.env.AIWORKDECK_PACKS_DIR = packsDir
  t.after(async () => {
    await stopDrawioServer()
    fs.rmSync(builtinDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    fs.rmSync(packsDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  })

  const { origin } = await startDrawioServer()
  const res = await get(origin, '/')
  assert.strictEqual(res.status, 200)
  assert.match(res.body, /BUILTIN-DRAWIO/)
  assert.doesNotMatch(res.body, /PACK-DRAWIO/)
})

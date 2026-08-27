// 内嵌 draw.io 静态服务的回归测试。
//
// 守几件真出过事故 / 明确设计契约的东西：
// 1. URL 参数里的 stealth=1 —— 它是「案件材料不出网」这条红线在 draw.io 侧的落点。
//    删掉之后功能完全正常，只是编辑器会开始往外发请求，没有测试就没人会发现。
// 2. 路径穿越 —— URL 是不可信输入，静态服务把根目录之外的文件读出来是经典事故。
// 3. native pack 多根查找（NATIVE_PACK_DISTRIBUTION.md §4.4）：内置根优先，
//    pack 根惰性解析（revoked / 无完成标记的版本不参与）。

const test = require('node:test')

// CI 按改动面跳过（desktop-build.yml 的「Detect drawio changes」步骤注入）：
// 这组用例要真起 HTTP 服务 + 真读写 Temp 目录，是 Windows runner 上 Defender
// 锁临时文件 EPERM 的主要来源（dev-board#146，多次发版被它咬）。本区间没碰
// drawio 服务/本测试/资源脚本时整套不打；tag 发版与手动触发永远全量。
if (process.env.SKIP_DRAWIO_TESTS === '1') {
  test('drawio-server 全套用例跳过（本区间未改动 drawio）', { skip: true }, () => {})
  return
}

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

// teardown 删临时目录统一走这里：Windows 上刚被读流/杀毒扫描碰过的文件有
// delete-pending 窗口，目录里的 unlink 已成功但句柄未释放，紧跟着的 rmdir 会
// 报 ENOTEMPTY（CI windows-latest 间歇复现，run 32236896837）。
// 必须用异步 rm 而不是 rmSync：rmSync 的 maxRetries 是同步忙等，会把事件循环
// 一起卡死——当句柄正是本进程读流 autoClose 排队待跑的 fs.close 时，同步重试
// 给多大预算都永远等不到释放（run 32237671073 实测 5.6s 预算整段跑穿）。
// fs.promises.rm 的重试间隔走 setTimeout，让出事件循环，挂起的 close 才有机会执行。
//
// 外层再包一圈 EPERM/EBUSY 短重试：windows-latest 上 Defender 实时扫描会拿着
// 排他句柄锁临时文件，rm 内部扫描目录的 lstat 直接报 EPERM（形态：hookFailed
// "EPERM: operation not permitted, lstat '...\Temp\drawio-*\index.html'"，
// 2026-08-24 一天内咬了两次、rerun 即绿，run 32709185491）。rm 自带的
// maxRetries=10/retryDelay=100 总预算约 5.5s，扫描锁窗口可以更长，且内部
// 重试次数用完就把 EPERM 原样抛出——t.after 里一抛就是 hookFailed。这里每轮
// 都是完整重跑 rm（诚实重试，不跳过、不吞其它错误码），重试仍失败就照常抛。
async function rmrf(dir) {
  const RETRIES = 3
  for (let attempt = 0; ; attempt++) {
    try {
      return await fs.promises.rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    } catch (e) {
      if ((e.code !== 'EPERM' && e.code !== 'EBUSY') || attempt >= RETRIES) throw e
      await new Promise((r) => setTimeout(r, 500 * (attempt + 1)))
    }
  }
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
    await rmrf(root)
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
    await rmrf(emptyBuiltin)
    await rmrf(packsDir)
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
  t.after(async () => {
    await rmrf(emptyBuiltin)
    await rmrf(packsDir)
  })

  assert.strictEqual(await isAvailable(), false, 'current.json 标了 revoked，pack 根不该被当成可用')
})

test('版本目录缺 .pack-complete 时不参与解析', async (t) => {
  const emptyBuiltin = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-builtin-empty-'))
  const packsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-'))
  seedPack(packsDir, { noComplete: true })

  process.env.AIWORKDECK_DRAWIO_DIR = emptyBuiltin
  process.env.AIWORKDECK_PACKS_DIR = packsDir
  t.after(async () => {
    await rmrf(emptyBuiltin)
    await rmrf(packsDir)
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
    await rmrf(builtinDir)
    await rmrf(packsDir)
  })

  const { origin } = await startDrawioServer()
  const res = await get(origin, '/')
  assert.strictEqual(res.status, 200)
  assert.match(res.body, /BUILTIN-DRAWIO/)
  assert.doesNotMatch(res.body, /PACK-DRAWIO/)
})

test('读流中途出错只失败这一个请求，不能掀掉整个进程', async (t) => {
  // 造「stat 成功但 open 失败」：chmod 000 之后 stat 只需要父目录的搜索权限，
  // 仍然报告是个普通文件，而 createReadStream 打开时 EACCES。这比真去抢
  // stat 与 open 之间那几毫秒（删目录/拔盘/杀毒锁文件）稳定得多，暴露的是
  // 同一条路径：ReadStream 的 'error' 没人听 → Node 直接抛 → 主进程没有
  // uncaughtException 兜底 → 整个 Electron 应用当场消失。
  if (process.platform === 'win32' || (process.getuid && process.getuid() === 0)) {
    t.skip('Windows 与 root 下权限位不生效，造不出 stat 成功而 open 失败的文件')
    return
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-unreadable-'))
  const emptyPacks = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-packs-empty-'))
  fs.writeFileSync(path.join(dir, 'index.html'), '<html>drawio</html>')
  const locked = path.join(dir, 'locked.js')
  fs.writeFileSync(locked, 'console.log(1)')
  fs.chmodSync(locked, 0o000)

  process.env.AIWORKDECK_DRAWIO_DIR = dir
  process.env.AIWORKDECK_PACKS_DIR = emptyPacks
  t.after(async () => {
    await stopDrawioServer()
    fs.chmodSync(locked, 0o600)
    await rmrf(dir)
    await rmrf(emptyPacks)
  })

  const { origin } = await startDrawioServer()
  // 200 的头（含 Content-Length）已经定了，改不回 500，所以约定是掐断这一条连接：
  // 客户端看到的是一次传输失败，而不是长度对不上的半截文件。
  await assert.rejects(get(origin, '/locked.js'), '读不出来的文件应当让这一个请求失败')
  // 进程还活着的直接证据：同一个 server 继续正常服务下一个请求。
  const idx = await get(origin, '/')
  assert.strictEqual(idx.status, 200)
  assert.match(idx.body, /drawio/)
})

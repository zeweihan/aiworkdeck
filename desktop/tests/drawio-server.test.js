// 内嵌 draw.io 静态服务的回归测试。
//
// 守两件真出过事故的东西：
// 1. URL 参数里的 stealth=1 —— 它是「案件材料不出网」这条红线在 draw.io 侧的落点。
//    删掉之后功能完全正常，只是编辑器会开始往外发请求，没有测试就没人会发现。
// 2. 路径穿越 —— URL 是不可信输入，静态服务把根目录之外的文件读出来是经典事故。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'drawio-'))
process.env.AIWORKDECK_DRAWIO_DIR = root
const { startDrawioServer, stopDrawioServer, drawioUrl, isAvailable } = require('../main/drawio-server')

function get(origin, urlPath) {
  return new Promise((resolve, reject) => {
    http
      .get(origin + urlPath, (res) => {
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
    fs.rmSync(root, { recursive: true, force: true })
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

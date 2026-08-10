// drawio-server.js — 内嵌 draw.io 编辑器的本地静态服务。
//
// 「诉讼可视化」出的 .drawio 是唯一的可继续编辑版；这个 server 让它在应用内直接
// 打开编辑，而不是变成一个必须装别的软件才能用的死文件。资源由
// desktop/scripts/fetch-drawio-assets.js 在构建期烙进 frontend/dist/drawio，
// 经 extraResources 装包，**运行期完全离线**——案件材料不出网是硬要求。
//
// WHY 单开一个 server 而不是挂在 zetaoffice-server 上：那条链路给 LOWA 带着
// COOP/COEP 跨源隔离（SharedArrayBuffer 的前置条件），并且在 persist:zetaoffice
// 分区上注入响应头。draw.io 一样都不需要——它是纯 DOM 应用，跑在主分区的普通
// <iframe> 里就行。混进去等于给它套一层用不上的约束，还把两个升级节奏不同的
// 组件绑在了一起。
//
// WHY 固定端口：与 zetaoffice-server 同理。Chromium 的磁盘缓存按 origin+URL 存，
// listen(0) 每次启动换端口 = 40 MB 资源每次冷启动重下重解析。端口被占（开了第二个
// 实例 / dev 与打包版并存）就退回随机端口，功能不受影响，只是少了跨启动的缓存复用。

const path = require('path')
const http = require('node:http')
const { stat } = require('node:fs/promises')
const { createReadStream } = require('node:fs')

const FIXED_PORT = 47614 // zetaoffice 用 47613，挨着放便于排查

// electron 只在壳里存在。惰性取 + 容错，是为了让这个模块能在纯 node 下被单测
// （与 services/overlay.js 用 ctx 入参而非全局 app 是同一个考虑）。
function electronApp() {
  try {
    return require('electron').app
  } catch (e) {
    return null
  }
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.xml': 'application/xml; charset=utf-8',
}

let serverPromise = null

// AIWORKDECK_DRAWIO_DIR 覆盖资源目录：dev 时指向自己解出来的 draw.io，
// 单测里指向临时目录。与后端那侧注入 LITVIZ_DIR / AWD_PYTHON_HOME 同一套思路。
function drawioRoot() {
  if (process.env.AIWORKDECK_DRAWIO_DIR) return process.env.AIWORKDECK_DRAWIO_DIR
  const app = electronApp()
  return app && app.isPackaged
    ? path.join(process.resourcesPath, 'frontend/dist/drawio')
    : path.join(__dirname, '../../frontend/dist/drawio')
}

/** draw.io 资源是否已烙进本次构建。dev 树上没跑过 fetch 脚本时为 false。 */
async function isAvailable() {
  try {
    const st = await stat(path.join(drawioRoot(), 'index.html'))
    return st.isFile()
  } catch (e) {
    return false
  }
}

/**
 * 启动（或复用）draw.io 静态服务。
 * @returns {Promise<{port:number, origin:string}>}
 */
function startDrawioServer() {
  if (serverPromise) return serverPromise
  const root = drawioRoot()
  serverPromise = new Promise((resolve, reject) => {
    const s = http.createServer(async (req, res) => {
      try {
        let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
        if (urlPath === '/') urlPath = '/index.html'
        const filePath = path.normalize(path.join(root, urlPath))
        // 路径穿越防护：URL 是不可信输入，normalize 之后必须仍在根下。
        if (filePath !== root && !filePath.startsWith(root + path.sep)) {
          res.writeHead(403).end('forbidden')
          return
        }
        const st = await stat(filePath)
        if (!st.isFile()) throw new Error('not a file')
        // ETag + no-cache：draw.io 升级后文件名多数不变（app.min.js 等），
        // 必须让浏览器回源校验；本地 304 只要 1ms，命中后仍复用已缓存的正文。
        const etag = '"' + st.size + '-' + Math.round(st.mtimeMs) + '"'
        const cacheHeaders = { ETag: etag, 'Cache-Control': 'public, no-cache' }
        if (req.headers['if-none-match'] === etag) {
          res.writeHead(304, cacheHeaders)
          res.end()
          return
        }
        res.writeHead(200, {
          'Content-Type': TYPES[path.extname(filePath)] || 'application/octet-stream',
          'Content-Length': st.size,
          ...cacheHeaders,
        })
        createReadStream(filePath).pipe(res)
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found')
      }
    })
    let fellBack = false
    s.on('listening', () => {
      const port = s.address().port
      resolve({ port, origin: 'http://127.0.0.1:' + port, server: s })
    })
    s.on('error', (e) => {
      if (!fellBack && e && e.code === 'EADDRINUSE') {
        fellBack = true
        s.listen(0, '127.0.0.1')
        return
      }
      serverPromise = null
      reject(e)
    })
    s.listen(FIXED_PORT, '127.0.0.1')
  })
  return serverPromise
}

/**
 * 内嵌编辑器的完整 URL。
 *
 * 参数逐个都是有意的：
 * - embed=1&proto=json  走 postMessage 协议，宿主负责读写文件（见 DrawioEditor.vue）
 * - stealth=1           禁掉一切外发请求。法律工具不能让案件材料有出网路径，
 *                       这个参数是那条红线在 draw.io 侧的落点。
 * - lang=zh             中文界面
 * - ui=min              精简工具栏，与工作台的密度一致
 * - spin=1              加载期显示转圈，别让用户对着白屏猜
 */
function drawioUrl(origin) {
  const q = 'embed=1&proto=json&stealth=1&spin=1&lang=zh&ui=min&noSaveBtn=0&saveAndExit=0'
  return origin + '/index.html?' + q
}

/** 关掉静态服务并清掉记忆化（单测收尾用；应用生命周期内不需要主动调）。 */
async function stopDrawioServer() {
  const p = serverPromise
  if (!p) return
  serverPromise = null
  try {
    const s = await p.then((r) => r.server)
    if (s) await new Promise((resolve) => s.close(resolve))
  } catch (e) { /* 起都没起来，无需关闭 */ }
}

module.exports = { startDrawioServer, stopDrawioServer, drawioUrl, isAvailable }

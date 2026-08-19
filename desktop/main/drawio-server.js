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
const os = require('node:os')
const http = require('node:http')
const fs = require('node:fs')
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

// native pack（docs/NATIVE_PACK_DISTRIBUTION.md）：诉讼可视化的 draw.io 资源摘出
// 安装包之后，广场下载安装到 <home>/.aiworkdeck/packs/litigation-visual/<version>/drawio。
const PACK_ID = 'litigation-visual'
const PACK_COMPONENT = 'drawio'

// packs 根目录：AIWORKDECK_PACKS_DIR 覆盖仅供单测/开发指向临时目录，
// 与 AIWORKDECK_DRAWIO_DIR 是同一套「显式覆盖」思路，正常运行时走
// <home>/.aiworkdeck/packs（与 overlay.js 的 dataDir 同一个 home 惯例）。
function packsBaseDir() {
  if (process.env.AIWORKDECK_PACKS_DIR) return process.env.AIWORKDECK_PACKS_DIR
  const app = electronApp()
  const home = (app && app.getPath('home')) || os.homedir()
  return path.join(home, '.aiworkdeck', 'packs')
}

// pack 根惰性解析：每次调用都现读 current.json，装完即生效、Electron 不需要重启
// （NATIVE_PACK_DISTRIBUTION.md §4.4）。fs 开销可忽略，不做任何缓存。
// 三种情况判定该根不参与：current.json 缺失/不是合法 JSON、revoked:true、
// 指向的版本目录没有 .pack-complete 完成标记。
function packRoot() {
  try {
    const packDir = path.join(packsBaseDir(), PACK_ID)
    const cur = JSON.parse(fs.readFileSync(path.join(packDir, 'current.json'), 'utf8'))
    if (!cur || typeof cur.version !== 'string' || cur.revoked === true) return null
    const versionDir = path.join(packDir, cur.version)
    if (!fs.existsSync(path.join(versionDir, '.pack-complete'))) return null
    return path.join(versionDir, PACK_COMPONENT)
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

// 单根解析（内置资源）：AIWORKDECK_DRAWIO_DIR 覆盖优先——dev 时指向自己解出来的
// draw.io，单测里指向临时目录（与后端那侧注入 LITVIZ_DIR / AWD_PYTHON_HOME 同一套
// 思路）；否则打包态取 Resources/frontend/dist/drawio，dev 态取源树里的
// frontend/dist/drawio（跑过 fetch-drawio-assets.js 才有）。
function builtinRoot() {
  if (process.env.AIWORKDECK_DRAWIO_DIR) return process.env.AIWORKDECK_DRAWIO_DIR
  const app = electronApp()
  return app && app.isPackaged
    ? path.join(process.resourcesPath, 'frontend/dist/drawio')
    : path.join(__dirname, '../../frontend/dist/drawio')
}

// 请求时按序命中的根列表（NATIVE_PACK_DISTRIBUTION.md §4.4，照抄
// zetaoffice-server.js editorRoots() 的双根手法，这里是两根）：
//   1. 内置根（builtinRoot，含 AIWORKDECK_DRAWIO_DIR 覆盖）——只有目录里真的有
//      index.html 才算一根，不存在就跳过（老版本随包资源仍在时优先用它，
//      不强迫改吃 pack）。
//   2. pack 当前版本目录——惰性解析，见 packRoot()。
function drawioRoots() {
  const roots = []
  const builtin = builtinRoot()
  if (fs.existsSync(path.join(builtin, 'index.html'))) roots.push(builtin)
  const pr = packRoot()
  if (pr) roots.push(pr)
  return roots
}

/** draw.io 资源是否在任一根就位（内置或 pack 皆可）。 */
async function isAvailable() {
  for (const root of drawioRoots()) {
    try {
      const st = await stat(path.join(root, 'index.html'))
      if (st.isFile()) return true
    } catch (e) { /* 该根没有，试下一根 */ }
  }
  return false
}

/**
 * 启动（或复用）draw.io 静态服务。
 * @returns {Promise<{port:number, origin:string}>}
 */
function startDrawioServer() {
  if (serverPromise) return serverPromise
  serverPromise = new Promise((resolve, reject) => {
    const s = http.createServer(async (req, res) => {
      try {
        let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
        if (urlPath === '/') urlPath = '/index.html'
        // 根列表每次请求现读（pack 装完即生效，不用重启 Electron）。
        const roots = drawioRoots()
        let hit = null
        for (const root of roots) {
          const filePath = path.normalize(path.join(root, urlPath))
          // 路径穿越防护：URL 是不可信输入，normalize 之后必须仍在根下。
          if (filePath !== root && !filePath.startsWith(root + path.sep)) {
            res.writeHead(403).end('forbidden')
            return
          }
          try {
            const st = await stat(filePath)
            if (st.isFile()) { hit = { filePath, st }; break }
          } catch (e) { /* 该根没有此文件，试下一根 */ }
        }
        if (!hit) throw new Error('not found in any root')
        const { filePath, st } = hit
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
    if (s) {
      // server.close() 只停止接受新连接，已建立的 keep-alive 连接（哪怕已经
      // 处理完上一个请求、正闲置着）不会被它主动断开——callback 会等这些连接
      // 自然结束才触发。单测里连续起停多个服务、且客户端复用了 keep-alive 连接时，
      // 这条空档会让「close() 已 resolve」与「端口真的空出来」脱节：下一个服务
      // 一样绑同一个 FIXED_PORT，客户端的连接池却仍拿着指向旧 server 的那个
      // socket，一复用就是 ECONNRESET/socket hang up。closeAllConnections()
      // 强制切断，保证 stop 完成时端口与连接都真正清干净。
      if (typeof s.closeAllConnections === 'function') s.closeAllConnections()
      await new Promise((resolve) => s.close(resolve))
    }
  } catch (e) { /* 起都没起来，无需关闭 */ }
}

module.exports = { startDrawioServer, stopDrawioServer, drawioUrl, isAvailable }

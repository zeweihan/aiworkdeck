// build-patch-assets.js — 小版本补丁产物 + 签名 manifest（增量更新设计 §5/§9）。
//
// 在 tag 构建末尾（单个 runner）跑一次，产出：
//   <out>/patch-backend-app-<ver>.tar.gz        后端业务 jar（app.jar）
//   <out>/patch-frontend-h5-<ver>.tar.gz        h5 整包
//   <out>/patch-zetaoffice-wrapper-<ver>.tar.gz 编辑器壳层（排除 LOWA 引擎与字体）
//   <out>/manifest.json + manifest.json.sig     Ed25519 签名清单
//
// 组件版本去重：与上一版 manifest 的 contentHash 相同的组件沿用旧版本号与旧
// asset URL（客户端便跳过下载）。补丁产物平台无关（业务 jar / h5 / 壳层 js
// 均不含原生二进制），mac 与 win 共用。
//
// Usage:
//   node scripts/build-patch-assets.js --version 0.11.2 \
//     --backend <app.jar> --h5 <h5 dist dir> --zeta <zetaoffice dist dir> \
//     --out <out dir> [--pysvc <bundled pysvc dir>] \
//     [--prev <上一版 manifest 的 URL 或本地路径>]
//
// 签名私钥经 env UPDATE_SIGNING_KEY（PEM 文本，CI secret）传入；
// 缺失时报错退出（无签名的 manifest 客户端不会接受）。

const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { spawnSync } = require('child_process')
const https = require('https')
const http = require('http')

const MIRROR_ASSET_BASE = 'https://www.aiworkdeck.com/update/desktop/assets/'
const GH_RELEASE_BASE = 'https://github.com/zeweihan/aiworkdeck/releases/download/'
const DOWNLOAD_PAGE = 'https://www.aiworkdeck.com'

// 壳层组件排除项：LOWA 引擎与全部 CJK 字体只随大版本走（fetch-lowa-assets 烙入）。
// 字体必须按前缀排，不能只列 cjk.ttc——cjk-kai.ttf(23.6MB)/cjk-serif.otf(11.1MB)/
// cjk-fangsong.ttf(8.4MB) 曾漏网，让 v0.11.0 的壳层补丁涨到 26.9MB（业务代码仅 0.3MB）。
const zetaExcluded = (name) => name === 'lowa' || /^cjk[.-]/.test(name)

// pysvc-src 排除项：字节码缓存（客户端 Python 会自行重建）与随服务烙入的字体
// 资产（app/fonts/NotoSansSC-Regular.ttf 15.7MB）——同理只随大版本走。
const PYSVC_SRC_EXCLUDE_DIRS = new Set(['__pycache__', 'fonts'])

function parseArgs(argv) {
  const args = {}
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) args[argv[i].slice(2)] = argv[++i]
  }
  return args
}

function fail(msg) {
  console.error(`[build-patch-assets] ${msg}`)
  process.exit(1)
}

// Windows 优先 System32 bsdtar（PATH 里的 GNU tar 会把盘符冒号当远程主机，
// 见 prepare-python-service.js / pysvc-runtime.js 同款地雷）
function tarCmd() {
  if (process.platform === 'win32') {
    const sys = process.env.SystemRoot || 'C:\\Windows'
    const bsdtar = path.join(sys, 'System32', 'tar.exe')
    if (fs.existsSync(bsdtar)) return bsdtar
    return 'tar.exe'
  }
  return 'tar'
}

function walkFiles(root, dir = root, out = []) {
  for (const en of fs.readdirSync(dir, { withFileTypes: true })) {
    const fp = path.join(dir, en.name)
    if (en.isDirectory()) walkFiles(root, fp, out)
    else if (en.isFile()) out.push(path.relative(root, fp).split(path.sep).join('/'))
  }
  return out
}

function sha256File(fp) {
  return crypto.createHash('sha256').update(fs.readFileSync(fp)).digest('hex')
}

// 组件内容指纹：对（排序后的相对路径 + 文件 sha256）再做 sha256。
// tar.gz 字节不可复现（时间戳/顺序），跨构建判等必须基于内容而非包字节。
function contentHash(dir) {
  const files = walkFiles(dir).sort()
  const h = crypto.createHash('sha256')
  for (const rel of files) {
    h.update(rel)
    h.update('\0')
    h.update(sha256File(path.join(dir, rel)))
    h.update('\n')
  }
  return h.digest('hex')
}

function makeTar(srcDir, outFile) {
  const r = spawnSync(tarCmd(), ['-czf', outFile, '-C', srcDir, '.'], { stdio: 'inherit' })
  if (r.status !== 0) fail(`tar 打包失败: ${outFile}`)
}

function fetchText(url) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('http:') ? http : https
    const req = mod.get(url, { timeout: 15000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume()
        return resolve(fetchText(new URL(res.headers.location, url).toString()))
      }
      if (res.statusCode !== 200) {
        res.resume()
        // 带上 statusCode：调用方要靠它区分"确实没有上一版"(404) 与"这次没拿到"(其余)
        return reject(Object.assign(new Error(`HTTP ${res.statusCode}: ${url}`), { statusCode: res.statusCode }))
      }
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve(body))
      res.on('error', reject)
    })
    req.on('timeout', () => req.destroy(new Error('timeout')))
    req.on('error', reject)
  })
}

// 拉取上一版 manifest。**只有"确实不存在"才允许返回 null**（URL 404 / 本地文件缺失
// ——即首个补丁版本）；网络抖动、超时、5xx、WAF 返回的 HTML、schema 不符一律重试后炸掉
// 构建。原因：prev 为 null 时下面的 channels 会退成 {}，latestMajor/majorDownloadPage/
// telemetryUrl 也全部回落默认值，生成的 manifest 只剩当前大版本一条通道；
// deploy/update-mirror-sync.sh 会把它原子替换到线上，已发布的其它大版本通道就此被
// 静默抹掉（无报错、无告警、无备份），停在旧大版本的用户从此收不到增量补丁。
// 宁可让 tag 构建红着重跑，也不能悄悄发一份残缺清单。
async function loadPrevManifest(prev, { attempts = 3, retryDelayMs = 2000 } = {}) {
  if (!prev) return null
  const isUrl = /^https?:/.test(prev)
  let lastErr = null
  for (let i = 1; i <= attempts; i++) {
    try {
      const text = isUrl ? await fetchText(prev) : fs.readFileSync(prev, 'utf8')
      const m = JSON.parse(text)
      if (!m || m.schema !== 1) {
        throw Object.assign(new Error(`上一版 manifest schema 不是 1（拿到 ${m && m.schema}）: ${prev}`), { fatal: true })
      }
      return m
    } catch (e) {
      if (e.statusCode === 404 || e.code === 'ENOENT') {
        console.warn(`[build-patch-assets] 上一版 manifest 不存在（首个补丁版本属正常）: ${prev}`)
        return null
      }
      if (e.fatal) throw e
      lastErr = e
      console.warn(`[build-patch-assets] 上一版 manifest 拉取失败（第 ${i}/${attempts} 次）: ${e.message}`)
      if (i < attempts) await new Promise((r) => setTimeout(r, retryDelayMs * i))
    }
  }
  throw new Error(`上一版 manifest 拉取失败，已重试 ${attempts} 次: ${prev} — ${lastErr && lastErr.message}`)
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  for (const k of ['version', 'backend', 'h5', 'zeta', 'out']) {
    if (!args[k]) fail(`缺少 --${k}`)
  }
  const version = args.version
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(version)
  if (!m) fail(`版本号不合法: ${version}`)
  const major = `${m[1]}.${m[2]}`

  const signingKey = process.env.UPDATE_SIGNING_KEY
  if (!signingKey) fail('env UPDATE_SIGNING_KEY 缺失（Ed25519 私钥 PEM）——无签名的 manifest 客户端不接受')
  const privateKey = crypto.createPrivateKey(signingKey)

  const outDir = path.resolve(args.out)
  fs.rmSync(outDir, { recursive: true, force: true })
  fs.mkdirSync(outDir, { recursive: true })

  // --- 组装三个组件的暂存目录 ------------------------------------------------
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'patch-assets-'))
  const stage = {}

  // backend-app：单文件 app.jar
  if (!fs.existsSync(args.backend)) fail(`app.jar 不存在: ${args.backend}`)
  stage['backend-app'] = path.join(work, 'backend-app')
  fs.mkdirSync(stage['backend-app'], { recursive: true })
  fs.copyFileSync(args.backend, path.join(stage['backend-app'], 'app.jar'))

  // frontend-h5：整目录
  if (!fs.existsSync(path.join(args.h5, 'index.html'))) fail(`h5 目录不完整（缺 index.html）: ${args.h5}`)
  stage['frontend-h5'] = path.resolve(args.h5)

  // zetaoffice-wrapper：壳层文件（排除引擎）
  stage['zetaoffice-wrapper'] = path.join(work, 'zetaoffice-wrapper')
  fs.mkdirSync(stage['zetaoffice-wrapper'], { recursive: true })
  for (const en of fs.readdirSync(args.zeta, { withFileTypes: true })) {
    if (zetaExcluded(en.name)) continue
    fs.cpSync(path.join(args.zeta, en.name), path.join(stage['zetaoffice-wrapper'], en.name), { recursive: true })
  }

  // pysvc-src（P3）：Python 服务源码层（各服务 app/ 目录，不含 pip 依赖 lib/）。
  // 客户端由 pysvc-runtime.syncSrcPatch 覆盖进解压树（带备份可回滚）。
  if (args.pysvc && fs.existsSync(args.pysvc)) {
    stage['pysvc-src'] = path.join(work, 'pysvc-src')
    fs.mkdirSync(stage['pysvc-src'], { recursive: true })
    for (const en of fs.readdirSync(args.pysvc, { withFileTypes: true })) {
      const appDir = path.join(args.pysvc, en.name, 'app')
      if (en.isDirectory() && fs.existsSync(appDir)) {
        fs.cpSync(appDir, path.join(stage['pysvc-src'], en.name, 'app'), {
          recursive: true,
          filter: (src) => !PYSVC_SRC_EXCLUDE_DIRS.has(path.basename(src))
        })
      }
    }
  }

  // --- 与上一版 manifest 做内容级去重 ---------------------------------------
  const prev = await loadPrevManifest(args.prev)
  const prevComponents = new Map()
  if (prev && prev.channels && prev.channels[major]) {
    for (const c of prev.channels[major].components || []) prevComponents.set(c.name, c)
  }

  const components = []
  for (const [name, dir] of Object.entries(stage)) {
    const hash = contentHash(dir)
    const prevComp = prevComponents.get(name)
    if (prevComp && prevComp.contentHash === hash) {
      console.log(`[build-patch-assets] ${name} 内容未变，沿用 ${prevComp.version}`)
      components.push(prevComp)
      continue
    }
    const assetName = `patch-${name}-${version}.tar.gz`
    const assetPath = path.join(outDir, assetName)
    makeTar(dir, assetPath)
    const st = fs.statSync(assetPath)
    components.push({
      name,
      version,
      sha256: sha256File(assetPath),
      size: st.size,
      contentHash: hash,
      urls: [
        MIRROR_ASSET_BASE + assetName,
        `${GH_RELEASE_BASE}v${version}/${assetName}`
      ]
    })
    console.log(`[build-patch-assets] ${name} -> ${assetName} (${(st.size / 1048576).toFixed(1)}MB)`)
  }

  // --- 体积自检 --------------------------------------------------------------
  // 补丁的全部意义就是小。任何一次"大文件漏进排除规则"（v0.11.0 的 CJK 字体与
  // pysvc 字体）都会静默把补丁涨成几十 MB，用户侧只会表现为"更新有点慢"而不会
  // 报错——所以在产出时就炸，别等用户发现。
  const MAX_COMPONENT_MB = 8
  const oversized = components.filter((c) => c.size > MAX_COMPONENT_MB * 1048576)
  if (oversized.length && !process.env.ALLOW_LARGE_PATCH) {
    console.error('[build-patch-assets] 补丁组件体积异常（多半是大文件漏进了排除规则）：')
    for (const c of oversized) console.error(`  ${c.name}: ${(c.size / 1048576).toFixed(1)}MB > ${MAX_COMPONENT_MB}MB`)
    fail('确认体积合理后可用 ALLOW_LARGE_PATCH=1 放行')
  }

  // --- 生成并签名 manifest ---------------------------------------------------
  const channels = { ...(prev && prev.channels ? prev.channels : {}) }
  channels[major] = {
    latest: version,
    notes: `https://github.com/zeweihan/aiworkdeck/releases/tag/v${version}`,
    components
  }
  // latestMajor 单调不降：本次发布的大版本与上一版记录取大者
  let latestMajor = major
  if (prev && prev.latestMajor) {
    const [pa, pb] = prev.latestMajor.split('.').map(Number)
    if (pa > Number(m[1]) || (pa === Number(m[1]) && pb > Number(m[2]))) latestMajor = prev.latestMajor
  }
  const manifest = {
    schema: 1,
    generatedAt: new Date().toISOString(),
    latestMajor,
    majorDownloadPage: (prev && prev.majorDownloadPage) || DOWNLOAD_PAGE,
    ...(prev && prev.telemetryUrl ? { telemetryUrl: prev.telemetryUrl } : {}),
    channels
  }
  const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2))
  fs.writeFileSync(path.join(outDir, 'manifest.json'), manifestBytes)
  const sig = crypto.sign(null, manifestBytes, privateKey)
  fs.writeFileSync(path.join(outDir, 'manifest.json.sig'), sig.toString('base64') + '\n')
  fs.rmSync(work, { recursive: true, force: true })
  console.log(`[build-patch-assets] done: ${outDir}`)
}

// CLI 入口只在直接执行本文件时跑；被 require() 当模块用时（单测）只取函数。
if (require.main === module) {
  main().catch((e) => fail(e.stack || String(e)))
}

module.exports = { loadPrevManifest, fetchText }

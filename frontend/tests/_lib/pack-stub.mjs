// 原生资源包（native pack）测试桩：现生成一对 Ed25519 密钥、现造一个极小的 pack
// 产物（manifest.json + .sig + 一个 tar.gz 组件 + 包内 contents.sha256），起一个
// 本地 HTTP 静态源把它们 serve 出来。供 app-e2e J13「广场安装带资源包的插件」用。
//
// 契约照 docs/NATIVE_PACK_DISTRIBUTION.md §2/§3：manifest 签名盖在原始字节上，
// 组件内自带 contents.sha256，安装端逐文件复核。签名用的私钥只活在这个进程里，
// 公钥经被测后端的 --ai.plugins.registry-public-key 命令行参数注入——pack 与
// JAR 插件共用同一把注册表公钥（NativePackService 的验签逻辑），测试桩没有理由
// 另立一套，直接复用同一个校验路径。
//
// 归档刻意带一份填充文件把体积撑到几百 KB、且下载端点故意分块限速：不这样做的话
// 本地回环网络几毫秒就能传完，MarketDetailPane 的下载进度分支（bytesTotal>0 时才
// 渲染的那一档 UI）在真实断言窗口里根本来不及被轮询到，「进度出现」这条断言会变成
// 纯粹的运气——这里的延迟不是模拟真实网络，是让进度条状态有时间存在。
//
// HTTP 服务器刻意跑在独立 worker 线程（不是随 startPackStub() 调用方共用主线程的
// 事件循环）：2026-08-20 排查 J13「下载卡在固定字节不再推进」发现，run.mjs 全程在
// 一个 Node 进程里驱动 Puppeteer/CDP，J1-J12 若干旅程的 page.evaluate 结果处理、
// GC 都在同一条事件循环上跑；archive 端点靠 setTimeout 链一路推 20 多个分块，主
// 线程只要有一次忙起来拖住事件循环几百毫秒，pump() 的下一个 tick 就跟着迟到——
// 两轮复现都卡在同一字节数（~120KB，约合 5 个分块），正是事件循环卡顿早期截断的
// 痕迹，不是 NativePackService 下载逻辑本身的问题（同一份代码单独起隔离后端直连
// 这个桩反复验证，从未卡过）。真实场景的 pack 源是独立机器上的 nginx，不共享任何
// 客户端事件循环，不会被这类问题命中。挪进 worker 线程后，pump() 的计时不再受
// 主线程忙闲影响，测试桩本身更健壮。

import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads'

const CHUNK_BYTES = 24 * 1024
const CHUNK_DELAY_MS = 120

function sha256Hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex')
}

/**
 * 造 pack 产物到 dir 下：manifest.json / manifest.json.sig / <archive>。
 * 返回 { id, version, archiveName, archivePath, publicKeyPem }。
 */
function buildFixture(dir, { id, version, minAppVersion = '0.1.0' }) {
  fs.mkdirSync(dir, { recursive: true })
  const stage = path.join(dir, 'stage')
  fs.mkdirSync(stage, { recursive: true })

  fs.writeFileSync(path.join(stage, 'README.txt'), 'app-e2e J13 pack fixture — not a real litviz engine\n')
  fs.writeFileSync(path.join(stage, 'hello.json'), JSON.stringify({ hello: 'world', ts: Date.now() }) + '\n')
  // 纯填充，撑体积好让下载有可观测的进行时长（见文件头注释）；内容对断言无意义。
  fs.writeFileSync(path.join(stage, 'payload.bin'), crypto.randomBytes(560 * 1024))

  const files = ['README.txt', 'hello.json', 'payload.bin']
  const contents = files
    .map((f) => `${sha256Hex(fs.readFileSync(path.join(stage, f)))}  ${f}`)
    .join('\n') + '\n'
  fs.writeFileSync(path.join(stage, 'contents.sha256'), contents)

  const archiveName = `litviz-${version}.tar.gz`
  const archivePath = path.join(dir, archiveName)
  // macOS tar 默认会为 xattr 生成 AppleDouble（._*）伴生条目，COPYFILE_DISABLE 关掉它——
  // 那些条目会被后端 extract() 的「非常规条目」防护直接拒绝解压。
  execFileSync('tar', ['-czf', archivePath, '-C', stage, ...files, 'contents.sha256'], {
    env: { ...process.env, COPYFILE_DISABLE: '1' },
  })

  const archiveBuf = fs.readFileSync(archivePath)
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519')
  const publicKeyPem = publicKey.export({ type: 'spki', format: 'pem' }).toString()

  const manifestObj = {
    schema: 1,
    id,
    version,
    publishedAt: new Date().toISOString(),
    minAppVersion,
    engineApi: 1,
    components: [
      {
        name: 'litviz',
        platforms: ['*'],
        archive: archiveName,
        size: archiveBuf.length,
        sha256: sha256Hex(archiveBuf),
        unpackDir: 'litviz',
      },
    ],
  }
  const manifestBytes = Buffer.from(JSON.stringify(manifestObj, null, 2), 'utf8')
  fs.writeFileSync(path.join(dir, 'manifest.json'), manifestBytes)
  const sig = crypto.sign(null, manifestBytes, privateKey) // Ed25519：算法固定为 null（内置哈希）
  fs.writeFileSync(path.join(dir, 'manifest.json.sig'), sig.toString('base64'))

  return { id, version, archiveName, archivePath, archiveSize: archiveBuf.length, publicKeyPem }
}

/** worker 线程体：造 fixture、起 HTTP 服务器，通过 parentPort 把端口/公钥报回主线程。 */
function runWorkerServer() {
  const { dir, id, version, minAppVersion } = workerData
  const fx = buildFixture(dir, { id, version, minAppVersion })
  const manifestBytes = fs.readFileSync(path.join(dir, 'manifest.json'))
  const sigBytes = fs.readFileSync(path.join(dir, 'manifest.json.sig'))
  const archiveBuf = fs.readFileSync(fx.archivePath)

  // 诊断口：AWD_PACK_STUB_DEBUG_LOG 指一个文件路径时，把每个请求的 method/url/头
  // （含代理相关头）、archive 端点每个 chunk 的写入时间戳、连接 close/aborted 都落盘。
  // 默认不开（不设该环境变量则完全零开销），排查 J13 卡死专用。
  const dbgLogPath = process.env.AWD_PACK_STUB_DEBUG_LOG
  const dbg = dbgLogPath
    ? (line) => { try { fs.appendFileSync(dbgLogPath, `[${new Date().toISOString()}] ${line}\n`) } catch (e) { /* ignore */ } }
    : null

  const server = http.createServer((req, res) => {
    if (dbg) {
      dbg(`REQ ${req.method} ${req.url} remote=${req.socket.remoteAddress}:${req.socket.remotePort} headers=${JSON.stringify(req.headers)}`)
      req.on('aborted', () => dbg(`REQ ABORTED ${req.url}`))
      req.on('close', () => dbg(`REQ CLOSE ${req.url}`))
      res.on('close', () => dbg(`RES CLOSE ${req.url} finished=${res.writableFinished}`))
      res.on('error', (e) => dbg(`RES ERROR ${req.url} ${e && e.message}`))
    }
    const url = new URL(req.url, 'http://internal')
    const prefix = `/plugin-packs/${id}/`
    if (!url.pathname.startsWith(prefix)) { res.writeHead(404); res.end(); return }
    const rel = url.pathname.slice(prefix.length)
    if (rel === 'manifest.json') {
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(manifestBytes); return
    }
    if (rel === 'manifest.json.sig') {
      res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end(sigBytes); return
    }
    if (rel === `${version}/${fx.archiveName}`) {
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': String(archiveBuf.length) })
      let offset = 0
      let chunkN = 0
      const pump = () => {
        if (offset >= archiveBuf.length) { if (dbg) dbg(`ARCHIVE END chunks=${chunkN} bytes=${offset}`); res.end(); return }
        const end = Math.min(offset + CHUNK_BYTES, archiveBuf.length)
        const ok = res.write(archiveBuf.subarray(offset, end))
        chunkN++
        if (dbg) dbg(`ARCHIVE CHUNK #${chunkN} offset=${offset} end=${end} write()=${ok}`)
        offset = end
        setTimeout(pump, CHUNK_DELAY_MS)
      }
      pump()
      return
    }
    res.writeHead(404); res.end('not found: ' + rel)
  })

  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port
    parentPort.postMessage({
      type: 'ready', port, publicKeyPem: fx.publicKeyPem, version: fx.version, archiveSize: fx.archiveSize,
    })
  })

  parentPort.on('message', (msg) => {
    if (msg === 'close') {
      try { server.closeAllConnections?.() } catch (e) { /* 老 Node 没有这个方法也无妨 */ }
      server.close(() => { parentPort.postMessage({ type: 'closed' }) })
    }
  })
}

if (!isMainThread) {
  runWorkerServer()
}

/**
 * 起本地 HTTP pack 源，serve `/plugin-packs/<id>/manifest.json(.sig)` 与
 * `/plugin-packs/<id>/<version>/<archive>`（与 PackProperties.baseUrls 的拼接
 * 规则一致）。archive 端点故意分块限速，manifest/sig 照常即时返回。服务器本体
 * 跑在独立 worker 线程，见文件头「HTTP 服务器刻意跑在独立 worker 线程」一节。
 *
 * @returns {{ url: string, publicKeyPem: string, version: string, archiveSize: number, close: () => Promise<void> }}
 */
export async function startPackStub(dir, { id = 'litigation-visual', version = '9.9.9', minAppVersion } = {}) {
  const worker = new Worker(new URL(import.meta.url), { workerData: { dir, id, version, minAppVersion } })
  const ready = await new Promise((resolve, reject) => {
    worker.once('error', reject)
    worker.once('exit', (code) => reject(new Error('pack-stub worker exited early (code ' + code + ')')))
    worker.on('message', (msg) => { if (msg && msg.type === 'ready') resolve(msg) })
  })
  return {
    url: `http://127.0.0.1:${ready.port}/plugin-packs`,
    publicKeyPem: ready.publicKeyPem,
    version: ready.version,
    archiveSize: ready.archiveSize,
    close: () => new Promise((resolve) => {
      const done = () => { clearTimeout(fallback); worker.terminate().then(resolve, resolve) }
      const fallback = setTimeout(done, 2000) // worker 万一没应答，兜底强制终止，别让 close() 悬着
      worker.on('message', (msg) => { if (msg && msg.type === 'closed') done() })
      try { worker.postMessage('close') } catch (e) { done() }
    }),
  }
}

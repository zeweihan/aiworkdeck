const path = require('path')
const fs = require('fs')
const http = require('http')
const https = require('https')
const crypto = require('crypto')
const overlay = require('./overlay')

/**
 * update-service.js — 应用内增量更新（设计文档 docs/INCREMENTAL_UPDATE_DESIGN.md §5-§7）。
 *
 * 职责：拉 manifest → Ed25519 验签 → 判定大/小版本 → 小版本逐组件下载到
 * staging（sha256 校验、按 urls 顺序降级）→ overlay.activate 原子激活 →
 * 通知渲染层"重启后生效"。大版本只提示 + 引导官网下载全量包。
 *
 * 安全不变式（§8）：
 *   - manifest 验签失败 / 组件 sha256 不符 → 丢弃整批，本次不更新；
 *   - 只接受高于当前生效版本的补丁（overlay.activate 再挡一道防降级）；
 *   - 绝不激活未验证内容。
 */

// 更新通道公钥（Ed25519）。私钥在 CI secret UPDATE_SIGNING_KEY，
// 备份在维护者本机 ~/.ssh/aiworkdeck_update_signing.pem。换钥需发大版本。
const UPDATE_PUBKEY_PEM = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEARzNmFaY6rOsiGPnduwi+O09OROxpYidwiQNLUXksFMs=
-----END PUBLIC KEY-----`

// manifest 地址：官网镜像优先（大陆可达），GitHub Release 兜底。
// CHECKBA_UPDATE_MANIFEST_URL 供 e2e/排障显式覆盖（叠加自定义公钥需同时设
// CHECKBA_UPDATE_PUBKEY_FILE——仅本机环境变量可设，不扩大远程攻击面）。
const DEFAULT_MANIFEST_URLS = [
  'https://www.aiworkdeck.com/update/desktop/manifest.json',
  'https://github.com/zeweihan/aiworkdeck/releases/latest/download/manifest.json'
]

const CHECK_DELAY_MS = 2 * 60 * 1000        // 启动后首查延迟
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000 // 周期检查
const MAX_COMPONENT_BYTES = 512 * 1024 * 1024
const FETCH_TIMEOUT_MS = 30000

function manifestUrls() {
  if (process.env.CHECKBA_UPDATE_MANIFEST_URL) return [process.env.CHECKBA_UPDATE_MANIFEST_URL]
  return DEFAULT_MANIFEST_URLS
}

function publicKey() {
  // e2e 覆盖：从本机文件读测试公钥；生产路径永远用内置常量
  const f = process.env.CHECKBA_UPDATE_PUBKEY_FILE
  if (f) return crypto.createPublicKey(fs.readFileSync(f, 'utf8'))
  return crypto.createPublicKey(UPDATE_PUBKEY_PEM)
}

// GET 一个 URL，跟随最多 5 次重定向（GitHub Release asset 会 302 到对象存储）。
// 可选边下边写文件 + sha256 边算，避免大文件驻留内存。
function fetchUrl(url, { toFile = null, maxBytes = MAX_COMPONENT_BYTES, timeoutMs = FETCH_TIMEOUT_MS, redirects = 0, onBytes = null } = {}) {
  return new Promise((resolve, reject) => {
    let u
    try { u = new URL(url) } catch (e) { return reject(new Error(`bad url: ${url}`)) }
    const mod = u.protocol === 'http:' ? http : https
    const req = mod.get(u, { timeout: timeoutMs }, (res) => {
      const sc = res.statusCode || 0
      if (sc >= 300 && sc < 400 && res.headers.location && redirects < 5) {
        res.resume()
        return resolve(fetchUrl(new URL(res.headers.location, url).toString(), { toFile, maxBytes, timeoutMs, redirects: redirects + 1, onBytes }))
      }
      if (sc !== 200) {
        res.resume()
        return reject(new Error(`HTTP ${sc}: ${url}`))
      }
      const hash = crypto.createHash('sha256')
      let total = 0
      const chunks = []
      const out = toFile ? fs.createWriteStream(toFile) : null
      res.on('data', (c) => {
        total += c.length
        if (total > maxBytes) {
          req.destroy(new Error(`响应超过大小上限 ${maxBytes}: ${url}`))
          return
        }
        hash.update(c)
        if (out) out.write(c)
        else chunks.push(c)
        if (onBytes) onBytes(total)
      })
      res.on('end', () => {
        const done = () => resolve({ bytes: out ? null : Buffer.concat(chunks), sha256: hash.digest('hex'), size: total })
        if (out) out.end(done)
        else done()
      })
      res.on('error', reject)
    })
    req.on('timeout', () => req.destroy(new Error(`timeout: ${url}`)))
    req.on('error', reject)
  })
}

// 按 urls 顺序尝试（镜像优先、GitHub 兜底）
async function fetchFirst(urls, opts) {
  let lastErr = null
  for (const url of urls) {
    try {
      const r = await fetchUrl(url, opts)
      return { ...r, url }
    } catch (e) {
      lastErr = e
    }
  }
  throw lastErr || new Error('no urls')
}

function createUpdateService(ctx, hooks = {}) {
  // ctx: { packaged, dataDir, appVersion }
  // hooks: { onEvent(evt), extractTar(archive, destDir) => Promise, onLog }
  const state = {
    phase: 'idle', // idle | checking | downloading | ready | error
    checkedAt: null,
    effectiveVersion: overlay.effectiveVersion(ctx),
    appVersion: ctx.appVersion,
    available: null,       // { version } 补丁已就绪待重启
    majorAvailable: null,  // { major, page } 新大版本
    progress: null,        // { component, received, total }
    error: null
  }
  let timer = null
  let checking = null
  let telemetryUrl = null

  function logEvent(type, data) {
    const evt = { ts: new Date().toISOString(), type, ...data }
    try {
      const dir = path.join(ctx.dataDir, 'logs')
      fs.mkdirSync(dir, { recursive: true })
      fs.appendFileSync(path.join(dir, 'update.log'), JSON.stringify(evt) + '\n')
    } catch (e) { /* 日志失败不阻断更新 */ }
    // 失败遥测（P3）：manifest 声明 telemetryUrl 才发送，fire-and-forget，
    // 只报事件类型与版本号，不含任何用户数据
    if (telemetryUrl && (type === 'error' || type === 'reverted')) {
      try {
        const u = new URL(telemetryUrl)
        const mod = u.protocol === 'http:' ? http : https
        const req = mod.request(u, { method: 'POST', headers: { 'Content-Type': 'application/json' }, timeout: 3000 })
        req.on('error', () => {})
        req.on('timeout', () => req.destroy())
        req.end(JSON.stringify({ type, version: state.appVersion, effective: state.effectiveVersion, detail: data && data.message }))
      } catch (e) { /* ignore */ }
    }
  }

  function emit(type, data = {}) {
    const evt = { type, ...data, state: snapshot() }
    try { if (hooks.onEvent) hooks.onEvent(evt) } catch (e) { /* ignore */ }
  }

  function snapshot() {
    return { ...state, effectiveVersion: overlay.effectiveVersion(ctx) }
  }

  async function fetchManifest() {
    const urls = manifestUrls()
    let lastErr = null
    for (const url of urls) {
      try {
        const m = await fetchUrl(url, { maxBytes: 1024 * 1024 })
        const s = await fetchUrl(url + '.sig', { maxBytes: 4096 })
        const sig = Buffer.from(s.bytes.toString('utf8').trim(), 'base64')
        if (!crypto.verify(null, m.bytes, publicKey(), sig)) {
          throw new Error(`manifest 验签失败: ${url}`)
        }
        const parsed = JSON.parse(m.bytes.toString('utf8'))
        if (parsed.schema !== 1) throw new Error(`未知 manifest schema: ${parsed.schema}`)
        return parsed
      } catch (e) {
        lastErr = e
      }
    }
    throw lastErr || new Error('manifest 不可达')
  }

  async function downloadPatch(channel) {
    const target = channel.latest
    const staging = overlay.stagingDir(ctx)
    fs.rmSync(staging, { recursive: true, force: true })
    fs.mkdirSync(staging, { recursive: true })

    const staged = {}
    for (const comp of channel.components || []) {
      // 组件级去重：本机已生效（overlay 或内置基线）不低于清单版本则跳过
      if (overlay.compareVersions(comp.version, overlay.installedComponentVersion(ctx, comp.name)) <= 0) continue
      state.progress = { component: comp.name, received: 0, total: comp.size || 0 }
      emit('progress')
      const archive = path.join(staging, `${comp.name}.tar.gz`)
      const r = await fetchFirst(comp.urls || [], {
        toFile: archive,
        onBytes: (n) => {
          state.progress = { component: comp.name, received: n, total: comp.size || 0 }
        }
      })
      if (r.sha256 !== String(comp.sha256 || '').toLowerCase()) {
        throw new Error(`${comp.name} sha256 校验失败（预期 ${comp.sha256}，实际 ${r.sha256}）`)
      }
      const destDir = path.join(staging, comp.name)
      fs.mkdirSync(destDir, { recursive: true })
      await hooks.extractTar(archive, destDir)
      fs.rmSync(archive, { force: true })
      staged[comp.name] = destDir
    }

    if (Object.keys(staged).length === 0) {
      // 清单更新但组件全部已就位（重复检查/上次已下）——直接推进指针
      overlay.activate(ctx, target, {})
    } else {
      overlay.activate(ctx, target, staged)
    }
    fs.rmSync(staging, { recursive: true, force: true })
    state.available = { version: target }
    state.phase = 'ready'
    state.progress = null
    logEvent('activated', { version: target })
    emit('ready', { version: target })
  }

  async function check() {
    if (checking) return checking
    checking = (async () => {
      state.phase = 'checking'
      state.error = null
      emit('checking')
      try {
        const manifest = await fetchManifest()
        telemetryUrl = manifest.telemetryUrl || null
        state.checkedAt = new Date().toISOString()
        const myMajor = overlay.majorOf(ctx.appVersion)

        // 大版本：manifest.latestMajor 比本机新 → 引导全量下载（§7）
        if (manifest.latestMajor && overlay.compareVersions(manifest.latestMajor + '.0', myMajor + '.0') > 0) {
          state.majorAvailable = { major: manifest.latestMajor, page: manifest.majorDownloadPage || 'https://www.aiworkdeck.com' }
          emit('major-available', state.majorAvailable)
        } else {
          state.majorAvailable = null
        }

        // 小版本：同大版本频道有更新 → 下载补丁
        const channel = manifest.channels && manifest.channels[myMajor]
        const effective = overlay.effectiveVersion(ctx)
        if (channel && overlay.compareVersions(channel.latest, effective) > 0) {
          state.phase = 'downloading'
          emit('downloading', { version: channel.latest })
          await downloadPatch(channel)
        } else if (state.phase !== 'ready') {
          state.phase = 'idle'
          emit('up-to-date')
        }
        return snapshot()
      } catch (e) {
        state.phase = 'error'
        state.error = String(e && e.message ? e.message : e)
        state.progress = null
        logEvent('error', { message: state.error })
        emit('error', { message: state.error })
        return snapshot()
      } finally {
        checking = null
      }
    })()
    return checking
  }

  function start() {
    if (!ctx.packaged && !process.env.CHECKBA_UPDATE_MANIFEST_URL) return // dev 态默认不查
    setTimeout(() => { check().catch(() => {}) }, CHECK_DELAY_MS)
    timer = setInterval(() => { check().catch(() => {}) }, CHECK_INTERVAL_MS)
    if (timer.unref) timer.unref()
  }

  function stop() {
    if (timer) clearInterval(timer)
    timer = null
  }

  return { start, stop, check, getState: snapshot, logEvent }
}

module.exports = { createUpdateService, fetchUrl, UPDATE_PUBKEY_PEM }

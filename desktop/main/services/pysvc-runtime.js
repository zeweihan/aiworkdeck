const path = require('path')
const fs = require('fs')
const { spawn } = require('child_process')

/**
 * pysvc 运行时定位与首启解压。
 *
 * 打包产物不再直接携带 pysvc/ 目录（上万个小文件让 macOS 逐文件 codesign 的
 * Apple 时间戳请求抖动、EMFILE 频发），改为 Resources/pysvc.tar.gz 单文件；
 * 首次启动（或 app 版本变更）解压到用户数据目录 <userData>/pysvc-<version>/。
 * 绝不能解压回 .app 内部——会破坏 bundle 签名密封（Gatekeeper 直接拒启）。
 */

const MARKER = '.aiworkdeck-extracted'

// 服务代码统一经此定位 pysvc 内文件：
// - 打包态（ctx.pysvcRoot 已设置）→ 用户数据目录里的解压产物
// - dev 态 / 旧布局（未设置）→ 沿用 resourcesPath/pysvc
function pysvcPath(ctx, ...segments) {
  const root = ctx.pysvcRoot || path.join(ctx.resourcesPath || '', 'pysvc')
  return path.join(root, ...segments)
}

function dirSize(root) {
  let total = 0
  const stack = [root]
  while (stack.length) {
    const cur = stack.pop()
    let entries
    try { entries = fs.readdirSync(cur, { withFileTypes: true }) } catch (e) { continue }
    for (const en of entries) {
      const fp = path.join(cur, en.name)
      if (en.isDirectory()) stack.push(fp)
      else if (en.isFile()) { try { total += fs.statSync(fp).size } catch (e) { /* 解压中文件可能在改名 */ } }
    }
  }
  return total
}

// Windows 优先 System32 的 bsdtar（处理盘符冒号无坑）；PATH 里的 GNU tar 会把
// "D:\..." 的冒号当远程主机（见 prepare-python-service.js 同款地雷）
function tarCandidates() {
  if (process.platform === 'win32') {
    const sys = process.env.SystemRoot || 'C:\\Windows'
    return [path.join(sys, 'System32', 'tar.exe'), 'tar.exe']
  }
  return ['/usr/bin/tar', 'tar']
}

function extractTarOnce(cmd, archive, destDir) {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, ['-xzf', archive, '-C', destDir], { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    proc.stderr.on('data', (d) => { stderr = (stderr + d.toString()).slice(-2000) })
    proc.once('error', (e) => reject(Object.assign(e, { _spawnError: true })))
    proc.once('exit', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`tar exited ${code}: ${stderr}`))
    })
  })
}

async function extractTar(archive, destDir) {
  let lastErr = null
  for (const cmd of tarCandidates()) {
    try {
      await extractTarOnce(cmd, archive, destDir)
      return
    } catch (e) {
      lastErr = e
      if (!e._spawnError) throw e // tar 存在但解压失败：不是换候选能解决的
    }
  }
  throw lastErr || new Error('no tar available')
}

// 成功解压后清理同级旧版本目录（pysvc-<oldVersion>），升级不留双份体积
function cleanupOldVersions(versionDir) {
  const parent = path.dirname(versionDir)
  const current = path.basename(versionDir)
  let entries
  try { entries = fs.readdirSync(parent, { withFileTypes: true }) } catch (e) { return }
  for (const en of entries) {
    if (!en.isDirectory() || !en.name.startsWith('pysvc-') || en.name === current) continue
    try { fs.rmSync(path.join(parent, en.name), { recursive: true, force: true }) } catch (e) { /* 尽力而为 */ }
  }
}

/**
 * 确保 pysvc 已解压到 versionDir（幂等：marker 命中直接复用）。
 * opts = { archive, metaFile, versionDir, onProgress?({percent}) }
 * 返回 { ok, reused?, message? }，不抛异常。
 */
async function ensurePysvcExtracted(opts) {
  const { archive, metaFile, versionDir, onProgress } = opts
  const marker = path.join(versionDir, MARKER)
  if (fs.existsSync(marker)) return { ok: true, reused: true }

  let poller = null
  try {
    // 无 marker 的残留（上次解压被打断）整体重来，保证目录内容完整
    fs.rmSync(versionDir, { recursive: true, force: true })
    fs.mkdirSync(versionDir, { recursive: true })

    let totalBytes = 0
    try { totalBytes = JSON.parse(fs.readFileSync(metaFile, 'utf8')).totalBytes || 0 } catch (e) { /* 无元数据则不报百分比 */ }
    if (onProgress) {
      poller = setInterval(() => {
        const percent = totalBytes > 0
          ? Math.min(99, Math.round(dirSize(versionDir) / totalBytes * 100)) // 封顶 99，tar 成功退出才算完成
          : undefined
        try { onProgress({ percent }) } catch (e) { /* ignore */ }
      }, 500)
    }

    await extractTar(archive, versionDir)
    fs.writeFileSync(marker, new Date().toISOString())
    cleanupOldVersions(versionDir)
    return { ok: true, reused: false }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  } finally {
    if (poller) clearInterval(poller)
  }
}

// ---------------------------------------------------------------------------
// P3：pysvc 源码层补丁（设计文档 §11 Phase 3）。
// 解压产物在用户数据目录（可写、不在签名密封内），补丁 = 把 overlay 组件
// pysvc-src 里的文件（相对 pysvc 根的同构路径）覆盖进解压树。覆盖前逐文件
// 备份原件到 .patch-backup/，回滚即还原备份——不必重解 728MB 的 tar。
// 全量升级换 pysvc-<version> 目录后补丁自然重放（marker 不匹配触发重应用）。

const APPLIED_FILE = '.patch-applied.json'
const BACKUP_DIR = '.patch-backup'

function walkFiles(root, dir = root, out = []) {
  let entries
  try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch (e) { return out }
  for (const en of entries) {
    const fp = path.join(dir, en.name)
    if (en.isDirectory()) walkFiles(root, fp, out)
    else if (en.isFile()) out.push(path.relative(root, fp))
  }
  return out
}

function restoreSrcPatch(pysvcRoot) {
  const appliedFp = path.join(pysvcRoot, APPLIED_FILE)
  let applied
  try { applied = JSON.parse(fs.readFileSync(appliedFp, 'utf8')) } catch (e) { return false }
  const backupRoot = path.join(pysvcRoot, BACKUP_DIR)
  for (const rel of applied.files || []) {
    const target = path.join(pysvcRoot, rel)
    const backup = path.join(backupRoot, rel)
    try {
      if (fs.existsSync(backup)) {
        fs.mkdirSync(path.dirname(target), { recursive: true })
        fs.copyFileSync(backup, target)
      } else {
        fs.rmSync(target, { force: true }) // 补丁新增的文件：还原 = 删除
      }
    } catch (e) { /* 尽力而为，单文件失败不阻断其余还原 */ }
  }
  try { fs.rmSync(backupRoot, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  try { fs.rmSync(appliedFp, { force: true }) } catch (e) { /* ignore */ }
  return true
}

/**
 * 让解压树与 overlay 的 pysvc-src 组件对齐（幂等）。
 * @param {string} pysvcRoot 解压产物根（<userData>/pysvc-<version>/pysvc）
 * @param {string|null} patchDir overlay 组件目录；null = 无补丁（触发还原）
 * @param {string|null} patchVersion 组件版本（marker 判等用）
 * @returns {{applied?: boolean, reverted?: boolean}}
 */
function syncSrcPatch(pysvcRoot, patchDir, patchVersion) {
  const appliedFp = path.join(pysvcRoot, APPLIED_FILE)
  if (!patchDir) {
    return { reverted: restoreSrcPatch(pysvcRoot) }
  }
  try {
    const applied = JSON.parse(fs.readFileSync(appliedFp, 'utf8'))
    if (applied.version === patchVersion) return { applied: false } // 已是该版本
  } catch (e) { /* 未应用过 */ }

  restoreSrcPatch(pysvcRoot) // 先回到干净基线，防旧补丁文件残留
  const files = walkFiles(patchDir)
  const backupRoot = path.join(pysvcRoot, BACKUP_DIR)
  for (const rel of files) {
    const target = path.join(pysvcRoot, rel)
    if (fs.existsSync(target)) {
      const backup = path.join(backupRoot, rel)
      fs.mkdirSync(path.dirname(backup), { recursive: true })
      fs.copyFileSync(target, backup)
    }
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.copyFileSync(path.join(patchDir, rel), target)
  }
  // marker 最后写（写入即视为应用完成；中途失败下次启动整套重放）
  fs.writeFileSync(appliedFp, JSON.stringify({ version: patchVersion, files }, null, 2))
  return { applied: true }
}

module.exports = { pysvcPath, ensurePysvcExtracted, MARKER, extractTar, syncSrcPatch }

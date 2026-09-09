// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const path = require('path')
const fs = require('fs')
const { spawn } = require('child_process')

/**
 * 四个 Python 服务的运行时定位。
 *
 * 0.38.0 起这四个服务不再随安装包分发（设计 §3）：每个服务一个 native pack
 * （<service 前缀>-runtime），装到 ~/.aiworkdeck/packs/<id>/<version>/{lib,app}。
 * 首启解压 pysvc.tar.gz 那一整套（含 splash 与 pysvc-src 补丁）已随本次改动删除——
 * 安装包里不再有 pysvc.tar.gz，没有什么可解压的。
 *
 * 解析优先级与 LitigationVisualService.resolveRuntime / 规范 §5 同构：
 *   1. 显式 env 覆盖 AIWORKDECK_PYSVC_<SVC>_DIR（dev 与排障；最高优先级）
 *   2. pack current 目录（current.json 指向、带 .pack-complete、未被封禁）
 *   3. dev 态随仓库的 desktop/bundled/<os>-<arch>/pysvc/<service>
 * 一档都不命中就返回 null：调用方据此把服务判为「组件未安装」，不 spawn、不报错。
 */

const PACK_ID_BY_SERVICE = {
  'pptx-service': 'pptx-runtime',
  'mineru-service': 'mineru-runtime',
  'kokoro-service': 'kokoro-runtime',
  'asr-service': 'asr-runtime',
}

const COMPLETE_MARKER = '.pack-complete'

function envOverride(service) {
  const key = 'AIWORKDECK_PYSVC_' + service.replace(/-service$/, '').toUpperCase() + '_DIR'
  const v = process.env[key]
  return v && fs.existsSync(v) ? v : null
}

function packRoot(ctx, service) {
  const id = PACK_ID_BY_SERVICE[service]
  if (!id || !ctx || !ctx.dataDir) return null
  const base = path.join(ctx.dataDir, 'packs', id)
  let cur
  try {
    cur = JSON.parse(fs.readFileSync(path.join(base, 'current.json'), 'utf8'))
  } catch (e) {
    return null // 从没装过
  }
  if (!cur || !cur.version || cur.revoked) return null // 封禁的包资源解析要视而不见
  const dir = path.join(base, String(cur.version))
  return fs.existsSync(path.join(dir, COMPLETE_MARKER)) ? dir : null // 半成品不算数
}

function bundledRoot(ctx, service) {
  if (!ctx || !ctx.projectRoot) return null
  const plat = process.platform === 'win32' ? 'win-x64' : 'mac-arm64'
  const dir = path.join(ctx.projectRoot, 'desktop', 'bundled', plat, 'pysvc', service)
  return fs.existsSync(dir) ? dir : null
}

/** 服务根目录（其下是 lib/ 与 app/）；未安装返回 null。 */
function resolveServiceRoot(ctx, service) {
  if (!PACK_ID_BY_SERVICE[service]) return null
  return envOverride(service) || packRoot(ctx, service) || bundledRoot(ctx, service) || null
}

function libDirFor(ctx, service) {
  const root = resolveServiceRoot(ctx, service)
  return root ? path.join(root, 'lib') : null
}

function appDirFor(ctx, service) {
  const root = resolveServiceRoot(ctx, service)
  return root ? path.join(root, 'app') : null
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

/** 增量更新（update-service.js）解补丁包用；pack 的解压在 Java 侧，不走这里。 */
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

module.exports = { PACK_ID_BY_SERVICE, resolveServiceRoot, libDirFor, appDirFor, extractTar }

const path = require('path')
const fs = require('fs')

/**
 * overlay.js — 增量更新覆盖层（设计文档 docs/INCREMENTAL_UPDATE_DESIGN.md §3）。
 *
 * 补丁绝不写入 .app 内部（签名密封，见 pysvc-runtime.js 同款地雷），而是落到
 * <dataDir>/overlay/<大版本>/，三个 seam（backend jar / h5 / zetaoffice 壳层）
 * 启动时覆盖优先、内置兜底。
 *
 * 目录布局：
 *   <dataDir>/overlay/0.11/
 *     current.json                  原子指针（tmp+rename 写入）
 *     backend-app/0.11.2/app.jar
 *     frontend-h5/0.11.2/...
 *     zetaoffice-wrapper/0.11.1/...
 *     pysvc-src/0.11.2/...
 *     staging/                      下载与校验中的临时区
 *
 * current.json 契约：
 *   { schema: 1, version: "0.11.2",
 *     components: { "backend-app": { version: "0.11.2" }, ... },
 *     previous: { version, components } | null,   // 仅保留一层，供回滚
 *     bootFailures: 0 }
 *
 * 不变式：
 *   - current.version 必须严格大于壳版本（ctx.appVersion），否则视为无效
 *     （用户全量重装同大版本更高小版本后，残留 overlay 自动失效）。
 *   - 大版本命名空间 != 壳大版本的 overlay 一律忽略，由 cleanupStaleMajors 清除。
 */

const CURRENT_FILE = 'current.json'

function parseVersion(v) {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(v || '').trim())
  if (!m) return null
  return [Number(m[1]), Number(m[2]), Number(m[3])]
}

// a > b => 1, a == b => 0, a < b => -1；非法版本视为最小
function compareVersions(a, b) {
  const pa = parseVersion(a) || [-1, -1, -1]
  const pb = parseVersion(b) || [-1, -1, -1]
  for (let i = 0; i < 3; i++) {
    if (pa[i] !== pb[i]) return pa[i] > pb[i] ? 1 : -1
  }
  return 0
}

function majorOf(version) {
  const p = parseVersion(version)
  return p ? `${p[0]}.${p[1]}` : null
}

function overlayBase(ctx) {
  return path.join(ctx.dataDir, 'overlay')
}

function overlayRoot(ctx) {
  const major = majorOf(ctx.appVersion)
  if (!major) return null
  return path.join(overlayBase(ctx), major)
}

function currentPath(ctx) {
  const root = overlayRoot(ctx)
  return root ? path.join(root, CURRENT_FILE) : null
}

// 读取并校验 current.json；无效（损坏/版本不高于壳版本）返回 null。
// 只在打包态生效——dev 态永远返回 null，三个 seam 全部走内置路径。
function readCurrent(ctx) {
  if (!ctx.packaged) return null
  const fp = currentPath(ctx)
  if (!fp) return null
  let cur
  try {
    cur = JSON.parse(fs.readFileSync(fp, 'utf8'))
  } catch (e) {
    return null
  }
  if (!cur || cur.schema !== 1 || !cur.components) return null
  if (compareVersions(cur.version, ctx.appVersion) <= 0) return null
  return cur
}

// 生效版本号：有 overlay 则取补丁版本，否则壳版本
function effectiveVersion(ctx) {
  const cur = readCurrent(ctx)
  return cur ? cur.version : ctx.appVersion
}

// 组件在 overlay 中的目录；未启用/目录缺失返回 null
function componentDir(ctx, name) {
  const cur = readCurrent(ctx)
  if (!cur || !cur.components[name]) return null
  const dir = path.join(overlayRoot(ctx), name, cur.components[name].version)
  try {
    if (fs.statSync(dir).isDirectory()) return dir
  } catch (e) { /* 缺失 */ }
  return null
}

// 已生效的组件版本（下载去重用）：overlay 里有则取之，否则视为内置基线 = 壳版本
function installedComponentVersion(ctx, name) {
  const cur = readCurrent(ctx)
  if (cur && cur.components[name]) return cur.components[name].version
  return ctx.appVersion
}

function writeCurrent(ctx, obj) {
  const fp = currentPath(ctx)
  fs.mkdirSync(path.dirname(fp), { recursive: true })
  const tmp = fp + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(obj, null, 2))
  fs.renameSync(tmp, fp)
}

function stagingDir(ctx) {
  return path.join(overlayRoot(ctx), 'staging')
}

/**
 * 激活一批已就位于 staging 的组件。
 * @param {object} ctx
 * @param {string} version 目标补丁版本（须 > 当前生效版本，否则拒绝——防降级）
 * @param {Object<string,string>} stagedDirs 组件名 -> staging 内已解压目录
 */
function activate(ctx, version, stagedDirs) {
  if (compareVersions(version, effectiveVersion(ctx)) <= 0) {
    throw new Error(`拒绝激活不高于当前生效版本的补丁: ${version} <= ${effectiveVersion(ctx)}`)
  }
  const root = overlayRoot(ctx)
  const old = readCurrent(ctx)
  const components = { ...(old ? old.components : {}) }
  for (const [name, src] of Object.entries(stagedDirs)) {
    const dest = path.join(root, name, version)
    fs.rmSync(dest, { recursive: true, force: true })
    fs.mkdirSync(path.dirname(dest), { recursive: true })
    fs.renameSync(src, dest)
    components[name] = { version }
  }
  writeCurrent(ctx, {
    schema: 1,
    version,
    components,
    // previous 只留一层：回滚一步到位，不做链式回退
    previous: old ? { version: old.version, components: old.components } : null,
    bootFailures: 0
  })
}

// 回滚到上一版本（无上一版本则整体撤销 overlay 回内置）。返回是否曾有 overlay。
function revert(ctx) {
  const fp = currentPath(ctx)
  let cur
  try { cur = JSON.parse(fs.readFileSync(fp, 'utf8')) } catch (e) { return false }
  if (cur && cur.previous && compareVersions(cur.previous.version, ctx.appVersion) > 0) {
    writeCurrent(ctx, { schema: 1, version: cur.previous.version, components: cur.previous.components, previous: null, bootFailures: 0 })
  } else {
    try { fs.rmSync(fp, { force: true }) } catch (e) { /* ignore */ }
  }
  return true
}

/**
 * 后端启动失败记账（设计 §6 自愈回滚）：连续 2 次失败自动回滚。
 * @returns {{reverted: boolean}}
 */
function noteBackendBootFailure(ctx) {
  const cur = readCurrent(ctx)
  if (!cur) return { reverted: false }
  const failures = (cur.bootFailures || 0) + 1
  if (failures >= 2) {
    revert(ctx)
    return { reverted: true }
  }
  writeCurrent(ctx, { ...cur, bootFailures: failures })
  return { reverted: false }
}

// 后端健康启动后调用：清零失败计数，并清理不再被 current/previous 引用的旧版本目录
function markBootOk(ctx) {
  const cur = readCurrent(ctx)
  if (!cur) return
  if (cur.bootFailures) writeCurrent(ctx, { ...cur, bootFailures: 0 })
  pruneUnreferenced(ctx, cur)
}

function pruneUnreferenced(ctx, cur) {
  const root = overlayRoot(ctx)
  const keep = new Set()
  for (const c of [cur.components, cur.previous && cur.previous.components]) {
    if (!c) continue
    for (const [name, info] of Object.entries(c)) keep.add(`${name}/${info.version}`)
  }
  let names
  try { names = fs.readdirSync(root, { withFileTypes: true }) } catch (e) { return }
  for (const en of names) {
    if (!en.isDirectory() || en.name === 'staging') continue
    let versions
    try { versions = fs.readdirSync(path.join(root, en.name)) } catch (e) { continue }
    for (const v of versions) {
      if (!keep.has(`${en.name}/${v}`)) {
        try { fs.rmSync(path.join(root, en.name, v), { recursive: true, force: true }) } catch (e) { /* ignore */ }
      }
    }
  }
}

/**
 * 启动清理：删除非本大版本的 overlay 命名空间（全量升级后安装器不会替我们清），
 * 以及本大版本内已失效的 current.json（版本不高于壳版本 = 全量重装覆盖了补丁）。
 */
function cleanupStaleMajors(ctx) {
  if (!ctx.packaged) return
  const base = overlayBase(ctx)
  const myMajor = majorOf(ctx.appVersion)
  let entries
  try { entries = fs.readdirSync(base, { withFileTypes: true }) } catch (e) { return }
  for (const en of entries) {
    if (!en.isDirectory()) continue
    if (en.name !== myMajor) {
      try { fs.rmSync(path.join(base, en.name), { recursive: true, force: true }) } catch (e) { /* ignore */ }
    }
  }
  // 本大版本内的失效指针（readCurrent 返回 null 但文件存在）连同旧组件一并清掉
  const fp = currentPath(ctx)
  if (fp && fs.existsSync(fp) && !readCurrent(ctx)) {
    try { fs.rmSync(overlayRoot(ctx), { recursive: true, force: true }) } catch (e) { /* ignore */ }
  }
  // 残留 staging（上次下载中断）无条件清
  try { fs.rmSync(stagingDir(ctx), { recursive: true, force: true }) } catch (e) { /* ignore */ }
}

module.exports = {
  parseVersion,
  compareVersions,
  majorOf,
  overlayRoot,
  stagingDir,
  readCurrent,
  effectiveVersion,
  componentDir,
  installedComponentVersion,
  activate,
  revert,
  noteBackendBootFailure,
  markBootOk,
  cleanupStaleMajors
}

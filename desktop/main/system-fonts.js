// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// system-fonts.js — 本机字体扫描 + 编辑器启用集（纯本地，无任何外发请求）。
//
// 为什么要有这层：LOWA 编辑器只能渲染启动前注入 MEMFS 的字体（fontconfig 启动扫描），
// 此前只有 4 个随包 OFL 中文字体，宋体/仿宋/楷体/黑体等专有名一律 assign 别名到它们。
// 用户本机装了仿宋_GB2312、方正小标宋简体之类的公文字体，理应能直接用。但不能全量注入：
// macOS 系统字体约 700MB，每个文档标签页都是独立引擎实例、字体常驻 wasm 内存。
// 所以：扫描 → 用户勾选（无配置时给公文/常用西文字体的推荐默认集）→ 启动时注入。
//
// 只读字体文件头与 name 表（fs 定位读），绝不整文件读入；坏文件逐个吞掉，扫描永不抛。
// 不依赖 electron 模块：纯 node 下可测，数据目录可注入。

const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')

const FONT_EXTS = new Set(['.ttf', '.otf', '.ttc', '.otc'])
const MAX_FILE_BYTES = 80 * 1024 * 1024 // Apple Color Emoji 之类的巨型文件直接跳过
const MAX_DEFAULT_FILE_BYTES = 40 * 1024 * 1024
const MAX_DEFAULT_TOTAL_BYTES = 120 * 1024 * 1024
const MAX_RECOMMENDED_BYTES = 200 * 1024 * 1024
const MAX_SCAN_DEPTH = 5
const MAX_SCAN_FILES = 6000
const MAX_NAME_TABLE_BYTES = 1024 * 1024
const MAX_TTC_FACES = 64
const CONFIG_FILE = 'editor-fonts.json'

// 推荐默认集：法律/公文常用中文字体 + 常用西文字体。顺序即优先级（总量封顶时先到先得）。
// 中英文名都列上——不同来源的字体文件 name 表语言不全。
const CURATED_FAMILIES = [
  '仿宋_GB2312', 'FangSong_GB2312', '楷体_GB2312', 'KaiTi_GB2312',
  '方正小标宋简体', 'FZXiaoBiaoSong-B05S', '方正小标宋_GBK', 'FZXiaoBiaoSong-B05',
  '方正仿宋_GBK', 'FZFangSong-Z02', '方正楷体_GBK', 'FZKai-Z03', '方正黑体_GBK', 'FZHei-B01',
  '仿宋', 'FangSong', '楷体', 'KaiTi', '黑体', 'SimHei', '宋体', 'SimSun', '新宋体', 'NSimSun',
  '华文仿宋', 'STFangsong', '华文楷体', 'STKaiti', '华文中宋', 'STZhongsong',
  '微软雅黑', 'Microsoft YaHei', '等线', 'DengXian',
  'Times New Roman', 'Arial', 'Calibri', 'Cambria', 'Courier New',
]

const ZH_LANGS = new Set([0x0804, 0x0404, 0x0C04, 0x1004, 0x1404])
const EN_LANG = 0x0409

// fontconfig 比较族名时忽略大小写与空白（FcStrCmpIgnoreBlanksAndCase），这里同口径
function normFamily(s) { return String(s || '').replace(/\s+/g, '').toLowerCase() }

function hasCjk(s) { return /[\u3400-\u9fff\uf900-\ufaff]/.test(s) }

// ---------------------------------------------------------------- name 表解析

function readAt(fd, offset, length) {
  const buf = Buffer.alloc(length)
  const n = fs.readSync(fd, buf, 0, length, offset)
  return n === length ? buf : buf.subarray(0, n)
}

function decodeUtf16BE(buf) {
  const even = buf.length & ~1
  const swapped = Buffer.alloc(even)
  for (let i = 0; i < even; i += 2) { swapped[i] = buf[i + 1]; swapped[i + 1] = buf[i] }
  return swapped.toString('utf16le')
}

function cleanName(s) {
  // eslint-disable-next-line no-control-regex
  return String(s || '').replace(/[\u0000-\u001f]/g, '').trim()
}

/**
 * 解析一个 sfnt face（偏移 faceOffset 处）的 name 表。
 * @returns {{zh:string[], en:string[], other:string[]}} 族名（nameID 16 优先，其次 1）
 */
function parseFace(fd, faceOffset, fileSize) {
  const head = readAt(fd, faceOffset, 12)
  if (head.length < 12) throw new Error('short sfnt header')
  const version = head.readUInt32BE(0)
  // 0x00010000 TrueType / 'OTTO' CFF / 'true' 'typ1' 老 Mac
  if (![0x00010000, 0x4f54544f, 0x74727565, 0x74797031].includes(version)) throw new Error('not sfnt')
  const numTables = head.readUInt16BE(4)
  if (numTables === 0 || numTables > 512) throw new Error('bad numTables')
  const dir = readAt(fd, faceOffset + 12, numTables * 16)
  if (dir.length < numTables * 16) throw new Error('short table directory')
  let nameOff = -1, nameLen = 0
  for (let i = 0; i < numTables; i++) {
    if (dir.toString('latin1', i * 16, i * 16 + 4) === 'name') {
      nameOff = dir.readUInt32BE(i * 16 + 8)
      nameLen = dir.readUInt32BE(i * 16 + 12)
      break
    }
  }
  if (nameOff < 0 || nameLen < 6 || nameOff >= fileSize) throw new Error('no name table')
  const t = readAt(fd, nameOff, Math.min(nameLen, MAX_NAME_TABLE_BYTES, fileSize - nameOff))
  if (t.length < 6) throw new Error('short name table')
  const count = t.readUInt16BE(2)
  const strBase = t.readUInt16BE(4)
  // nameID -> { zh:[], en:[], other:[], mac:[] }
  const by = { 1: { zh: [], en: [], other: [], mac: [] }, 16: { zh: [], en: [], other: [], mac: [] } }
  for (let i = 0; i < count; i++) {
    const r = 6 + i * 12
    if (r + 12 > t.length) break
    const platformID = t.readUInt16BE(r)
    const encodingID = t.readUInt16BE(r + 2)
    const languageID = t.readUInt16BE(r + 4)
    const nameID = t.readUInt16BE(r + 6)
    const len = t.readUInt16BE(r + 8)
    const off = t.readUInt16BE(r + 10)
    if (nameID !== 1 && nameID !== 16) continue
    const s0 = strBase + off
    if (s0 + len > t.length || len === 0) continue
    const raw = t.subarray(s0, s0 + len)
    const bucket = by[nameID]
    if (platformID === 3 && (encodingID === 1 || encodingID === 10 || encodingID === 0)) {
      const name = cleanName(decodeUtf16BE(raw))
      if (!name) continue
      if (ZH_LANGS.has(languageID)) bucket.zh.push(name)
      else if (languageID === EN_LANG) bucket.en.push(name)
      else bucket.other.push(name)
    } else if (platformID === 0) {
      const name = cleanName(decodeUtf16BE(raw))
      if (name) bucket.mac.push(name)
    } else if (platformID === 1 && encodingID === 0 && languageID === 0) {
      // Mac Roman 英文名：只作兜底，按 latin1 近似解码（族名几乎都是 ASCII）
      const name = cleanName(raw.toString('latin1'))
      if (name) bucket.mac.push(name)
    }
  }
  const pick = (b) => {
    const win = b.zh.length + b.en.length + b.other.length
    return { zh: b.zh, en: win ? b.en : b.mac, other: b.other }
  }
  const p16 = pick(by[16]), p1 = pick(by[1])
  const has16 = p16.zh.length + p16.en.length + p16.other.length > 0
  return has16 ? p16 : p1
}

/**
 * 解析字体文件（TTF/OTF/TTC/OTC）的族名。只读文件头 + name 表。
 * @returns {{families:string[], primary:string, faces:number}|null} 坏文件返回 null
 */
function parseFontFile(file) {
  let fd = null
  try {
    const st = fs.statSync(file)
    if (!st.isFile() || st.size < 12) return null
    fd = fs.openSync(file, 'r')
    const head = readAt(fd, 0, 12)
    let offsets = [0]
    if (head.toString('latin1', 0, 4) === 'ttcf') {
      const n = Math.min(head.readUInt32BE(8), MAX_TTC_FACES)
      if (n < 1) return null
      const tbl = readAt(fd, 12, n * 4)
      offsets = []
      for (let i = 0; i + 4 <= tbl.length; i += 4) offsets.push(tbl.readUInt32BE(i))
    }
    const zh = [], en = [], other = []
    let faces = 0
    for (const off of offsets) {
      if (off >= st.size) continue
      try {
        const r = parseFace(fd, off, st.size)
        zh.push(...r.zh); en.push(...r.en); other.push(...r.other)
        faces++
      } catch (e) { /* 单个 face 坏了不影响同一集合里的其它 face */ }
    }
    if (!faces) return null
    const seen = new Set()
    const families = []
    for (const n of [...zh, ...en, ...other]) {
      const k = normFamily(n)
      if (!k || seen.has(k)) continue
      seen.add(k)
      families.push(n)
    }
    if (!families.length) return null
    const primary = families.find(hasCjk) || en[0] || families[0]
    return { families, primary, faces }
  } catch (e) {
    return null
  } finally {
    if (fd !== null) { try { fs.closeSync(fd) } catch (e) { /* ignore */ } }
  }
}

// ---------------------------------------------------------------- 目录扫描

function fontDirs(platform, env, homeDir) {
  if (platform === 'darwin') {
    return [
      { dir: '/System/Library/Fonts', kind: 'system' },
      { dir: '/System/Library/Fonts/Supplemental', kind: 'system' },
      { dir: '/Library/Fonts', kind: 'system' },
      { dir: path.join(homeDir, 'Library/Fonts'), kind: 'user' },
    ]
  }
  if (platform === 'win32') {
    const winDir = env.WINDIR || env.SystemRoot || 'C:\\Windows'
    const out = [{ dir: path.join(winDir, 'Fonts'), kind: 'system' }]
    const local = env.LOCALAPPDATA || path.join(homeDir, 'AppData', 'Local')
    out.push({ dir: path.join(local, 'Microsoft', 'Windows', 'Fonts'), kind: 'user' })
    return out
  }
  return [
    { dir: '/usr/share/fonts', kind: 'system' },
    { dir: '/usr/local/share/fonts', kind: 'system' },
    { dir: path.join(homeDir, '.local/share/fonts'), kind: 'user' },
    { dir: path.join(homeDir, '.fonts'), kind: 'user' },
  ]
}

function fontId(absPath) {
  return crypto.createHash('sha1').update(absPath).digest('hex').slice(0, 16)
}

// 纯表情/符号字体对正文无用，体积还大
const SKIP_FILE_RE = /emoji|lastresort|symbol|wingding|webding|zapfdingbats|marlett/i

function scanDirs(dirs) {
  const files = []
  const seenPaths = new Set()
  const walk = (dir, kind, depth) => {
    if (depth > MAX_SCAN_DEPTH || files.length >= MAX_SCAN_FILES) return
    let ents
    try { ents = fs.readdirSync(dir, { withFileTypes: true }) } catch (e) { return }
    for (const ent of ents) {
      if (files.length >= MAX_SCAN_FILES) return
      const p = path.join(dir, ent.name)
      let isDir = ent.isDirectory(), isFile = ent.isFile()
      if (ent.isSymbolicLink()) {
        try { const st = fs.statSync(p); isDir = st.isDirectory(); isFile = st.isFile() } catch (e) { continue }
      }
      if (isDir) { if (!ent.name.startsWith('.')) walk(p, kind, depth + 1); continue }
      if (!isFile || !FONT_EXTS.has(path.extname(ent.name).toLowerCase())) continue
      if (seenPaths.has(p)) continue
      seenPaths.add(p)
      files.push({ path: p, kind })
    }
  }
  for (const d of dirs) walk(d.dir, d.kind, 0)
  return files
}

function scanFonts(dirs) {
  const out = []
  for (const f of scanDirs(dirs)) {
    try {
      const base = path.basename(f.path)
      if (SKIP_FILE_RE.test(base)) continue
      const st = fs.statSync(f.path)
      if (st.size > MAX_FILE_BYTES) continue
      const parsed = parseFontFile(f.path)
      if (!parsed) continue
      if (parsed.families.every((n) => SKIP_FILE_RE.test(n))) continue
      out.push({
        id: fontId(f.path),
        path: f.path,
        file: base,
        kind: f.kind,
        families: parsed.families,
        primary: parsed.primary,
        sizeBytes: st.size,
        mtimeMs: st.mtimeMs,
      })
    } catch (e) { /* 坏文件跳过，扫描永不抛 */ }
  }
  out.sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'user' ? -1 : 1) ||
    a.primary.localeCompare(b.primary, 'zh-Hans-CN'))
  return out
}

// ---------------------------------------------------------------- 默认集（纯函数）

const CURATED_RANK = new Map(CURATED_FAMILIES.map((n, i) => [normFamily(n), i]))

function curatedRank(entry) {
  let best = Infinity
  for (const n of entry.families || []) {
    const r = CURATED_RANK.get(normFamily(n))
    if (r !== undefined && r < best) best = r
  }
  return best
}

function isRecommended(entry) { return curatedRank(entry) !== Infinity }

/**
 * 推荐默认集：命中清单的字体；同名同族文件优先用户安装的那份；单文件 > maxFileBytes
 * 跳过；按清单优先级贪心装入，总量不超过 maxTotalBytes。
 * @param {Array<{id,file,kind,families,sizeBytes}>} entries
 * @returns {string[]} 选中的 id
 */
function selectDefaultIds(entries, opts = {}) {
  const maxFile = opts.maxFileBytes || MAX_DEFAULT_FILE_BYTES
  const maxTotal = opts.maxTotalBytes || MAX_DEFAULT_TOTAL_BYTES
  const byKey = new Map()
  for (const e of entries || []) {
    const rank = curatedRank(e)
    if (rank === Infinity || !(e.sizeBytes > 0) || e.sizeBytes > maxFile) continue
    const key = String(e.file || '').toLowerCase() + '|' + (e.families || []).map(normFamily).sort().join(',')
    const prev = byKey.get(key)
    if (!prev || (prev.e.kind !== 'user' && e.kind === 'user')) byKey.set(key, { e, rank })
  }
  const cands = [...byKey.values()].sort((a, b) =>
    a.rank - b.rank ||
    (a.e.kind === b.e.kind ? 0 : a.e.kind === 'user' ? -1 : 1) ||
    a.e.sizeBytes - b.e.sizeBytes)
  const ids = []
  let total = 0
  for (const { e } of cands) {
    if (total + e.sizeBytes > maxTotal) continue
    total += e.sizeBytes
    ids.push(e.id)
  }
  return ids
}

// ---------------------------------------------------------------- 服务实例

/**
 * @param {object} [ctx]
 * @param {string} [ctx.dataDir] 默认 ~/.aiworkdeck（与主进程 dataDir 同口径）
 * @param {string} [ctx.platform]
 * @param {Array<{dir,kind}>} [ctx.dirs] 覆盖扫描目录（测试用）
 */
function createSystemFonts(ctx = {}) {
  const homeDir = ctx.homeDir || os.homedir()
  const dataDir = ctx.dataDir || path.join(homeDir, '.aiworkdeck')
  const platform = ctx.platform || process.platform
  const dirs = ctx.dirs || fontDirs(platform, ctx.env || process.env, homeDir)
  const configFile = path.join(dataDir, CONFIG_FILE)
  let cache = null

  function scan(refresh) {
    if (!cache || refresh) cache = scanFonts(dirs)
    return cache
  }

  // null = 用户从未保存过（走推荐默认集）
  function readEnabledPaths() {
    try {
      const j = JSON.parse(fs.readFileSync(configFile, 'utf8'))
      if (j && Array.isArray(j.enabledPaths)) return new Set(j.enabledPaths.filter((p) => typeof p === 'string'))
    } catch (e) { /* 不存在 / 坏 JSON：按未配置 */ }
    return null
  }

  function enabledIdSet(entries) {
    const paths = readEnabledPaths()
    if (paths) return new Set(entries.filter((e) => paths.has(e.path)).map((e) => e.id))
    return new Set(selectDefaultIds(entries))
  }

  function listSystemFonts(opts = {}) {
    const entries = scan(!!(opts && opts.refresh))
    const enabled = enabledIdSet(entries)
    let enabledBytes = 0
    const fonts = entries.map((e) => {
      const on = enabled.has(e.id)
      if (on) enabledBytes += e.sizeBytes
      return {
        id: e.id, file: e.file, kind: e.kind, families: e.families.slice(), primary: e.primary,
        sizeBytes: e.sizeBytes, enabled: on, recommended: isRecommended(e),
      }
    })
    return { platform, fonts, enabledBytes, maxRecommendedBytes: MAX_RECOMMENDED_BYTES }
  }

  function setEnabledFonts(ids) {
    const entries = scan(false)
    const want = new Set(Array.isArray(ids) ? ids.filter((x) => typeof x === 'string') : [])
    const chosen = entries.filter((e) => want.has(e.id)) // 未知 id 忽略
    fs.mkdirSync(dataDir, { recursive: true })
    const tmp = configFile + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify({ version: 1, enabledPaths: chosen.map((e) => e.path) }, null, 2))
    fs.renameSync(tmp, configFile)
    return { ok: true, enabledBytes: chosen.reduce((s, e) => s + e.sizeBytes, 0), count: chosen.length }
  }

  function enabledFontsForEditor() {
    const entries = scan(false)
    const enabled = enabledIdSet(entries)
    return entries.filter((e) => enabled.has(e.id))
      .map((e) => ({ id: e.id, families: e.families.slice(), sizeBytes: e.sizeBytes, path: e.path, file: e.file }))
  }

  // HTTP 路由唯一的取文件入口：只认当前启用集里的 id，绝不按任意路径出文件
  function resolveEnabledFontPath(id) {
    if (typeof id !== 'string' || !/^[0-9a-f]{16}$/.test(id)) return null
    const hit = enabledFontsForEditor().find((e) => e.id === id)
    return hit ? hit.path : null
  }

  return { listSystemFonts, setEnabledFonts, enabledFontsForEditor, resolveEnabledFontPath, configFile }
}

let defaultInstance = null
function instance() {
  if (!defaultInstance) defaultInstance = createSystemFonts()
  return defaultInstance
}

module.exports = {
  createSystemFonts,
  parseFontFile,
  selectDefaultIds,
  isRecommended,
  fontDirs,
  normFamily,
  CURATED_FAMILIES,
  MAX_DEFAULT_TOTAL_BYTES,
  MAX_DEFAULT_FILE_BYTES,
  MAX_RECOMMENDED_BYTES,
  listSystemFonts: (opts) => instance().listSystemFonts(opts),
  setEnabledFonts: (ids) => instance().setEnabledFonts(ids),
  enabledFontsForEditor: () => instance().enabledFontsForEditor(),
  resolveEnabledFontPath: (id) => instance().resolveEnabledFontPath(id),
}

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 逐段溯源：把后端按历史算出的「这一段是哪一版改的」对到画布上此刻的段落序，
 * 再翻成律师读得懂的一句话（dev-board#632）——**纯函数，不许 import**。
 *
 * 为什么要对齐而不是按 key 直接取：后端的 units 是**落版那一刻**的段序（`p12` =
 * 正文第 12 段），而律师此刻画布上的段序会被还没保存的改动推着漂——插一段，后面
 * 全错一位。按 key 硬对的后果是把「律师乙改的那一段」的名字贴到邻段头上，
 * 溯源贴错名字比不显示更糟。所以按**文本**对齐：两侧各自归一后取 sha256，
 * 跑一次 LCS，落在公共子序列里的段落才继承出处，其余一律说「本机未保存的改动」。
 *
 * 为什么自带 sha256：后端 `ProvenanceService` 只回 textHash（不回原文——一份 400 段
 * 的合同全文回一遍既贵又没必要），前端必须能算出同一个哈希才对得上。crypto.subtle
 * 是异步的，会把这个纯函数染成 Promise；所以这里自带一份 60 行的同步实现。
 * 与后端的契约有三层：归一口径（NFC + 空白折叠 + trim）、编码（UTF-8）、算法（SHA-256）。
 */

/** 归一：NFC → 连续空白折一个 → 去首尾。与后端 `Unit.norm` 同口径（设计稿 §4.1）。 */
export function normalizeUnitText(text) {
  const s = text == null ? '' : String(text)
  const nfc = typeof s.normalize === 'function' ? s.normalize('NFC') : s
  return nfc.replace(/\s+/g, ' ').trim()
}

// ---------------- sha256（同步、无依赖） ----------------

const K256 = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

// TextEncoder 在桌面端（Electron 渲染进程）与 node 里都有；小程序端没有，手写兜底。
function utf8Bytes(str) {
  if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(str)
  const out = []
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i)
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
      const lo = str.charCodeAt(i + 1)
      if (lo >= 0xdc00 && lo <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (lo - 0xdc00); i++ }
    }
    if (c < 0x80) out.push(c)
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
  }
  return new Uint8Array(out)
}

const rotr = (x, n) => ((x >>> n) | (x << (32 - n))) >>> 0

function sha256Hex(bytes) {
  const h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  const len = bytes.length
  const total = ((len + 9 + 63) >> 6) << 6
  const buf = new Uint8Array(total)
  buf.set(bytes)
  buf[len] = 0x80
  const dv = new DataView(buf.buffer)
  const bits = len * 8
  dv.setUint32(total - 8, Math.floor(bits / 4294967296))
  dv.setUint32(total - 4, bits >>> 0)
  const w = new Uint32Array(64)
  for (let off = 0; off < total; off += 64) {
    for (let j = 0; j < 16; j++) w[j] = dv.getUint32(off + j * 4)
    for (let j = 16; j < 64; j++) {
      const a15 = w[j - 15], a2 = w[j - 2]
      const s0 = (rotr(a15, 7) ^ rotr(a15, 18) ^ (a15 >>> 3)) >>> 0
      const s1 = (rotr(a2, 17) ^ rotr(a2, 19) ^ (a2 >>> 10)) >>> 0
      w[j] = (w[j - 16] + s0 + w[j - 7] + s1) >>> 0
    }
    let a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], x = h[7]
    for (let j = 0; j < 64; j++) {
      const S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0
      const ch = ((e & f) ^ (~e & g)) >>> 0
      const t1 = (x + S1 + ch + K256[j] + w[j]) >>> 0
      const S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0
      const t2 = (S0 + maj) >>> 0
      x = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + x) >>> 0
  }
  let out = ''
  for (let i = 0; i < 8; i++) out += h[i].toString(16).padStart(8, '0')
  return out
}

/** 一段文字的单元哈希 = sha256(归一后的 UTF-8)，与后端 `Unit.textHash` 同一个值。 */
export function hashUnitText(text) {
  return sha256Hex(utf8Bytes(normalizeUnitText(text)))
}

// ---------------- 对齐 ----------------

// LCS 的 DP 表是 O(n·m)。500 段 × 500 段 = 25 万格没有任何问题；真正大的文档
// （引擎单次最多回 500 段，units 可以上千）超过这个预算就退回「同位置且文本相同
// 才算同一段」——比给出一份错位的溯源诚实。
const MAX_CELLS = 1500 * 1500

/**
 * 把后端的 units 对到画布此刻的段落上。
 *
 * @param {Array} units            后端 `/version/provenance` 的 units（要 textHash）
 * @param {Array} engineParagraphs 引擎 `get_document_text` 的 paragraphs（{index, text}）
 * @returns {Map<number, Object|null>} 引擎段序 → 那一段的出处；没对上的是 null
 */
export function alignProvenance(units, engineParagraphs) {
  const us = Array.isArray(units) ? units.filter(Boolean) : []
  const ps = Array.isArray(engineParagraphs) ? engineParagraphs.filter(Boolean) : []
  const out = new Map()
  const indexAt = (i) => {
    const v = ps[i] && ps[i].index
    return Number.isFinite(Number(v)) ? Number(v) : i
  }
  for (let i = 0; i < ps.length; i++) out.set(indexAt(i), null)
  if (!us.length || !ps.length) return out

  const a = us.map((u) => String(u.textHash || ''))
  const b = ps.map((p) => hashUnitText(p.text))

  if (a.length * b.length > MAX_CELLS) {
    for (let i = 0; i < b.length; i++) {
      if (i < a.length && a[i] && a[i] === b[i]) out.set(indexAt(i), us[i])
    }
    return out
  }

  // 经典 LCS：dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度，从后往前填，
  // 再从前往后回溯配对（回溯方向决定了「两段一模一样的文字」按位置对齐）。
  const w = b.length + 1
  const dp = new Uint32Array((a.length + 1) * w)
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      dp[i * w + j] = a[i] === b[j]
        ? dp[(i + 1) * w + (j + 1)] + 1
        : Math.max(dp[(i + 1) * w + j], dp[i * w + (j + 1)])
    }
  }
  let i = 0, j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { out.set(indexAt(j), us[i]); i++; j++ }
    else if (dp[(i + 1) * w + j] >= dp[i * w + (j + 1)]) i++
    else j++
  }
  return out
}

// ---------------- 文案 ----------------

const s = (v) => (v == null ? '' : String(v))

function dayText(t, when) {
  const d = new Date(when)
  if (isNaN(d.getTime())) return ''
  return t('version.dayHeader', { month: d.getMonth() + 1, day: d.getDate() })
}

/**
 * 一段的出处说成一句话：'韩泽伟 · 9 月 13 日 · 核对注册资本'。
 * 本人说「你」、自动存档说「自动存档」、没对上说「本机未保存的改动」、
 * 回溯到头说「更早的版本」。**永远不显示 username**（identity 契约）。
 */
export function provenanceLabel(t, unit, opts = {}) {
  if (!unit) return t('version.provenanceUnsaved')
  if (!unit.sha) return t('version.provenanceEarlier')
  const who = unit.type === 'auto'
    ? t('version.typeAuto')
    : (unit.self
      ? (s(opts.selfLabel).trim() || t('version.actorYou'))
      : (s(unit.authorName).trim() || t('version.unnamedColleague')))
  return [who, dayText(t, unit.when), s(unit.title).trim()].filter(Boolean).join(' · ')
}

/**
 * 侧栏顶部的一行摘要：'本稿 62 段：你 40 段 · 律师乙 20 段 · 更早 2 段'。
 * @param {Array} rows [{unit}]，每段一条（unit 可为 null）
 */
export function provenanceSummary(t, rows, opts = {}) {
  const list = Array.isArray(rows) ? rows : []
  if (!list.length) return ''
  const order = []
  const buckets = new Map()
  const bump = (label) => {
    if (!buckets.has(label)) { buckets.set(label, 0); order.push(label) }
    buckets.set(label, buckets.get(label) + 1)
  }
  for (const row of list) {
    const u = row && row.unit
    if (!u) bump(t('version.provenanceUnsaved'))
    else if (!u.sha) bump(t('version.provenanceEarlier'))
    else if (u.self) bump(s(opts.selfLabel).trim() || t('version.actorYou'))
    else bump(s(u.authorName).trim() || t('version.unnamedColleague'))
  }
  const parts = order.map((label) => t('version.provenanceSummaryPart', { name: label, count: buckets.get(label) }))
  return t('version.provenanceSummary', { total: list.length, parts: parts.join(t('version.mergeItemSep')) })
}

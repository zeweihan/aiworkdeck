// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 配色体系守卫。三件事：
//
//   (a) 色源对拍：design/tokens/awd-palette.json 的 sha256 必须等于下面的
//       EXPECTED_SHA256。三个仓（checkba_cloud / aiworkdeckweb / aiworkdeck_mobile）
//       各存一份色源副本，靠这个常量锁住「三份逐字节相同」。
//       **改配色时三个仓要同步更新这个常量**，否则另外两仓的 CI 会红。
//   (b) 令牌漂移：重新跑一遍 generate-tokens.mjs 的渲染，与入库文件逐字节比对。
//       有人手改了生成区段 = 红。
//   (c) 对比度：按 WCAG 2.1 相对亮度公式复算色源 contrast 块声明的每一项，
//       与声明值相差超过 0.05 即红；并强制正文/强调项 ≥ 4.5、其余 ≥ 3.0。
//
// 不依赖任何构建产物，node scripts/check-palette.mjs 直接跑。

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { renderAll, ROOT, PALETTE_PATH } from './generate-tokens.mjs'

const EXPECTED_SHA256 = '2eaa53a9d1a6437f587261b68ad2a372ad1aef9100c79551445089aaf5c2ba64'

/** 声明值与复算值允许的偏差（作者手算时的四舍五入余量） */
const CONTRAST_TOLERANCE = 0.05
/** 正文与强调项的硬闸 */
const GATE_TEXT = 4.5
/** 其余（大字、控件、图标等小面积）的硬闸 */
const GATE_CONTROL = 3.0

const failures = []
const fail = (msg) => { failures.push(msg); console.error(`FAIL  ${msg}`) }
const pass = (msg) => console.log(`ok    ${msg}`)

// ---------------------------------------------------------------- (a) 色源 sha256

const paletteRaw = readFileSync(PALETTE_PATH)
const sha = createHash('sha256').update(paletteRaw).digest('hex')
if (sha !== EXPECTED_SHA256) {
  fail(`色源 sha256 不匹配\n      实际 ${sha}\n      期望 ${EXPECTED_SHA256}\n`
    + '      色源变了就同步更新三个仓 check-palette.mjs 里的 EXPECTED_SHA256，并重跑 generate-tokens.mjs。')
} else {
  pass(`色源 sha256 = ${sha}`)
}

const palette = JSON.parse(paletteRaw.toString('utf8'))

// ---------------------------------------------------------------- (b) 令牌漂移

function unifiedish(a, b, label) {
  const A = a.split('\n')
  const B = b.split('\n')
  const out = []
  const n = Math.max(A.length, B.length)
  for (let i = 0; i < n; i += 1) {
    if (A[i] === B[i]) continue
    if (A[i] !== undefined) out.push(`      ${label}:${i + 1} -入库  ${A[i]}`)
    if (B[i] !== undefined) out.push(`      ${label}:${i + 1} +生成  ${B[i]}`)
    if (out.length > 60) { out.push('      …（差异过多，已截断）'); break }
  }
  return out.join('\n')
}

let rendered
try {
  rendered = renderAll()
} catch (e) {
  fail(`令牌渲染失败：${e.message}`)
  rendered = new Map()
}
for (const [rel, next] of rendered) {
  const prev = readFileSync(resolve(ROOT, rel), 'utf8')
  if (prev === next) {
    pass(`令牌无漂移 ${rel}`)
  } else {
    fail(`令牌漂移 ${rel}（生成区段被手改过）\n${unifiedish(prev, next, rel)}\n`
      + '      跑 node scripts/generate-tokens.mjs 重新生成。')
  }
}

// ---------------------------------------------------------------- (c) 对比度

function relativeLuminance(hex) {
  const m = /^#([0-9a-fA-F]{6})$/.exec(hex)
  if (!m) throw new Error(`对比度只能算不透明 #RRGGBB，收到 ${hex}`)
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrastRatio(fg, bg) {
  const l1 = relativeLuminance(fg)
  const l2 = relativeLuminance(bg)
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
}

/** contrast 键里的角色名写成小驼峰（textOnAccent），还原成色源里的 kebab 名 */
const kebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()

function lookup(theme, name) {
  if (name === 'white') return '#FFFFFF'
  if (name === 'black') return '#000000'
  const v = palette.roles[theme] && palette.roles[theme][name]
  if (!v) throw new Error(`roles.${theme}.${name} 不存在`)
  return v
}

// 正文/强调类判据：文字对底、强调色相关，一律 4.5 起。
const isTextGate = (key) => /(^|\/)text-on-|(^|\/)text-2-on-|accent/i.test(key)

const seen = new Set()
for (const [key, declared] of Object.entries(palette.contrast || {})) {
  if (key.startsWith('_')) continue
  const slash = key.indexOf('/')
  const theme = key.slice(0, slash)
  const rest = key.slice(slash + 1)
  const at = rest.lastIndexOf('-on-')
  if (slash < 0 || at < 0 || !palette.roles[theme]) {
    fail(`contrast 键格式不认：${key}（应为 <light|dark>/<前景>-on-<背景>）`)
    continue
  }
  const fgName = kebab(rest.slice(0, at))
  const bgName = kebab(rest.slice(at + 4))
  let actual
  try {
    actual = contrastRatio(lookup(theme, fgName), lookup(theme, bgName))
  } catch (e) {
    fail(`${key}：${e.message}`)
    continue
  }
  const gate = isTextGate(key) ? GATE_TEXT : GATE_CONTROL
  const delta = Math.abs(actual - declared)
  if (delta > CONTRAST_TOLERANCE) {
    fail(`${key} 声明 ${declared} 与复算 ${actual.toFixed(3)} 相差 ${delta.toFixed(3)} > ${CONTRAST_TOLERANCE}`)
  } else if (actual < gate) {
    fail(`${key} = ${actual.toFixed(2)}，低于硬闸 ${gate}`)
  } else {
    pass(`${key} = ${actual.toFixed(2)}（声明 ${declared}，闸 ${gate}）`)
  }
  seen.add(key)
}

// 四项必须被声明——漏声明等于把闸拆掉。
for (const theme of ['light', 'dark']) {
  for (const k of [`${theme}/text-on-bg`, `${theme}/text-2-on-bg`]) {
    if (!seen.has(k)) fail(`contrast 缺少必声明项 ${k}`)
  }
  if (![...seen].some((k) => k.startsWith(`${theme}/`) && /accent/i.test(k))) {
    fail(`contrast 缺少 ${theme} 的 accent 相关声明项`)
  }
}

// ---------------------------------------------------------------- 结果

if (failures.length) {
  console.error(`\n${failures.length} 项未通过。`)
  process.exit(1)
}
console.log('\n配色守卫全部通过。')

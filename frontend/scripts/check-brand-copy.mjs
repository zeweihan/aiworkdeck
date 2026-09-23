// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 锁桌面 locale 的品牌文案（onboarding.unlock.brand.*）与 design/copy/brand-copy.json 逐字相等。
// JSON 是各端品牌文案的唯一真源（与 design/tokens/awd-palette.json 同一机制），改文案先改 JSON。
// 同时钉两条结构约束：十类来源的先后顺序与 JSON 一致（与官网 ConvergenceHero 同名同序），
// 以及主标题的着色尾段确实是标题的后缀（否则左栏拆不出两段）。
// 用法：node scripts/check-brand-copy.mjs（挂在 npm run check:locales 里一起跑）
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import path from 'node:path'

const frontendRoot = process.cwd()
const jsonPath = path.resolve(frontendRoot, '../design/copy/brand-copy.json')
const LANGS = [
  { lang: 'zh', locale: 'zh-CN' },
  { lang: 'en', locale: 'en-US' },
]

const { copy } = JSON.parse(readFileSync(jsonPath, 'utf8'))

/** 由 JSON 推出某语言应有的 brand.* 扁平键值（sources 保持 JSON 顺序）。 */
function expectedFor(lang) {
  const out = new Map()
  out.set('brand', copy.brand[lang])
  out.set('edition', copy.edition[lang])
  out.set('tagline', copy.tagline[lang])
  out.set('taglineAccent', copy.tagline.accent[lang])
  out.set('lead', copy.lead[lang])
  for (const s of copy.sources) {
    out.set(`sources.${s.key}`, s[lang])
    if (s.intl) out.set(`sources.${s.key}Intl`, s.intl[lang])
  }
  out.set('sourcesCaption', copy.sourcesCaption[lang])
  out.set('valueStatement', copy.valueStatement[lang])
  return out
}

function flatten(obj, prefix = '') {
  const out = new Map()
  for (const [k, v] of Object.entries(obj || {})) {
    const full = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') for (const [kk, vv] of flatten(v, full)) out.set(kk, vv)
    else out.set(full, v)
  }
  return out
}

let failed = false
const fail = (msg) => { console.error('✗ ' + msg); failed = true }

if (!Array.isArray(copy.sources) || copy.sources.length !== 10) {
  fail(`brand-copy.json 的 sources 必须恰好 10 项，现为 ${copy.sources && copy.sources.length}`)
}

for (const { lang, locale } of LANGS) {
  const file = path.resolve(frontendRoot, 'src/locales', locale, 'onboarding.js')
  const mod = await import(pathToFileURL(file).href)
  const brand = mod.default && mod.default.unlock && mod.default.unlock.brand
  if (!brand) { fail(`${locale}/onboarding.js 缺 unlock.brand`); continue }

  const expected = expectedFor(lang)
  const actual = flatten(brand)
  for (const [k, v] of expected) {
    if (!actual.has(k)) fail(`${locale}: 缺键 unlock.brand.${k}（JSON 值「${v}」）`)
    else if (actual.get(k) !== v) fail(`${locale}: unlock.brand.${k} 与 JSON 不一致\n    locale:「${actual.get(k)}」\n    JSON:  「${v}」`)
  }
  for (const k of actual.keys()) {
    if (!expected.has(k)) fail(`${locale}: unlock.brand.${k} 在 brand-copy.json 里没有对应项（品牌文案只许从 JSON 来）`)
  }

  const order = Object.keys(brand.sources || {}).filter((k) => !k.endsWith('Intl'))
  const jsonOrder = copy.sources.map((s) => s.key)
  if (order.join(',') !== jsonOrder.join(',')) {
    fail(`${locale}: 十类来源顺序与 JSON 不一致\n    locale: ${order.join(',')}\n    JSON:   ${jsonOrder.join(',')}`)
  }

  if (!copy.tagline[lang].endsWith(copy.tagline.accent[lang])) {
    fail(`brand-copy.json: tagline.accent.${lang}「${copy.tagline.accent[lang]}」不是 tagline.${lang} 的后缀`)
  }
}

if (failed) process.exit(1)
console.log('✓ 品牌文案与 design/copy/brand-copy.json 逐字一致')

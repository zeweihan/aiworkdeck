// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 配色令牌生成器：把 design/tokens/awd-palette.json 的 roles.light / roles.dark
// 渲染进本仓三个令牌文件里由标记包住的区段。
//
//   frontend/src/App.vue            --awd-* 浅/深两套（插件 SDK 下发的那一套）
//   frontend/src/uni.scss           $awd-* SCSS 变量
//   office-addin/taskpane/styles.css  Office 任务窗格自己那一套 --awd-*
//   frontend/src/zetaoffice/editor.html  LOWA 编辑器外层页面，浅/深两套，与 App.vue 同源同值
//
// 三条纪律：
//   1. 只替换 AWD-TOKENS:BEGIN <名> 与 AWD-TOKENS:END <名> 之间的内容，
//      标记行本身与文件其余部分一个字节不碰；
//   2. 令牌名是公开契约（第三方插件 CSS、跨仓模板、字面量断言测试都在消费），
//      本文件只把名字映射到语义角色，**永远不改名**；
//   3. 幂等——连跑两次结果必须一致（check-palette.mjs 会拿这一点当判据对拍）。
//
// 改配色的办法是改 design/tokens/awd-palette.json，然后重跑本脚本；
// 三个仓各存一份逐字节相同的色源，由各自的 check-palette.mjs 用 sha256 锁住。
//
// 用法： node scripts/generate-tokens.mjs           写回文件
//        node scripts/generate-tokens.mjs --check   只对拍，不写（等价于 check 的 (b) 步）

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const PALETTE_PATH = resolve(ROOT, 'design/tokens/awd-palette.json')

const GENERATED_NOTE = '此段由 scripts/generate-tokens.mjs 生成，勿手改。色源：design/tokens/awd-palette.json'

// ---------------------------------------------------------------- 取值

function role(palette, theme, key) {
  const v = palette.roles[theme] && palette.roles[theme][key]
  if (!v) throw new Error(`palette.roles.${theme}.${key} 不存在`)
  return v
}

/** 从某个角色的十六进制值派生半透明值（用于色源里没有单独列出的毛玻璃档） */
function alpha(hex, a) {
  const m = /^#([0-9a-fA-F]{6})$/.exec(hex)
  if (!m) throw new Error(`alpha() 只接受 #RRGGBB，收到 ${hex}`)
  const n = parseInt(m[1], 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`
}

// ---------------------------------------------------------------- 分组表
//
// 每组 = [组注释, [令牌名…]]。App.vue 那一套的令牌名与语义角色名一一对应，
// 所以这里只列名字；uni.scss 与 Office 窗格各有自己的历史名，见下面的映射表。

const APP_GROUPS = [
  ['表面：底色 → 卡片 → 悬停/凹陷 → 更重的填充', ['bg', 'surface', 'surface-2', 'surface-3']],
  ['文字三阶 + 强调底上的反白', ['text', 'text-2', 'text-3', 'text-on-accent']],
  ['边框三阶', ['border-subtle', 'border', 'border-strong']],
  ['品牌墨竹青：accent 作底、accent-text 作字（深色下两者取值不同，必须分开）',
    ['accent', 'accent-hover', 'accent-text', 'accent-soft', 'accent-wash']],
  ['氛围色竹月青：大面积浅底/选中/悬停/图表/装饰线，对白底仅 2.6:1，不承载正文与按钮文字。\n' +
   '--awd-mint 是插件 SDK 的公开契约名（utils/appTheme.js 的 THEME_TOKEN_NAMES →\n' +
   'PluginPane 注入第三方插件 iframe，插件 CSS 里写着 var(--awd-mint)），改名会静默\n' +
   '打破它们，所以名字保留、只换值；--awd-bamboo 是同值别名，新代码用语义正确的这个。',
    ['mint', 'bamboo', 'text-on-mint']],
  ['浅茶金点睛：分隔金线 / 引用块 / 徽章 / 空状态描边。对白底 1.7:1，不做大面积底色、\n' +
   '不承载任何信息层级；需要茶金文字时一律用 gold-text。',
    ['gold', 'gold-line', 'gold-text', 'gold-soft']],
  ['语义状态', ['danger', 'danger-text', 'danger-soft', 'warning', 'warning-text', 'warning-soft',
    'info', 'info-text', 'info-soft']],
  ['阴影与遮罩', ['shadow-sm', 'shadow-md', 'shadow-lg', 'overlay']],
  ['空态光晕（工作区/首屏那圈柔光）与整页斜向柔光的落点色', ['halo-1', 'halo-2', 'halo-page']],
  ['毛玻璃表面（登录页那种半透明卡片）', ['glass', 'glass-border']],
  ['编辑器纸外工作区（纸张本身由 LOWA 引擎渲染，永远是纸白，不参与主题）', ['canvas']],
]

// uni.scss 的 $awd-* 是 2026-08 那版留下的历史名（chrome/forest/ivory…），
// 名字不动，按语义重新指到新色源的角色上。[主题, 角色]
const SCSS_GROUPS = [
  ['深色表面阶（历史名 chrome-*，深→浅）', [
    ['chrome-base', 'dark', 'canvas'],
    ['chrome-top', 'dark', 'bg'],
    ['chrome-panel', 'dark', 'surface'],
    ['chrome-line', 'dark', 'border'],
    ['chrome-hover', 'dark', 'surface-2'],
    ['chrome-active', 'dark', 'accent'],
  ]],
  ['品牌色：墨竹青作主色，竹月青作氛围色', [
    ['forest', 'light', 'accent'],
    ['forest-dark', 'light', 'accent-hover'],
    ['mint', 'light', 'mint'],
    ['mint-deep', 'light', 'accent'],
    ['mint-pale', 'light', 'accent-soft'],
    ['brass', 'dark', 'accent'],
  ]],
  ['浅色中性阶（玉脂白系暖调）', [
    ['ivory', 'light', 'surface'],
    ['bone', 'light', 'bg'],
    ['stone', 'light', 'border'],
    ['stone-md', 'light', 'border-strong'],
    ['slate', 'light', 'text-2'],
    ['charcoal', 'light', 'text'],
  ]],
  ['深底文字阶', [
    ['text-on-dark', 'dark', 'text'],
    ['text-on-dark-2', 'dark', 'text-2'],
    ['text-on-dark-3', 'dark', 'text-3'],
  ]],
  ['编辑画布（纸页周围）', [
    ['canvas', 'light', 'canvas'],
  ]],
  ['语义色（-on-dark 变体供深底文字用）', [
    ['amber', 'light', 'warning'],
    ['amber-on-dark', 'dark', 'warning-text'],
    ['brick', 'light', 'danger'],
    ['brick-on-dark', 'dark', 'danger-text'],
    ['sage', 'light', 'mint'],
  ]],
]

// Office 任务窗格自己那一套 --awd-*（与 App.vue 同名不同物，刻意不合并命名）。
// 窗格恒为浅色（外壳保持浅色是维护者定的红线），所以只取 roles.light。
const OFFICE_GROUPS = [
  ['暖白中性阶（玉脂白系）', [
    ['bg', 'bg'],
    ['surface', 'surface'],
    ['bone', 'surface-2'],
    ['border', 'border'],
    ['border-strong', 'border-strong'],
    ['text', 'text'],
    ['text-secondary', 'text-2'],
  ]],
  ['品牌墨竹青 + 氛围竹月青', [
    ['primary', 'accent'],
    ['primary-hover', 'accent-hover'],
    ['accent', 'accent'],
    ['mint', 'mint'],
    ['mint-pale', 'accent-soft'],
    ['user-bubble', 'accent-soft'],
  ]],
  ['语义状态', [
    ['danger', 'danger'],
  ]],
  ['毛玻璃表面（@supports 不支持时回落 glass-strong 纯色）', [
    ['glass', 'glass'],
    ['glass-strong', { alpha: ['surface', 0.9] }],
  ]],
  ['阴影', [
    ['shadow-soft', 'shadow-sm'],
    ['shadow-float', 'shadow-lg'],
  ]],
]

// ---------------------------------------------------------------- 渲染

/** 注释行拆成若干行（组注释里允许 \n） */
function commentBlock(text, style) {
  const lines = text.split('\n')
  if (style === 'css') {
    if (lines.length === 1) return [`/* ${lines[0]} */`]
    return [`/* ${lines[0]}`, ...lines.slice(1).map((l) => `   ${l}`).map((l, i, a) => (i === a.length - 1 ? `${l} */` : l))]
  }
  return lines.map((l) => `// ${l}`)
}

function renderApp(palette, theme) {
  const out = [...commentBlock(GENERATED_NOTE, 'css')]
  for (const [note, names] of APP_GROUPS) {
    out.push(...commentBlock(note, 'css'))
    for (const n of names) out.push(`--awd-${n}: ${role(palette, theme, n)};`)
  }
  return out
}

function renderScss(palette) {
  const out = [...commentBlock(GENERATED_NOTE, 'css')]
  for (const [note, rows] of SCSS_GROUPS) {
    out.push(...commentBlock(note, 'css'))
    for (const [name, theme, key] of rows) {
      out.push(`$awd-${name}: ${role(palette, theme, key)};`)
    }
  }
  return out
}

function renderOffice(palette) {
  const out = [...commentBlock(GENERATED_NOTE, 'css')]
  for (const [note, rows] of OFFICE_GROUPS) {
    out.push(...commentBlock(note, 'css'))
    for (const [name, spec] of rows) {
      const v = typeof spec === 'string'
        ? role(palette, 'light', spec)
        : alpha(role(palette, 'light', spec.alpha[0]), spec.alpha[1])
      out.push(`--awd-${name}: ${v};`)
    }
  }
  return out
}

// ---------------------------------------------------------------- 区段替换

const MARK_BEGIN = (name) => `AWD-TOKENS:BEGIN ${name}`
const MARK_END = (name) => `AWD-TOKENS:END ${name}`

/**
 * 只替换标记之间的内容。标记行本身、缩进、文件其余部分都保持原样。
 * 生成行按 BEGIN 标记行的缩进对齐。
 */
function replaceSection(text, file, name, body) {
  const begin = MARK_BEGIN(name)
  const end = MARK_END(name)
  const bi = text.indexOf(begin)
  if (bi < 0) throw new Error(`${file}: 找不到标记 ${begin}`)
  if (text.indexOf(begin, bi + 1) >= 0) throw new Error(`${file}: 标记 ${begin} 出现多次`)
  const ei = text.indexOf(end, bi)
  if (ei < 0) throw new Error(`${file}: 找不到标记 ${end}`)
  const beginLineStart = text.lastIndexOf('\n', bi) + 1
  const indent = /^[ \t]*/.exec(text.slice(beginLineStart, bi))[0]
  const beginLineEnd = text.indexOf('\n', bi)
  const endLineStart = text.lastIndexOf('\n', ei) + 1
  const rendered = body.map((l) => (l ? indent + l : '')).join('\n')
  return text.slice(0, beginLineEnd + 1) + rendered + '\n' + text.slice(endLineStart)
}

// ---------------------------------------------------------------- 目标表

export function renderAll() {
  const palette = JSON.parse(readFileSync(PALETTE_PATH, 'utf8'))
  const targets = [
    ['frontend/src/App.vue', [
      ['app-light', renderApp(palette, 'light')],
      ['app-dark', renderApp(palette, 'dark')],
    ]],
    ['frontend/src/uni.scss', [
      ['uni-scss', renderScss(palette)],
    ]],
    ['office-addin/taskpane/styles.css', [
      ['office-taskpane', renderOffice(palette)],
    ]],
    ['frontend/src/zetaoffice/editor.html', [
      ['editor-light', renderApp(palette, 'light')],
      ['editor-dark', renderApp(palette, 'dark')],
    ]],
  ]
  const out = new Map()
  for (const [rel, sections] of targets) {
    let text = readFileSync(resolve(ROOT, rel), 'utf8')
    for (const [name, body] of sections) text = replaceSection(text, rel, name, body)
    out.set(rel, text)
  }
  return out
}

function main() {
  const check = process.argv.includes('--check')
  let changed = 0
  for (const [rel, next] of renderAll()) {
    const path = resolve(ROOT, rel)
    const prev = readFileSync(path, 'utf8')
    if (prev === next) {
      console.log(`unchanged  ${rel}`)
      continue
    }
    changed += 1
    if (check) {
      console.error(`DRIFT      ${rel}（入库内容与色源重新生成的结果不一致）`)
    } else {
      writeFileSync(path, next)
      console.log(`written    ${rel}`)
    }
  }
  if (check && changed > 0) {
    console.error('\n跑 node scripts/generate-tokens.mjs 重新生成后再提交。')
    process.exit(1)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()

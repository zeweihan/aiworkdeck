// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 窗格底部许可告示的形态契约（dev-board#765）：
 *   node --test office-addin/taskpane/lib/legalNotice.test.js
 *
 * 这是 AGPL §0 Appropriate Legal Notices 要求的告示，同时是 CLAUDE.md 的溯源红线
 * （「设置页 / Office 与 WPS 任务窗格的许可告示不许删、不许做成强制 logo」）。
 * 2026-09-21 把它从四五行压成折叠一行，**内容一字未删**，所以这份用例钉的是
 * 「压缩之后还满不满足告示要求」，一共五条：
 *
 *   1. 折叠态确实只有一行——summary 里不许出现完整告示的任何一句，否则叫不上「一行」；
 *   2. 完整告示一字未删——版权句、AGPL 句、商标句、三条链接，中英各一份，都在展开区里；
 *   3. 告示不依赖 JS——原生 <details>，不接 onclick/script，且整块在 #app 之外
 *      （Office/WPS 里 office.js CDN 挂了、宿主注入延迟时，Vue 根本没挂上，
 *      告示仍要在。拿 JS 管开合等于把这条保证退化成「告示在、但永远展不开」）；
 *   4. 不是 logo——footer 里不许有 <img>，告示得是能读的文字；
 *   5. 两个入口页逐字相同——Office 与 WPS 各有一份页脚，改一处漏一处的话
 *      WPS 用户会一直停在旧形态，而且没有任何报错。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
/** office-addin/ 根目录（两个入口页在这里） */
const addinRoot = path.join(here, '..', '..')
const read = (rel) => fs.readFileSync(path.join(addinRoot, rel), 'utf8')

/** Office 面与 WPS 面各一个入口页，两份页脚必须一模一样 */
const ENTRIES = ['taskpane.html', 'taskpane-wps.html']

const FOOTER_OPEN = '<footer class="legal-notice">'
const FOOTER_CLOSE = '</footer>'

function footerOf(html) {
  const start = html.indexOf(FOOTER_OPEN)
  const end = html.indexOf(FOOTER_CLOSE, start)
  assert.ok(start >= 0 && end > start, '没找到 .legal-notice 页脚')
  return html.slice(start, end + FOOTER_CLOSE.length)
}

function summaryOf(footer) {
  const start = footer.indexOf('<summary')
  const end = footer.indexOf('</summary>', start)
  assert.ok(start >= 0 && end > start, '没找到 <summary>（折叠行）')
  return footer.slice(start, end)
}

/** 展开后才出现的完整告示：删掉任何一条都是许可问题，不是文案问题 */
const FULL_NOTICE = [
  '版权所有 2026 北京京微资易科技有限公司及 AI WorkDeck 贡献者',
  'Copyright 2026 Beijing Jingwei Ziyi Technology Co., Ltd. and AI WorkDeck contributors',
  '依 GNU Affero General Public License v3.0 或更高版本发布，不提供任何担保。',
  'Released under the GNU Affero General Public License v3.0 or later, with ABSOLUTELY NO WARRANTY.',
  '为北京京微资易科技有限公司的商标，再分发修改版时不得用作产品名',
  'is a trademark of Beijing Jingwei Ziyi Technology Co., Ltd.',
  'https://github.com/zeweihan/aiworkdeck"',
  'https://github.com/zeweihan/aiworkdeck/blob/master/LICENSE',
  'https://github.com/zeweihan/aiworkdeck/blob/master/legal/TRADEMARKS.md'
]

/** 折叠行上必须点到的东西（中英各一套）：品牌 + 许可证 + 三个去处 */
const SUMMARY_TOKENS = {
  zh: ['AI WorkDeck', 'AGPL-3.0', '源代码', '许可证', '商标说明'],
  en: ['AI WorkDeck', 'AGPL-3.0', 'Source', 'License', 'Trademark']
}

for (const entry of ENTRIES) {
  test(`${entry}：折叠态只有一行——完整告示的句子一句都不在折叠行上`, () => {
    const summary = summaryOf(footerOf(read(entry)))
    for (const sentence of FULL_NOTICE) {
      assert.ok(!summary.includes(sentence),
        `折叠行上出现了完整告示的内容，就不叫「一行」了: ${sentence}`)
    }
    // 折叠行上不放链接：点「许可证」是展开告示，不是跳走。链接内嵌在 summary 里时，
    // 点击到底是跟链接还是开合，各家 WebView 的行为并不一致。
    assert.ok(!summary.includes('<a '), '折叠行上不该有链接，三条链接在展开区里')
  })

  test(`${entry}：折叠行中英两套都点明品牌、许可证与三个去处`, () => {
    const summary = summaryOf(footerOf(read(entry)))
    const zh = summary.match(/data-lang="zh"[^>]*>([^<]*)</)
    const en = summary.match(/data-lang="en"[^>]*>([^<]*)</)
    assert.ok(zh && en, '折叠行缺中文或英文那一份')
    for (const token of SUMMARY_TOKENS.zh) {
      assert.ok(zh[1].includes(token), `中文折叠行缺: ${token}（实际: ${zh[1]}）`)
    }
    for (const token of SUMMARY_TOKENS.en) {
      assert.ok(en[1].includes(token), `英文折叠行缺: ${token}（实际: ${en[1]}）`)
    }
  })

  test(`${entry}：完整告示一字未删（版权 / AGPL / 商标 / 三条链接，中英各一份）`, () => {
    const footer = footerOf(read(entry))
    for (const sentence of FULL_NOTICE) {
      assert.ok(footer.includes(sentence), `告示里缺: ${sentence}`)
    }
  })

  test(`${entry}：折叠不接 JS，告示整块在 #app 之外（挂载失败时仍在）`, () => {
    const html = read(entry)
    const footer = footerOf(html)
    assert.ok(footer.includes('<details'), '折叠必须用原生 <details>')
    assert.ok(!/on[a-z]+=/.test(footer), '告示里不许有事件处理器（onclick 之类）')
    assert.ok(!footer.includes('<script'), '告示里不许有 <script>')
    // 空的 #app + 排在它后面的 footer = Vue 挂不上时告示照样渲染
    assert.ok(html.includes('<div id="app"></div>'), '#app 应当是空壳，由 Vue 挂载')
    assert.ok(html.indexOf(FOOTER_OPEN) > html.indexOf('<div id="app"></div>'),
      '告示必须在 #app 之外（而不是 Vue 树里的一个组件）')
  })

  test(`${entry}：告示是文字不是 logo`, () => {
    assert.ok(!footerOf(read(entry)).includes('<img'),
      '告示不许做成图形标记（溯源红线：不许变成强制 logo）')
  })
}

test('两个入口页的页脚逐字相同（改一处漏一处，WPS 用户会停在旧形态且不报错）', () => {
  const [office, wps] = ENTRIES.map((e) => footerOf(read(e)))
  assert.equal(office, wps)
})

test('CSS：默认显中文隐英文，英文只在 <html lang> 判英时才显', () => {
  const css = read('taskpane/styles.css')
  assert.ok(css.includes('.legal-notice .legal-lang[data-lang="en"] { display: none; }'),
    '默认必须隐掉英文那份——脚本没跑起来时 lang 还是页面自带的 zh-CN，' +
    '两份都显就成了同一段告示出现两遍')
  assert.ok(css.includes('html[lang^="en"] .legal-notice .legal-lang[data-lang="zh"] { display: none; }'),
    '判为英文时要隐掉中文那份')
  assert.ok(css.includes('.legal-summary'), '折叠行样式丢了')
})

test('i18n：切语言时同步 <html lang>，否则页脚永远停在一种语言', () => {
  const i18n = read('taskpane/lib/i18n.js')
  assert.ok(i18n.includes('document.documentElement.lang'),
    'i18n 必须把生效语言写到 <html lang>：页脚在 Vue 树外，:key 重挂载带不动它')
  const setLang = i18n.slice(i18n.indexOf('export function setLang'))
  assert.ok(setLang.slice(0, 400).includes('syncDocumentLang()'),
    'setLang 里要跟一句同步，只在模块加载时设一次的话手动切语言页脚不跟着变')
})

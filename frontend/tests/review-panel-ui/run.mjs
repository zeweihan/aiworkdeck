// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#874 verification: ReviewPanel's tab row must fit five English tabs
// (Changes/Comments/Sources/AI Review/Origin + counts) without clipping them off
// the right edge via the horizontal scroller. Screenshots + assertions, modeled
// on tests/chat-presentation-ui/run.mjs's fixture pattern.
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import puppeteer from 'puppeteer-core'

const root = fileURLToPath(new URL('../../', import.meta.url))
const fixture = fileURLToPath(new URL('./', import.meta.url))
const shots = process.env.AWD_SHOTS || '/tmp'

const server = await createServer({
  configFile: false, root: fixture, plugins: [vue()],
  resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] },
  server: { host: '127.0.0.1', port: 5187, strictPort: true, fs: { allow: [root, fileURLToPath(new URL('../../node_modules', import.meta.url))] } },
})
await server.listen()

const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
// favicon.ico 404（这个最小夹具没有站点图标，浏览器自动请求）与本次验证无关；
// 该请求确认过就是它（http 日志核对过 URL），按状态码过滤掉这一条资源加载错误，
// 其余 console error 仍然要报。
page.on('console', (msg) => { if (msg.type() === 'error' && !/status of 404/.test(msg.text())) errors.push(msg.text()) })

// 面板的 CSS 变量令牌来自 App.vue 的顶层令牌表；不引入的话背景/边框全是默认值，
// 截图看不出面板边界。跟 chat-presentation-ui/run.mjs 同一路子。
const { readFileSync } = await import('node:fs')
const appSource = readFileSync(`${root}src/App.vue`, 'utf8')
const tokens = appSource.slice(appSource.indexOf("html,\nhtml[data-theme='light']"), appSource.indexOf('</style>', appSource.indexOf("html,\nhtml[data-theme='light']")))

async function shot(lang, before, label) {
  await page.goto(`http://127.0.0.1:5187/?lang=${lang}&before=${before ? 1 : 0}`)
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app{margin:0;height:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}*{box-sizing:border-box;}view,scroll-view{display:block;}text{display:inline;}#app{height:100vh;}` })
  await page.waitForFunction(() => window.ready)
  await page.waitForSelector('.rp-tab')
  const el = await page.$('.rp')
  await el.screenshot({ path: `${shots}/874-${label}.png` })
  return el
}

// `.rp-tabs { flex: 1 }` stretches the scroller to fill the row regardless of content
// width, so `scrollWidth === clientWidth` whenever content fits — that comparison only
// ever proves "not overflowing", it cannot report *how much* slack is left (scrollWidth
// is spec'd to never read below clientWidth). To get the real margin, measure the last
// tab's right edge against the row's right edge directly.
function overflowInfo() {
  return page.evaluate(() => {
    const row = document.querySelector('.rp-tabs')
    const tabs = [...document.querySelectorAll('.rp-tab')]
    const last = tabs[tabs.length - 1]
    const rowRect = row.getBoundingClientRect()
    const lastRect = last.getBoundingClientRect()
    return {
      scrollWidth: row.scrollWidth, clientWidth: row.clientWidth,
      overflowing: row.scrollWidth > row.clientWidth + 1,
      // 负数＝最后一个标签的右边界已经超出可视区域（被横滚裁掉）；正数＝还剩多少像素余量。
      lastTabMargin: Math.round(rowRect.right - lastRect.right),
    }
  })
}
function panelWidth() {
  return page.$eval('.rp', (el) => el.getBoundingClientRect().width)
}
function tabCounts() {
  return page.$$eval('.rp-tab', (els) => els.map((el) => el.textContent.trim()))
}

try {
  await page.setViewport({ width: 900, height: 900 })

  // ---- zh-CN：宽度不变（320px），标签不溢出，改造前后一样 ----
  await shot('zh-CN', false, 'zh-after')
  const zhAfter = await overflowInfo()
  const zhWidth = await panelWidth()
  assert.equal(Math.round(zhWidth), 320, `zh-CN panel should stay 320px, got ${zhWidth}`)
  assert.equal(zhAfter.overflowing, false, `zh-CN tabs should not overflow: ${JSON.stringify(zhAfter)}`)
  await shot('zh-CN', true, 'zh-before')
  const zhBefore = await overflowInfo()
  assert.equal(zhBefore.overflowing, false, 'zh-CN was never affected by the bug (before === after)')

  // ---- en-US before：复现问题——320px 装不下五个英文标签，最后一个标签（Origin）
  //      的右边界跑到可视区域外，横向滚动条把它连同「AI Review」一起裁掉 ----
  await shot('en-US', true, 'en-before')
  const enBefore = await overflowInfo()
  assert.equal(enBefore.overflowing, true, `en-US at forced 320px should overflow (bug reproduced): ${JSON.stringify(enBefore)}`)
  assert.ok(enBefore.lastTabMargin < 0, `en-US at 320px: last tab should be clipped off-screen, margin=${enBefore.lastTabMargin}px`)
  const beforeCounts = await tabCounts()
  assert.equal(beforeCounts.length, 5, 'all five tabs still exist in the DOM (just visually clipped)')

  // ---- en-US after：面板放宽到 432px，五个标签一行放全、不再溢出，且留有安全余量 ----
  //      （scrollWidth/clientWidth 只能证明「没溢出」，不能证明有多少余量——.rp-tabs 是
  //      flex:1，内容小于容器时 scrollWidth 恒等于 clientWidth；真正的余量看最后一个
  //      标签右边界离行右边界还有多远，见 overflowInfo 的 lastTabMargin。）
  await shot('en-US', false, 'en-after')
  const enAfter = await overflowInfo()
  const enWidth = await panelWidth()
  assert.equal(Math.round(enWidth), 432, `en-US panel should widen to 432px, got ${enWidth}`)
  assert.equal(enAfter.overflowing, false, `en-US tabs should fit at 432px: ${JSON.stringify(enAfter)}`)
  assert.ok(enAfter.lastTabMargin >= 8, `en-US tab row should keep >=8px safety margin (font metrics vary by OS), got ${enAfter.lastTabMargin}px`)
  const afterCounts = await tabCounts()
  assert.deepEqual(afterCounts, beforeCounts, 'same five tabs, same text/counts — only the layout changed')
  console.log('en-US tab labels (after):', afterCounts)

  // ---- en-US after：切到「AI 审校」标签，核对分类行（bucket 行）也放得下 ----
  // uni-h5 把 @tap 编译成手势层监听，puppeteer 的合成点击（鼠标坐标、或直接调
  // DOM el.click()）两种都试过，触发不了它——这与本次要验证的「布局装不装得下」
  // 无关，属手势层兼容性问题。要测的是切过去之后的布局，不是点击手势本身，所以
  // 直接拨动 ReviewPanel 自己的 tab 状态（一个 data 字段）跳过手势层，与 main.js
  // 里直接 push window.chatState.bubbles 是同一个套路（该夹具同款做法，见其注释）。
  await page.evaluate(() => { window.panel.tab = 'chk' })
  await page.waitForFunction(() => window.panel && window.panel.tab === 'chk')
  const bucketOverflow = await page.$eval('.irp-tabs', (el) => el.scrollWidth > el.clientWidth + 1)
  assert.equal(bucketOverflow, false, 'inline-review bucket row (All/Missing info/Consistency/Format & IDs/AI review) should wrap, not overflow')
  const rp = await page.$('.rp')
  await rp.screenshot({ path: `${shots}/874-en-after-buckets.png` })
  const bucketLabels = await page.$$eval('.irp-tab', (els) => els.map((el) => el.textContent.trim()))
  console.log('inline-review bucket labels:', bucketLabels)

  assert.deepEqual(errors, [], `no console/page errors: ${JSON.stringify(errors)}`)
  console.log('OK: dev-board#874 review panel width verification passed')
  console.log(JSON.stringify({ zhWidth, enWidth, zhAfter, enBefore, enAfter }, null, 2))
} finally {
  await browser.close()
  await server.close()
}

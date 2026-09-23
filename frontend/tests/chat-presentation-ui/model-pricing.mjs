// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#853：模型下拉的价格显示，真 ChatInterface + 真 ModelSelectorDropdown，合成 /api/ai/models 响应。
//
// 两种口径各走一遍：平台通道实付价（CNY）与厂商美元标价；空态（向下展开）与对话中（向上展开）
// 两处下拉都要打开看。截图落在 AWD_SHOTS（默认 /tmp）。无头运行。
import assert from 'node:assert/strict'
import { readFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import puppeteer from 'puppeteer-core'

const root = fileURLToPath(new URL('../../', import.meta.url))
const fixture = fileURLToPath(new URL('./', import.meta.url))
const source = readFileSync(`${root}src/App.vue`, 'utf8')
const tokens = source.slice(source.indexOf("html,\nhtml[data-theme='light']"), source.indexOf('</style>', source.indexOf("html,\nhtml[data-theme='light']")))
const shots = process.env.AWD_SHOTS || '/tmp'
mkdirSync(shots, { recursive: true })

// 合成目录：形状照 AiModelCatalogController 的响应。单价取自 AllowedModels（首档，美元 / 百万 tokens），
// 档位按后端同一规则（0.8×输入 + 0.2×输出，阈值 0.3 / 1.0 / 3.0）算好——这里只是夹具，判据在后端
const CATALOG = [
  ['deepseek/deepseek-v4-flash', 'DeepSeek V4 Flash', 'DeepSeek', 'GLOBAL', false, 0.08596, 0.17192, false],
  ['deepseek/deepseek-v4-pro', 'DeepSeek V4 Pro', 'DeepSeek', 'GLOBAL', false, 0.711312, 1.422624, false],
  ['z-ai/glm-5.2', 'GLM-5.2', '智谱', 'GLOBAL', false, 1.19, 3.74, false],
  ['moonshotai/kimi-k2.6', 'Kimi K2.6', '月之暗面', 'GLOBAL', true, 0.95, 4.0, false],
  ['moonshotai/kimi-k3', 'Kimi K3', '月之暗面', 'GLOBAL', true, 3.0, 15.0, false],
  ['qwen/qwen3.8-max', '通义千问 3.8 Max', '阿里云', 'GLOBAL', true, 2.0, 6.0, false],
  ['qwen/qwen3.7-flash', '通义千问 3.7 Flash', '阿里云', 'GLOBAL', true, 0.03, 0.13, true],
  ['bytedance-seed/seed-2.0-lite', '豆包 Seed 2.0 Lite', '字节跳动', 'GLOBAL', true, 0.25, 2.0, true],
  ['minimax/minimax-m3', 'MiniMax M3', 'MiniMax', 'GLOBAL', true, 0.3, 1.2, false],
  ['anthropic/claude-sonnet-5', 'Claude Sonnet 5', 'Anthropic', 'INTERNATIONAL', true, 2.0, 10.0, false],
  ['anthropic/claude-haiku-4.5', 'Claude Haiku 4.5', 'Anthropic', 'INTERNATIONAL', true, 1.0, 5.0, false],
  ['google/gemini-3.6-flash', 'Gemini 3.6 Flash', 'Google', 'INTERNATIONAL', true, 0.75, 3.75, false],
  ['openai/gpt-5.6-terra', 'GPT-5.6 Terra', 'OpenAI', 'INTERNATIONAL', true, 2.0, 12.0, true],
  ['x-ai/grok-4.5', 'Grok 4.5', 'xAI', 'INTERNATIONAL', true, 2.0, 6.0, true],
]
const level = (i, o) => { const b = 0.8 * i + 0.2 * o; return 1 + [0.3, 1.0, 3.0].filter(t => b >= t).length }
const round6 = v => Math.round(v * 1e6) / 1e6
const catalog = (priceDisplay, region = 'INTERNATIONAL') => ({
  networkRegion: region,
  networkRegionMode: 'auto',
  networkRegionBasis: region === 'GLOBAL' ? '系统地区为中国大陆（合成）' : '系统地区不在中国大陆（合成）',
  defaultModel: 'deepseek/deepseek-v4-flash',
  priceDisplay,
  models: CATALOG.filter(r => region === 'INTERNATIONAL' || r[3] === 'GLOBAL').map(([id, name, vendor, reg, vision, inP, outP, tiered]) => ({
    id, name, vendor, region: reg, contextLength: 1000000, vision, inputPricePerM: inP, outputPricePerM: outP, tiered,
    displayInputPerM: round6(inP * priceDisplay.factor), displayOutputPerM: round6(outP * priceDisplay.factor),
    priceLevel: level(inP, outP),
  })),
})
const CHARGED_CNY = { basis: 'charged', channel: 'platform', currency: 'CNY', factor: 8.096, exchangeRate: 6.7468, marginMultiplier: 1.2, currencyBasis: 'reported', rateSource: 'live', rateUpdatedAt: '2026-09-23T02:00:00Z' }
const LIST_PLATFORM = { basis: 'list', channel: 'platform', currency: 'USD', factor: 1, exchangeRate: null, marginMultiplier: null, currencyBasis: null, rateSource: null, rateUpdatedAt: null }

const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5203, strictPort: true, fs: { allow: [root, fileURLToPath(new URL('../../node_modules', import.meta.url))] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', e => errors.push(e.message))
page.on('console', m => { if (m.type() === 'warn' && /Vue warn/.test(m.text()) && /ModelSelector/.test(m.text())) errors.push(m.text()) })

const open = async (body, { lang = 'zh-CN', width = 420, empty = false } = {}) => {
  await page.setViewport({ width, height: 860, deviceScaleFactor: 2 })
  await page.evaluateOnNewDocument(b => { window.__modelsFixture = b }, body)
  await page.goto(`http://127.0.0.1:5203/?lang=${lang}`)
  await page.waitForFunction(() => window.ready)
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family: -apple-system,BlinkMacSystemFont,'PingFang SC','Segoe UI',sans-serif;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} #app{height:100dvh;}` })
  await page.waitForFunction(() => window.chatState.modelGroups.length > 0)
  if (empty) await page.evaluate(() => window.chat.loadMessages('synthetic-empty', []))
  await page.evaluate(() => { window.chatState.showModelDropdown = true })
  await page.waitForSelector('.model-dropdown .model-option')
}
const rows = () => page.$$eval('.model-dropdown .model-option', els => els.map(el => ({
  name: el.querySelector('.model-option-name').textContent.trim(),
  nums: [...el.querySelectorAll('.model-price-num')].map(n => n.textContent.trim()),
  level: [...el.querySelector('.model-option-price').classList].find(c => c.startsWith('lv-')),
  cells: el.querySelectorAll('.model-level-cell.on').length,
  title: el.querySelector('.model-option-price').getAttribute('title'),
  color: getComputedStyle(el.querySelector('.model-option-price')).color,
  weight: getComputedStyle(el.querySelector('.model-option-price')).fontWeight,
  numRight: el.querySelector('.model-price-num').getBoundingClientRect().right,
  sepLeft: el.querySelector('.model-price-sep').getBoundingClientRect().left,
  nameSize: getComputedStyle(el.querySelector('.model-option-name')).fontSize,
  height: el.getBoundingClientRect().height,
})))
const footnote = () => page.$$eval('.model-footnote-line', els => els.map(e => e.textContent.trim()))
// 截图只截下拉本身（它与输入卡是一体的，截整页的话大半是对话历史）
const shot = async (name) => {
  const el = await page.$('.model-dropdown')
  await page.$eval('.model-dropdown', d => { d.scrollTop = 0 })
  await el.screenshot({ path: `${shots}/${name}-top.png` })
  await page.$eval('.model-dropdown', d => { d.scrollTop = d.scrollHeight })
  await el.screenshot({ path: `${shots}/${name}-bottom.png` })
  await page.$eval('.model-dropdown', d => { d.scrollTop = 0 })
}

try {
  // ---- 1. 平台通道实付价，人民币，对话中（向上展开）----
  await open(catalog(CHARGED_CNY))
  assert.ok(await page.$('.model-dropdown.up'), '对话中应向上展开')
  let r = await rows()
  const k3 = r.find(x => x.name === 'Kimi K3')
  assert.deepEqual(k3.nums, ['¥24.3', '¥121'], 'Kimi K3：3×8.096=24.29、15×8.096=121.4')
  assert.equal(k3.level, 'lv-4'); assert.equal(k3.cells, 4)
  assert.match(k3.title, /旗舰/)
  const flash = r.find(x => x.name === 'DeepSeek V4 Flash')
  assert.deepEqual(flash.nums, ['¥0.70', '¥1.4'])
  assert.equal(flash.level, 'lv-1')
  assert.ok(r.every(x => x.nums.every(n => n.startsWith('¥'))), '实付价口径下全部是人民币')
  // 「/」对齐成一列、模型名字号不变
  const sepLefts = new Set(r.map(x => Math.round(x.sepLeft)))
  assert.equal(sepLefts.size, 1, `「/」没有对齐：${[...sepLefts]}`)
  assert.ok(r.every(x => x.nameSize === '13px'))
  // 档位的颜色/字重确实有层级：四档四种组合
  const styles = new Set(r.map(x => `${x.level}|${x.color}|${x.weight}`))
  assert.equal(new Set(r.map(x => x.level)).size, 4)
  assert.equal(styles.size, 4, `四档应恰好四种样式：${[...styles]}`)
  let fn = await footnote()
  assert.ok(fn[0].includes('元 / 百万 tokens') && fn[0].includes('前为输入'), fn[0])
  assert.match(fn[1], /实付价 = 厂商美元标价 × 汇率 6\.75 × 平台系数 1\.20（即时汇率，更新于 2026-09-2[23]）/)
  assert.doesNotMatch(fn[1], /汇率 8\./, '乘积不许冒充汇率')
  assert.match(fn[2], /经济.*标准.*高端.*旗舰/)
  assert.match(fn[3], /网络判定/)
  const overflow = await page.$eval('.model-dropdown', el => {
    const box = el.getBoundingClientRect()
    const lim = box.left + el.clientLeft + el.clientWidth + 1
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    const texts = []
    while (walker.nextNode()) { const r = document.createRange(); r.selectNodeContents(walker.currentNode); const rr = r.getBoundingClientRect(); if (rr.right > lim) texts.push(walker.currentNode.textContent.slice(0, 30) + ' ' + Math.round(rr.right)) }
    return { texts: texts.slice(0, 6), sw: el.scrollWidth, cw: el.clientWidth, bw: Math.round(box.width), wide: [...el.querySelectorAll('*')].filter(c => c.getBoundingClientRect().right > box.left + el.clientLeft + el.clientWidth + 1).slice(0, 8).map(c => c.className + ' ' + Math.round(c.getBoundingClientRect().right) + '/' + Math.round(box.right)) }
  })
  assert.ok(overflow.sw <= overflow.cw + 1, '420px 下不应横向溢出 ' + JSON.stringify(overflow))
  await shot('charged-cny-up-420')

  // 窄面板（AI 面板最窄 240px 对应的输入卡约 300px 视口）
  await open(catalog(CHARGED_CNY), { width: 300 })
  assert.ok(await page.$eval('.model-dropdown', el => el.scrollWidth <= el.clientWidth + 1), '窄面板横向溢出')
  r = await rows()
  assert.ok(r.every(x => x.nums.every(n => n !== '-')))
  await shot('charged-cny-up-300')

  // ---- 2. 厂商美元标价（平台通道但没取到汇率），新对话空态（向下展开）----
  await open(catalog(LIST_PLATFORM, 'GLOBAL'), { empty: true })
  assert.ok(await page.$('.model-dropdown.down'), '空态应向下展开')
  r = await rows()
  assert.equal(r.length, 9, '境内清单 9 条')
  assert.deepEqual(r.find(x => x.name === 'Kimi K3').nums, ['$3.0', '$15.0'])
  assert.deepEqual(r.find(x => x.name === '通义千问 3.7 Flash').nums, ['$0.030', '$0.13'])
  assert.equal(new Set(r.map(x => x.level)).size, 4, '境内清单四档都要有')
  fn = await footnote()
  assert.ok(fn[0].includes('美元 / 百万 tokens'), fn[0])
  assert.match(fn[1], /厂商美元标价，实际按 Credits 扣费/)
  await shot('list-usd-down-420')

  // ---- 3. 英文 ----
  await open(catalog(CHARGED_CNY), { lang: 'en-US' })
  fn = await footnote()
  assert.match(fn[0], /CNY per 1M tokens/)
  assert.match(fn[1], /Charged price = vendor USD list price × exchange rate 6\.75 × platform multiplier 1\.20 \(live rate, updated/)
  await shot('charged-cny-up-420-en')

  // 选中一个模型仍然走 ChatInterface.selectModel（持久化 + 关闭下拉）
  await page.evaluate(() => {
    const el = [...document.querySelectorAll('.model-dropdown .model-option')].find(e => e.textContent.includes('Kimi K3'))
    el.dispatchEvent(new CustomEvent('tap', { bubbles: true }))
  })
  await page.waitForFunction(() => window.chatState.currentModelId === 'moonshotai/kimi-k3' && !document.querySelector('.model-dropdown'))
  assert.equal(await page.evaluate(() => window.fixtureStorage.ai_selected_model), 'moonshotai/kimi-k3')

  assert.deepEqual(errors, [])
  console.log(`PASS: charged CNY / list USD, both placements, alignment, 4 tiers, footnote, en-US, select. Shots in ${shots}`)
} finally {
  await browser.close()
  await server.close()
}

#!/usr/bin/env node
// 反馈浮窗端到端 / feedback widget e2e (Electron + CDP)。
//
// 覆盖浏览器目标够不到的整条链：右下角浮窗 → 打字 → **真的走一次主进程框选截图**
// （在覆盖窗那个独立 BrowserWindow 上派发真实鼠标拖拽）→ **真的录一段音**
// （Chromium 假麦克风）→ 提交 → 后端落库 + 附件落盘 → API 取回校验字节。
//
// 跑法（本机）：
//   1) frontend：`npx uni --port 5174`（dev:h5）
//   2) 一个 local-mode 后端在跑（默认 9696；冷启动可用新 jar 在别的端口顶班）
//   3) cd frontend && npm run test:feedback-e2e
// 注意：会在屏幕上弹出一个 dev Electron 窗口，跑完自动关闭。
//
// Env：FEEDBACK_E2E_DEVURL（默认 http://localhost:5174）、APP_E2E_BACKEND（默认 9696）。
// 端口经 CHECKBA_BACKEND_PORT 传给 Electron 壳——渲染层的基址是壳注入的，
// 只改 VITE_API_BASE_URL 对它无效（desktop-e2e 曾整条链因此失败）。

import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { pickCdpPort, spawnElectron, waitForCdpWs, cdpOwnershipError, hardenPageInput } from '../_lib/electron-cdp.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const desktopDir = path.resolve(frontendDir, '../desktop')
const DEVURL = process.env.FEEDBACK_E2E_DEVURL || 'http://localhost:5174'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9696'
const BACKEND_PORT = new URL(BACKEND).port
// 端口现挑 + 进程树整棵收 + 连前核身份 + 输入加固，见 tests/_lib/electron-cdp.mjs
const CDP_PORT = pickCdpPort('FEEDBACK_E2E_CDP_PORT', 9334)
const MARKER = 'QA_FEEDBACK_' + Date.now()

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---- preflight ----
for (const [what, ok] of [
  ['dev server ' + DEVURL, await fetch(DEVURL).then(() => true).catch(() => false)],
  ['后端 ' + BACKEND, await fetch(BACKEND + '/api/skills/market/list').then(() => true).catch(() => false)],
  ['desktop/node_modules', fs.existsSync(path.join(desktopDir, 'node_modules'))],
]) { if (!ok) { console.error('前置缺失: ' + what); process.exit(2) } }

const QA = { sid: null }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}
{
  const lic = await api('/api/license/status')
  if (lic && !lic.unlocked) {
    const code = process.env.APP_E2E_TRIAL_CODE
      || 'AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK'
    const act = await api('/api/license/activate', { method: 'POST', body: { code } })
    if (!act || act.unlocked !== true) { console.error('试用码解锁失败'); process.exit(2) }
  }
  const wiz = await api('/api/admin/wizard')
  if (wiz && wiz.initialized === false) {
    await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'gemini' } } })
  }
  const proj = await api('/api/projects', { method: 'POST', body: { name: '反馈QA_' + Date.now(), projectType: 'BLANK' } })
  QA.projectId = proj.id
  console.log('本机用户（免登）/ 项目 #' + QA.projectId)
}

// ---- launch dev Electron with CDP + 假麦克风 ----
// --use-fake-device-for-media-stream 让 getUserMedia({audio}) 返回一路合成音轨，
// --use-fake-ui-for-media-stream 免掉权限询问。没有它们这条用例只能靠人对着电脑说话。
console.log('启动 dev Electron（屏幕会出现窗口，结束自动关闭）...')
const { elec, killTree } = spawnElectron({
  desktopDir,
  cdpPort: CDP_PORT,
  env: { AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: DEVURL, CHECKBA_BACKEND_PORT: BACKEND_PORT },
  // 假麦克风：getUserMedia({audio}) 返回一路合成音轨，并免掉权限询问
  extraArgs: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
})
const elecLog = fs.createWriteStream(path.join(os.tmpdir(), 'feedback-e2e-electron.log'))
elec.stdout.pipe(elecLog); elec.stderr.pipe(elecLog)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const ws = await waitForCdpWs(CDP_PORT)
if (!ws) { console.error('CDP 端点未就绪（端口 ' + CDP_PORT + '）'); killTree(); process.exit(1) }
{
  const bad = cdpOwnershipError(CDP_PORT, elec)
  if (bad) { console.error(bad + '。换个端口重跑：FEEDBACK_E2E_CDP_PORT=9401'); killTree(); process.exit(1) }
}

let failed = 0
const step = async (name, fn) => {
  try { await fn(); console.log('  ✓ ' + name) }
  catch (e) { failed++; console.log('  ✗ ' + name + ': ' + String(e.message || e).slice(0, 300)) }
}

// waitForFunction 默认用 requestAnimationFrame 轮询——框选覆盖窗一抢焦点，主窗口的 rAF
// 就被 Chromium 停掉，所有条件等待会集体假超时（表现成"截图没出来/录音没开始"，
// 而实际上两件事都发生了）。窗口失焦是这条链的常态，一律改成定时轮询。
const POLL = (timeout) => ({ timeout, polling: 300 })

const browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
let feedbackId = null
try {
  let page = null
  for (let i = 0; i < 30 && !page; i++) {
    await sleep(1000)
    page = (await browser.pages()).find((p) => p.url().startsWith(DEVURL))
  }
  if (!page) throw new Error('找不到主渲染页')
  await hardenPageInput(page)

  {
    const injected = await page.evaluate(() => (window.checkbaDesktop || {}).apiBaseUrl || null)
    if (!injected || new URL(injected).port !== BACKEND_PORT) {
      throw new Error('渲染层后端(' + injected + ') 与测试后端(' + BACKEND + ') 不一致')
    }
  }

  // 点击命中校验：全屏浮层吃掉点击时，现象只是"点了没反应"，真正的报错会落在
  // 十几秒后一句没头没尾的超时上。当场变成可读错误（沿用 desktop-e2e 的做法）。
  const HIT_CHECK = `
    function hitCheck(el) {
      const r = el.getBoundingClientRect()
      const x = r.x + r.width / 2, y = r.y + r.height / 2
      const hit = document.elementFromPoint(x, y)
      if (hit && el.contains(hit)) return { x, y }
      return { x, y, blockedBy: hit ? hit.tagName.toLowerCase() : '(空白)' }
    }`
  const clickSel = async (sel, timeout = 15000) => {
    await page.waitForSelector(sel, { timeout })
    const box = await page.evaluate((check, s) => {
      const el = document.querySelector(s); if (!el) return null
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK, sel)
    if (!box) throw new Error('找不到可点元素: ' + sel)
    if (box.blockedBy) throw new Error('点击被遮挡[' + box.blockedBy + ']: ' + sel)
    await page.mouse.click(box.x, box.y)
    await sleep(500)
  }
  const clickToolByText = async (label) => {
    const box = await page.evaluate((check, lbl) => {
      const el = [...document.querySelectorAll('.awdfb-tool')].find((e) => e.innerText.trim().includes(lbl))
      if (!el) {
        return { missing: true, panel: !!document.querySelector('.awdfb-panel'),
          labels: [...document.querySelectorAll('.awdfb-tool')].map((e) => e.innerText.trim()) }
      }
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK, label)
    if (!box || box.missing) {
      throw new Error('找不到工具按钮 ' + label + '（浮窗在场=' + (box && box.panel)
        + '，现有按钮=' + JSON.stringify(box && box.labels) + '）')
    }
    if (box.blockedBy) throw new Error('点击被遮挡[' + box.blockedBy + ']: ' + label)
    await page.mouse.click(box.x, box.y)
    await sleep(400)
  }

  await step('进入工作台，右下角浮窗常驻可见', async () => {
    // dev server 冷启动时首次 transform 整个工作台页要几十秒（project-overview 一万多行），
    // 20s 的等待会稳定超时并把原因伪装成「浮窗没出来」
    await page.goto(DEVURL + '/#/pages/project-overview/project-overview?id=' + QA.projectId,
      { waitUntil: 'domcontentloaded', timeout: 120000 })
    await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), POLL(120000))
    await page.waitForSelector('.awdfb-launcher', { timeout: 15000 })
  })

  await step('点开浮窗并打字', async () => {
    await clickSel('.awdfb-launcher')
    await page.waitForSelector('.awdfb-panel', { timeout: 10000 })
    await clickSel('.awdfb-text')
    await page.keyboard.type(MARKER + ' 保存按钮点了没反应')
    const v = await page.$eval('.awdfb-text', (el) => el.value)
    if (!v.includes(MARKER)) throw new Error('输入没进 textarea: ' + v)
  })

  await step('框选截图（主进程覆盖窗上真实拖拽）', async () => {
    await clickToolByText('框选截图')
    // 覆盖窗是独立的 BrowserWindow（data:text/html），在 CDP 里是另一个 page target
    let overlay = null
    for (let i = 0; i < 20 && !overlay; i++) {
      await sleep(500)
      overlay = (await browser.pages()).find((p) => p.url().startsWith('data:text/html'))
    }
    if (!overlay) throw new Error('框选覆盖窗未出现')
    await overlay.mouse.move(200, 200)
    await overlay.mouse.down()
    await overlay.mouse.move(520, 400, { steps: 12 })
    await overlay.mouse.up()
    // 覆盖窗关闭 → 主进程 capturePage → 渲染层裁剪 → 缩略图出现
    await page.waitForFunction(() => document.querySelectorAll('.awdfb-shot').length === 1, POLL(30000))
    // 缩略图是 blob: URL（不是 dataURL），只能看解码后的尺寸来判断裁剪有没有出东西
    const dim = await page.evaluate(() => {
      const img = document.querySelector('.awdfb-shot img')
      return img ? { w: img.naturalWidth, h: img.naturalHeight } : null
    })
    if (!dim || dim.w < 50 || dim.h < 50) {
      throw new Error('截图缩略图尺寸异常（裁剪失败？）: ' + JSON.stringify(dim))
    }
  })

  await step('录一段语音（Chromium 假麦克风）', async () => {
    await clickToolByText('说一段')
    await page.waitForFunction(
      () => [...document.querySelectorAll('.awdfb-tool')].some((e) => e.innerText.includes('停止录音')),
      POLL(15000))
    await sleep(2500)
    await clickToolByText('停止录音')
    await page.waitForSelector('.awdfb-audio', { timeout: 10000 })
  })

  await step('提交并拿到编号', async () => {
    await clickSel('.awdfb-submit')
    // 提交结果落在结果卡（.awdfb-result-msg），不再是页脚状态行，且不再自动关闭
    await page.waitForFunction(
      () => (document.querySelector('.awdfb-result-msg') || {}).innerText?.includes('编号 #'),
      POLL(60000))
    const txt = await page.$eval('.awdfb-result-msg', (el) => el.innerText)
    const m = txt.match(/#(\d+)/)
    if (!m) throw new Error('结果卡里没有编号: ' + txt)
    feedbackId = Number(m[1])
    console.log('    反馈 #' + feedbackId)
  })

  await step('后端库里确有这条：正文/上下文/两个附件', async () => {
    if (!feedbackId) throw new Error('上一步没拿到编号')
    const res = await api('/api/feedback/' + feedbackId)
    const d = res && res.data
    if (!d) throw new Error('取不到反馈详情: ' + JSON.stringify(res).slice(0, 200))
    if (!String(d.text).includes(MARKER)) throw new Error('正文对不上: ' + d.text)
    if (d.status !== 'NEW') throw new Error('初始状态应为 NEW，实际 ' + d.status)
    if (!d.appVersion) throw new Error('缺应用版本')
    const ctx = JSON.parse(d.contextJson || '{}')
    if (!ctx.runtime || !ctx.client) throw new Error('上下文缺 runtime/client 段')
    if (!ctx.client.page) throw new Error('上下文里没有当前页面')
    const types = (d.attachments || []).map((a) => a.type).sort()
    if (types.join(',') !== 'AUDIO,IMAGE') throw new Error('附件类型不对: ' + types.join(','))
  })

  await step('附件字节可取回：PNG 是真图、语音非空', async () => {
    const res = await api('/api/feedback/' + feedbackId)
    for (const a of res.data.attachments) {
      const r = await fetch(BACKEND + '/api/feedback/' + feedbackId + '/attachment/' + a.id,
        { headers: QA.sid ? { 'X-Session-Id': QA.sid } : {} })
      const buf = Buffer.from(await r.arrayBuffer())
      if (buf.length < 100) throw new Error(a.type + ' 附件太小: ' + buf.length)
      if (a.type === 'IMAGE' && buf.slice(0, 8).toString('hex') !== '89504e470d0a1a0a') {
        throw new Error('图片不是 PNG: ' + buf.slice(0, 8).toString('hex'))
      }
      console.log('    ' + a.type + ' ' + buf.length + ' 字节')
    }
  })

  await step('浮窗关闭后 BrowserView 可见性被交还', async () => {
    // 浮窗只置全局 ref，由 project-overview 的 watcher 统一控制 view 显隐；
    // 这里断言提交完成后那个 ref 已复位，否则下次开浏览器 tab 会是黑的。
    // 提交成功后不再自动关闭（用户要能看清结果卡），得手动点关闭。
    await clickSel('.awdfb-x')
    await page.waitForFunction(() => !document.querySelector('.awdfb-mask'), POLL(10000))
    await page.waitForSelector('.awdfb-launcher', { timeout: 10000 })
  })
} finally {
  try { await api('/api/projects/' + QA.projectId, { method: 'DELETE' }) } catch { /* 清不掉不影响结论 */ }
  try { browser.disconnect() } catch { /* ignore */ }
  killTree()
  await sleep(1500)
  try { elec.kill('SIGKILL') } catch { /* ignore */ }
}
console.log(failed ? '\n结果：' + failed + ' 步失败' : '\n结果：反馈浮窗全链路通（截图+语音+落库+附件回读）')
process.exit(failed ? 1 : 0)

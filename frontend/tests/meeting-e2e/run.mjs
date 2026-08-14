#!/usr/bin/env node
// 会议录音端到端走查（Electron + CDP + Chromium 假麦克风），配方沿用 feedback-e2e。
//
// 覆盖链：广场启用 skill → 左栏出现「会议录音」→ 一键开录（真 getUserMedia）→
// 分片边录边传（X-File-Offset）→ 顶部浮动指示器在场 → 停止 → 后端 finish →
// 未配听悟凭证时停在 RECORDED（降级提示在场）→ 音频字节可从文件 API 取回。
// 听悟/OSS 真调用不在本套内（要真凭证），由单测桩覆盖状态机。
//
// 跑法（本机）：
//   1) frontend：`npx uni --port 5174`（dev:h5）
//   2) 本 worktree 后端 jar 已打好：backend/target/*.jar
//   3) cd frontend && npm run test:meeting-e2e
// Env：MEETING_E2E_DEVURL（默认 http://localhost:5174）、MEETING_E2E_JAR（默认自动找）。

import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const backendDir = path.resolve(frontendDir, '../backend')
const desktopDir = path.resolve(frontendDir, '../desktop')
const DEVURL = process.env.MEETING_E2E_DEVURL || 'http://localhost:5174'
const BACKEND_PORT = 9899
const BACKEND = 'http://127.0.0.1:' + BACKEND_PORT
const CDP_PORT = 9337
const ts = Date.now()

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---- 找 jar ----
const jar = process.env.MEETING_E2E_JAR
  || fs.readdirSync(path.join(backendDir, 'target')).filter(f => f.endsWith('.jar') && !f.includes('sources'))
    .map(f => path.join(backendDir, 'target', f))[0]
if (!jar || !fs.existsSync(jar)) { console.error('找不到后端 jar，请先 mvn package'); process.exit(2) }

// ---- preflight dev server ----
if (!(await fetch(DEVURL).then(() => true).catch(() => false))) {
  console.error('前置缺失: dev server ' + DEVURL); process.exit(2)
}

// ---- 起隔离后端（配方源自 app-e2e spawnBackend：隔离 user.home 与 H2） ----
const home = path.join(os.tmpdir(), 'meeting-e2e-' + ts)
fs.mkdirSync(path.join(home, 'cwd'), { recursive: true })
console.log('启动隔离后端 :' + BACKEND_PORT + '（日志 ' + home + '/stdout.log）...')
const backendChild = spawn(process.env.JAVA_HOME + '/bin/java',
  ['-Duser.home=' + home, '-jar', jar], {
  cwd: path.join(home, 'cwd'),
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'desktop',
    SERVER_PORT: String(BACKEND_PORT),
    // jar 的 cwd 在隔离目录里，内置 skill 必须显式指回仓库（发行版由桌面壳注入同名 env）
    AI_SKILLS_BUILTIN_DIR: path.join(backendDir, 'skills'),
    SPRING_DATASOURCE_URL: `jdbc:h2:file:${home}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE`,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
{
  const logFile = fs.createWriteStream(path.join(home, 'stdout.log'))
  backendChild.stdout.pipe(logFile); backendChild.stderr.pipe(logFile)
}
// 提前退出也必须收尸——不杀后端的话它继续占端口，下一轮会悄悄连上这个旧进程
const die = (msg) => { console.error(msg); try { backendChild.kill() } catch (e) { /* ignore */ } process.exit(2) }
let backendUp = false
for (let i = 0; i < 150 && !backendUp; i++) {
  try { const r = await fetch(BACKEND + '/api/auth/me'); backendUp = r.status === 200 } catch (e) { /* 未就绪 */ }
  if (!backendUp) await sleep(1000)
}
if (!backendUp) die('后端 150s 未就绪')

const QA = { sid: null }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}

// ---- 解锁 + 向导 + 项目 + 启用 skill ----
{
  const lic = await api('/api/license/status')
  if (lic && !lic.unlocked) {
    const code = process.env.APP_E2E_TRIAL_CODE
      || 'AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK'
    const act = await api('/api/license/activate', { method: 'POST', body: { code } })
    if (!act || act.unlocked !== true) die('试用码解锁失败')
  }
  const wiz = await api('/api/admin/wizard')
  if (wiz && wiz.initialized === false) {
    // 三档枚举 AWD_CLOUD/OPENROUTER/OLLAMA（feedback-e2e 里的 'gemini' 是改造前的化石，
    // 它连的常驻后端早已初始化，这行从没真正跑过）；OLLAMA 无 key 无跨境闸，最适合 e2e
    const init = await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'OLLAMA' } } })
    if (!init || init.code !== 0) die('向导初始化失败: ' + JSON.stringify(init))
  }
  const proj = await api('/api/projects', { method: 'POST', body: { name: '会议QA_' + ts, projectType: 'BLANK' } })
  QA.projectId = proj.id
  // 广场启停语义：enabled_by_default:false 的 skill 启用即安装，左栏 requiresSkill 过滤据此放行
  const en = await api('/api/skills/meeting-recorder/enable', { method: 'POST' })
  // /api/skills/list 裸返回 List<SkillView>（前端代码里的 .data 是 uni.request 的响应包装，别照抄）
  const list = await api('/api/skills/list')
  const skills = Array.isArray(list) ? list : []
  const mine = skills.find(s => s.id === 'meeting-recorder')
  if (!mine || mine.enabled !== true) {
    die('meeting-recorder skill 未启用: ' + JSON.stringify({ en, mine }))
  }
  console.log('项目 #' + QA.projectId + '，skill 已启用')
}

// ---- dev Electron + 假麦克风 ----
console.log('启动 dev Electron（屏幕会出现窗口，结束自动关闭）...')
const elec = spawn('npx', ['electron', '.',
  '--remote-debugging-port=' + CDP_PORT,
  '--use-fake-device-for-media-stream',
  '--use-fake-ui-for-media-stream',
], {
  cwd: desktopDir,
  env: {
    ...process.env,
    AIWORKDECK_DESKTOP_DEV: '1',
    CHECKBA_DEV_SERVER_URL: DEVURL,
    CHECKBA_BACKEND_PORT: String(BACKEND_PORT),
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
{
  const elecLog = fs.createWriteStream(path.join(os.tmpdir(), 'meeting-e2e-electron.log'))
  elec.stdout.pipe(elecLog); elec.stderr.pipe(elecLog)
}

let ws = null
for (let i = 0; i < 60 && !ws; i++) {
  await sleep(1000)
  ws = await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version').then(r => r.json()).then(j => j.webSocketDebuggerUrl).catch(() => null)
}
if (!ws) { try { elec.kill() } catch (e) { /* ignore */ } die('CDP 端点未就绪') }

let failed = 0
const step = async (name, fn) => {
  try { await fn(); console.log('  ✓ ' + name) }
  catch (e) { failed++; console.log('  ✗ ' + name + ': ' + String(e.message || e).slice(0, 300)) }
}
const POLL = (timeout) => ({ timeout, polling: 300 })

const browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
try {
  let page = null
  for (let i = 0; i < 30 && !page; i++) {
    await sleep(1000)
    page = (await browser.pages()).find(p => p.url().startsWith(DEVURL))
  }
  if (!page) throw new Error('找不到主渲染页')

  {
    const injected = await page.evaluate(() => (window.checkbaDesktop || {}).apiBaseUrl || null)
    if (!injected || new URL(injected).port !== String(BACKEND_PORT)) {
      throw new Error('渲染层后端(' + injected + ') 与测试后端(' + BACKEND + ') 不一致')
    }
  }

  const clickSel = async (sel, timeout = 15000) => {
    await page.waitForSelector(sel, { timeout })
    const box = await page.evaluate((s) => {
      const el = document.querySelector(s); if (!el) return null
      const r = el.getBoundingClientRect()
      const x = r.x + r.width / 2, y = r.y + r.height / 2
      const hit = document.elementFromPoint(x, y)
      if (hit && el.contains(hit)) return { x, y }
      return { x, y, blockedBy: hit ? hit.tagName.toLowerCase() : '(空白)' }
    }, sel)
    if (!box) throw new Error('找不到可点元素: ' + sel)
    if (box.blockedBy) throw new Error('点击被遮挡[' + box.blockedBy + ']: ' + sel)
    await page.mouse.click(box.x, box.y)
    await sleep(500)
  }

  page.on('pageerror', (e) => console.log('    [pageerror] ' + String(e).slice(0, 200)))
  page.on('console', (m) => { if (m.type() === 'error') console.log('    [console.error] ' + m.text().slice(0, 200)) })

  await step('进入工作台，左栏 rail 出现「会议录音」（skill 启用 → requiresSkill 放行）', async () => {
    // 启动直达配方（app-e2e 同款）：写最近项目 → 回根路由 reload → launch 流程送进工作台。
    // 直接 hash 跳 project-overview 会被启动逻辑弹回项目列表。
    await page.goto(DEVURL + '/', { waitUntil: 'domcontentloaded', timeout: 120000 })
    await page.evaluate((id) => localStorage.setItem('checkba_last_project_id', String(id)), QA.projectId)
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 120000 })
    try {
      await page.waitForFunction(
        () => location.hash.includes('pages/project-overview/project-overview'), POLL(60000))
      await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), POLL(120000))
    } catch (e) {
      const url = page.url()
      const text = await page.evaluate(() => document.body.innerText.slice(0, 400)).catch(() => '(取不到)')
      throw new Error('工作台未渲染。url=' + url + ' body=' + JSON.stringify(text))
    }
    await page.waitForSelector('.rail-btn[title="会议录音"]', { timeout: 30000 })
  })

  await step('打开面板：录音区与降级提示（未配听悟凭证）在场', async () => {
    await clickSel('.rail-btn[title="会议录音"]')
    await page.waitForSelector('.mr-record-zone', { timeout: 15000 })
    await page.waitForSelector('.mr-config-hint', { timeout: 10000 })
  })

  await step('一键开录：假麦克风出真音轨，面板进入录音态', async () => {
    await clickSel('.mr-record-btn')
    await page.waitForSelector('.mr-recording-live', { timeout: 20000 })
  })

  await step('顶部浮动「录音中」指示器在场（body 级挂载）', async () => {
    await page.waitForSelector('.mri-pill', { timeout: 10000 })
  })

  let meetingId = null
  await step('后端已建档（RECORDING），分片开始落盘', async () => {
    const res = await api('/api/meetings/projects/' + QA.projectId)
    const list = (res && res.meetings) || []
    if (list.length !== 1) throw new Error('会议列表数量=' + list.length)
    meetingId = list[0].id
    if (list[0].status !== 'RECORDING') throw new Error('状态=' + list[0].status)
    // 等过第一个 5s chunk，直接看落盘字节（upload-status 是断点续传游标接口，
    // 按 wpsFileId 口径查询，对录音这种 wpsFileId 为空的文件报「不存在」，不适用）
    await sleep(12000)
    const audioFileId = list[0].audioFileId
    const r = await fetch(BACKEND + '/api/files/' + audioFileId + '/download')
    const bytes = (await r.arrayBuffer()).byteLength
    if (!bytes || bytes <= 0) throw new Error('12s 后落盘字节=' + bytes)
    console.log('    （录音进行中已落盘 ' + bytes + ' 字节）')
  })

  await step('计时器在走', async () => {
    const t = await page.$eval('.mr-live-time', el => el.innerText.trim())
    if (!/^(\d+:)?\d{2}:\d{2}$/.test(t) || t === '00:00') throw new Error('计时异常: ' + t)
  })

  await step('停止录音：状态收口 RECORDED（无凭证不自动转写），时长落库', async () => {
    await clickSel('.mr-btn.danger')
    await page.waitForFunction(() => !document.querySelector('.mr-recording-live'), POLL(30000))
    let meeting = null
    for (let i = 0; i < 15; i++) {
      meeting = await api('/api/meetings/' + meetingId)
      if (meeting && meeting.status === 'RECORDED') break
      await sleep(1000)
    }
    if (!meeting || meeting.status !== 'RECORDED') throw new Error('状态=' + (meeting && meeting.status))
    if (!meeting.durationMs || meeting.durationMs < 5000) throw new Error('durationMs=' + meeting.durationMs)
  })

  await step('音频字节可从文件 API 取回（>10KB 的 webm）', async () => {
    const meeting = await api('/api/meetings/' + meetingId)
    const r = await fetch(BACKEND + '/api/files/' + meeting.audioFileId + '/download')
    const buf = Buffer.from(await r.arrayBuffer())
    if (buf.length < 10 * 1024) throw new Error('音频只有 ' + buf.length + ' 字节')
    console.log('    （音频 ' + buf.length + ' 字节）')
  })

  await step('列表项状态与浮动指示器收尾', async () => {
    await page.waitForFunction(() => {
      const chip = document.querySelector('.mr-status')
      return chip && chip.innerText.includes('未转写')
    }, POLL(15000))
    const pill = await page.$('.mri-pill')
    if (pill) throw new Error('停止后浮动指示器仍在')
  })

  await step('无凭证提交转写给出可读错误（降级路径）', async () => {
    const res = await api('/api/meetings/' + meetingId + '/transcribe', { method: 'POST' })
    const msg = JSON.stringify(res || {})
    if (!msg.includes('未配置转写服务凭证')) throw new Error('返回=' + msg.slice(0, 200))
  })
} catch (e) {
  failed++
  console.error('致命: ' + (e.message || e))
} finally {
  try { await browser.disconnect() } catch (e) { /* ignore */ }
  try { elec.kill() } catch (e) { /* ignore */ }
  try { backendChild.kill() } catch (e) { /* ignore */ }
}

console.log(failed === 0 ? '\n会议录音 e2e：全部通过' : '\n会议录音 e2e：' + failed + ' 步失败')
process.exit(failed === 0 ? 0 : 1)

#!/usr/bin/env node
// 全应用"真人模拟"e2e / whole-app human-simulation e2e (browser target).
//
// 从登录页真实打字登录开始，以真实鼠标点击走完核心用户旅程：个人中心四 tab、
// 进入项目、上传文件（含 >5MB 分片路径回归）、打开文件、左栏功能区、独立页面
// ——全程收集控制台错误 / 失败 API / 可疑文案，任何断言失败退出码非 0。
//
// 前置：dev:h5 起在 APP_E2E_BASE（默认 http://127.0.0.1:5174，
//       `npx uni --port 5174`），桌面后端 9696 在跑（打包版常驻即可）。
// 自包含：每次运行自注册 qa_bot_<ts> 账号 + 自建 BLANK 项目，不碰真实账号数据；
//       只读 admin 页面，绝不保存全局配置、绝不触发向导重置。
//
// 已知驱动陷阱（来自真机 QA 实录，改动需回看 docs/QA_JOURNEYS.md）：
//  - uni-app 的 @tap 必须用真实鼠标坐标点击（page.mouse.click），DOM el.click() 不触发
//  - 输入框是 .uni-input-input；placeholder 在兄弟 div 不在 input 属性
//  - 图标按钮无文字，用 [title="..."] 定位（左栏 rail、头部工具栏）
//  - 页面/列表异步渲染，点击前必须 waitFor，固定 sleep 不可靠
//  - 编辑器 LOWA 需 COOP/COEP + Electron webview，浏览器目标只验容器不验引擎
//    （引擎键盘链路由 tests/lowa-e2e 专门覆盖）
//
// Env: APP_E2E_BASE / APP_E2E_BACKEND / PUPPETEER_EXECUTABLE_PATH

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.APP_E2E_BASE || 'http://127.0.0.1:5174'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9696'
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = path.join(os.tmpdir(), 'app-e2e-out')
fs.mkdirSync(OUT, { recursive: true })

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---------- self-provision: fresh account + project via API ----------
const ts = Date.now()
const QA = { user: 'qa_bot_' + ts, pass: 'QaBot123456', project: 'QA走查_' + ts }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}
{
  const reg = await api('/api/auth/register', { method: 'POST', body: { username: QA.user, password: QA.pass, displayName: 'QA机器人' } })
  if (!reg || reg.code !== 0) { console.error('注册 QA 账号失败: ' + JSON.stringify(reg).slice(0, 200)); process.exit(2) }
  QA.sid = reg.data.sessionId
  QA.userObj = reg.data.user
  const proj = await api('/api/projects', { method: 'POST', body: { name: QA.project, projectType: 'BLANK' } })
  if (!proj || !proj.id) { console.error('建 QA 项目失败: ' + JSON.stringify(proj).slice(0, 200)); process.exit(2) }
  QA.projectId = proj.id
  console.log('QA 账号 ' + QA.user + ' / 项目 #' + QA.projectId)
}

// ---------- test fixtures ----------
const smallFile = path.join(OUT, 'qa-small.txt')
const bigFile = path.join(OUT, 'qa-big.txt')
fs.writeFileSync(smallFile, 'QA 测试文档 第一行\n第二行 sample\n')
if (!fs.existsSync(bigFile) || fs.statSync(bigFile).size < 6_000_000) {
  const line = '大文件分片上传回归 chunked upload regression padding line\n'
  fs.writeFileSync(bigFile, line.repeat(Math.ceil(6_500_000 / line.length)))
}

// ---------- issue collection ----------
const issues = []
let stepFails = 0, passed = 0
const note = (sev, what) => { issues.push({ sev, what }); console.log('  [' + sev + '] ' + what) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'], defaultViewport: { width: 1440, height: 900 } })
try {
  const page = await browser.newPage()
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    const t = m.text()
    // 资源加载失败由 response 监听按 URL 精确上报（favicon 已滤），这里只收脚本错误
    if (/favicon|sourcemap|vite|Failed to load resource/i.test(t)) return
    note('console', t.slice(0, 280))
  })
  page.on('pageerror', (e) => note('pageerror', String(e).slice(0, 280)))
  page.on('response', (r) => {
    if (r.status() >= 400 && /\/api\//.test(r.url())) note('http' + r.status(), r.request().method() + ' ' + r.url().slice(0, 150))
    else if (r.status() === 404 && !/favicon|hot-update/.test(r.url())) note('asset404', r.url().slice(0, 150))
  })

  const shot = (n) => page.screenshot({ path: path.join(OUT, n + '.png') })
  const textOf = () => page.evaluate(() => document.body.innerText.replace(/\n{2,}/g, '\n'))
  const waitText = async (t, ms = 15000) => {
    await page.waitForFunction((x) => document.body.innerText.includes(x), { timeout: ms }, t)
  }
  const mouseClickText = async (label, { contains = false, nth = 0 } = {}) => {
    const box = await page.evaluate((lbl, cont, n) => {
      const els = [...document.querySelectorAll('*')].filter((el) => {
        if (el.children.length !== 0 || !el.innerText || el.offsetParent === null) return false
        const t = el.innerText.trim()
        return cont ? t.includes(lbl) : t === lbl
      })
      const el = els[n]; if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, label, contains, nth)
    if (!box) throw new Error('找不到文本: ' + label)
    await page.mouse.click(box.x, box.y)
    await sleep(700)
  }
  const mouseClickSel = async (sel) => {
    await page.waitForSelector(sel, { timeout: 10000 })
    const box = await page.evaluate((s) => {
      const el = document.querySelector(s); if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, sel)
    if (!box) throw new Error('找不到选择器: ' + sel)
    await page.mouse.click(box.x, box.y)
    await sleep(700)
  }
  const step = async (name, fn) => {
    try { await fn(); passed++; console.log('  ✓ ' + name); return true }
    catch (e) { stepFails++; note('step-fail', name + ': ' + String(e.message || e).slice(0, 180)); await shot('FAIL-' + name.replace(/[^\w一-龥]/g, '_')); return false }
  }

  // ============ J1 登录（真实打字） ============
  console.log('== J1 登录页真实登录 ==')
  await page.goto(BASE + '/#/pages/login/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.waitForSelector('.uni-input-input', { timeout: 15000 })
  await step('输入账号密码并登录', async () => {
    const inputs = await page.$$('.uni-input-input')
    await inputs[0].click({ clickCount: 3 }); await inputs[0].type(QA.user, { delay: 15 })
    await inputs[1].click({ clickCount: 3 }); await inputs[1].type(QA.pass, { delay: 15 })
    try { await mouseClickText('登 录') } catch { await mouseClickText('登录') }
    await page.waitForFunction(() => !location.hash.includes('login'), { timeout: 15000 })
  })

  // ============ J2 个人中心四 tab ============
  console.log('== J2 个人中心 ==')
  await page.goto(BASE + '/#/pages/userprofile/userprofile', { waitUntil: 'networkidle2' })
  await page.waitForSelector('.project-item-card', { timeout: 20000 })
  for (const tab of ['工作记录', '我的收藏', '我的代办', '设置']) {
    await step('tab ' + tab, async () => {
      await mouseClickText(tab)
      const t = await textOf()
      const m = t.match(/.{0,40}(undefined|NaN|\[object).{0,40}/)
      if (m) throw new Error('页面文本可疑: ' + m[0])
    })
  }
  await mouseClickText('我的项目')

  // ============ J3 进入项目 ============
  console.log('== J3 进入项目 ==')
  await step('点项目卡片进入', async () => {
    await page.waitForSelector('.project-item-card', { timeout: 15000 })
    await waitText(QA.project.slice(0, 8))
    // 注意：卡片标题绑定 @tap.stop=startRename（点名字=重命名），进入项目要点
    // 卡片主体 —— UX 疑点已记录于 docs/QA_JOURNEYS.md
    await mouseClickSel('.project-item-card')
    await page.waitForFunction(() => location.hash.includes('project-overview'), { timeout: 15000 })
    await waitText('资源管理器', 20000)
  })
  await shot('j3-project')

  // ============ J4 上传（小 + 大分片） ============
  console.log('== J4 文件上传 ==')
  const uploadOne = async (file, name) => {
    await mouseClickSel('[title="上传文件"]')
    await waitText('选择文件（支持多选）', 8000)
    const [chooser] = await Promise.all([
      page.waitForFileChooser({ timeout: 8000 }),
      mouseClickText('选择文件（支持多选）', { contains: true }),
    ])
    await chooser.accept([file])
    await sleep(500)
    await mouseClickText('确定上传')
  }
  await step('上传小文件', async () => {
    await uploadOne(smallFile, 'qa-small.txt')
    await waitText('qa-small', 20000)
  })
  await step('上传 >5MB 大文件（分片回归 #156）', async () => {
    await uploadOne(bigFile, 'qa-big.txt')
    await waitText('qa-big', 60000)
  })
  await shot('j4-uploaded')

  // ============ J5 打开文件 ============
  console.log('== J5 打开文件 ==')
  await step('点开 qa-small.txt', async () => {
    await mouseClickText('qa-small', { contains: true })
    await sleep(2500)
    await shot('j5-open')
  })

  // ============ J6 左栏功能区（title 定位图标） ============
  console.log('== J6 左栏功能区 ==')
  for (const title of ['搜索', '文件脱敏', 'EasyVoice', '文件暂存区', '资源管理器']) {
    await step('左栏 ' + title, () => mouseClickSel('[title="' + title + '"]'))
  }
  await shot('j6-rails')

  // ============ J6.6 剪贴板面板 ============
  // 剪贴板簇长期没有端到端覆盖，而它重度依赖 document/window 全局监听
  // （copy/paste/keydown 三路兜底）——正是"搬进 .js 模块后静态检查发现不了"的失效面。
  //
  // 注意：截图(OCR)簇**无法在浏览器目标里驱动**。浏览器路径走
  // navigator.mediaDevices.getDisplayMedia（见 project-overview.vue 的
  // startOcrCapture），headless Chrome 点下去直接 Target closed；桌面路径又依赖
  // 主进程 OverlayWindow。同 LOWA 引擎，属于本套件的目标能力边界，
  // 不要再往这里加 OCR 步骤（加了会整套崩，不是"抖动"）。
  console.log('== J6.6 剪贴板面板 ==')
  await step('剪贴板面板可打开', async () => {
    await mouseClickSel('[title="常用工具"]')
    await mouseClickText('剪贴板')
    await page.waitForFunction(
      () => [...document.querySelectorAll('.tab-indicator')].length > 0,
      { timeout: 10000 }
    )
  })
  await shot('j6.6-clipboard')

  // ============ J6.5 AI 对话（真 UI 打字发送；默认模型 deepseek-v4-flash，
  // $0.09/M tokens，一条消息成本可忽略；AI_E2E=0 跳过） ============
  if (process.env.AI_E2E !== '0') {
    console.log('== J6.5 AI 对话 ==')
    await step('AI 面板发送并收到流式回复', async () => {
      if (!(await page.$('.chat-input-rich'))) await mouseClickSel('[title="AI 助手"]')
      await page.waitForSelector('.chat-input-rich', { timeout: 10000 })
      await mouseClickSel('.chat-input-rich')
      await page.keyboard.type('这是自动化测试。请只回复四个字：测试通过', { delay: 10 })
      await mouseClickSel('.send-btn')
      await waitText('测试通过', 90000) // 流式回复落进气泡
    })
    await step('对话历史落库（#153 轮次回归）', async () => {
      const r = await fetch(BACKEND + '/api/ai/history?projectId=' + QA.projectId, { headers: { 'X-Session-Id': QA.sid } })
      const body = await r.text()
      if (!body.includes('测试通过')) throw new Error('历史中未见 AI 回复内容: ' + body.slice(0, 150))
    })
  }

  // ============ J7 独立页面 ============
  console.log('== J7 独立页面 ==')
  for (const [name, route, expectText] of [
    ['插件广场', '/pages/plugin-market/plugin-market', '插件广场'],
    ['变量库', '/pages/variable-library/variable-library', '新增变量'],
    ['管理页(只读)', '/pages/admin/admin', '系统配置'],
    ['新建项目页', '/pages/newproject/index', '项目创建向导'],
  ]) {
    await step(name, async () => {
      await page.goto(BASE + '/#' + route, { waitUntil: 'networkidle2', timeout: 20000 })
      await waitText(expectText, 15000)
      const t = await textOf()
      const m = t.match(/.{0,40}(undefined|NaN|\[object|服务器内部错误).{0,40}/)
      if (m) throw new Error('页面文本可疑: ' + m[0])
    })
  }

  // ============ J8 API 烟测 ============
  console.log('== J8 API 烟测 ==')
  for (const ep of ['/api/projects/my', '/api/ai/assistants', '/api/ai/config', '/api/skills/list',
    '/api/plugins/list', '/api/sensitive/options', '/api/variables/user', '/api/favorites/my', '/api/auth/me']) {
    await step('GET ' + ep, async () => {
      const r = await fetch(BACKEND + ep, { headers: { 'X-Session-Id': QA.sid } })
      if (r.status >= 400) throw new Error('HTTP ' + r.status)
    })
  }
} finally {
  await browser.close()
  // 清理：删除本次运行的 QA 项目（账号无删除接口，qa_bot_* 会留存，可在管理页清）
  try { await api('/api/projects/' + QA.projectId, { method: 'DELETE' }) } catch {}
}

// ---------- report ----------
const report = { passed, stepFails, issues, out: OUT }
fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
console.log('\n===== 结果 =====')
console.log('步骤: ' + passed + ' 通过, ' + stepFails + ' 失败; 异常信号 ' + issues.length + ' 条 (截图/报告: ' + OUT + ')')
for (const i of issues) console.log('  - [' + i.sev + '] ' + i.what)
process.exit(stepFails ? 1 : 0)

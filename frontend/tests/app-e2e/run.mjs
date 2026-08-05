#!/usr/bin/env node
// 全应用"真人模拟"e2e / whole-app human-simulation e2e (browser target).
//
// 从桌面首启解锁门（launch → unlock，试用码真实打字解锁）开始，以真实鼠标点击
// 走完核心用户旅程：个人中心四 tab、进入项目、上传文件（含 >5MB 分片路径回归）、
// 打开文件、左栏功能区、独立页面——全程收集控制台错误 / 失败 API / 可疑文案，
// 任何断言失败退出码非 0。
//
// 前置：dev:h5 起在 APP_E2E_BASE（默认 http://127.0.0.1:5174，
//       `npx uni --port 5174`，VITE_API_BASE_URL 指向后端），后端须是
//       PR-A（商业化改造去登录）之后的 local-mode 桌面后端——默认 9696
//       （打包版常驻即可）；冷启动联调可用新 jar 在 9797 顶班
//       （SPRING_PROFILES_ACTIVE=desktop + 隔离 user.home/H2/cwd）。
// 自包含：local-mode 免登（任何请求都解析为本机用户，qa_bot 注册已随登录一起
//       消亡），自建 BLANK 项目；只读 admin 页面，绝不保存全局配置、绝不触发
//       向导重置。注意：J1 需要「未解锁」起点，若后端已解锁会先 deactivate，
//       跑完停留在试用版状态——对着真实长驻后端跑时若原状态是账户模式，
//       会在报告里给出无法自动还原的警告。
//
// 桌面环境假冒：launch/unlock/userprofile 等页面用 window.checkbaDesktop 存在性
// 判定桌面（免登）语境，浏览器目标全程注入一个最小桩（shell.openExternal）。
// 全仓桌面 API 调用点都有子对象守卫（window.checkbaDesktop.xxx && ...），
// 最小桩不会引爆任何页面（2026-08-05 全量 grep 核实）。
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
import { spawn } from 'node:child_process'

const BASE = process.env.APP_E2E_BASE || 'http://127.0.0.1:5174'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9696'
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = path.join(os.tmpdir(), 'app-e2e-out')
fs.mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ============ J11 基建：起两个额外后端（团队服务器 S / 同事桌面 B） ============
// 三个隔离旋钮（核验实证，见 ProjectRepoService 构造器与 application-desktop.yml）：
// SERVER_PORT（Spring relaxed binding）、SPRING_DATASOURCE_URL（desktop profile 默认的
// H2 带 AUTO_SERVER=TRUE，同路径会附着而非隔离——这里连 AUTO_SERVER 一起去掉，反正一个
// 文件只有一个 JVM 用）、cwd（storage root-path 默认相对路径 "data"，
// ProjectRepoService 构造器对 user.dir 做「结尾是 backend 则剥离」的 hack，随便一个不以
// backend 结尾的 cwd 就能让 data 落在 cwd/data 下，天然与主实例、彼此隔离）。
// J11_JAR 缺失时整段 J11 在真正跑到时会显式 note('skip', ...)，不静默假绿。
const J11_JAR = process.env.APP_E2E_JAR // 由跑法提供：ls backend/target/*.jar
const spawned = []
// A（长驻真实桌面后端）上建的 CloudConnection id，跑完在 finally 里断开——声明在这个
// 模块顶层作用域（不是 try 块内部）是必须的，`try { let x } finally { ... x ... }`
// 里 x 对 finally 不可见（try 与 finally 是兄弟块，不是嵌套块，这条本身在写 :1204
// 附近的清理逻辑时现场踩过一次 ReferenceError）。
let aConnectionId = null
async function spawnBackend(tag, port, extraArgs = []) {
  // home 带 ts（本次运行的时间戳，QA.project 同一个）：OUT 是固定的系统临时目录，
  // 不带 ts 的话连续两次跑会复用同一份 H2 文件库，第二次跑会撞上第一次跑注册过的
  // lawyer_a/lawyer_b/b_local 账号名（现场调试实证：第二轮直接「用户名已存在」）。
  const home = path.join(OUT, 'j11-' + tag + '-' + ts)
  fs.mkdirSync(path.join(home, 'cwd'), { recursive: true })
  // -Duser.home 隔离：license.json 落在 ${user.home}/.aiworkdeck，不隔离的话
  // 临时后端会读写真实桌面的授权状态文件。
  const child = spawn(process.env.JAVA_HOME + '/bin/java',
    ['-Duser.home=' + home, '-jar', J11_JAR, ...extraArgs], {
    cwd: path.join(home, 'cwd'),
    env: {
      ...process.env,
      SPRING_PROFILES_ACTIVE: 'desktop',
      SERVER_PORT: String(port),
      SPRING_DATASOURCE_URL: `jdbc:h2:file:${home}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const logFile = fs.createWriteStream(path.join(home, 'stdout.log'))
  child.stdout.pipe(logFile); child.stderr.pipe(logFile)
  spawned.push(child)
  const base = `http://127.0.0.1:${port}`
  for (let i = 0; i < 120; i++) { // 最多等 120s
    try { const r = await fetch(base + '/api/auth/me'); if (r.status === 200) return base }
    catch (e) { /* 未就绪 */ }
    await sleep(1000)
  }
  throw new Error(`backend ${tag}:${port} 未在 120s 内就绪，日志见 ${home}/stdout.log`)
}
// 复刻下方 :41 附近的 api()：base 可变（构造时传入）、sid 独立（每个实例自己的可写
// 属性），供 J11 里 S（团队服务器）/B（同事桌面）两套裸 REST 身份各自维护登录态、互不干扰。
function mkApi(base) {
  const call = async (ep, opts = {}) => {
    const r = await fetch(base + ep, {
      method: opts.method || 'GET',
      headers: { 'Content-Type': 'application/json', ...(call.sid ? { 'X-Session-Id': call.sid } : {}) },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    })
    return r.json().catch(() => null)
  }
  call.sid = null
  call.base = base
  return call
}

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---------- self-provision: local-mode 免登，直接建项目 ----------
// PR-A 去登录后 desktop profile 开 security.local-mode：任何请求（含无 session
// 头）一律解析为本机用户，注册/登录接口已不在桌面链路上。QA.sid 恒为 null，
// api() 的条件头保留是为了 S（团队服务器，非 local-mode）侧 mkApi 的对称性。
const ts = Date.now()
const QA = { project: 'QA走查_' + ts, sid: null }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}
{
  const proj = await api('/api/projects', { method: 'POST', body: { name: QA.project, projectType: 'BLANK' } })
  if (!proj || !proj.id) { console.error('免登建 QA 项目失败: ' + JSON.stringify(proj).slice(0, 200)); process.exit(2) }
  QA.projectId = proj.id
  console.log('本机用户（免登）/ 项目 #' + QA.projectId)
}

// J1 用的公开通用试用码（GitHub README 公开发布的那枚，Ed25519 离线验签）。
const TRIAL_CODE = process.env.APP_E2E_TRIAL_CODE
  || 'AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK'

// ---------- test fixtures ----------
const smallFile = path.join(OUT, 'qa-small.txt')
const bigFile = path.join(OUT, 'qa-big.txt')
const versionFileA = path.join(OUT, 'qa-版本测试A.txt')
const versionFileB = path.join(OUT, 'qa-版本测试B.txt')
const versionFileC = path.join(OUT, 'qa-版本测试.txt')
fs.writeFileSync(smallFile, 'QA 测试文档 第一行\n第二行 sample\n')
if (!fs.existsSync(bigFile) || fs.statSync(bigFile).size < 6_000_000) {
  const line = '大文件分片上传回归 chunked upload regression padding line\n'
  fs.writeFileSync(bigFile, line.repeat(Math.ceil(6_500_000 / line.length)))
}
fs.writeFileSync(versionFileA, 'QA 版本记录旅程测试文件 A\n')
fs.writeFileSync(versionFileB, 'QA 版本记录旅程测试文件 B\n')
fs.writeFileSync(versionFileC, 'QA 版本记录旅程测试文件（单文件历史/MODIFY 用）\n')

// ---------- issue collection ----------
const issues = []
let stepFails = 0, passed = 0
const note = (sev, what) => { issues.push({ sev, what }); console.log('  [' + sev + '] ' + what) }

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'], defaultViewport: { width: 1440, height: 900 } })
try {
  const page = await browser.newPage()
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    const t = m.text()
    // 资源加载失败由 response 监听按 URL 精确上报（favicon 已滤），这里只收脚本错误。
    // api.js 对每个非 2xx 都会 console.error('HTTP 状态码错误')——与 response 监听
    // 完全重复（后者带 URL 与方法，更精确），且 J1 坏码步骤会故意触发一次，滤掉。
    if (/favicon|sourcemap|vite|Failed to load resource|HTTP 状态码错误/i.test(t)) return
    note('console', t.slice(0, 280))
  })
  page.on('pageerror', (e) => note('pageerror', String(e).slice(0, 280)))
  page.on('response', (r) => {
    // J1 故意用坏码打 /api/license/activate 验证 400 内联报错——这个 400 是断言
    // 目标本身，不是异常信号
    if (r.status() === 400 && r.url().includes('/api/license/activate')) return
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
  // 协作抽屉（PR-E）：交稿/取回/放进团队案件库三个动作全在这个页面级弹窗里，做完
  // 必须显式关掉——它是全屏遮罩，不关的话后续点版本面板/rail 的坐标全落在遮罩上，
  // 且不会报错、只会让后面的断言默默超时（v1 地雷 #24 的同款失败形态）。
  const closeCollabDialog = async () => {
    if (!(await page.$('.collab-dialog'))) return
    await mouseClickSel('.collab-dialog .awd-footer .awd-btn')
    await page.waitForFunction(() => !document.querySelector('.collab-dialog'), { timeout: 10000 })
  }
  // 右键版本：FileTree.vue 的右键菜单绑定的是原生 @contextmenu.prevent（不是 uni
  // @tap），真实鼠标右键会在 headless Chrome 里派发一个会冒泡的原生 contextmenu
  // 事件，能直接命中绑定在祖先行元素上的监听器，不需要 page.evaluate 派发合成事件
  // 降级（读过 FileTree.vue :383/:521/:2066 确认过）。
  const mouseRightClickText = async (label, { contains = false, nth = 0 } = {}) => {
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
    await page.mouse.click(box.x, box.y, { button: 'right' })
    await sleep(700)
  }
  const step = async (name, fn) => {
    try { await fn(); passed++; console.log('  ✓ ' + name); return true }
    catch (e) { stepFails++; note('step-fail', name + ': ' + String(e.message || e).slice(0, 180)); await shot('FAIL-' + name.replace(/[^\w一-龥]/g, '_')); return false }
  }

  // ============ J1 首启解锁 → 直达（商业化改造 PR-A 后的启动链） ============
  // 旧 J1「登录页真实打字登录」已随桌面去登录整体移除：login.vue 只剩浏览器访问
  // 团队服务器的场景（不在本套件覆盖面内）。issue #200「J1 登录抖动」（登录输入
  // 去抖截断密码）失去了存在的土壤，随本次重写一并消亡。
  //
  // 桌面判定桩：launch/unlock/userprofile 以 window.checkbaDesktop 存在性判定
  // 桌面（免登）语境。evaluateOnNewDocument 注册的最小桩对之后每个新文档生效，
  // 全程保持——这正是新基线（桌面=免登）的浏览器映射。
  console.log('== J1 首启解锁门 ==')
  await page.evaluateOnNewDocument(() => {
    window.checkbaDesktop = { shell: { openExternal: () => Promise.resolve() } }
  })

  // J1 需要「未解锁」起点。对着已解锁的长驻后端跑时先 deactivate（跑完停在
  // 试用版）；原状态若是账户模式无法自动还原，如实警告。
  {
    const lic0 = await api('/api/license/status')
    if (lic0 && lic0.unlocked) {
      if (lic0.mode && lic0.mode !== 'trial') {
        note('warn', '后端原授权模式为 ' + lic0.mode + '，J1 将 deactivate 且只能还原为 trial，需手工重连账户')
      }
      await api('/api/license/deactivate', { method: 'POST' })
    }
  }

  await step('launch 未解锁分流到 unlock 页', async () => {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForFunction(() => location.hash.includes('pages/unlock/unlock'), { timeout: 20000 })
    // uni-app 的 .unlock-input 是 wrapper，真 textarea 在里面
    await page.waitForSelector('.unlock-input textarea', { timeout: 10000 })
  })

  await step('坏码走后端 400 内联报错', async () => {
    await page.type('.unlock-input textarea', 'AWD-T-BAD-CODE')
    // uni useValueSync 的 triggerInput 是 100ms throttle：快速连打只有首字符进
    // v-model。停一拍再补敲一个会被前端去空白的空格，让最后一次 input 以完整值
    // 触发 leading call（真实用户粘贴是单次 input 事件，不受此影响）。
    await sleep(250); await page.type('.unlock-input textarea', ' '); await sleep(250)
    await mouseClickSel('.unlock-btn')
    await page.waitForFunction(() => {
      const el = document.querySelector('.unlock-error')
      return !!el && el.textContent.includes('格式不正确')
    }, { timeout: 10000 })
  })

  await step('真试用码解锁（含粘贴态换行空格去除）', async () => {
    // 重进拿干净输入框（hash 同页时 goto 不重载文档，补一次 reload）
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForFunction(() => location.hash.includes('pages/unlock/unlock'), { timeout: 20000 })
    await page.waitForSelector('.unlock-input textarea', { timeout: 10000 })
    // 模拟从邮件/网页复制来的粘贴形态：中间夹换行和空格，验证前端去空白
    const messy = TRIAL_CODE.slice(0, 30) + '\n ' + TRIAL_CODE.slice(30)
    await page.type('.unlock-input textarea', messy)
    await sleep(250); await page.type('.unlock-input textarea', ' '); await sleep(250)
    await mouseClickSel('.unlock-btn')
    // 解锁成功 → toast → reLaunch 回 launch 分流：向导未初始化去 wizard，
    // 已初始化直接进应用（长驻后端场景）
    await page.waitForFunction(() => {
      const h = location.hash
      return h.includes('pages/wizard/wizard') || h.includes('pages/userprofile/userprofile')
        || h.includes('pages/project-overview/project-overview')
    }, { timeout: 30000 })
  })

  await step('向导页无 admin/123 口令提示（未初始化时）', async () => {
    if (!page.url().includes('pages/wizard/wizard')) return // 已初始化后端：此腿天然不出现
    const t = await textOf()
    if (t.includes('admin') && t.includes('123')) throw new Error('wizard 页仍含 admin/123 提示')
    // API 置初始化（与向导 UI 等价的后端出口；向导 UI 自身的交互不在本套件覆盖面）
    const init = await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'gemini' } } })
    if (!init || init.code !== 0) throw new Error('API 置向导初始化失败: ' + JSON.stringify(init).slice(0, 150))
  })

  await step('已解锁重启 → 直达上次项目', async () => {
    // uni h5 getStorageSync 兼容裸字符串
    await page.evaluate((id) => localStorage.setItem('checkba_last_project_id', String(id)), QA.projectId)
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForFunction(() => location.hash.includes('project-overview'), { timeout: 30000 })
    await waitText('资源管理器', 20000)
  })

  await step('project-overview 常驻试用版标识', async () => {
    await page.waitForSelector('.trial-chip', { timeout: 15000 })
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
    // 「常用工具」是开关：面板已开时再点会把它关掉——先探测再决定是否点击
    if (!(await page.$('.bottom-panel'))) await mouseClickSel('[title="常用工具"]')
    await page.waitForSelector('.bottom-panel', { timeout: 10000 })
    await mouseClickText('剪贴板')
    // 断言 ClipboardPanel 组件真的挂载了（.tab-indicator 只说明有 tab 激活，面板挂载失败也存在）
    await page.waitForSelector('.clip-panel', { timeout: 10000 })
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
      const r = await fetch(BACKEND + '/api/ai/history?projectId=' + QA.projectId, { headers: QA.sid ? { 'X-Session-Id': QA.sid } : {} })
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
    ['新建项目页', '/pages/newproject/index', '新建或打开项目'],
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
      const r = await fetch(BACKEND + ep, { headers: QA.sid ? { 'X-Session-Id': QA.sid } : {} })
      if (r.status >= 400) throw new Error('HTTP ' + r.status)
    })
  }

  // ============ J9 版本记录 ============
  // 注：本 harness 跑浏览器目标，不驱动 LOWA 引擎，工作段不能靠"改文档"触发，
  // 改用真实 UI 上传文件（走既有 uploadOne，与 J4 同一条链路，真正落盘到项目
  // 工作区目录，git diff 才看得见）。上传前后要切回/切出资源管理器面板，
  // 因为版本面板与文件树共用同一个侧栏挂载点（project-overview.vue 的
  // sidebar-content 按 leftPaneKey 互斥渲染），版本面板打开时文件树（含
  // "上传文件"按钮）不在 DOM 里。
  //
  // 旅程结构（task-15 报告定案后的修正版，需要两段工作）：
  // brief 原设计的"退回"点的是刚结束的那个工作段自己——单工作段场景下，退回
  // 目标恒等于当前 HEAD，天然是自我退回、天然不会增长历史，测不出"历史只增
  // 不减"这件事。这里改成两段工作，退回到第一段（非当前 HEAD、也不是初始提
  // 交）——这是 task-15 报告里第三组 API 验证过确实健全的路径。
  console.log('== J9 版本记录 ==')
  await page.goto(BASE + '/#/pages/project-overview/project-overview?id=' + QA.projectId,
    { waitUntil: 'networkidle2', timeout: 30000 })
  await sleep(1500)

  await step('打开版本面板并看到未开启引导', async () => {
    await mouseClickSel('[title="版本"]')
    await waitText('本项目还没有开启版本记录')
  })

  await step('开启版本记录后回到空闲态', async () => {
    // brief 原断言是 waitText('主线')；实际 WorkSessionBar.vue 空闲态文案是
    // "当前没有进行中的工作"，全仓没有"主线"这个用户可见文案（唯一命中是
    // VersionTimeline.vue 里一行代码注释）。以实际组件模板为准改断言，不改产品文案。
    await mouseClickText('开启版本记录')
    await waitText('当前没有进行中的工作')
  })

  // 一段完整工作：上传一个文件、进入工作中、结束并命名、回到空闲态。
  const runWorkSession = async (file, uploadLabel, title) => {
    await mouseClickSel('[title="资源管理器"]')
    await uploadOne(file, uploadLabel)
    await waitText(uploadLabel, 20000)
    await mouseClickSel('[title="版本"]')
    await waitText('工作中')

    await mouseClickText('结束本次工作')
    // 注意：brief 原写法选 .awd-input（模板里 <input> 标签自带的 class），
    // 但那是 uni-app <input> 组件编译到 H5 后的外层包装元素的 class，
    // 真正可编辑的原生 <input> 在其内部另有 .uni-input-input（顶部注释
    // 已点名的陷阱）。用 .awd-input 定位+type 会打字打空，导致命名没生效、
    // 后端按空标题回退成自动生成的"修改了《X》"文案——本地实测过一次，
    // 时间线里出现的确实是自动文案而非命名标题，故在此改用真实输入框。
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type(title, { delay: 15 })
    // uni-app 编译出的 input 把 DOM 值同步回 v-model 的 title 有一拍去抖延迟——
    // 键入最后一个字符后 DOM .value 已经是全量文本，但若立刻点击"完成"，
    // Vue 数据层的 title 仍是去抖前的值，提交的标题会丢最后一个字。
    // 实测过 300ms 足够让同步落定（真实网络请求里标题不再缺字）。
    await sleep(300)
    await mouseClickText('完成')
    // brief 原断言是 waitText('主线')；实际空闲态文案是"当前没有进行中的工作"
    // （见上一步同样的改动理由），以实际组件模板为准。
    await waitText('当前没有进行中的工作')
  }

  await step('第一段工作：上传文件并命名结束', () =>
    runWorkSession(versionFileA, 'qa-版本测试A', '端到端测试稿一'))

  await step('时间线出现第一个工作段的命名节点', async () => {
    await waitText('端到端测试稿一')
  })

  await step('第二段工作：上传另一文件并命名结束', () =>
    runWorkSession(versionFileB, 'qa-版本测试B', '端到端测试稿二'))

  await step('时间线同时保留两个工作段的命名节点', async () => {
    await waitText('端到端测试稿二')
    await waitText('端到端测试稿一')
  })

  let beforeCount = 0
  await step('第一个工作段的节点详情列出被改文件', async () => {
    beforeCount = await page.evaluate(() =>
      document.querySelectorAll('.timeline-node').length)
    await mouseClickText('端到端测试稿一')
    await waitText('qa-版本测试A')
  })

  await step('退回到非当前 HEAD 的早先节点后历史只增不减', async () => {
    await mouseClickText('退回到这一版')
    // uni.showModal 的确认按钮：浏览器目标（无 zh-CN 定位配置）下 uni-app 用的是
    // 内置英文默认文案"OK"，不是"确定"（本地实测截图确认，brief 对文案的假设
    // 是按 Electron 壳的本地化推断的，H5 裸浏览器环境下不成立）——两个都试。
    try { await mouseClickText('确定') } catch { await mouseClickText('OK') }
    await sleep(2000)
    const afterCount = await page.evaluate(() =>
      document.querySelectorAll('.timeline-node').length)
    if (afterCount <= beforeCount) {
      throw new Error('退回后版本数没有增加：' + beforeCount + ' -> ' + afterCount)
    }
  })

  // ============ J9 追加 1/3：标为重要版本 ============
  // 读过 VersionNodeDetail.vue 实际模板（不信 brief 转述，J9 上一轮已吃过一次
  // 文案不符的亏）：footer 按钮首次标记时文案是「标为重要版本」（已标过才变成
  // 「重新命名重要版本」），命名弹窗是独立的 .awd-dialog（milestoneNaming），
  // 输入框同样是 .uni-input-input（外层 .awd-input 只是 uni-app 编译出的包装
  // class），确认按钮文案是「确定」——这个弹窗是纯自定义 view，不是 uni.showModal，
  // 不存在浏览器 H5 下英文默认文案的问题，不用 OK/确定 双试。标记后节点标题按
  // VersionTimeline.vue :17-20 变成 milestone-flag「重要版本」+ milestone 名字
  // （原标题「端到端测试稿二」不再作为标题展示，但仍在 DOM 别处保留不影响后续步骤）。
  await step('打开第二个命名节点详情', async () => {
    await mouseClickText('端到端测试稿二')
    await waitText('标为重要版本')
  })

  await step('标为重要版本并命名', async () => {
    await mouseClickText('标为重要版本')
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type('e2e 里程碑', { delay: 15 })
    await sleep(300) // 同款去抖陷阱，见上面命名结束工作的注释
    await mouseClickText('确定')
    await waitText('重要版本')
    await waitText('e2e 里程碑')
  })

  await step('关闭节点详情', () => mouseClickText('关闭'))

  // ---- 为「对比入口」步骤准备一个真实 MODIFY 变更 ----
  // 现有两段工作（A/B）里的文件改动都是 ADD（各自新建一个文件），时间线上目前
  // 没有任何 MODIFY 条目——而 VersionNodeDetail.vue 的「和上一版对比」按钮只在
  // `c.type === 'MODIFY'` 时渲染。浏览器目标不驱动 LOWA，不能靠"编辑文档"产生
  // MODIFY；FileTree 右键菜单也没有"替换/重新上传"这类入口（同名上传会被后端
  // ProjectFileService.createFile 的同名校验拒绝），UI 上真做不出一次 MODIFY。
  // 改用与 J6.5/J8 一致的裸 REST 手段：先正常上传一份测试文件并结束（ADD 落进
  // 历史），再直接 POST 到同一个 wpsFileId 的上传端点覆盖字节（FileController
  // .uploadFile 对已存在 wpsFileId 的裸覆盖上传和 UI 上传走的是同一段
  // signalChange 逻辑，产生的是同一种真实变更信号，不是伪造断言）。
  await step('追加工作：上传单文件历史/MODIFY 测试用文件', () =>
    runWorkSession(versionFileC, 'qa-版本测试', '端到端测试稿三'))

  await step('REST 直传覆盖同一文件产生 MODIFY 变更', async () => {
    const list = await api('/api/projects/' + QA.projectId + '/files')
    const f = (Array.isArray(list) ? list : []).find((x) => x.name === 'qa-版本测试.txt')
    if (!f || !f.wpsFileId) throw new Error('找不到 qa-版本测试.txt 或其 wpsFileId: ' + JSON.stringify(list).slice(0, 200))
    const form = new FormData()
    form.append('file', new Blob(['QA 版本记录旅程测试文件（已修改，用于 MODIFY 断言）\n'], { type: 'text/plain' }), 'qa-版本测试.txt')
    const r = await fetch(BACKEND + '/api/files/' + f.wpsFileId + '/upload', {
      method: 'POST',
      headers: QA.sid ? { 'X-Session-Id': QA.sid } : {},
      body: form,
    })
    const j = await r.json()
    if (!j || j.code !== 0) throw new Error('REST 直传失败: ' + JSON.stringify(j))
  })

  await step('切回版本面板确认已进入工作中', async () => {
    // 裸 REST 调用不会推送给前端；版本面板只在挂载时读一次状态，靠切出/切回
    // 侧栏挂载点强制重新挂载、重新拉取状态（与 runWorkSession 里同样的手法）。
    await mouseClickSel('[title="资源管理器"]')
    await mouseClickSel('[title="版本"]')
    await waitText('工作中')
  })

  await step('结束追加工作并命名（产生 MODIFY 提交）', async () => {
    await mouseClickText('结束本次工作')
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type('端到端测试稿四', { delay: 15 })
    await sleep(300)
    await mouseClickText('完成')
    await waitText('当前没有进行中的工作')
  })

  // ============ J9 追加 2/3：对比入口 ============
  // 本 harness 是浏览器目标，fileOpenTabs.js 的 onVersionCompareFile 对非 docx
  // （或非桌面）一律走文本对比降级分支 openVersionTextDiffTab，最终由
  // DocDiffViewer.vue 以 versionSpec 模式渲染——工具栏副标题
  // `{{ displaySourceName }} vs {{ displayTargetName }}` 不在任何 #ifdef H5
  // 条件块里，始终渲染，取值是 versionSpec.oldLabel/newLabel，即
  // fileOpenTabs.js 里写死的「上一版」「这一版」（DocDiffViewer.vue :92-98）。
  // 桌面 docx 走的 LOWA 修订稿对比分支已由 lowa-e2e 组 13（compare_document）
  // 覆盖，这里只补浏览器目标能验证的这一半。
  await step('打开产生 MODIFY 变更的节点详情', async () => {
    await mouseClickText('端到端测试稿四')
    await waitText('qa-版本测试.txt')
  })

  await step('MODIFY 文件行存在「和上一版对比」按钮', async () => {
    await waitText('和上一版对比')
  })

  await step('点击对比按钮打开文本对比标签', async () => {
    await mouseClickText('和上一版对比')
    // 断言标签**真的渲染出来了**，而不是只躺在 leftFiles 里被 isTabVisible 藏死。
    // 只 waitText('上一版')/('这一版') 是假阳性：还开着的详情弹窗里那颗按钮的
    // 文字本身就含「上一版」——第 2 期终审 C1（版本面板下对比标签永远不可见）
    // 就是被这个假阳性放过去的。改成认 DocDiffViewer 自己的根类名 + 工具栏副标题
    // （`{{ displaySourceName }} vs {{ displayTargetName }}`，取值是 fileOpenTabs.js
    // 写死的「上一版」「这一版」），并要求元素有真实布局盒（被 display:none
    // 祖先藏起来时 getClientRects() 为空）。
    await page.waitForFunction(() => {
      const el = document.querySelector('.doc-diff-viewer')
      if (!el || el.getClientRects().length === 0) return false
      const sub = el.querySelector('.toolbar-subtitle')
      return !!sub && sub.innerText.includes('上一版') && sub.innerText.includes('这一版')
    }, { timeout: 20000 })
    // 详情弹窗应当已自行关闭：它既挡住刚打开的对比结果，又是上面那种假阳性的来源。
    const dialogStillOpen = await page.evaluate(() => !!document.querySelector('.detail-meta'))
    if (dialogStillOpen) throw new Error('点击「和上一版对比」后节点详情弹窗仍开着')
  })

  // ============ J9 追加 3/3：单文件历史 ============
  // project-overview.vue 的 onFileHistory 会自动把左栏切到「版本」面板并设置
  // fileFilter，VersionPanel.vue 据此渲染过滤条「只看《{name}》的历史」
  // （VersionPanel.vue :25-28）。
  await step('文件树右键测试文件打开历史', async () => {
    await mouseClickSel('[title="资源管理器"]')
    await waitText('qa-版本测试.txt')
    await mouseRightClickText('qa-版本测试.txt')
    await mouseClickText('这份文件的历史')
    await waitText('只看《qa-版本测试.txt》的历史')
  })

  await step('单文件历史保留律师命名的工作段节点', async () => {
    // 守终审 I3：JGit 的 addPath（git 默认历史简化）会把「相对第一父提交 TREESAME」
    // 的合并提交整条剪掉，而结束工作用的正是 NO_FF 合并——过滤视图里会只剩自动
    // 存档，律师命名的版本全部消失。这里认 timeline 节点标题本身，不用 waitText
    // （页面别处也可能出现同名文本）。「端到端测试稿四」这一段就是覆盖上传
    // qa-版本测试.txt 产生 MODIFY 的那段工作，必须在过滤结果里。
    await page.waitForFunction(() => {
      const titles = [...document.querySelectorAll('.timeline-node .node-title')]
        .map((e) => e.innerText || '')
      return titles.some((t) => t.includes('端到端测试稿四'))
    }, { timeout: 15000 })
  })

  await step('显示全部恢复时间线过滤', async () => {
    await mouseClickText('显示全部')
    await page.waitForFunction(
      () => !document.body.innerText.includes('只看《qa-版本测试.txt》的历史'),
      { timeout: 10000 },
    )
  })

  // ============ J10 另起一稿 / 双向切线 / 采纳（冲突三选一）/ 放弃 ============
  // 第 3 期新增：从任意版本另起一稿并命名、主线与稿之间双向切换（两线内容与文件树
  // 完全隔离）、采纳一稿（冲突时逐文件三选一）、放弃一稿。计划 Task 8 节七步旅程。
  // 断言认三个新组件各自独有的选择器（WorkSessionBar 的 .draft-dot 稿态标记 /
  // DraftList 的 .draft-list / AdoptConflictDialog 的 .adopt-dialog、.adopt-row-name），
  // 不用 body innerText 包含——第 2 期终审 C1（对比标签假阳性）的直接教训。
  //
  // 与 brief 转述有一处出入，读过 WorkSessionBar.vue/DraftList.vue 实际模板后按代码
  // 改正：brief 第 5 步字面写了两次"回主线"，第二次夹在"稿上也改文件 A"与"采纳这
  // 一稿"之间——但"采纳这一稿"按钮只存在于 WorkSessionBar 的稿态模板里
  // （v-if="onDraft"，DraftList 没有采纳入口），必须仍站在稿上才点得到它；
  // WorkSessionService.adoptDraft 自己"锁内先无条件走一次 switchToMainline"（方法
  // 注释原话："律师按下「采纳这一稿」时通常正站在稿上，必须先停靠稿、回到主线侧
  // 才能合并"）。第二次"回主线"描述的是这个后端内部的停靠动作，不是一次手工点
  // 击——测试按实际 UI 走，直接在稿态下点「采纳这一稿」。
  console.log('== J10 另起一稿与采纳 ==')

  const j10Base = path.join(OUT, 'qa-J10冲突文件.txt')
  const j10DraftOnly = path.join(OUT, 'qa-J10稿专属文件.txt')
  fs.writeFileSync(j10Base, 'QA J10 垫底文件内容\n')
  fs.writeFileSync(j10DraftOnly, 'QA J10 稿专属文件（只应在稿上看到）\n')

  // 裸 REST 覆盖同一 wpsFileId 的字节——与 J9 造 MODIFY 同一手段，这里用来在两条线
  // 上分别改同一个文件、制造一次真实的三方合并冲突（同一段文本两边改成不同内容）。
  const restOverwrite = async (fileName, content) => {
    const list = await api('/api/projects/' + QA.projectId + '/files')
    const f = (Array.isArray(list) ? list : []).find((x) => x.name === fileName)
    if (!f || !f.wpsFileId) throw new Error('找不到 ' + fileName + ' 或其 wpsFileId: ' + JSON.stringify(list).slice(0, 200))
    const form = new FormData()
    form.append('file', new Blob([content], { type: 'text/plain' }), fileName)
    const r = await fetch(BACKEND + '/api/files/' + f.wpsFileId + '/upload', {
      method: 'POST',
      headers: QA.sid ? { 'X-Session-Id': QA.sid } : {},
      body: form,
    })
    const j = await r.json()
    if (!j || j.code !== 0) throw new Error('REST 直传失败: ' + JSON.stringify(j))
    return f.wpsFileId
  }

  // ---- 1. 开启版本记录（J9 已开）→ 一段命名工作垫底，给后面的另起一稿一个基点 ----
  await step('J10 垫底：上传冲突测试文件并命名结束', () =>
    runWorkSession(j10Base, 'qa-J10冲突文件', 'J10 垫底工作'))

  // ---- 2. 节点详情「从这一版另起一稿」→ 命名「试验稿」→ 断言状态条进入稿态 ----
  await step('打开垫底节点详情', async () => {
    await mouseClickText('J10 垫底工作')
    await waitText('从这一版另起一稿')
  })

  await step('从这一版另起一稿并命名「试验稿」', async () => {
    await mouseClickText('从这一版另起一稿')
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type('试验稿', { delay: 15 })
    await sleep(300) // 同款 v-model 去抖定居延迟，见前面命名弹窗的注释
    await mouseClickText('开始')
  })

  await step('状态条进入稿态（认 WorkSessionBar 的 draft-dot，不用 body innerText 包含）', async () => {
    await page.waitForFunction(() => {
      const dot = document.querySelector('.session-bar .draft-dot')
      const text = document.querySelector('.session-bar .session-text')
      return !!dot && !!text && text.innerText.includes('试验稿')
    }, { timeout: 10000 })
  })

  // ---- 3. 稿上上传一个新文件 → 「回到主线工作」→ 断言稿态消失、该文件从文件树消失
  //         （稿的改动不漏到主线） ----
  await step('稿上上传专属文件', async () => {
    await mouseClickSel('[title="资源管理器"]')
    await uploadOne(j10DraftOnly, 'qa-J10稿专属文件')
    await waitText('qa-J10稿专属文件', 20000)
  })

  await step('回到主线工作后稿态消失', async () => {
    await mouseClickSel('[title="版本"]')
    await mouseClickText('回到主线工作')
    await page.waitForFunction(
      () => !!document.querySelector('.session-bar .session-idle'),
      { timeout: 10000 },
    )
  })

  await step('主线上看不到稿专属文件', async () => {
    await mouseClickSel('[title="资源管理器"]')
    await page.waitForFunction(() => {
      const t = document.querySelector('.file-tree')
      return !!t && !t.innerText.includes('qa-J10稿专属文件')
    }, { timeout: 10000 })
  })

  // ---- 4. 「切到这一稿」→ 断言文件回来（两线内容隔离的正反双证） ----
  await step('切到这一稿', async () => {
    await mouseClickSel('[title="版本"]')
    await waitText('进行中的稿')
    await mouseClickText('切到这一稿')
    await page.waitForFunction(
      () => !!document.querySelector('.session-bar .draft-dot'),
      { timeout: 10000 },
    )
  })

  await step('稿专属文件确实回来了', async () => {
    await mouseClickSel('[title="资源管理器"]')
    await page.waitForFunction(() => {
      const t = document.querySelector('.file-tree')
      return !!t && t.innerText.includes('qa-J10稿专属文件')
    }, { timeout: 15000 })
  })

  // ---- 5. 回主线 → 主线上裸 REST 改文件 → 稿上也裸 REST 改同一文件 → 「采纳这一稿」
  //         → 断言三选一弹窗出现且列出该文件（认 AdoptConflictDialog 选择器） ----
  await step('回到主线工作', async () => {
    await mouseClickSel('[title="版本"]')
    await mouseClickText('回到主线工作')
    await page.waitForFunction(
      () => !!document.querySelector('.session-bar .session-idle'),
      { timeout: 10000 },
    )
  })

  await step('主线上裸 REST 改冲突测试文件', () =>
    restOverwrite('qa-J10冲突文件.txt', 'QA J10 主线修改内容（用于制造冲突）\n'))

  await step('结束主线上这段隐式打开的工作（采纳前置：不能带着 ACTIVE 工作段采纳）', async () => {
    // REST 直传会隐式在主线上开一段工作段（onChangeSignal 对非稿分支的口径），
    // adoptDraft 要求"没有进行中的工作"，必须先收尾，理由见领域文档核心契约。
    await mouseClickSel('[title="资源管理器"]')
    await mouseClickSel('[title="版本"]')
    await waitText('工作中')
    await mouseClickText('结束本次工作')
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type('J10 主线追加修改', { delay: 15 })
    await sleep(300)
    await mouseClickText('完成')
    await page.waitForFunction(
      () => !!document.querySelector('.session-bar .session-idle'),
      { timeout: 10000 },
    )
  })

  await step('切回稿上，裸 REST 改同一文件（制造真实冲突）', async () => {
    await mouseClickText('切到这一稿')
    await page.waitForFunction(() => !!document.querySelector('.session-bar .draft-dot'), { timeout: 10000 })
    await restOverwrite('qa-J10冲突文件.txt', 'QA J10 稿修改内容（用于制造冲突）\n')
  })

  await step('点击「采纳这一稿」，三选一弹窗出现且列出冲突文件', async () => {
    // 直接在稿态下点，不手工切回主线——adoptDraft 内部自己会先停靠（见本节头部注释）。
    await mouseClickText('采纳这一稿')
    await page.waitForFunction(() => {
      const dlg = document.querySelector('.adopt-dialog')
      if (!dlg || dlg.getClientRects().length === 0) return false
      const row = dlg.querySelector('.adopt-row-name')
      return !!row && row.innerText.includes('qa-J10冲突文件.txt')
    }, { timeout: 15000 })
  })

  // ---- 5.5 裁决窗口期切去别的面板：三选一弹窗随版本面板卸载消失，页面级固定条必须
  //          接住（P3 终审 C1 第 3 层）。这期间后端停在待裁决、版本捕获整体关闭，没有
  //          这条提示律师会以为采纳早就结束了。认 .adopt-pending-bar 真渲染 + 几何可见，
  //          不用 body innerText 包含（第 2 期终审 C1 的教训）。 ----
  //          走的是真实路径：弹窗展开时 .awd-mask 是全屏遮罩，点侧栏图标只会落在遮罩上
  //          （实测过），律师要先点「对比」把弹窗收起（AdoptConflictDialog 自己的收起条），
  //          这时才切得走面板——而那条收起条也随面板一起消失，正是本固定条要接住的空档。
  await step('切去资源管理器后仍有采纳待处理提示条，点「去处理」能回到裁决现场', async () => {
    await mouseClickSel('.adopt-row-compare')
    await page.waitForFunction(
      () => !!document.querySelector('.adopt-collapsed-bar'), { timeout: 10000 })
    await mouseClickSel('[title="资源管理器"]')
    await page.waitForFunction(() => {
      const bar = document.querySelector('.adopt-pending-bar')
      return !!bar && bar.getClientRects().length > 0
    }, { timeout: 10000 })
    await mouseClickText('去处理')
    await page.waitForFunction(() => {
      const dlg = document.querySelector('.adopt-dialog')
      return !!dlg && dlg.getClientRects().length > 0
    }, { timeout: 10000 })
  })

  // ---- 6. 选「两份都留着」→ 确认 → 断言文件树同时出现《A》与《A（来自：试验稿）》、
  //         时间线出现「采纳：试验稿」节点、稿列表清空 ----
  await step('选「两份都留着」并确认采纳', async () => {
    await mouseClickText('两份都留着')
    await mouseClickText('就按我选的来')
    await page.waitForFunction(
      () => !document.querySelector('.adopt-dialog') && !document.querySelector('.adopt-collapsed-bar'),
      { timeout: 15000 },
    )
  })

  await step('文件树同时出现两份文件', async () => {
    await mouseClickSel('[title="资源管理器"]')
    await page.waitForFunction(() => {
      const t = document.querySelector('.file-tree')
      return !!t
        && t.innerText.includes('qa-J10冲突文件.txt')
        && t.innerText.includes('qa-J10冲突文件（来自：试验稿）.txt')
    }, { timeout: 15000 })
  })

  await step('时间线出现「采纳：试验稿」节点', async () => {
    await mouseClickSel('[title="版本"]')
    await page.waitForFunction(() => {
      const titles = [...document.querySelectorAll('.timeline-node .node-title')].map((e) => e.innerText || '')
      return titles.some((t) => t.includes('采纳：试验稿'))
    }, { timeout: 15000 })
  })

  await step('稿列表清空', async () => {
    await page.waitForFunction(() => !document.querySelector('.draft-list'), { timeout: 10000 })
  })

  // ---- 7. 再开一稿 → 「放弃这一稿」→ 确认 → 断言回主线、稿列表空、时间线无采纳节点 ----
  // 稿列表刚清空（DraftList 整体 v-if="drafts.length"），「另起一稿」按钮此刻不在
  // DOM 里，只能仍走节点详情的「从这一版另起一稿」入口——从刚采纳完的这个节点开。
  await step('从「采纳：试验稿」节点再开一稿', async () => {
    await mouseClickText('采纳：试验稿')
    await waitText('从这一版另起一稿')
    await mouseClickText('从这一版另起一稿')
    await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
    const input = await page.$('.awd-dialog .uni-input-input')
    await input.click(); await input.type('作废稿', { delay: 15 })
    await sleep(300)
    await mouseClickText('开始')
    await page.waitForFunction(() => {
      const dot = document.querySelector('.session-bar .draft-dot')
      const text = document.querySelector('.session-bar .session-text')
      return !!dot && !!text && text.innerText.includes('作废稿')
    }, { timeout: 10000 })
  })

  await step('放弃这一稿并确认', async () => {
    await mouseClickText('放弃这一稿')
    // confirmAbandon 走 uni.showModal，浏览器目标下确认按钮默认英文文案，同款双试。
    try { await mouseClickText('确定') } catch { await mouseClickText('OK') }
    await page.waitForFunction(
      () => !!document.querySelector('.session-bar .session-idle'),
      { timeout: 10000 },
    )
  })

  await step('稿列表空、时间线没有这一稿的采纳节点', async () => {
    const noDraftList = await page.evaluate(() => !document.querySelector('.draft-list'))
    if (!noDraftList) throw new Error('放弃后稿列表仍非空')
    const hasBogusAdopt = await page.evaluate(() =>
      [...document.querySelectorAll('.timeline-node .node-title')]
        .some((e) => (e.innerText || '').includes('作废稿')))
    if (hasBogusAdopt) throw new Error('放弃后时间线出现了不该有的采纳节点')
  })

  // ============ J11 云端协作：共享/接入/双向同步/冲突三选一 ============
  // 拓扑：A = 既有 9696 桌面后端（本文件全程 UI 驱动的那一台），S = 团队服务器
  // （spawnBackend 9701），B = 同事桌面（spawnBackend 9702）。前端只有一个（连 A）——
  // B 的所有动作走裸 REST，这是拓扑决定的（没有第二个浏览器/Electron 实例），不是偷懒。
  // 冲突判定链（v2 终审 I2 后语义）：结束工作 → 后台自动上传被拒 → 后台不自动整合，
  // 只置待上传（remoteAhead 灯亮）→ 律师在 CloudSyncBar 点「立即上传」→ 前台自动整合
  // 遇到真实内容冲突 → CONFLICT，这是 CloudSyncService.uploadToCloud 的 mode='cloud'
  // 语境（标签「用我这边的/用云端的」），不是 endSession 自身的 sessionEndConflict 语境
  // （那需要本机 git 收到过一次 receive-pack，这里 A/S/B 三个后端物理隔离，走不到那条路）。
  if (!J11_JAR) {
    note('skip', 'J11 需要 APP_E2E_JAR（backend/target/*.jar 绝对路径）未提供，已跳过多人协作旅程')
  } else {
    console.log('== J11 云端协作 ==')
    // S 是团队服务器：要真实多账号鉴权（lawyer_a/lawyer_b 注册登录、成员管理）。
    // desktop profile 在 PR-A 后默认 security.local-mode=true（一切请求解析为
    // 本机用户），对 S 必须显式关掉，否则两个律师会被折叠成同一个人。
    // B 是同事的桌面：保持 local-mode 免登（与真实拓扑一致），裸 REST 即本机用户。
    const S = await spawnBackend('server', 9701, ['--security.local-mode=false'])
    const B = await spawnBackend('desktopB', 9702)
    const sApi = mkApi(S)
    const bApi = mkApi(B)

    const restOverwriteAt = async (apiFn, projectId, fileName, content) => {
      const list = await apiFn('/api/projects/' + projectId + '/files')
      const f = (Array.isArray(list) ? list : []).find((x) => x.name === fileName)
      if (!f) throw new Error('找不到 ' + fileName + ': ' + JSON.stringify(list).slice(0, 200))
      // 认 f.id（数据库主键，永远非空），不认 f.wpsFileId——跟 J9/J10 既有的
      // restOverwrite 不同，这里的文件是清单同步（git clone）落库的，v2 清单只带
      // uid/relPath，不携带 wpsFileId，走清单同步新建的行 wpsFileId 天然是 null。
      // 现场调试实证：FileController.uploadFile 原来只认 wpsFileId，缺了数字 id 兜底，
      // 撞上这类文件会把字节写进跟真文件不相干的孤儿路径且不触发版本信号——已经在
      // FileController.resolveProjectFileForUpload 里补了跟 downloadFile 同款的双查
      // 顺序（先按数据库 id 查，查不到再退回 wpsFileId），这里直接用 f.id 是对齐
      // LibreOfficeEditor.vue 保存时 `f.wpsFileId || f.id` 的同一条兜底路径。
      const form = new FormData()
      form.append('file', new Blob([content], { type: 'text/plain' }), fileName)
      const r = await fetch(apiFn.base + '/api/files/' + f.id + '/upload', {
        method: 'POST',
        headers: apiFn.sid ? { 'X-Session-Id': apiFn.sid } : {},
        body: form,
      })
      const j = await r.json()
      if (!j || j.code !== 0) throw new Error('REST 直传失败: ' + JSON.stringify(j))
    }
    const endSessionAt = async (apiFn, projectId, title) => {
      const r = await apiFn('/api/projects/' + projectId + '/version/session/end', { method: 'POST', body: { title } })
      if (!r || r.code !== 0) throw new Error('结束工作失败: ' + JSON.stringify(r).slice(0, 200))
      return r.data
    }
    const pollUntil = async (fn, timeoutMs, intervalMs = 1000) => {
      const start = Date.now()
      for (;;) {
        if (await fn()) return true
        if (Date.now() - start >= timeoutMs) return false
        await sleep(intervalMs)
      }
    }

    const j11Base = path.join(OUT, 'qa-J11协作文件.txt')
    fs.writeFileSync(j11Base, 'QA J11 云端协作基线文件\n')

    let remoteProjectId = null
    await step('J11-服务器注册两个账号', async () => {
      const regA = await sApi('/api/auth/register', { method: 'POST', body: { username: 'lawyer_a', password: 'PwLawyerA123', displayName: '律师甲' } })
      if (!regA || regA.code !== 0) throw new Error('注册 lawyer_a 失败: ' + JSON.stringify(regA).slice(0, 200))
      sApi.sid = regA.data.sessionId // 全程以 lawyer_a 身份留在 S 上（加成员/服务器侧断言都用它）
      const regB = await sApi('/api/auth/register', { method: 'POST', body: { username: 'lawyer_b', password: 'PwLawyerB123', displayName: '律师乙' } })
      if (!regB || regB.code !== 0) throw new Error('注册 lawyer_b 失败: ' + JSON.stringify(regB).slice(0, 200))
    })

    await step('A：上传协作文件并结束一段命名工作（云端协作基线）', () =>
      runWorkSession(j11Base, 'qa-J11协作文件', 'J11 垫底工作'))

    await step('A：设置页连接团队服务器 S（admin cloud 分区表单）', async () => {
      // admin.vue 的「云端协作」nav item 是 desktopOnly（isDesktop 计算属性认
      // window.checkbaDesktop.model 是否存在），浏览器目标不是 Electron，天然拿不到。
      // 注入一个最小桩，只满足 mounted() 里 loadComponents()/onProgress 两处调用不抛异常
      // （读过 admin.vue :711-743 确认过这两处是唯一用到 window.checkbaDesktop.model 的地方），
      // 不影响其余断言——J11 是全套旅程最后一段，这个桩留到会话结束也无副作用。
      // 两个坑都是现场调试实证抓到的，缺一步都进不去：
      // ① evaluateOnNewDocument 只在「真的产生新 document」时才重放，project-overview
      //   跳到 admin 走的是 uni-app 的 hash 路由，同一份 document 没重新加载，
      //   单独注册不会生效；page.evaluate 直接对当前已加载的 document 写 window
      //   属性能带过去，两处都注入才稳。
      // ② uni-app 的 H5 页面栈是常驻的——J7 更早已经只读访问过一次 admin 页（那次还
      //   没有这个桩），isDesktop 是没有响应式依赖的计算属性，那次挂载算出 false 后
      //   就一直缓存，之后再怎么切 hash 回来都不会重算（单独跑这一步能过，接在 J7
      //   后面跑就再也不出现「云端协作」）。唯一可靠办法是这里强制来一次真实整页
      //   reload，把 uni-app 页面栈里那个旧 admin.vue 实例连着一起清空重来——reload
      //   时 evaluateOnNewDocument 注册的桩会在新文档最早的脚本执行前就位，这次挂载
      //   从第一次求值起就是 true。
      const stubDesktop = () => {
        // 合并进全局最小桩（shell.openExternal），不要整体覆盖——userprofile 等
        // 页面靠 window.checkbaDesktop 存在性判定免登语境，J1 起全程依赖它。
        window.checkbaDesktop = Object.assign({}, window.checkbaDesktop, {
          model: {
            status: async () => ({ components: [] }),
            onProgress: () => () => {},
          },
        })
      }
      await page.evaluateOnNewDocument(stubDesktop)
      await page.evaluate(stubDesktop)
      await page.goto(BASE + '/#/pages/admin/admin', { waitUntil: 'networkidle2', timeout: 20000 })
      await page.reload({ waitUntil: 'networkidle2', timeout: 20000 })
      await waitText('团队案件库')
      await mouseClickText('团队案件库')
      await waitText('连接团队案件库')
      await page.waitForSelector('.form-input .uni-input-input', { timeout: 8000 })
      const inputs = await page.$$('.form-input .uni-input-input')
      if (inputs.length < 3) throw new Error('团队案件库表单输入框数量不对: ' + inputs.length)
      await inputs[0].click({ clickCount: 3 }); await inputs[0].type(S, { delay: 10 })
      await inputs[1].click({ clickCount: 3 }); await inputs[1].type('lawyer_a', { delay: 10 })
      await inputs[2].click({ clickCount: 3 }); await inputs[2].type('PwLawyerA123', { delay: 10 })
      await sleep(300) // 同款 v-model 去抖定居延迟，见本文件其余命名弹窗的注释
      await mouseClickText('连接')
      await page.waitForSelector('.cloud-conn-header', { timeout: 15000 })
      // 记下这次连接的 id，finally 里断开——A 的桌面后端是长驻真实数据（不像 S/B 是
      // 跑完就扔的临时进程），CloudConnection 不清理会跨多次 e2e 运行累积。这不只是
      // 测试卫生问题：PR-E 之前 CloudSyncBar.onShare() 直接拿 listCloudConnections()
      // 的 list[0]，累积的旧连接（服务器早已不在、设备令牌早已失效）一旦排在最前面，
      // 「放进团队案件库」就会拿着死令牌去连一个死后端，POST /api/projects 应答里没有
      // "id"，服务端侧 shareToCloud 对着空结果取 .getLong("id") 直接 NPE——现场调试
      // 真踩过这个坑（连续跑几轮不清理，第二轮起必现），不是假设性风险。现在协作抽屉
      // 让律师指名选哪一个案件库（多于一个时才渲染选择器），这条路径已经堵上。
      const connList = await api('/api/cloud/connections')
      const conns = (connList && connList.data && connList.data.connections) || []
      aConnectionId = conns.length ? conns[conns.length - 1].id : null
    })

    await page.goto(BASE + '/#/pages/project-overview/project-overview?id=' + QA.projectId,
      { waitUntil: 'networkidle2', timeout: 30000 })
    await sleep(1500)

    await step('A：把案卷放进团队案件库并取得云端项目 id', async () => {
      // 先切一次「资源管理器」再切「版本」，不要在整页 goto 落地后只点一次「版本」——
      // 现场调试实证：goto 回 project-overview 后单点一次「版本」栏目，面板经常整个
      // 不出现（.version-panel 15s 内都不挂载，非文案没等到，是组件压根没渲染），
      // 换成先点别的栏目、再切回「版本」就稳定能挂载。跟本文件其余「裸 REST 改动后
      // 靠切出/切回侧栏挂载点强制重新挂载」是同一条既有纪律（J9/J10 到处这么用），
      // 这里只是补上「整页 goto 之后第一次开面板」这一种也需要它的场景。
      await mouseClickSel('[title="资源管理器"]')
      await mouseClickSel('[title="版本"]')
      // CloudSyncBar 的内容要等 VersionPanel.refresh()→fetchCloudState() 两次串行网络
      // 请求都落地才出现；这个项目此时已经带着 J9/J10 攒下的一整段真实历史，读取比
      // 早期空项目慢，mouseClickSel 自带的 700ms 不够稳（现场调试实证：直接点文案
      // 偶发「找不到文本」，面板其实还没渲染完）。用 .cloud-bar 选择器等面板真挂载
      // （两态都会渲染这个容器）比等具体文案更稳。
      //
      // PR-E 起，交稿/取回/放进案件库这三个动作都收到页面级协作抽屉（.collab-dialog）里，
      // 版本面板的 .cloud-bar 只剩一行只读状态 + 一个开抽屉的链接。
      await page.waitForSelector('.cloud-bar', { timeout: 15000 })
      await mouseClickText('放进案件库')
      await page.waitForSelector('.collab-dialog', { timeout: 10000 })
      await mouseClickText('放进团队案件库')
      // 放进去之后顶栏协作 chip 才会出现（只在案卷真的进了案件库时渲染，零打扰）
      await page.waitForSelector('.collab-chip', { timeout: 20000 })
      await closeCollabDialog()
      await page.waitForSelector('.cloud-dot', { timeout: 20000 })
      const cs = await api('/api/cloud/projects/' + QA.projectId + '/status')
      if (!cs || !cs.data || !cs.data.linked || !cs.data.remoteProjectId) {
        throw new Error('共享后本地云端状态不对: ' + JSON.stringify(cs).slice(0, 200))
      }
      remoteProjectId = cs.data.remoteProjectId
    })

    await step('服务器侧：项目已出现、版本记录已开启、文件已同步', async () => {
      const list = await sApi('/api/projects/my')
      const proj = (Array.isArray(list) ? list : []).find((p) => String(p.id) === String(remoteProjectId))
      if (!proj) throw new Error('S 上未见到共享的项目: ' + JSON.stringify(list).slice(0, 200))
      if (proj.name !== QA.project) throw new Error('S 上项目名不对: ' + proj.name)
      const st = await sApi('/api/projects/' + remoteProjectId + '/version/status')
      if (!st || !st.data || !st.data.enabled) throw new Error('S 上版本记录未开启: ' + JSON.stringify(st).slice(0, 200))
      const files = await sApi('/api/projects/' + remoteProjectId + '/files')
      const hasBase = (Array.isArray(files) ? files : []).some((f) => f.name === 'qa-J11协作文件.txt')
      if (!hasBase) throw new Error('S 上未见到基线文件: ' + JSON.stringify(files).slice(0, 200))
    })

    await step('服务器：lawyer_a 把 lawyer_b 加为项目参与者', async () => {
      const r = await sApi('/api/projects/' + remoteProjectId + '/members', {
        method: 'POST', body: { username: 'lawyer_b', role: 'PARTICIPANT' },
      })
      if (!r || r.code !== 0) throw new Error('加成员失败: ' + JSON.stringify(r).slice(0, 200))
    })

    // B 的桌面后端是 local-mode 免登：不再注册本地账号（登录已不存在），
    // bApi.sid 保持 null，所有裸 REST 天然是 B 的本机用户。
    let bConnectionId = null
    await step('B：连接团队服务器 S（裸 REST，lawyer_b 账号）', async () => {
      const r = await bApi('/api/cloud/connect', { method: 'POST', body: { serverUrl: S, username: 'lawyer_b', password: 'PwLawyerB123', deviceName: '同事的电脑' } })
      if (!r || r.code !== 0) throw new Error('B 连接云端失败: ' + JSON.stringify(r).slice(0, 200))
      bConnectionId = r.data.connectionId
    })

    let bProjectId = null
    await step('B：列出并接入云端项目', async () => {
      const remotes = await bApi('/api/cloud/connections/' + bConnectionId + '/remote-projects')
      const list = (remotes && remotes.data && remotes.data.projects) || []
      const proj = list.find((p) => p.name === QA.project)
      if (!proj) throw new Error('B 在远端项目列表里没看到共享的项目: ' + JSON.stringify(list).slice(0, 200))
      const acc = await bApi('/api/cloud/accept', { method: 'POST', body: { connectionId: bConnectionId, remoteProjectId: proj.id } })
      if (!acc || acc.code !== 0) throw new Error('B 接入失败: ' + JSON.stringify(acc).slice(0, 200))
      bProjectId = acc.data.localProjectId
    })

    await step('B：本地项目出现且文件内容与 A 一致', async () => {
      const mine = await bApi('/api/projects/my')
      const hasProj = (Array.isArray(mine) ? mine : []).some((p) => String(p.id) === String(bProjectId))
      if (!hasProj) throw new Error('B 本地项目列表里没有接入的项目')
      const filesB = await bApi('/api/projects/' + bProjectId + '/files')
      const fB = (Array.isArray(filesB) ? filesB : []).find((x) => x.name === 'qa-J11协作文件.txt')
      if (!fB) throw new Error('B 本地没有基线文件: ' + JSON.stringify(filesB).slice(0, 200))
      const filesA = await api('/api/projects/' + QA.projectId + '/files')
      const fA = (Array.isArray(filesA) ? filesA : []).find((x) => x.name === 'qa-J11协作文件.txt')
      if (!fA) throw new Error('A 本地反而没有基线文件了，不应该发生')
      // 比字节（/download 原始流），不用 /text（Tika 抽取纯文本）判断内容是否同步
      // 对了——现场调试实证：uploadOne 走的真实浏览器文件选择上传路径会把 .txt
      // 转存成 .docx（跟 J11、跟云端同步都无关的既有行为），Tika 抽刚转换出的这类
      // 文档偶发只剩一个换行；A 自己刚上传完、云端还没介入时 /text 就已经是这样，
      // 拿它判断"克隆内容对不对"会被这个无关噪音坑（曾经因此误判成 B 没收到文件）。
      // /download 是原始字节流，不经过 Tika，也是对"clone 到底带没带对内容"更直接
      // 的证据——两台机器上同一个文件字节完全一致，才真正说明 git clone 没出错。
      const dlA = await fetch(BACKEND + '/api/files/' + fA.id + '/download', { headers: QA.sid ? { 'X-Session-Id': QA.sid } : {} })
      const dlB = await fetch(B + '/api/files/' + fB.id + '/download', { headers: bApi.sid ? { 'X-Session-Id': bApi.sid } : {} })
      if (dlA.status !== 200 || dlB.status !== 200) throw new Error('下载失败: A=' + dlA.status + ' B=' + dlB.status)
      const bufA = Buffer.from(await dlA.arrayBuffer())
      const bufB = Buffer.from(await dlB.arrayBuffer())
      if (!bufA.equals(bufB)) {
        throw new Error('B 本地文件字节与 A 不一致: A=' + bufA.length + '字节, B=' + bufB.length + '字节')
      }
    })

    await step('B：修改协作文件并结束工作（触发后台自动上传）', async () => {
      await restOverwriteAt(bApi, bProjectId, 'qa-J11协作文件.txt', 'QA J11 来自 B 的第一次修改\n')
      await endSessionAt(bApi, bProjectId, 'B 第一次修改')
    })

    await step('轮询 S 的主线前进：出现 B 第一次修改的节点', async () => {
      const ok = await pollUntil(async () => {
        const tl = await sApi('/api/projects/' + remoteProjectId + '/version/timeline?limit=30')
        const versions = (tl && tl.data && tl.data.versions) || []
        return versions.some((v) => (v.note || v.message || '').includes('B 第一次修改'))
      }, 40000, 1500)
      if (!ok) throw new Error('等待超时：S 的时间线始终没有出现 B 第一次修改的节点')
    })

    await step('A：点「取回最新稿」', async () => {
      await mouseClickSel('[title="资源管理器"]')
      await mouseClickSel('[title="版本"]')
      // 同上一步的教训：等 .cloud-dot 出现（已放进案件库才有的选择器）比等 700ms 定式稳，
      // 面板这时候要重新拉 /status+/drafts+/cloud/status 三串请求才会渲染出这行状态。
      await page.waitForSelector('.cloud-dot', { timeout: 15000 })
      await mouseClickText('打开协作')
      await page.waitForSelector('.collab-dialog', { timeout: 10000 })
      await mouseClickText('取回最新稿')
      await sleep(2000)
      await closeCollabDialog()
    })

    await step('A：本地文件内容已同步 B 的修改', async () => {
      const ok = await pollUntil(async () => {
        const files = await api('/api/projects/' + QA.projectId + '/files')
        const f = (Array.isArray(files) ? files : []).find((x) => x.name === 'qa-J11协作文件.txt')
        if (!f) return false
        // /text 只认数字 id（见「B：本地项目出现且文件内容与 A 一致」步骤同款注释），
        // 这里即使 A 自己的文件本来就有 wpsFileId，也一律用 f.id。
        const text = await api('/api/files/' + f.id + '/text')
        return !!(text && text.data && text.data.includes('来自 B 的第一次修改'))
      }, 15000, 1000)
      if (!ok) throw new Error('从云端更新后 A 本地文件内容仍不是 B 的版本')
    })

    await step('A：版本时间线出现 B 的工作段节点', async () => {
      await mouseClickSel('[title="资源管理器"]')
      await mouseClickSel('[title="版本"]')
      await page.waitForFunction(() => {
        const titles = [...document.querySelectorAll('.timeline-node .node-title')].map((e) => e.innerText || '')
        return titles.some((t) => t.includes('B 第一次修改'))
      }, { timeout: 15000 })
    })

    await step('B：再次修改同一文件并结束工作（推上去，为制造冲突垫底）', async () => {
      await restOverwriteAt(bApi, bProjectId, 'qa-J11协作文件.txt', 'QA J11 来自 B 的第二次修改（制造冲突用）\n')
      await endSessionAt(bApi, bProjectId, 'B 第二次修改（制造冲突用）')
    })

    await step('轮询 S 的主线再次前进：出现 B 第二次修改的节点', async () => {
      const ok = await pollUntil(async () => {
        const tl = await sApi('/api/projects/' + remoteProjectId + '/version/timeline?limit=30')
        const versions = (tl && tl.data && tl.data.versions) || []
        return versions.some((v) => (v.note || v.message || '').includes('B 第二次修改'))
      }, 40000, 1500)
      if (!ok) throw new Error('等待超时：S 的时间线始终没有出现 B 第二次修改的节点')
    })

    await step('A：本地也修改同一文件（裸 REST 覆盖，制造真实冲突）', () =>
      restOverwrite('qa-J11协作文件.txt', 'QA J11 来自 A 的本地修改（甲的撞车工作）\n'))

    await step('A：结束这段隐式打开的工作（标题「甲的撞车工作」）', async () => {
      await mouseClickSel('[title="资源管理器"]')
      await mouseClickSel('[title="版本"]')
      await waitText('工作中')
      await mouseClickText('结束本次工作')
      await page.waitForSelector('.awd-dialog .uni-input-input', { timeout: 10000 })
      const input = await page.$('.awd-dialog .uni-input-input')
      await input.click(); await input.type('甲的撞车工作', { delay: 15 })
      await sleep(300)
      await mouseClickText('完成')
      await waitText('当前没有进行中的工作')
    })

    await step('A：结束工作后台上传被拒只置待交稿标记（不自动整合）', async () => {
      // v2 终审 I2：后台路径（结束工作的自动上传）被拒时不做自动整合——后台没有通道
      // 通知打开中的编辑器重载，后台整合撞冲突还会开出律师不知情的 MERGING 窗口。
      // 直接查 pendingUpload 比查界面文案更贴近 I2 本身：此刻 remoteAhead 也为真
      // （B 推了两次），而 PR-E 的状态口径把「同事交了新稿」排在「有改动还没交稿」
      // 前面（remoteAhead 是阻塞条件——不先取回根本交不了稿，先说该做的那件事），
      // 所以界面上显示的是前者，pendingUpload 这条真实信号只能从接口读。
      const ok = await pollUntil(async () => {
        const st = await api('/api/cloud/projects/' + QA.projectId + '/status')
        return !!(st && st.data && st.data.pendingUpload)
      }, 40000, 2000)
      if (!ok) throw new Error('等待超时：结束工作后 pendingUpload 没有被置上')
      await mouseClickSel('[title="资源管理器"]')
      await mouseClickSel('[title="版本"]')
      const shown = await page.evaluate(() => document.body.innerText.includes('同事交了新稿'))
      if (!shown) throw new Error('协作状态没有提示「同事交了新稿」')
      const dialogOpen = await page.evaluate(() => {
        const dlg = document.querySelector('.adopt-dialog')
        return !!dlg && dlg.getClientRects().length > 0
      })
      if (dialogOpen) throw new Error('后台上传不该自动整合出冲突弹窗（I2 新语义被破坏）')
    })

    await step('A：点「交稿」前台整合撞上冲突弹窗且标签正确', async () => {
      await mouseClickText('打开协作')
      await page.waitForSelector('.collab-dialog', { timeout: 10000 })
      await mouseClickText('交稿')
      // 撞冲突时协作抽屉自己关掉、页面把人送到裁决现场（版本面板），不用再手工关
      await page.waitForFunction(() => {
        const dlg = document.querySelector('.adopt-dialog')
        if (!dlg || dlg.getClientRects().length === 0) return false
        const row = dlg.querySelector('.adopt-row-name')
        return !!row && row.innerText.includes('qa-J11协作文件.txt')
      }, { timeout: 30000 })
      const labels = await page.evaluate(() =>
        [...document.querySelectorAll('.adopt-dialog .radio-label')].map((e) => e.innerText))
      if (!labels.includes('留我这份') || !labels.includes('用同事那份')) {
        throw new Error('冲突弹窗标签不对，可能弹的是另一种语境: ' + JSON.stringify(labels))
      }
    })

    await step('先点「看看两边差在哪」验证收起条出现', async () => {
      await mouseClickSel('.adopt-row-compare')
      await page.waitForFunction(() => !!document.querySelector('.adopt-collapsed-bar'), { timeout: 10000 })
    })

    await step('切去资源管理器后仍有待处理提示条，点「去处理」能回到裁决现场', async () => {
      await mouseClickSel('[title="资源管理器"]')
      await page.waitForFunction(() => {
        const bar = document.querySelector('.adopt-pending-bar')
        return !!bar && bar.getClientRects().length > 0
      }, { timeout: 10000 })
      await mouseClickText('去处理')
      await page.waitForFunction(() => {
        const dlg = document.querySelector('.adopt-dialog')
        return !!dlg && dlg.getClientRects().length > 0
      }, { timeout: 10000 })
    })

    await step('选「两份都留着」并确认', async () => {
      await mouseClickText('两份都留着')
      await mouseClickText('就按我选的来')
      await page.waitForFunction(
        () => !document.querySelector('.adopt-dialog') && !document.querySelector('.adopt-collapsed-bar'),
        { timeout: 20000 },
      )
    })

    await step('文件树同时出现原文件与「（来自：团队案件库）」副本', async () => {
      await mouseClickSel('[title="资源管理器"]')
      await page.waitForFunction(() => {
        const t = document.querySelector('.file-tree')
        return !!t
          && t.innerText.includes('qa-J11协作文件.txt')
          && t.innerText.includes('qa-J11协作文件（来自：团队案件库）.txt')
      }, { timeout: 15000 })
    })

    await step('时间线出现「取回最新稿」节点', async () => {
      await mouseClickSel('[title="版本"]')
      await page.waitForFunction(() => {
        const titles = [...document.querySelectorAll('.timeline-node .node-title')].map((e) => e.innerText || '')
        return titles.some((t) => t.includes('取回最新稿'))
      }, { timeout: 15000 })
    })

    await step('S 的主线与 A 一致（裁决已重推）', async () => {
      const ok = await pollUntil(async () => {
        const tl = await sApi('/api/projects/' + remoteProjectId + '/version/timeline?limit=30')
        const versions = (tl && tl.data && tl.data.versions) || []
        return versions.some((v) => (v.note || v.message || '').includes('取回最新稿'))
      }, 20000, 1500)
      if (!ok) throw new Error('等待超时：S 的时间线没有出现裁决后的「取回最新稿」节点')
      const files = await sApi('/api/projects/' + remoteProjectId + '/files')
      const names = (Array.isArray(files) ? files : []).map((f) => f.name)
      if (!names.includes('qa-J11协作文件.txt') || !names.includes('qa-J11协作文件（来自：团队案件库）.txt')) {
        throw new Error('S 上文件列表没有同步裁决结果: ' + JSON.stringify(names))
      }
    })
  }
} finally {
  await browser.close()
  // 清理：删除本次运行的 QA 项目（账号无删除接口，qa_bot_* 会留存，可在管理页清）
  try { await api('/api/projects/' + QA.projectId, { method: 'DELETE' }) } catch {}
  // J11 在 A（长驻真实桌面后端，不像 S/B 是跑完就扔的进程）上建的 CloudConnection 同样
  // 要清掉——不清理会跨多次运行累积死连接。PR-E 起「放进团队案件库」由协作抽屉让律师
  // 指名选哪一个库（不再拿 list[0]），死连接不会再静默炸 NPE，但累积本身仍是测试卫生
  // 问题（选择列表会越来越长）。aConnectionId 为 null（J11 被跳过或连接步骤没走到）
  // 时这里是无操作的空转。
  if (aConnectionId) {
    try { await api('/api/cloud/connections/' + aConnectionId + '/disconnect', { method: 'POST' }) } catch {}
  }
  // J11 起的团队服务器 S / 同事桌面 B 是本次运行专属的长驻进程，跑完必须杀掉；
  // J11 被跳过（缺 APP_E2E_JAR）时 spawned 是空数组，这里是无操作的空转。
  spawned.forEach((c) => { try { c.kill('SIGTERM') } catch (e) {} })
}

// ---------- report ----------
const report = { passed, stepFails, issues, out: OUT }
fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
console.log('\n===== 结果 =====')
console.log('步骤: ' + passed + ' 通过, ' + stepFails + ' 失败; 异常信号 ' + issues.length + ' 条 (截图/报告: ' + OUT + ')')
for (const i of issues) console.log('  - [' + i.sev + '] ' + i.what)
process.exit(stepFails ? 1 : 0)

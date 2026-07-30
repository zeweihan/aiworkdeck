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

  // ============ J1 登录（真实打字） ============
  console.log('== J1 登录页真实登录 ==')
  await page.goto(BASE + '/#/pages/login/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.waitForSelector('.uni-input-input', { timeout: 15000 })
  await step('输入账号密码并登录', async () => {
    const inputs = await page.$$('.uni-input-input')
    await inputs[0].click({ clickCount: 3 }); await inputs[0].type(QA.user, { delay: 15 })
    await inputs[1].click({ clickCount: 3 }); await inputs[1].type(QA.pass, { delay: 15 })
    // 与本文件其余命名弹窗输入框同款去抖陷阱（uni-app 编译出的 input 把 DOM 值
    // 同步回 v-model 有一拍延迟）：负载高时最后一次按键落定前点"登录"，提交的
    // 密码会截断一个字符，后端按"用户名或密码错误"拒绝——用网络请求体验证过
    // （曾抓到提交的密码是 QaBot12345，少了最后一位 6）。这正是 issue #200
    // "J1 登录抖动"的根因之一，之前一直没补这个 300ms 定居延迟，系统负载高时
    // 会稳定复现而不是偶发。
    await sleep(300)
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
      headers: { 'X-Session-Id': QA.sid },
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
    await waitText('上一版')
    await waitText('这一版')
  })

  await step('关闭节点详情', () => mouseClickText('关闭'))

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

  await step('显示全部恢复时间线过滤', async () => {
    await mouseClickText('显示全部')
    await page.waitForFunction(
      () => !document.body.innerText.includes('只看《qa-版本测试.txt》的历史'),
      { timeout: 10000 },
    )
  })
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

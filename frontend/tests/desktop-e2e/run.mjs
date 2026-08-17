#!/usr/bin/env node
// 桌面宿主链路 e2e / desktop host-chain e2e (Electron + CDP).
//
// 覆盖浏览器目标够不到的两处：
//  ① 编辑器保存链路：新建 Word → 编辑器（<webview> 内真实 LOWA 引擎）boot → 宿主
//     执行器插入文本 → 点保存按钮 → 后端落盘 → API 下载 docx 验证内容真的写进了文件。
//  ② 需要真实桌面能力（window.checkbaDesktop.fs）才渲染的界面形态——目前是项目
//     列表页那两张新建卡。app-e2e 的最小桌面桩不含 fs，那边只能验降级形态。
//
// 跑法（本机）：
//   1) worktree/主仓库 frontend：`npx uni --port 5174`（dev:h5，VITE_API_BASE_URL
//      指向后端），且 dist/zetaoffice 里有引擎（build 后从打包版复制，见
//      lowa-e2e README）
//   2) 桌面后端在跑（须是 PR-A 去登录后的 local-mode 后端；默认 9696 打包版
//      常驻即可，冷启动联调可用新 jar 在 9797 顶班）
//   3) cd frontend && npm run test:desktop-e2e
// 注意：会在屏幕上弹出一个 dev Electron 窗口，跑完自动关闭。
// PR-A 后无登录：local-mode 免登直达，不再注册 qa_desk 账号、不再注入会话。
//
// Env：DESKTOP_E2E_DEVURL（默认 http://localhost:5174）、APP_E2E_BACKEND（默认 9696；
// 端口会经 CHECKBA_BACKEND_PORT 传给 Electron 壳，渲染层因此跟测试用同一个后端）

import { spawn, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const desktopDir = path.resolve(frontendDir, '../desktop')
const DEVURL = process.env.DESKTOP_E2E_DEVURL || 'http://localhost:5174'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9696'
// 渲染层的后端地址由桌面壳注入（BrowserWindow additionalArguments → preload →
// window.checkbaDesktop.apiBaseUrl），api.js 最优先读它——比 VITE_API_BASE_URL 还
// 靠前。所以只把 dev server 指向 APP_E2E_BACKEND 不够：壳仍会注入 dev 默认 9696，
// 页面便去了另一个后端，而这里 provision 的项目在 APP_E2E_BACKEND 上。
// CHECKBA_BACKEND_PORT 是 backend-service.js 留的显式覆盖口，同时让壳复用这个已在
// 跑的后端（verifyReuse 探 /api/admin/wizard）而不是另起一个 java。
const BACKEND_PORT = new URL(BACKEND).port
const CDP_PORT = 9333
const MARKER = 'QA_SAVE_MARKER_' + Date.now()

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---- preflight ----
for (const [what, ok] of [
  ['dev server ' + DEVURL, await fetch(DEVURL).then(() => true).catch(() => false)],
  ['后端 ' + BACKEND, await fetch(BACKEND + '/api/skills/market/list').then(() => true).catch(() => false)],
  ['引擎 dist/zetaoffice/lowa', fs.existsSync(path.join(frontendDir, 'dist/zetaoffice/lowa/soffice.js'))],
  ['desktop/node_modules', fs.existsSync(path.join(desktopDir, 'node_modules'))],
]) { if (!ok) { console.error('前置缺失: ' + what); process.exit(2) } }

// ---- provision（local-mode 免登：任何请求都解析为本机用户） ----
const QA = { sid: null, project: '', projectId: null }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}
{
  // 冷启动后端可能还锁着/未过向导：解锁门与向导分流由 app-e2e J1 专门覆盖，
  // 这里只把状态铺平，让 Electron 启动链不停在 unlock/wizard 页。
  const lic = await api('/api/license/status')
  if (lic && !lic.unlocked) {
    const code = process.env.APP_E2E_TRIAL_CODE
      || 'AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK'
    const act = await api('/api/license/activate', { method: 'POST', body: { code } })
    if (!act || act.unlocked !== true) { console.error('试用码解锁失败: ' + JSON.stringify(act).slice(0, 150)); process.exit(2) }
  }
  const wiz = await api('/api/admin/wizard')
  if (wiz && wiz.initialized === false) {
    // 三档收敛后 gemini 会被枚举校验打成 400（见 AdminConfigController.toSettingsUpdates）
    await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'OPENROUTER' } } })
  }
  // 名字记进 QA：启动落项目列表之后要靠它从卡片里认出这一轮的项目
  QA.project = '桌面链路QA_' + Date.now()
  const proj = await api('/api/projects', { method: 'POST', body: { name: QA.project, projectType: 'BLANK' } })
  QA.projectId = proj.id
  console.log('本机用户（免登）/ 项目 #' + QA.projectId)
}

// ---- launch dev Electron with CDP ----
console.log('启动 dev Electron（屏幕会出现窗口，结束自动关闭）...')
const elec = spawn('npx', ['electron', '.', '--remote-debugging-port=' + CDP_PORT], {
  cwd: desktopDir,
  env: {
    ...process.env,
    AIWORKDECK_DESKTOP_DEV: '1',
    CHECKBA_DEV_SERVER_URL: DEVURL,
    CHECKBA_BACKEND_PORT: BACKEND_PORT,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
const elecLog = fs.createWriteStream(path.join(os.tmpdir(), 'desktop-e2e-electron.log'))
elec.stdout.pipe(elecLog); elec.stderr.pipe(elecLog)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let ws = null
for (let i = 0; i < 60 && !ws; i++) {
  await sleep(1000)
  ws = await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version').then((r) => r.json()).then((j) => j.webSocketDebuggerUrl).catch(() => null)
}
if (!ws) { console.error('CDP 端点未就绪'); elec.kill(); process.exit(1) }

let failed = 0
const step = async (name, fn) => {
  try { await fn(); console.log('  ✓ ' + name) }
  // 截 250 字会把各步精心攒的现场快照（点击链路/实例计数/组件状态）正好切掉，
  // 只剩前半句没用的——间歇性失败本来就只有这一次现场可看，别省这点输出。
  catch (e) { failed++; console.log('  ✗ ' + name + ': ' + String(e.message || e).slice(0, 2000)) }
}

const browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
try {
  // main renderer page = the dev URL
  let page = null
  for (let i = 0; i < 30 && !page; i++) {
    await sleep(1000)
    page = (await browser.pages()).find((p) => p.url().startsWith(DEVURL))
  }
  if (!page) throw new Error('找不到主渲染页')

  // 壳注入的基址若和 provision 用的后端不是同一个，后面每一步都会以"点了没反应"
  // 的形态超时（文件建在别的库里/根本建不出来）。这里当场报死，别让人去查 UI。
  {
    const injected = await page.evaluate(() => (window.checkbaDesktop || {}).apiBaseUrl || null)
    if (!injected || new URL(injected).port !== BACKEND_PORT) {
      throw new Error('渲染层后端(' + injected + ') 与测试后端(' + BACKEND + ') 不一致')
    }
  }

  // 坐标点击的通病：任何全屏浮层都会把点吃掉，而现象只是"点了没反应"——真正的
  // 报错落在下一个 waitFor 上，十几秒后一句没头没尾的超时。dev 的 vite-error-overlay
  // 就这么骗过一次（node_modules 落后于新增依赖，vite 推编译错误盖满视口）。
  // 命中校验把它当场变成可读的错误。
  const HIT_CHECK = `
    function hitCheck(el) {
      const r = el.getBoundingClientRect()
      const x = r.x + r.width / 2, y = r.y + r.height / 2
      const hit = document.elementFromPoint(x, y)
      if (hit && el.contains(hit)) return { x, y }
      let who = hit ? hit.tagName.toLowerCase() : '(空白)'
      try { if (hit && hit.shadowRoot) who += ': ' + hit.shadowRoot.textContent.replace(/\\s+/g, ' ').trim().slice(0, 200) } catch (e) {}
      return { x, y, blockedBy: who }
    }`
  const clickAt = async (box, what) => {
    if (!box) throw new Error('找不到可点元素: ' + what)
    if (box.blockedBy) throw new Error('点击被遮挡[' + box.blockedBy + ']: ' + what)
    await page.mouse.click(box.x, box.y)
    await sleep(700)
  }

  const mouseClickSel = async (sel) => {
    // dev Electron 首帧比普通浏览器慢不少（vite 按需编译 + webview 初始化），
    // 15s 不够；超时也要带上页面当时的样子，别只丢一句 timeout。
    try {
      await page.waitForSelector(sel, { timeout: 40000 })
    } catch (e) {
      const snap = await page.evaluate(() => ({
        url: location.href,
        lang: (() => { try { return localStorage.getItem('awd_app_language') } catch (e2) { return '?' } })(),
        titles: [...document.querySelectorAll('[title]')].map((el) => el.getAttribute('title')).slice(0, 20),
        text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 250),
      })).catch(() => null)
      throw new Error('等不到 ' + sel + '；' + JSON.stringify(snap))
    }
    const box = await page.evaluate((check, s) => {
      const el = document.querySelector(s); if (!el) return null
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK, sel)
    await clickAt(box, sel)
  }

  // 语言必须钉死中文：本套断言全是中文字面量（「资源管理器」「新建文档」…），
  // 而 appLanguage 对全新安装是按 navigator.language 猜的——Electron 常带
  // --lang=en-GB、无头 Chrome 是 en-US，两边都会猜成英文，第一步就找不到中文。
  // 注意不能用 evaluateOnNewDocument：壳启动时已经把页面引导到项目列表并按环境
  // 语言落了盘，随后跳工作台只是改 hash（同文档导航），钩子根本不触发。
  await page.evaluate(() => { try { localStorage.setItem('awd_app_language', 'zh-CN') } catch (e) { /* ignore */ } })

  // 列表页的新建入口有两种合法形态，桌面那一种只有这里能验。app-e2e 的浏览器目标
  // 注入的最小桌面桩故意不含 fs（补 fs 会把全应用每个 `host.fs && …` 守卫一起从
  // false 翻成真，让所有页面拿着一个只有 showOpenDialog 的假 fs 走桌面分支，把
  // "最小桩不引爆任何页面"那次全仓审计整个作废），所以列表页 isDesktop 在那边恒假、
  // 只渲染一张降级卡。而真实律师在列表页看到的恰恰是这两张（打开文件夹 / 新建项目
  // 文件夹）——此前它们只有 check-navigation-contract 的静态断言守着"方法接上了"，
  // 没有任何运行时证据证明它们真的渲染得出来。
  await step('列表页桌面形态：两张新建卡真的渲染（浏览器目标够不到）', async () => {
    await page.goto(DEVURL + '/#/pages/project-list/project-list', { waitUntil: 'networkidle2' })
    // 接着上面钉中文那段：壳启动时已经按环境语言 boot 过一次（Electron 常带
    // --lang=en-GB），appLanguage.js 的模块级 cached 和 i18n 单例都在那次加载时定死。
    // 只改 hash 是**同文档导航**，模块不会重来，写进 localStorage 的 zh-CN 也就不生效。
    // 必须整页重载一次让新文档重新读盘。**这一步现场踩过**：不 reload 时两张卡确实
    // 渲染出来了、数量也对，但标题是 "Open Folder… | New Project Folder…"，中文断言全红。
    await page.reload({ waitUntil: 'networkidle2' })
    // 壳自己的 loadURL(DEV_SERVER_URL) 随时可能在这次导航之后才落地（#379 的教训），
    // 但 2026-08 起它的落点也是项目列表页，所以这里只要轮询到「列表路由 + 新建区
    // 已挂」为止，谁先谁后都不影响结论（它带来的也是新文档，同样读到 zh-CN）。
    const deadline = Date.now() + 60000
    let snap = null
    while (Date.now() < deadline) {
      snap = await page.evaluate(() => {
        if (!location.hash.includes('pages/project-list/project-list')) return null
        const sec = document.querySelector('.create-section')
        if (!sec) return null
        const host = window.checkbaDesktop || {}
        return {
          hasDialog: !!(host.fs && host.fs.showOpenDialog),
          cards: sec.querySelectorAll('.create-card').length,
          titles: [...sec.querySelectorAll('.create-title')].map((el) => (el.innerText || '').trim()),
        }
      }).catch(() => null)
      if (snap) break
      await sleep(1500)
    }
    if (!snap) throw new Error('等不到项目列表页的新建区（.create-section）')
    // isDesktop 判据是「有没有系统文件夹对话框」而不是「是不是桌面壳」（老版本壳没有
    // fs 命名空间时该降级而不是给出点不动的按钮）。这条先断言：否则壳哪天漏了 fs，
    // 现象会是"卡数不对"而不是"桌面能力没暴露到渲染层"，白查一轮。
    if (!snap.hasDialog) {
      throw new Error('壳没把 fs.showOpenDialog 暴露到渲染层，列表页会整体降级成浏览器形态')
    }
    if (snap.cards !== 2) {
      throw new Error('桌面形态应当两张新建卡，实际 ' + snap.cards + ' 张：' + JSON.stringify(snap.titles))
    }
    const joined = snap.titles.join(' | ')
    if (!joined.includes('打开文件夹')) throw new Error('缺「打开文件夹」卡：' + joined)
    if (!joined.includes('新建项目文件夹')) throw new Error('缺「新建项目文件夹」卡：' + joined)
    // 「单独打开一个文件」造出的是没有归属的临时项目（律师下次找不到它在哪），已从
    // 新建入口去掉。它原本就住在这个桌面分支里，所以这条负向断言也只有在桌面目标
    // 下才有牙——浏览器目标压根不渲染这个分支。
    if (snap.titles.some((t) => /^打开文件(?!夹)/.test(t))) {
      throw new Error('「单独打开文件」又回到新建入口了：' + joined)
    }
  })

  await step('免登进入项目（启动落项目列表 → 点卡片进工作台）', async () => {
    await page.goto(DEVURL + '/#/pages/project-overview/project-overview?id=' + QA.projectId, { waitUntil: 'networkidle2' })
    // 同上：跳工作台若只是改 hash，uni 路由会把它弹回项目列表（工作台参与的跳转
    // 本该走 reLaunch）。整页重载一次，直接以工作台路由、以中文重新 boot。
    await page.reload({ waitUntil: 'networkidle2' })
    // 2026-08 起**启动一律落项目列表页**（launch.vue 不再读 checkba_last_project_id
    // 直达上次项目）。而壳自己的 loadURL(DEV_SERVER_URL) 是不带 hash 的，它随时可能
    // 在上面这次导航之后才完成，把页面又带回列表——**点一次是不够的**：实测有一轮
    // 点完之后壳的启动导航才落地，页面被拽回列表，整套 8 步全红。
    // 所以这里轮询到真进了工作台为止：在列表上就按真人走法点卡片，已经在工作台
    // 就直接出去。点卡片主体、避开标题行（标题绑 @tap.stop=startRename）与卡片
    // 底部那排成员头像/加人按钮（各自也有 @tap.stop）。
    const deadline = Date.now() + 60000
    while (Date.now() < deadline) {
      const where = await page.evaluate(() => ({
        list: location.hash.includes('pages/project-list/project-list'),
        wb: location.hash.includes('pages/project-overview/project-overview'),
        ready: document.body.innerText.includes('资源管理器'),
      })).catch(() => null)
      if (where && where.wb && where.ready) break
      if (where && where.list) {
        const box = await page.evaluate((name) => {
          const cards = [...document.querySelectorAll('.project-item-card')]
          const card = cards.find((c) => (c.innerText || '').includes(name)) || cards[0]
          if (!card) return null
          const r = card.getBoundingClientRect()
          return { x: r.x + r.width / 2, y: r.y + r.height * 0.55 }
        }, QA.project).catch(() => null)
        if (box) await page.mouse.click(box.x, box.y).catch(() => {})
      }
      await new Promise((r) => setTimeout(r, 1500))
    }
    try {
      await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), { timeout: 30000 })
    } catch (e) {
      // 光一句超时查不出任何东西（本套件为此栽过好几轮：先后误判成单实例锁、
      // 编译超时、界面语言）。把页面当场的真实样子带进错误里。
      const snap = await page.evaluate(() => ({
        url: location.href,
        lang: (() => { try { return localStorage.getItem('awd_app_language') } catch (e2) { return '?' } })(),
        text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 400),
      })).catch(() => null)
      throw new Error('页面未出现「资源管理器」；' + JSON.stringify(snap))
    }
  })

  const mouseClickText = async (label) => {
    const box = await page.evaluate((check, lbl) => {
      const els = [...document.querySelectorAll('*')].filter((el) =>
        el.children.length === 0 && el.innerText && el.offsetParent !== null && el.innerText.trim().includes(lbl))
      const el = els[0]; if (!el) return null
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK, label)
    await clickAt(box, '文本 ' + label)
  }

  // 点击类失败最难查的是"点了到底有没有发出请求"。把写请求录下来，失败时一并报出。
  const apiWrites = []
  page.on('request', (r) => {
    try { if (r.method() !== 'GET' && r.url().includes('/api/')) apiWrites.push(r.method() + ' ' + new URL(r.url()).pathname) } catch (e) { /* ignore */ }
  })
  const pageErrs = []
  page.on('pageerror', (e) => pageErrs.push(String(e).slice(0, 160)))

  await step('新建 Word 文档并点击打开（编辑器 webview）', async () => {
    // 「新建文档」是个 18×16 的小图标，而顶栏（试用徽标/负责人行）在这前后还在
    // 重排——命中检查算出坐标、真正点下去之间元素会挪几像素，于是点空。表现是
    // 间歇性的"点了没反应、一个写请求都没发"。所以：点了就验效果，没效果就重点。
    // 先等文件树组件真的挂上：onFileTreeQuickAction 的第一行就是
    // `const tree = this.$refs.fileTree; if (!tree) return` —— ref 没到位时点击
    // 静默什么都不做，现象正是"一个写请求都没发"。
    await page.waitForFunction(() => {
      let seed = null
      for (const el of document.querySelectorAll('*')) { if (el.__vueParentComponent) { seed = el.__vueParentComponent; break } }
      if (!seed) return false
      let root = seed; while (root.parent) root = root.parent
      const q = [root]
      while (q.length) {
        const c = q.shift()
        if (c.proxy && c.proxy.$refs && c.proxy.$refs.fileTree) return true
        const stack = [c.subTree]
        while (stack.length) {
          const v = stack.pop()
          if (!v) continue
          if (v.component) q.push(v.component)
          else if (Array.isArray(v.children)) stack.push(...v.children)
        }
      }
      return false
    }, { timeout: 30000 }).catch(() => {})

    // 把点击链路录下来。光凭坐标和写请求列表分不清四种可能：① 这一下压根没进页面
    // （CDP 输入被丢弃）；② 进了但没落在这个按钮上（坐标/重排/页面栈里另一个实例）；
    // ③ 落上了但冒泡被掐断；④ 冒泡走完了，是 @tap 处理器自己早退（$refs.fileTree
    // 挂在另一个页面实例上）。录下来失败就能自己说明落在哪一种——2026-08-17 这轮
    // 查出来是 ①，但另外三种以后照样可能来，判据留着。
    const installTrace = () => page.evaluate(() => {
      if (window.__awdClickTrace) return
      const rec = (window.__awdClickTrace = [])
      const desc = (el) => {
        if (!el || !el.tagName) return String(el)
        const cls = typeof el.className === 'string' ? el.className
          : (el.className && el.className.baseVal) || ''
        return el.tagName.toLowerCase() + (cls ? '.' + cls.trim().split(/\s+/).join('.') : '')
          + (el.getAttribute && el.getAttribute('title') ? '[title=' + el.getAttribute('title') + ']' : '')
      }
      // 四种鼠标事件都录：只有 click 缺席 = down/up 落在了不同元素上（click 会被
      // 提升到共同祖先甚至不派发）；四种全缺席 = 这一下根本没进这个文档。
      for (const type of ['pointerdown', 'mousedown', 'mouseup', 'click']) {
        window.addEventListener(type, (e) => {
          const path = (e.composedPath ? e.composedPath() : []).filter((n) => n && n.tagName)
          rec.push({
            type,
            target: desc(e.target),
            xy: [Math.round(e.clientX), Math.round(e.clientY)],
            onBtn: path.some((n) => n.getAttribute && n.getAttribute('title') === '新建文档'),
            reachedDoc: false,
          })
        }, true)
      }
      // document 冒泡是整条链最后一站：它响了，说明按钮那一层的冒泡监听
      // （Vue 给 @tap 挂的就是这个）一定拿到过这次事件。
      document.addEventListener('click', () => {
        for (let i = rec.length - 1; i >= 0; i--) {
          if (rec[i].type === 'click') { rec[i].reachedDoc = true; break }
        }
      }, false)
    }).catch(() => {})

    // 本步间歇性红的真正原因（2026-08-17 实测复现三次）：**CDP 合成的鼠标事件被
    // 整个丢掉了**——渲染器还活着（evaluate 照常跑、命中检测照常准、$refs 都在），
    // 但 pointerdown/mousedown/mouseup/click 四种事件一个都没进页面，于是"点了没
    // 反应、一个写请求都没发"。这不是界面坏了，是输入通道坏了，同坐标再点几次
    // 毫无意义。所以：点完先问"这一下到底进没进页面"，没进就换一条不依赖操作系统
    // 输入层的路——直接在页面里派发冒泡 click。uni 的 @tap 在 H5 上就是绑在 click
    // 上的（uni-h5 的 $nne：isClickEvent = evt.type === 'click'），派发同样会走完
    // handleCreateWord → createFile → 后端落盘，断言强度不打折。
    // 兜底只在"零鼠标事件"时才启用——界面真坏了是收得到事件的，糊不住真回归。
    const clickCounted = async (sel) => {
      await installTrace()
      const before = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      await mouseClickSel(sel)
      const after = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      if (after > before) return 'mouse'
      console.log('      ! 真实鼠标事件一个都没进页面（CDP 输入被丢弃），改用页面内派发')
      await page.evaluate((s) => {
        const el = document.querySelector(s)
        if (el) el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }))
      }, sel)
      return 'dispatch'
    }

    let created = false
    let attempts = 0
    for (let attempt = 0; attempt < 3 && !created; attempt++) {
      attempts++
      await clickCounted('[title="新建文档"]')
      created = await page.waitForFunction(() => document.body.innerText.includes('newdocument'), { timeout: 8000 })
        .then(() => true).catch(() => false)
    }
    // 创建只落文件不开编辑器；等文件出现在树里再点击打开
    try {
      if (!created) await page.waitForFunction(() => document.body.innerText.includes('newdocument'), { timeout: 8000 })
    } catch (e) {
      const snap = await page.evaluate(() => {
        const all = [...document.querySelectorAll('[title="新建文档"]')]
        const el = all[0]
        const r = el ? el.getBoundingClientRect() : null
        // 点中的按钮属于哪个页面实例？@tap 处理器第一行取的是**它自己那个实例**的
        // $refs.fileTree——页面栈里若堆了两个工作台，按钮可能属于没有 ref 的那个，
        // 而从根遍历找到的却是另一个（探针据此误判过"ref 在的"）。
        let owner = null
        let inst = el && el.__vueParentComponent
        for (let hop = 0; inst && hop < 30; hop++, inst = inst.parent) {
          const p = inst.proxy
          if (p && p.$refs && Object.prototype.hasOwnProperty.call(p.$refs, 'fileTree')) {
            const t = p.$refs.fileTree
            owner = {
              hops: hop,
              refPresent: !!t,
              hasCreate: !!(t && typeof t.handleCreateWord === 'function'),
              projectId: t ? t.projectId : null,
              quickAction: typeof p.onFileTreeQuickAction,
            }
            break
          }
        }
        return {
          btnRect: r ? { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) } : null,
          btnMatches: all.length,
          // uni h5 的页面栈在 DOM 里并存（app-e2e 就用根节点计数抓这类堆叠）
          overviewInstances: document.querySelectorAll('.page-project-overview').length,
          listInstances: document.querySelectorAll('.page-project-list').length,
          hash: location.hash.slice(0, 80),
          owner,
          clicks: window.__awdClickTrace || null,
          // 一次鼠标事件都没收到时，要能分清"输入没进这个渲染器"和"页面被换过"
          focus: document.hasFocus(), vis: document.visibilityState,
          atPoint: (() => {
            if (!r) return null
            const h = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)
            return h ? h.tagName.toLowerCase() : null
          })(),
          viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
          text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 200),
        }
      }).catch(() => null)
      throw new Error('点了新建文档但文件没出现；点击次数=' + attempts
        + ' 写请求=' + JSON.stringify(apiWrites.slice(-6))
        + ' 页面错误=' + JSON.stringify(pageErrs.slice(0, 3)) + ' 现场=' + JSON.stringify(snap))
    }
    // 打开文件这一下同样吃"输入被丢弃"的亏（丢了就卡在下面等 webview），一样处理
    {
      await installTrace()
      const before = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      await mouseClickText('newdocument')
      const after = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      if (after === before) {
        console.log('      ! 打开文件那一下也没进页面，改用页面内派发')
        await page.evaluate(() => {
          const el = [...document.querySelectorAll('*')].find((n) => n.children.length === 0
            && n.innerText && n.offsetParent !== null && n.innerText.trim().includes('newdocument'))
          if (el) el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }))
        })
        await sleep(700)
      }
    }
    await page.waitForSelector('webview', { timeout: 30000 })
  })

  // 宿主侧编辑器组件（executor / statusText / saveDocument 所在）。
  // 注意：keepalive 池（PR#159）用 createElement 命令式建 webview，元素上没有
  // __vueParentComponent —— 必须从 Vue 组件树根 DFS 找 saveDocument 组件。
  // 且必须要求 file 非空：预热备胎（librePool.js spare）也是同一组件，只是
  // file=null 的隐藏空白实例——遍历顺序可能先碰到它，打在备胎上的插入/保存
  // 全部"成功"但真文档纹丝不动（真实路由按活跃文件键控 executor，无此问题）。
  const FIND_EDITOR = `
    function findEditor() {
      let seed = null
      for (const el of document.querySelectorAll('*')) { if (el.__vueParentComponent) { seed = el.__vueParentComponent; break } }
      if (!seed) return null
      let root = seed; while (root.parent) root = root.parent
      const q = [root]
      while (q.length) {
        const c = q.shift()
        if (c.proxy && typeof c.proxy.saveDocument === 'function' && c.proxy.file) return c.proxy
        const stack = [c.subTree]
        while (stack.length) {
          const v = stack.pop()
          if (!v) continue
          if (v.component) q.push(v.component)
          else if (Array.isArray(v.children)) stack.push(...v.children)
        }
      }
      return null
    }`
  const editorEval = (fnBody) => page.evaluate((finder, body) => {
    const ed = eval(finder + '; findEditor()')
    if (!ed) return { err: 'editor component not found' }
    return new Function('ed', body)(ed)
  }, FIND_EDITOR, fnBody)

  await step('等待引擎就绪（首次 boot 约 1-2 分钟）', async () => {
    for (let i = 0; i < 180; i++) {
      const st = await editorEval('return { status: ed.statusText, ready: ed.ready === true || !!ed.executor }')
      if (st.err) throw new Error(st.err)
      if (/就绪|加载文档中/.test(st.status) && st.ready) {
        // 加载文档中 -> 就绪；等到就绪或超时
        if (st.status.includes('就绪') || i > 30) return
      }
      if (/失败/.test(st.status)) throw new Error('编辑器状态: ' + st.status)
      await sleep(2000)
    }
    throw new Error('引擎未就绪(超时)')
  })

  // 自建工具栏（P2a）：必须走完 UI 链路验证——原语级测试通过不代表按钮真的接上了。
  // 断言三件事：① 工具栏渲染出来了；② 激活态从引擎读到了真值；③ 点一个按钮，
  // 文档状态**真的变了**（不是「点了没报错」）。
  await step('自建工具栏渲染并接上引擎', async () => {
    const READ_TOOLBAR = (finder) => {
      const ed = eval(finder + '; findEditor()')
      if (!ed) return { err: 'editor component not found' }
      const el = ed.$el && ed.$el.querySelector ? ed.$el.querySelector('.etb') : null
      // 组件树里找 EditorToolbar 实例（拿它的 state 与方法）
      let tb = null
      const q = [ed.$]
      while (q.length && !tb) {
        const c = q.shift()
        if (c && c.type && c.type.name === 'EditorToolbar') { tb = c.proxy; break }
        const stack = [c && c.subTree]
        while (stack.length) {
          const v = stack.pop()
          if (!v) continue
          if (v.component) q.push(v.component)
          else if (Array.isArray(v.children)) stack.push(...v.children)
        }
      }
      return {
        rendered: !!el,
        buttons: el ? el.querySelectorAll('.etb-btn').length : 0,
        hasToolbar: !!tb,
        style: tb && tb.state && tb.state.paragraph ? tb.state.paragraph.styleName : null,
        zoom: tb && tb.state && tb.state.view ? tb.state.view.zoom : null,
        fonts: tb ? tb.fontList.length : 0,
        styles: tb ? tb.styleList.length : 0,
      }
    }
    let r = await page.evaluate(READ_TOOLBAR, FIND_EDITOR)
    if (r.err) throw new Error(r.err)
    if (!r.rendered) throw new Error('工具栏未渲染（.etb 不存在）')
    // 组件挂载后 get_ui_state / list_styles / list_fonts 是异步拉的，引擎刚
    // ready 时还没回来——立刻断言必然读到空值。等它填好再判（本步曾因此误报）。
    for (let i = 0; i < 40 && (!r.style || !r.zoom || r.fonts < 5 || r.styles < 50); i++) {
      await sleep(500)
      r = await page.evaluate(READ_TOOLBAR, FIND_EDITOR)
      if (r.err) throw new Error(r.err)
    }
    if (r.buttons < 15) throw new Error('工具栏按钮数异常: ' + r.buttons)
    if (!r.hasToolbar) throw new Error('组件树里找不到 EditorToolbar 实例')
    if (!r.style || !r.zoom) throw new Error('激活态没从引擎读到真值: ' + JSON.stringify(r))
    if (r.fonts < 5 || r.styles < 50) throw new Error('字体/样式清单没拉到: ' + JSON.stringify(r))
    console.log('      工具栏: ' + r.buttons + ' 个按钮 / 样式=' + r.style + ' / 缩放=' + r.zoom
      + ' / 字体 ' + r.fonts + ' 款 / 样式库 ' + r.styles + ' 条')
  })

  await step('点工具栏按钮 → 文档状态真的变了', async () => {
    const r = await page.evaluate(async (finder) => {
      const ed = eval(finder + '; findEditor()')
      if (!ed) return { err: 'editor component not found' }
      const exec = (a, p) => ed.executor.executeCommand(a, p || {})
      await exec('insert_at_cursor', { text: '工具栏按钮链路验证段落' })
      await exec('select_paragraph', {})
      const el = ed.$el.querySelector('.etb')
      const btns = Array.from(el.querySelectorAll('.etb-btn'))
      const boldBtn = btns.find((b) => (b.textContent || '').trim() === 'B')
      if (!boldBtn) return { err: '找不到加粗按钮' }
      const before = await exec('get_ui_state')
      boldBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await new Promise((r2) => setTimeout(r2, 900))
      const after = await exec('get_ui_state')
      return {
        beforeBold: before.character.bold, afterBold: after.character.bold,
        highlighted: boldBtn.className.indexOf('on') >= 0,
      }
    }, FIND_EDITOR)
    if (r.err) throw new Error(r.err)
    if (r.beforeBold === r.afterBold) throw new Error('点了加粗但引擎里没变: ' + JSON.stringify(r))
    if (!r.highlighted) throw new Error('加粗生效了但按钮没高亮（激活态没刷新）: ' + JSON.stringify(r))
    console.log('      加粗: ' + r.beforeBold + ' → ' + r.afterBold + '，按钮已高亮')
  })

  await step('宿主执行器插入标记文本', async () => {
    const r = await page.evaluate(async (finder, marker) => {
      const ed = eval(finder + '; findEditor()')
      if (!ed) return { err: 'editor component not found' }
      return await ed.executor.executeCommand('insert_at_cursor', { text: marker + ' 保存链路中文验证' })
    }, FIND_EDITOR, MARKER)
    if (!r || r.success !== true) throw new Error('insert 失败: ' + JSON.stringify(r).slice(0, 150))
  })

  await step('等待自动保存完成', async () => {
    // 手动保存按钮已随 PR#185 实验工具栏移除——插入即触发 modified → 自动
    // 保存（约 2.5s 防抖）。**成功保存不再显示「已保存」**（PR#345：那个徽标
    // 反复闪变很打扰），所以完成信号就是「脏标记已清 + 不在保存中 + 没有失败」；
    // 保留对「已保存」的识别只是为了兼容旧版本前端。
    for (let i = 0; i < 60; i++) {
      const st = await editorEval('return { status: ed.statusText, saving: ed.saving, dirty: ed.dirty }')
      // editorEval 找不到组件时回 { err }，而 !st.dirty / !st.saving 对 undefined
      // 恒为真——不挡住就会把「编辑器压根没挂上」判成「保存完成」（实测假绿过）
      if (st.err) throw new Error('读不到编辑器实例: ' + st.err)
      if (/已保存/.test(st.status)) return
      if (i > 5 && !st.dirty && !st.saving && !/失败/.test(st.status)) return
      if (/失败/.test(st.status)) throw new Error('保存状态: ' + st.status)
      await sleep(1000)
    }
    const last = await editorEval('return { status: ed.statusText, key: ed.statusKey, saving: ed.saving, dirty: ed.dirty, docLoadFailed: ed.docLoadFailed }')
    throw new Error('自动保存未确认(超时)；最后状态=' + JSON.stringify(last))
  })

  await step('API 下载 docx 验证内容落盘', async () => {
    const files = await api('/api/projects/' + QA.projectId + '/files')
    const list = Array.isArray(files) ? files : (files && files.data) || []
    const flat = JSON.stringify(list)
    const m = flat.match(/"id":(\d+)[^}]*?docx/) || flat.match(/docx[^}]*?"id":(\d+)/)
    if (!m) throw new Error('项目文件列表中找不到 docx: ' + flat.slice(0, 200))
    const fileId = m[1]
    const buf = Buffer.from(await (await fetch(BACKEND + '/api/files/' + fileId + '/download', { headers: QA.sid ? { 'X-Session-Id': QA.sid } : {} })).arrayBuffer())
    const tmp = path.join(os.tmpdir(), 'desktop-e2e-doc.docx')
    fs.writeFileSync(tmp, buf)
    const text = execSync('unzip -p "' + tmp + '" word/document.xml | sed "s/<[^>]*>//g"').toString()
    if (!text.includes(MARKER)) throw new Error('docx 中未找到标记（保存链路断）；大小=' + buf.length)
    console.log('    docx ' + buf.length + ' 字节，标记命中')
  })
} finally {
  try { await api('/api/projects/' + QA.projectId, { method: 'DELETE' }) } catch {}
  try { browser.disconnect() } catch {}
  elec.kill('SIGTERM')
  await sleep(1500)
  try { elec.kill('SIGKILL') } catch {}
}
console.log(failed ? '\n结果：' + failed + ' 步失败' : '\n结果：桌面保存链路全通')
process.exit(failed ? 1 : 0)

#!/usr/bin/env node
// 桌面宿主链路 e2e / desktop host-chain e2e (Electron + CDP).
//
// 覆盖浏览器目标够不到的三处：
//  ① 编辑器保存链路：新建 Word → 编辑器（<webview> 内真实 LOWA 引擎）boot → 宿主
//     执行器插入文本 → 点保存按钮 → 后端落盘 → API 下载 docx 验证内容真的写进了文件。
//  ② 需要真实桌面能力（window.checkbaDesktop.fs）才渲染的界面形态——目前是项目
//     列表页那两张新建卡。app-e2e 的最小桌面桩不含 fs，那边只能验降级形态。
//  ③ 浏览器面板的 BrowserView 生命周期：切走标签再切回来必须还是原来那一页（保活），
//     关掉标签才销毁（不泄漏）。浏览器目标里根本没有 BrowserView，只有这里能验。
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

import { execSync } from 'node:child_process'
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { pickCdpPort, portFree, spawnElectron, waitForCdpWs, cdpOwnershipError, hardenPageInput, reassertFocusEmulation } from '../_lib/electron-cdp.mjs'
import { ensureUnlocked } from '../_lib/license-gate.mjs'

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
// CDP 端口现挑、进程树整棵收、连前核身份、输入通道加固，统一在 tests/_lib/electron-cdp.mjs
// （三套 e2e 共用；这些坑的来龙去脉见 .claude/agents/eng-infra.md）
const CDP_PORT = pickCdpPort('DESKTOP_E2E_CDP_PORT', 9333)
// 同样的理由（维护者常年多开）：浏览器面板那一段自起的本机测试站也得现挑端口
const pickFreePort = (from) => {
  for (let p = from; p < from + 60; p++) if (portFree(p)) return p
  throw new Error(from + '-' + (from + 59) + ' 全被占，挑不出空闲端口')
}
const MARKER = 'QA_SAVE_MARKER_' + Date.now()

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---- preflight ----
for (const [what, ok] of [
  ['dev server ' + DEVURL, await fetch(DEVURL).then(() => true).catch(() => false)],
  // r.ok 而不是"能拿到响应就算活"：fetch() 对 4xx/5xx 照样 resolve，只有网络层失败
  // 才会走 catch。以前这里只要连得上端口就判 OK，后端 500（比如 skill 注册表坏了）
  // 会被判成健康，前置检查形同虚设，失败要等 ~10 分钟后在无关步骤里以一堆看不懂的
  // 报错冒出来，而不是这里干脆利落的"前置缺失"提示。
  ['后端 ' + BACKEND, await fetch(BACKEND + '/api/skills/market/list').then((r) => r.ok).catch(() => false)],
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
  const body = await r.json().catch(() => null)
  // 4xx/5xx 以前直接把响应体（甚至 null）当正常结果原样返回，调用方看到的只是
  // "字段缺失/数组为空"这类下游症状，真实原因（后端拒绝了这次请求）被吞掉、没人
  // 打印出来。这里改成一律抛出，让每个调用点原有的 throw/catch 逻辑接住真实原因。
  if (!r.ok) throw new Error('API ' + (opts.method || 'GET') + ' ' + ep + ' -> ' + r.status + ': ' + JSON.stringify(body))
  return body
}
{
  // 冷启动后端可能还锁着/未过向导：解锁门与向导分流由 app-e2e J1 专门覆盖，
  // 这里只把状态铺平，让 Electron 启动链不停在 unlock/wizard 页。
  // 解锁起点收进共享模块（发版默认值关掉试用码之后这段三处都要改，抄三份必漏）
  try { await ensureUnlocked(api) } catch (e) { console.error(e.message); process.exit(2) }
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
const { elec, killTree } = spawnElectron({
  desktopDir,
  cdpPort: CDP_PORT,
  env: { AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: DEVURL, CHECKBA_BACKEND_PORT: BACKEND_PORT },
})
const elecLog = fs.createWriteStream(path.join(os.tmpdir(), 'desktop-e2e-electron.log'))
elec.stdout.pipe(elecLog); elec.stderr.pipe(elecLog)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const ws = await waitForCdpWs(CDP_PORT, 60, elec)
if (!ws) { console.error('CDP 端点未就绪（端口 ' + CDP_PORT + '）'); killTree(); process.exit(1) }

{
  const bad = cdpOwnershipError(CDP_PORT, elec)
  if (bad) { console.error(bad + '。换个端口重跑：DESKTOP_E2E_CDP_PORT=9400'); killTree(); process.exit(1) }
}

let failed = 0
const step = async (name, fn) => {
  try { await fn(); console.log('  ✓ ' + name) }
  // 截 250 字会把各步精心攒的现场快照（点击链路/实例计数/组件状态）正好切掉，
  // 只剩前半句没用的——间歇性失败本来就只有这一次现场可看，别省这点输出。
  catch (e) { failed++; console.log('  ✗ ' + name + ': ' + String(e.message || e).slice(0, 2000)) }
}

const browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
// 本地测试站点（浏览器面板那组用），下面 finally 里要兜底关掉它。
// 声明必须留在 try 外面：try 块里的 let 对同级 finally 不可见，写在里面 finally 只会抛
// ReferenceError，再被那行自己的空 catch 吞掉——兜底就成了永不生效的死代码。
let site = null
try {
  // main renderer page = the dev URL
  let page = null
  for (let i = 0; i < 30 && !page; i++) {
    await sleep(1000)
    page = (await browser.pages()).find((p) => p.url().startsWith(DEVURL))
  }
  if (!page) throw new Error('找不到主渲染页')
  await hardenPageInput(page)

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
      // 点在视口外时 elementFromPoint 直接返回 null，跟"被浮层盖住"是两种病，
      // 只报一句 (空白) 分不出来——把当时的矩形与视口一起带上。
      let who = hit ? hit.tagName.toLowerCase()
        : ('(空白) rect=' + Math.round(r.x) + ',' + Math.round(r.y) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height)
           + ' 视口=' + window.innerWidth + 'x' + window.innerHeight
           + ' 文档高=' + Math.round(document.scrollingElement.scrollHeight)
           + ' 滚动=' + Math.round(document.scrollingElement.scrollTop))
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

  // ---- 浏览器面板：切走标签再切回来必须还是原来那一页 ----
  // 修复前的行为：BrowserPane 一卸载就 destroy 掉 BrowserView（切个标签就把整个网页
  // 连根拔掉），而渲染层记的 tab.url 又从不跟随页内跳转（点链接/搜索主进程知道、
  // 渲染层不知情）。两件事叠起来，切回来就是「退回打开时那个地址」——默认标签的
  // 那个地址是 https://www.baidu.com，用户看到的现象正是「变成默认地址、内容没了」。
  // 只有桌面目标能验：浏览器目标里压根没有 BrowserView。
  // 用本机起的两页小站而不是真网站：断言不能挂在外网可达性上。
  const SITE_PORT = pickFreePort(8811)
  const SITE = 'http://127.0.0.1:' + SITE_PORT
  site = null
  const clickTabAt = async (idx) => {
    const box = await page.evaluate((check, i) => {
      const el = document.querySelectorAll('.tabs-pane-left .tab-item')[i]
      if (!el) return null
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK, idx)
    await clickAt(box, '第 ' + (idx + 1) + ' 个标签')
    await sleep(1200)
  }
  const webTabs = () => page.evaluate(() => {
    const vm = window.__checkbaActiveOverviewVm
    if (!vm) return null
    return (vm.leftFiles || []).filter((f) => f && f.tabType === 'web').map((f) => ({ name: f.name, url: f.url }))
  })
  const sitePages = async () => {
    const out = []
    for (const t of browser.targets().filter((t) => t.url().startsWith(SITE))) {
      const p = await t.page().catch(() => null)
      if (p) out.push(p)
    }
    return out
  }
  // 从窗口摘下的 BrowserView，Chromium 会把它的渲染进程冻起来（后台标签的正常待遇，
  // 页面状态照样留着）。往冻着的 target 里 evaluate 会一直挂着，最后只落一句
  // 「Runtime.callFunctionOn timed out」——查不出是哪一步。所以对 view 的求值自带超时，
  // 切回来之后先轮询到它重新应答为止。
  const evalInView = (vp, fn, ms = 4000) => Promise.race([
    vp.evaluate(fn),
    new Promise((_, rej) => setTimeout(() => rej(new Error('view 求值超时(' + ms + 'ms)')), ms)),
  ])
  const evalInViewWhenAwake = async (vp, fn, tries = 12) => {
    let last = null
    for (let i = 0; i < tries; i++) {
      try { return await evalInView(vp, fn) } catch (e) { last = e; await sleep(1000) }
    }
    throw new Error('view 一直没醒过来：' + String(last && last.message ? last.message : last))
  }

  await step('浏览器标签：切走再切回来仍是原来那一页（BrowserView 保活）', async () => {
    site = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      if ((req.url || '/').startsWith('/b')) res.end('<html><head><title>QA-PAGE-B</title></head><body><h1>B</h1></body></html>')
      else res.end('<html><head><title>QA-PAGE-A</title></head><body><h1>A</h1><a id="go" href="' + SITE + '/b">B</a></body></html>')
    })
    await new Promise((r) => site.listen(SITE_PORT, '127.0.0.1', r))

    // 两个网页标签：第一个用来翻页，第二个只是「切走去别处」的落点。
    // 顶栏图标很小、这前后界面还在重排，点空是常态（同「新建文档」那一步的教训）：
    // 点了就验效果、不够就补点，别一次点完直接断言。
    // 这个按钮是顶栏里 21×21 的小图标，而且它所在的 .project-header 是无边框窗口的
    // 拖拽区（按钮自己是 no-drag）。点空是常态，所以点了就验效果、不够就补点。
    // 失败时把「点击有没有真的落到按钮上」一起报出来——只报「标签没开出来」的话，
    // 分不清是没点到还是点到了但处理器早退。
    await page.evaluate(() => {
      if (window.__awdBrowserBtnHits) return
      window.__awdBrowserBtnHits = 0
      document.addEventListener('click', (e) => {
        const el = e.target && e.target.closest ? e.target.closest('[title="浏览器"]') : null
        if (el) window.__awdBrowserBtnHits++
      }, true)
    })
    let opened = []
    for (let round = 0; round < 8 && opened.length < 2; round++) {
      await mouseClickSel('[title="浏览器"]')
      await sleep(1200)
      opened = (await webTabs()) || []
    }
    if (opened.length < 2) {
      const snap = await page.evaluate(() => {
        const el = document.querySelector('[title="浏览器"]')
        const r = el ? el.getBoundingClientRect() : null
        return {
          domTabs: document.querySelectorAll('.tabs-pane-left .tab-item').length,
          btnRect: r ? [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] : null,
          clicksReachedBtn: window.__awdBrowserBtnHits,
          vm: !!window.__checkbaActiveOverviewVm,
          files: window.__checkbaActiveOverviewVm
            ? (window.__checkbaActiveOverviewVm.leftFiles || []).map((f) => f.tabType + ':' + f.name) : null,
        }
      }).catch(() => null)
      throw new Error('两个网页标签没开出来：' + JSON.stringify(snap) + '；页面错误=' + JSON.stringify(pageErrs.slice(-3)))
    }
    await clickTabAt(0)

    // 地址栏真人输入 + 点「打开」（uni 的 <input> 外面套了一层 uni-input，要点里面那个）。
    // 地址栏里本来就有当前地址，得先全选再打字——否则是接在后面续写，
    // 实测会得到 "https://www.baidu.com/http://127.0.0.1:xxxx/a" 这种四不像。
    // 全选用三连击而不是 ⌘A：CDP 打进来的 ⌘A 到不了原生菜单的 Select All role，
    // 实测选不中，于是新地址被接在旧地址后面。三连击是纯渲染层行为，稳。
    const addrBox = await page.evaluate((check) => {
      const el = document.querySelector('.browser-toolbar .url-input input')
      if (!el) return null
      return eval(check + '; hitCheck(el)')
    }, HIT_CHECK)
    if (!addrBox || addrBox.blockedBy) throw new Error('地址栏点不到：' + JSON.stringify(addrBox))
    await page.mouse.click(addrBox.x, addrBox.y, { clickCount: 3 })
    await sleep(300)
    await page.keyboard.type(SITE + '/a')
    await sleep(300)
    // 输进去没有当场就验：没验的话下面只会报「找不到 BrowserView」，
    // 分不清是没输进去、没点开、还是保活链路坏了。
    const typed = await page.evaluate(() => {
      const el = document.querySelector('.browser-toolbar .url-input input')
      return el ? { value: el.value, focused: document.activeElement === el } : null
    })
    if (!typed || !String(typed.value).endsWith('/a')) {
      throw new Error('地址栏没吃到输入：' + JSON.stringify(typed))
    }
    await mouseClickSel('[title="打开"]')
    let viewPages = []
    for (let i = 0; i < 20 && !viewPages.length; i++) { await sleep(500); viewPages = await sitePages() }
    if (!viewPages.length) {
      const snap = await page.evaluate(() => {
        const vm = window.__checkbaActiveOverviewVm
        const el = document.querySelector('.browser-toolbar .url-input input')
        return {
          addr: el ? el.value : '(没有地址栏)',
          tabs: vm ? (vm.leftFiles || []).map((f) => f.tabType + ':' + f.url) : '(没有实例指针)',
        }
      }).catch(() => null)
      throw new Error('地址栏导航没生效：找不到指向测试站的 BrowserView；' + JSON.stringify(snap))
    }

    // 页内跳转（点页面里的链接）——这一步渲染层此前完全不知情
    const vp = viewPages[0]
    await vp.click('#go')
    for (let i = 0; i < 20 && !vp.url().endsWith('/b'); i++) await sleep(300)
    if (!vp.url().endsWith('/b')) throw new Error('页内跳转没成功：' + vp.url())
    // 只活在这一个文档实例上的标记：重新 loadURL 会把它冲掉，据此判断"是不是同一页"
    await evalInViewWhenAwake(vp, () => { window.__awdQaAlive = 'ALIVE'; return 'ok' })

    await sleep(800)
    const tracked = (await webTabs())[0]
    if (!tracked || !String(tracked.url).endsWith('/b')) {
      throw new Error('标签没跟上页内跳转（切回来就会退回旧地址）：' + JSON.stringify(tracked))
    }

    // 切到第二个标签，再切回来
    await clickTabAt(1)
    await clickTabAt(0)
    await sleep(1500)

    const back = await sitePages()
    if (!back.length) throw new Error('切回来之后 BrowserView 没了（又变成卸载即销毁）')
    const alive = await evalInViewWhenAwake(back[0], () => window.__awdQaAlive || '(页面被重新加载了)')
      .catch((e) => '(读不到：' + String(e.message || e) + ')')
    if (alive !== 'ALIVE') throw new Error('网页被重建了，不是原来那一页：' + alive + '；URL=' + back[0].url())
    if (!back[0].url().endsWith('/b')) throw new Error('切回来地址变了：' + back[0].url())
    const shown = await page.evaluate(() => {
      const el = document.querySelector('.browser-toolbar .url-input input')
      return el ? el.value : '(没有地址栏)'
    })
    if (!String(shown).endsWith('/b')) throw new Error('地址栏没跟上，显示的是 ' + shown)
  })

  await step('关闭网页标签才销毁 BrowserView（保活不等于泄漏）', async () => {
    for (let i = 0; i < 6; i++) {
      const n = await page.evaluate(() => document.querySelectorAll('.tabs-pane-left .tab-item').length)
      if (!n) break
      const box = await page.evaluate((check) => {
        const el = document.querySelector('.tabs-pane-left .tab-item .tab-close')
        if (!el) return null
        return eval(check + '; hitCheck(el)')
      }, HIT_CHECK)
      if (!box) break
      await clickAt(box, '标签的关闭按钮')
      await sleep(800)
    }
    let left = await sitePages()
    for (let i = 0; i < 10 && left.length; i++) { await sleep(500); left = await sitePages() }
    if (left.length) throw new Error('标签关了但 BrowserView 还在（主进程里泄漏）：' + left.map((p) => p.url()).join(','))
    try { site.close() } catch (e) { /* ignore */ }
    site = null
  })

  // 焦点仿真是挂在 CDP 会话上的，而上面那步做了 goto + reload + reLaunch 好几次导航。
  // 它是幂等的、也不要钱，进这一步之前再压一次，省得赌"导航之后还在不在"。
  await reassertFocusEmulation(page)

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

    // 本步间歇性红的真正原因（2026-08-17 逐层夹出来的）：**这一轮的 CDP 输入通道
    // 坏了**，不是界面坏了。特征很干净、也很反直觉：
    //   · mouseMoved 照常送达；mousePressed / mouseReleased / dispatchKeyEvent 被静默丢弃；
    //   · 渲染器一切正常——evaluate、布局度量、elementFromPoint、$refs、页面栈全对；
    //   · 一旦坏了就整轮不恢复，同坐标再点几次毫无意义。
    // 已排除（各有一次实测）：窗口被遮挡/隐藏（这台机器上窗口本来常年 hidden，照样
    // 能点）、别的 App 抢焦点、无边框标题栏的 app-region 拖拽带、页面栈堆两个工作台、
    // 坐标重排点空、OOPIF/webview 盖住、连错了别人的 Electron。
    // 主因是进程泄漏（见文件开头 CDP 端口那段）：修掉之后失败率从 6/36 掉到 1/61。
    // 残留的极少数仍未定位到 Chromium 内部机制，所以这里只做**一次诚实的恢复**：
    // 整页重载让浏览器把这一页的输入路径重建一遍，然后仍旧用真实鼠标重点一次；
    // 恢复不了就当场报死，并在报错里点明是输入通道坏了。
    // 绝不退回"页面内 dispatchEvent 伪造点击"——桌面端就这一条真实输入的覆盖，
    // 换成假的之后这一步以后就再也挡不住真的界面回归了。
    const pressChannelLive = async () => {
      try {
        await page.evaluate(() => {
          window.__pcl = 0
          if (!window.__pclBound) { window.__pclBound = 1; window.addEventListener('mousedown', () => { window.__pcl++ }, true) }
        })
        await page.mouse.move(700, 500)
        await page.mouse.down(); await page.mouse.up()
        await sleep(200)
        return (await page.evaluate(() => window.__pcl)) > 0
      } catch (e) { return false }
    }
    const clickCounted = async (what, clickFn) => {
      await installTrace()
      const before = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      await clickFn()
      if ((await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)) > before) return true
      // 先补焦点仿真——实测这才是这一档的解药（重载救不回来，2026-08-18 现场三次一致）
      console.log('      ! ' + what + '：真实鼠标点了但一个事件都没进页面，补一次焦点仿真再试')
      await reassertFocusEmulation(page)
      if (!(await pressChannelLive())) {
        // 真不是焦点门的话再试重载，属于兜底的兜底
        console.log('      ! 补焦点仿真无效，再试整页重载')
        await page.reload({ waitUntil: 'networkidle2' }).catch(() => {})
        await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), { timeout: 30000 }).catch(() => {})
        await reassertFocusEmulation(page)
        if (!(await pressChannelLive())) { console.log('      ! 重载之后按下通道仍然是死的'); return false }
      }
      await installTrace()
      const b2 = await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)
      await clickFn()
      return (await page.evaluate(() => (window.__awdClickTrace || []).length).catch(() => 0)) > b2
    }

    let created = false
    let attempts = 0
    let inputOk = true
    for (let attempt = 0; attempt < 3 && !created; attempt++) {
      attempts++
      inputOk = await clickCounted('新建文档', () => mouseClickSel('[title="新建文档"]'))
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
      // 两种红要一眼分得开：inputOk=false 是"这一轮的 CDP 输入通道坏了"（环境问题，
      // 重跑通常就好），不是界面回归——别再让人拿着这条去查 UI（本套件为此栽过一轮）。
      throw new Error((inputOk
        ? '点了新建文档但文件没出现'
        : '真实鼠标事件进不了页面且重载后未恢复：本轮 CDP 输入通道坏了，不是界面问题（重跑一次通常即可）')
        + '；点击次数=' + attempts
        + ' 写请求=' + JSON.stringify(apiWrites.slice(-6))
        + ' 页面错误=' + JSON.stringify(pageErrs.slice(0, 3)) + ' 现场=' + JSON.stringify(snap))
    }
    // 打开文件这一下同样吃"输入被丢弃"的亏（丢了就卡在下面等 webview），同样处理
    if (!(await clickCounted('打开 newdocument', () => mouseClickText('newdocument')))) {
      throw new Error('打开文件那一下真实鼠标事件没进页面，且重载后仍未恢复（CDP 输入通道坏了，不是界面问题）')
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
  try { if (site) site.close() } catch {}
  // 以前这里是空 catch：清理失败（后端瞬时不可达/DELETE 4xx 等）完全无声无息，
  // QA_<timestamp> 项目连同真实生成的 docx 永久留在项目列表里，没有任何输出能
  // 告诉维护者为什么、需要手动去清。至少打一行，让残留有迹可查。
  try { await api('/api/projects/' + QA.projectId, { method: 'DELETE' }) }
  catch (e) { console.error('⚠️ 清理测试项目失败（' + QA.project + ' #' + QA.projectId + '）：' + e.message + '；需要手动去项目列表删除') }
  try { browser.disconnect() } catch {}
  killTree()
  await sleep(1500)
}
console.log(failed ? '\n结果：' + failed + ' 步失败' : '\n结果：桌面保存链路全通')
process.exit(failed ? 1 : 0)

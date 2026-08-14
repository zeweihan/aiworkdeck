#!/usr/bin/env node
// 桌面宿主链路 e2e / desktop host-chain e2e (Electron + CDP).
//
// 覆盖浏览器目标够不到的一条关键链：新建 Word → 编辑器（<webview> 内真实 LOWA
// 引擎）boot → 宿主执行器插入文本 → 点保存按钮 → 后端落盘 → API 下载 docx 验证
// 内容真的写进了文件。这是"编辑器保存链路"的端到端证明。
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
  const proj = await api('/api/projects', { method: 'POST', body: { name: '桌面链路QA_' + Date.now(), projectType: 'BLANK' } })
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
  catch (e) { failed++; console.log('  ✗ ' + name + ': ' + String(e.message || e).slice(0, 250)) }
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

  await step('免登直达进入项目（PR-A 去登录：不注会话）', async () => {
    await page.goto(DEVURL + '/#/pages/project-overview/project-overview?id=' + QA.projectId, { waitUntil: 'networkidle2' })
    // 同上：跳工作台若只是改 hash，uni 路由会把它弹回项目列表（工作台参与的跳转
    // 本该走 reLaunch）。整页重载一次，直接以工作台路由、以中文重新 boot。
    await page.reload({ waitUntil: 'networkidle2' })
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

    let created = false
    for (let attempt = 0; attempt < 3 && !created; attempt++) {
      await mouseClickSel('[title="新建文档"]')
      created = await page.waitForFunction(() => document.body.innerText.includes('newdocument'), { timeout: 8000 })
        .then(() => true).catch(() => false)
    }
    // 创建只落文件不开编辑器；等文件出现在树里再点击打开
    try {
      if (!created) await page.waitForFunction(() => document.body.innerText.includes('newdocument'), { timeout: 8000 })
    } catch (e) {
      const snap = await page.evaluate(() => {
        const el = document.querySelector('[title="新建文档"]')
        const r = el ? el.getBoundingClientRect() : null
        return {
          btnRect: r ? { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) } : null,
          viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
          text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 200),
        }
      }).catch(() => null)
      throw new Error('点了新建文档但文件没出现；写请求=' + JSON.stringify(apiWrites.slice(-6))
        + ' 页面错误=' + JSON.stringify(pageErrs.slice(0, 3)) + ' 现场=' + JSON.stringify(snap))
    }
    await mouseClickText('newdocument')
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

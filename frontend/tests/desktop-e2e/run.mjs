#!/usr/bin/env node
// 桌面宿主链路 e2e / desktop host-chain e2e (Electron + CDP).
//
// 覆盖浏览器目标够不到的一条关键链：新建 Word → 编辑器（<webview> 内真实 LOWA
// 引擎）boot → 宿主执行器插入文本 → 点保存按钮 → 后端落盘 → API 下载 docx 验证
// 内容真的写进了文件。这是"编辑器保存链路"的端到端证明。
//
// 跑法（本机）：
//   1) worktree/主仓库 frontend：`npx uni --port 5174`（dev:h5），且
//      dist/zetaoffice 里有引擎（build 后从打包版复制，见 lowa-e2e README）
//   2) 桌面后端 9696 在跑（打包版开着即可；dev Electron 会复用不再起 java）
//   3) cd frontend && npm run test:desktop-e2e
// 注意：会在屏幕上弹出一个 dev Electron 窗口，跑完自动关闭。
//
// Env：DESKTOP_E2E_DEVURL（默认 http://localhost:5174）、APP_E2E_BACKEND（默认 9696）

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

// ---- provision ----
const QA = { user: 'qa_desk_' + Date.now(), pass: 'QaBot123456' }
async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(QA.sid ? { 'X-Session-Id': QA.sid } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  return r.json().catch(() => null)
}
{
  const reg = await api('/api/auth/register', { method: 'POST', body: { username: QA.user, password: QA.pass, displayName: 'QA桌面' } })
  QA.sid = reg.data.sessionId; QA.userObj = reg.data.user
  const proj = await api('/api/projects', { method: 'POST', body: { name: '桌面链路QA_' + Date.now(), projectType: 'BLANK' } })
  QA.projectId = proj.id
  console.log('QA 账号 ' + QA.user + ' / 项目 #' + QA.projectId)
}

// ---- launch dev Electron with CDP ----
console.log('启动 dev Electron（屏幕会出现窗口，结束自动关闭）...')
const elec = spawn('npx', ['electron', '.', '--remote-debugging-port=' + CDP_PORT], {
  cwd: desktopDir,
  env: { ...process.env, AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: DEVURL },
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

  const mouseClickSel = async (sel) => {
    await page.waitForSelector(sel, { timeout: 15000 })
    const box = await page.evaluate((s) => {
      const el = document.querySelector(s); if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, sel)
    await page.mouse.click(box.x, box.y)
    await sleep(700)
  }

  await step('注入会话并进入项目', async () => {
    await page.evaluate((sid, user) => {
      uni.setStorageSync('checkba_session_id', sid)
      uni.setStorageSync('checkba_user', user)
    }, QA.sid, QA.userObj)
    await page.goto(DEVURL + '/#/pages/project-overview/project-overview?id=' + QA.projectId, { waitUntil: 'networkidle2' })
    await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), { timeout: 20000 })
  })

  const mouseClickText = async (label) => {
    const box = await page.evaluate((lbl) => {
      const els = [...document.querySelectorAll('*')].filter((el) =>
        el.children.length === 0 && el.innerText && el.offsetParent !== null && el.innerText.trim().includes(lbl))
      const el = els[0]; if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, label)
    if (!box) throw new Error('找不到文本: ' + label)
    await page.mouse.click(box.x, box.y)
    await sleep(700)
  }

  await step('新建 Word 文档并点击打开（编辑器 webview）', async () => {
    await mouseClickSel('[title="新建文档"]')
    // 创建只落文件不开编辑器；等文件出现在树里再点击打开
    await page.waitForFunction(() => document.body.innerText.includes('newdocument'), { timeout: 15000 })
    await mouseClickText('newdocument')
    await page.waitForSelector('webview', { timeout: 30000 })
  })

  // 宿主侧编辑器组件（executor / statusText / saveDocument 所在）。
  // 注意：keepalive 池（PR#159）用 createElement 命令式建 webview，元素上没有
  // __vueParentComponent —— 必须从 Vue 组件树根 DFS 找 saveDocument 组件。
  const FIND_EDITOR = `
    function findEditor() {
      let seed = null
      for (const el of document.querySelectorAll('*')) { if (el.__vueParentComponent) { seed = el.__vueParentComponent; break } }
      if (!seed) return null
      let root = seed; while (root.parent) root = root.parent
      const q = [root]
      while (q.length) {
        const c = q.shift()
        if (c.proxy && typeof c.proxy.saveDocument === 'function') return c.proxy
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

  await step('宿主执行器插入标记文本', async () => {
    const r = await page.evaluate(async (finder, marker) => {
      const ed = eval(finder + '; findEditor()')
      if (!ed) return { err: 'editor component not found' }
      return await ed.executor.executeCommand('insert_at_cursor', { text: marker + ' 保存链路中文验证' })
    }, FIND_EDITOR, MARKER)
    if (!r || r.success !== true) throw new Error('insert 失败: ' + JSON.stringify(r).slice(0, 150))
  })

  await step('点保存按钮并等待「已保存」', async () => {
    await mouseClickSel('.libre-save')
    for (let i = 0; i < 60; i++) {
      const st = await editorEval('return { status: ed.statusText, saving: ed.saving }')
      if (/已保存/.test(st.status)) return
      if (/失败/.test(st.status)) throw new Error('保存状态: ' + st.status)
      await sleep(1000)
    }
    throw new Error('保存未确认(超时)')
  })

  await step('API 下载 docx 验证内容落盘', async () => {
    const files = await api('/api/projects/' + QA.projectId + '/files')
    const list = Array.isArray(files) ? files : (files && files.data) || []
    const flat = JSON.stringify(list)
    const m = flat.match(/"id":(\d+)[^}]*?docx/) || flat.match(/docx[^}]*?"id":(\d+)/)
    if (!m) throw new Error('项目文件列表中找不到 docx: ' + flat.slice(0, 200))
    const fileId = m[1]
    const buf = Buffer.from(await (await fetch(BACKEND + '/api/files/' + fileId + '/download', { headers: { 'X-Session-Id': QA.sid } })).arrayBuffer())
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
console.log(failed ? '\n结果：' + failed + ' 步失败 ❌' : '\n结果：桌面保存链路全通 ✅')
process.exit(failed ? 1 : 0)

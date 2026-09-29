#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 计划审阅（dev-board#1022）真渲染走查：H5 dev + 无头 Chromium，真鼠标真键盘走完
//   段一：进审阅态 → 改一行 → 删一行 → 选一行加批注 → 按修订版推进（拦截 POST /api/agent/chat 断言回喂）
//   段二：再进审阅态 → 改一行 → 放弃修改 → 确认 → 文件字节回到基线
//   段三：标签开着时走宿主「打开修订」入口（handleOpenPlanReviewTab），编辑器自己 POST open 进审阅态
//   段四：对话里种一条带计划卡与「> 已保存到项目文件：Plan.md」的助手气泡（拦 GET /api/ai/history 回放），
//         真点计划卡「打开修订」（按路径反查 GET files/resolve）→ 卡片「修订中 · …」→ 改一行、提交 →
//         这张卡变成已修订；再回放一份同名复用 Plan.md 的第二份计划，点它的「打开修订」后仍有按钮（I-1）
// 不打真模型：/api/agent/chat 被拦截并就地回 200，请求体只做断言。
//
// 前置（跑法照 tests/app-e2e/run.mjs 头注释）：
//   后端：隔离 user.home 起 desktop profile（默认 9797），播 trial 票据；不配 OPENROUTER_API_KEY。
//   前端：cd frontend && npx uni --port 5176（本脚本用 window.checkbaDesktop.apiBaseUrl 指向后端）。
//   node frontend/tests/plan-review/walk-plan-review.mjs
// Env: PLAN_REVIEW_BASE（默认 http://127.0.0.1:5176）/ PLAN_REVIEW_BACKEND（默认 http://127.0.0.1:9797）
//      PUPPETEER_EXECUTABLE_PATH / OUT（截图目录，默认 /tmp/dev-board-1022-plan-review）/ TAG（截图前缀）
//
// 驱动要点：uni 的 @tap 只认真实鼠标坐标（page.mouse.click），el.click() 不触发；
// uni textarea 的 v-model 有 100ms 节流，打完字要等一会儿再点保存。

import fs from 'node:fs'
import puppeteer from 'puppeteer-core'

const BASE = process.env.PLAN_REVIEW_BASE || 'http://127.0.0.1:5176'
const BACKEND = process.env.PLAN_REVIEW_BACKEND || 'http://127.0.0.1:9797'
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = process.env.OUT || '/tmp/dev-board-1022-plan-review'
const TAG = process.env.TAG || 'run'
fs.mkdirSync(OUT, { recursive: true })

const BASELINE = [
  '# 实施计划',
  '1. 新建数据表 plan_review',
  '2. 增加审阅 REST 接口',
  '3. 前端接审阅条与右栏',
  '4. 写单测',
  '5. 真渲染走查'
].join('\n')
const FILE_NAME = 'Plan.md'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let failed = false
const check = (cond, msg) => {
  console.log((cond ? 'PASS ' : 'FAIL ') + msg)
  if (!cond) failed = true
}

async function api(ep, opts = {}) {
  const r = await fetch(BACKEND + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: opts.body ? JSON.stringify(opts.body) : undefined
  })
  const t = await r.text()
  try { return JSON.parse(t) } catch (e) { return t }
}
async function download(fid) {
  const r = await fetch(BACKEND + '/api/files/' + fid + '/download')
  return r.text()
}
async function openReviewRecord(pid, fid, conversationId) {
  const r = await api(`/api/projects/${pid}/files/${fid}/review`, {
    method: 'POST',
    body: { conversationId, artifactId: 'walk-artifact', baselineText: BASELINE }
  })
  if (!r || !r.review || r.review.status !== 'open') throw new Error('open review ' + JSON.stringify(r))
  return r
}

const proj = await api('/api/projects', { method: 'POST', body: { name: 'QA计划审阅_' + Date.now(), projectType: 'BLANK' } })
const pid = proj.id
if (!pid) throw new Error('create project ' + JSON.stringify(proj))
console.log('project', pid)
const created = await api(`/api/projects/${pid}/files/file`, {
  method: 'POST',
  body: { parentId: null, name: FILE_NAME, fileType: 'md', fileSize: Buffer.byteLength(BASELINE) }
})
// 审阅 REST 与 /api/files/{id}/* 都认数字主键；wpsFileId 是给 WPS 的字符串 id，不能拿来用
const fid = created.id
{
  const form = new FormData()
  form.append('file', new Blob([BASELINE], { type: 'text/markdown' }), FILE_NAME)
  const j = await (await fetch(BACKEND + '/api/files/' + fid + '/upload', { method: 'POST', body: form })).json()
  if (j.code !== 0) throw new Error('upload ' + JSON.stringify(j))
}
console.log('file', fid)
const CONV = 'walk-conv-' + Date.now()
await openReviewRecord(pid, fid, CONV)

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox'],
  defaultViewport: { width: 1600, height: 960 }
})
const chatBodies = []
const pageErrors = []
// 段四：种进对话的历史（0 = 不拦，1 = 一份计划，2 = 同名复用的第二份计划）与 resolve 计数
const SEED_CONV = 'walk-seed-conv-' + Date.now()
let historyMode = 0
let resolveHits = 0
const PLAN_B = BASELINE + '\n6. 补文档'
const seedMessages = () => {
  const saved = '\n\n> 已保存到项目文件：' + FILE_NAME
  const msgs = [
    { id: 9001, role: 'USER', content: '给我列个实施计划', createdAt: '2026-09-29T10:00:00' },
    // 卡片正文刻意与文件不同：审阅基线必须取文件真字节，不取卡片正文
    { id: 9002, role: 'ASSISTANT', content: '<artifact type="implementation_plan" name="Plan">\n' + BASELINE + '\n（卡片正文与文件不同）\n</artifact>' + saved, createdAt: '2026-09-29T10:00:05' }
  ]
  if (historyMode === 2) {
    msgs.push({ id: 9003, role: 'USER', content: '修订版', displayContent: '已按修订版推进（1 处改动、0 条批注）', createdAt: '2026-09-29T10:01:00' })
    msgs.push({ id: 9004, role: 'ASSISTANT', content: '<artifact type="implementation_plan" name="Plan">\n' + PLAN_B + '\n</artifact>' + saved, createdAt: '2026-09-29T10:01:05' })
  }
  return msgs
}
try {
  const page = await browser.newPage()
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)))
  await page.evaluateOnNewDocument((b) => {
    window.checkbaDesktop = { apiBaseUrl: b, shell: { openExternal: () => Promise.resolve() } }
  }, BACKEND)
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const corsHeaders = { 'Access-Control-Allow-Origin': req.headers().origin || BASE, 'Access-Control-Allow-Credentials': 'true' }
    if (req.method() === 'GET' && /\/api\/projects\/\d+\/files\/resolve\?/.test(req.url())) resolveHits++
    if (historyMode && req.method() === 'GET' && /\/api\/ai\/history\?/.test(req.url())
        && new URL(req.url()).searchParams.get('conversationId') === SEED_CONV) {
      req.respond({
        status: 200,
        contentType: 'application/json',
        headers: corsHeaders,
        body: JSON.stringify({ messages: seedMessages(), hasMore: false, nextBefore: null })
      })
      return
    }
    if (req.method() === 'POST' && /\/api\/agent\/chat(\?|$)/.test(req.url())) {
      try { chatBodies.push(JSON.parse(req.postData() || '{}')) } catch (e) { chatBodies.push({ _raw: req.postData() }) }
      // 页面源 5176、后端 9797 跨源：桩响应不带 CORS 头会让 fetch 当场抛「Failed to fetch」
      const origin = req.headers().origin || BASE
      req.respond({
        status: 200,
        contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Credentials': 'true' },
        body: JSON.stringify({ code: 0, data: {} })
      })
      return
    }
    req.continue()
  })

  const clickText = async (txt) => {
    await page.waitForFunction((t) => [...document.querySelectorAll('*')].some((e) => e.children.length === 0 && e.textContent.trim() === t && e.getBoundingClientRect().width > 0), { timeout: 60000 }, txt)
    const box = await page.evaluate((t) => {
      const e = [...document.querySelectorAll('*')].find((e) => e.children.length === 0 && e.textContent.trim() === t && e.getBoundingClientRect().width > 0)
      const r = e.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, txt)
    await page.mouse.click(box.x, box.y)
  }
  const clickSel = async (sel) => {
    await page.waitForSelector(sel, { visible: true, timeout: 15000 })
    const box = await page.$eval(sel, (e) => { const r = e.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 } })
    await page.mouse.click(box.x, box.y)
  }
  const count = (sel) => page.$$eval(sel, (els) => els.length)
  const summary = () => page.$eval('.prb-bar .prb-summary', (e) => e.textContent.trim()).catch(() => null)
  // 第 n 行（1 起）文字的屏幕区间
  const lineBox = (n) => page.evaluate((i) => {
    const line = document.querySelectorAll('.ptx-cm-host .cm-content .cm-line')[i - 1]
    if (!line) return null
    const range = document.createRange()
    range.selectNodeContents(line)
    const rects = [...range.getClientRects()]
    const first = rects[0]; const last = rects[rects.length - 1]
    return { x0: first.left + 1, x1: last.right - 1, y: first.top + first.height / 2 }
  }, n)
  // 进入工作台并等编辑器进审阅态（标签恢复与否都兜住：没自动打开就点文件树）
  // 整页重载（同 hash 的 goto 只是 hash 跳转，不会重挂载编辑器）：审阅记录是走查脚本从 REST 直接开的，
  // 不经「打开修订」传 review prop，只有新挂载的 PlainTextEditor 会自己 GET review 进审阅态。
  const enterReview = async () => {
    await page.goto('about:blank')
    await page.goto(BASE + '/#/pages/project-overview/project-overview?id=' + pid, { waitUntil: 'domcontentloaded', timeout: 60000 })
    const opened = await page.waitForSelector('.prb-bar', { visible: true, timeout: 8000 }).then(() => true).catch(() => false)
    if (!opened) {
      await clickText(FILE_NAME)
      await page.waitForSelector('.prb-bar', { visible: true, timeout: 30000 })
    }
    await page.waitForSelector('.ptx-cm-host .cm-content .cm-line', { visible: true, timeout: 30000 })
    await sleep(500)
  }
  const typeAtLineEnd = async (n, text) => {
    const b = await lineBox(n)
    await page.mouse.click(b.x1 + 20, b.y)
    await page.keyboard.press('End')
    await page.keyboard.type(text)
    await sleep(400)
  }

  // ---------------- 段一：改 / 删 / 批注 / 按修订版推进 ----------------
  await enterReview()
  check(await count('.prb-bar') === 1, '审阅条可见')
  check((await summary()) === '0 处改动 · 0 条批注', `审阅条初始文案 = ${await summary()}`)
  check(await count('.prc-panel') === 1, '右侧批注栏可见')

  await typeAtLineEnd(2, '（改）')
  check(await count('.cm-review-edited') === 1, `改第 2 行后 .cm-review-edited = ${await count('.cm-review-edited')}`)
  check(/^1 处改动/.test(await summary() || ''), `审阅条 = ${await summary()}`)
  const editedTitle = await page.$eval('.cm-review-edited', (e) => e.getAttribute('title')).catch(() => null)
  check(editedTitle === '1. 新建数据表 plan_review', `改动行悬停原文 title = ${editedTitle}`)
  // UX-1：光标就在这一行（activeLine），琥珀底不许被当前行底色盖掉
  const editedStyle = await page.$eval('.cm-review-edited', (e) => ({ active: e.classList.contains('cm-activeLine'), bg: getComputedStyle(e).backgroundColor }))
  check(editedStyle.active && editedStyle.bg === 'rgb(255, 246, 229)', `光标所在改动行底色 = ${editedStyle.bg}（activeLine=${editedStyle.active}）`)

  // 删第 5 行（「4. 写单测」）：行首 shift+↓ 选中整行再退格
  {
    const b = await lineBox(5)
    await page.mouse.click(b.x0, b.y)
    await page.keyboard.press('Home')
    await page.keyboard.down('Shift'); await page.keyboard.press('ArrowDown'); await page.keyboard.up('Shift')
    await page.keyboard.press('Backspace')
    await sleep(400)
  }
  check(await count('.cm-review-deleted') === 1, `删一行后 .cm-review-deleted = ${await count('.cm-review-deleted')}`)
  check(/^2 处改动/.test(await summary() || ''), `审阅条 = ${await summary()}`)

  // 选中第 3 行文字加批注
  {
    const b = await lineBox(3)
    await page.mouse.move(b.x0, b.y)
    await page.mouse.down()
    await page.mouse.move((b.x0 + b.x1) / 2, b.y, { steps: 4 })
    await page.mouse.move(b.x1, b.y, { steps: 4 })
    await page.mouse.up()
    await sleep(200)
  }
  await page.waitForSelector('.cm-review-add', { visible: true, timeout: 5000 }).catch(() => {})
  check(await count('.cm-review-add') === 1, '选区旁出现「+」')
  await clickSel('.cm-review-add')
  await page.waitForSelector('.prc-draft textarea', { visible: true, timeout: 5000 })
  const draftQuote = await page.$eval('.prc-draft .prc-quote', (e) => e.textContent.trim())
  check(draftQuote === '2. 增加审阅 REST 接口', `草稿引用原文 = ${draftQuote}`)
  await page.click('.prc-draft textarea')
  await page.keyboard.type('补充引用')
  await sleep(400)
  await clickSel('.prc-draft .prc-primary')
  await page.waitForFunction(() => document.querySelectorAll('.prc-item').length === 1, { timeout: 10000 }).catch(() => {})
  await sleep(400)
  check(await count('.cm-review-commented') === 1, `.cm-review-commented = ${await count('.cm-review-commented')}`)
  // UX-2：批注高亮加深 + 2px 强调色下划线
  const cmtStyle = await page.$eval('.cm-review-commented', (e) => { const cs = getComputedStyle(e); return { bg: cs.backgroundColor, bw: cs.borderBottomWidth, bs: cs.borderBottomStyle } })
  check(cmtStyle.bg === 'rgb(207, 235, 221)' && cmtStyle.bw === '2px' && cmtStyle.bs === 'solid', `批注高亮 = ${cmtStyle.bg} / 下划线 ${cmtStyle.bw} ${cmtStyle.bs}`)
  check(await count('.prc-item') === 1, `右栏批注条数 = ${await count('.prc-item')}`)
  check(/1 条批注$/.test(await summary() || ''), `审阅条 = ${await summary()}`)
  // 点空白处收起「+」再截图，标记更清楚
  await page.mouse.click((await lineBox(1)).x1 + 40, (await lineBox(1)).y)
  await sleep(300)
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-1-marks.png` })

  // 按修订版推进
  await clickSel('.prb-bar .prb-primary')
  for (let i = 0; i < 60 && !chatBodies.length; i++) await sleep(250)
  check(chatBodies.length === 1, `POST /api/agent/chat 次数 = ${chatBodies.length}`)
  const body = chatBodies[0] || {}
  const msg = String(body.message || '')
  check(msg.includes('修订版计划全文'), 'message 含「修订版计划全文」')
  check(msg.includes('针对「2. 增加审阅 REST 接口」：补充引用'), 'message 含批注「针对「…」：补充引用」')
  check(msg.includes('1. 新建数据表 plan_review（改）'), 'message 含修订后的第 2 行')
  check(!msg.includes('4. 写单测'), 'message 全文不含已删除行')
  check(msg.includes('共 2 处（+1 行 / -2 行）'), `message 改动摘要 = ${(msg.match(/改动摘要：[^\n]*/) || [''])[0]}`)
  check(body.displayText === '已按修订版推进（2 处改动、1 条批注）', `displayText = ${body.displayText}`)
  check(body.mode === 'AGENT', `mode = ${body.mode}`)
  check(body.conversationId === CONV, `conversationId 回到开审阅的会话 = ${body.conversationId}`)
  await page.waitForFunction(() => !document.querySelector('.prb-bar'), { timeout: 10000 }).catch(() => {})
  check(await count('.prb-bar') === 0, '提交后审阅条消失')
  check(await count('.cm-review-edited, .cm-review-deleted, .cm-review-commented') === 0, '提交后审阅标记清空')
  await sleep(500)
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-2-submitted.png` })
  const afterSubmit = await download(fid)
  check(afterSubmit.includes('（改）') && !afterSubmit.includes('4. 写单测'), '提交后文件是修订版')
  const st1 = await api(`/api/projects/${pid}/files/${fid}/review`)
  check(st1 === '' || st1 == null, `提交后 GET review 无 open 记录（${JSON.stringify(st1).slice(0, 80)}）`)

  // ---------------- 段二：放弃修改 ----------------
  // 文件先恢复成基线，再开一条新的 open 记录
  {
    const form = new FormData()
    form.append('file', new Blob([BASELINE], { type: 'text/markdown' }), FILE_NAME)
    await fetch(BACKEND + '/api/files/' + fid + '/upload', { method: 'POST', body: form })
  }
  await openReviewRecord(pid, fid, CONV)
  await enterReview()
  check((await summary()) === '0 处改动 · 0 条批注', `段二审阅条初始 = ${await summary()}`)
  await typeAtLineEnd(4, '（要放弃）')
  check(await count('.cm-review-edited') === 1, `段二改一行 .cm-review-edited = ${await count('.cm-review-edited')}`)
  await sleep(3800) // 等自动保存落盘（3s 防抖），放弃必须把已落盘的修订也写回
  const beforeDiscard = await download(fid)
  check(beforeDiscard.includes('（要放弃）'), '放弃前修订已自动保存到后端')
  // 再敲几个字、不等自动保存就放弃：挂着的 3s 防抖不许在写回基线之后把修订存回去
  await typeAtLineEnd(4, '未落盘')
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-3-before-discard.png` })
  await clickSel('.prb-bar .prb-btn:not(.prb-primary)')
  await page.waitForSelector('.awd-dlg', { visible: true, timeout: 5000 })
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-4-discard-confirm.png` })
  await clickSel('.awd-dlg .awd-dlg__confirm')
  await page.waitForFunction(() => !document.querySelector('.prb-bar'), { timeout: 10000 }).catch(() => {})
  check(await count('.prb-bar') === 0, '放弃后审阅条消失')
  await sleep(800)
  const editorText = await page.$$eval('.ptx-cm-host .cm-content .cm-line', (ls) => ls.map((l) => l.textContent).join('\n'))
  check(editorText === BASELINE, '放弃后编辑器内容 = 基线')
  const afterDiscard = await download(fid)
  check(afterDiscard === BASELINE, '放弃后 GET /download = 基线')
  await sleep(3800) // 放弃后不应再有迟到的自动保存把修订写回
  check((await download(fid)) === BASELINE, '放弃 3.8s 后文件仍 = 基线（无迟到保存）')
  check(chatBodies.length === 1, `放弃不发对话（chat 次数仍 = ${chatBodies.length}）`)
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-5-discarded.png` })

  // ---------------- 段三：宿主「打开修订」入口（标签已开着） ----------------
  // 计划卡按钮之后的那段接线：handleOpenPlanReviewTab → openFile(target,{review}) → tab.review →
  // PlainTextEditor 的 review watch → POST open。卡片按钮本身不在这里点（要一条带计划产物的助手消息），
  // 直接调工作台页面实例的同名方法，等价于 ChatInterface emit 了 open-review-tab。
  const artId = 'walk-art-seg3-' + Date.now()
  const called = await page.evaluate((fid, conv, art, base) => {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    const vm = pages.length ? pages[pages.length - 1].$vm : null
    if (!vm || typeof vm.handleOpenPlanReviewTab !== 'function') return false
    vm.handleOpenPlanReviewTab({ fileId: fid, name: 'Plan.md', review: { conversationId: conv, artifactId: art, baselineText: base } })
    return true
  }, fid, CONV, artId, BASELINE)
  check(called, '工作台页面实例上找到 handleOpenPlanReviewTab')
  const seg3Bar = await page.waitForSelector('.prb-bar', { visible: true, timeout: 15000 }).then(() => true).catch(() => false)
  check(seg3Bar, '「打开修订」入口：已开着的标签就地进审阅态')
  const rec3 = await api(`/api/projects/${pid}/files/${fid}/review`)
  check(!!(rec3 && rec3.review && rec3.review.status === 'open' && rec3.review.artifactId === artId && rec3.review.conversationId === CONV),
    `「打开修订」入口由编辑器 POST open 建出记录（artifactId=${rec3 && rec3.review && rec3.review.artifactId}）`)
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-6-open-from-host.png` })

  // ---------------- 段四：真点计划卡「打开修订」 ----------------
  // 段三留下的 open 记录先放弃掉（写回基线），再整页重载，从对话里的计划卡进
  await api(`/api/projects/${pid}/files/${fid}/review/discard`, { method: 'POST' })
  historyMode = 1
  resolveHits = 0
  const chatBase = chatBodies.length
  await page.goto('about:blank')
  await page.goto(BASE + '/#/pages/project-overview/project-overview?id=' + pid, { waitUntil: 'domcontentloaded', timeout: 60000 })
  const loadSeed = () => page.evaluate(async (conv) => {
    for (let i = 0; i < 100; i++) {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
      const vm = pages.length ? pages[pages.length - 1].$vm : null
      if (vm && typeof vm.loadHistoryChat === 'function' && typeof vm.resolveChatInterface === 'function') {
        const chat = await vm.resolveChatInterface()
        if (!chat) return 'no-chat'
        return await vm.loadHistoryChat({ conversationId: conv })
      }
      await new Promise((r) => setTimeout(r, 200))
    }
    return 'no-vm'
  }, SEED_CONV)
  const cardBox = (k, sel) => page.evaluate((i, s) => {
    const card = document.querySelectorAll('.artifact-card')[i]
    const el = card && card.querySelector(s)
    if (!el) return null
    el.scrollIntoView({ block: 'center' })
    const r = el.getBoundingClientRect()
    return r.width > 0 ? { x: r.x + r.width / 2, y: r.y + r.height / 2 } : null
  }, k, sel)
  const cardHas = async (k, sel) => !!(await cardBox(k, sel))
  const cardText = (k, sel) => page.evaluate((i, s) => {
    const card = document.querySelectorAll('.artifact-card')[i]
    return card ? [...card.querySelectorAll(s)].map((e) => e.textContent.trim()) : []
  }, k, sel)
  const clickCard = async (k, sel) => {
    const b = await cardBox(k, sel)
    if (!b) throw new Error(`card ${k} ${sel} not found`)
    await sleep(200)
    const b2 = await cardBox(k, sel)
    await page.mouse.click(b2.x, b2.y)
  }
  const seedRes = await loadSeed()
  check(seedRes === true, `种入的会话回放成功（loadHistoryChat = ${seedRes}）`)
  await page.waitForFunction(() => document.querySelectorAll('.artifact-card').length === 1, { timeout: 15000 }).catch(() => {})
  check(await count('.artifact-card') === 1, `对话里出现 1 张计划卡（${await count('.artifact-card')}）`)
  check(await cardHas(0, '.btn-revise'), '计划卡有「打开修订」按钮')
  await clickCard(0, '.btn-revise')
  const seg4Bar = await page.waitForSelector('.prb-bar', { visible: true, timeout: 20000 }).then(() => true).catch(() => false)
  check(seg4Bar, '点计划卡「打开修订」后标签进审阅态')
  check(resolveHits >= 1, `历史回放卡按路径反查 fileId（GET files/resolve ${resolveHits} 次）`)
  await page.waitForSelector('.ptx-cm-host .cm-content .cm-line', { visible: true, timeout: 20000 })
  await sleep(600)
  const recA = await api(`/api/projects/${pid}/files/${fid}/review`)
  check(!!(recA && recA.review && recA.review.status === 'open' && recA.review.conversationId === SEED_CONV),
    `卡片入口建出的审阅记录归属种入的会话（conversationId=${recA && recA.review && recA.review.conversationId}）`)
  check(!!(recA && recA.review && recA.review.baselineText === BASELINE), '审阅基线 = 文件真字节')
  let chip = await cardText(0, '.status-badge.revised')
  check(chip.includes('修订中 · 0 处改动 · 0 条批注'), `卡片 chip = ${JSON.stringify(chip)}`)
  await typeAtLineEnd(2, '（卡片修订）')
  await sleep(600)
  chip = await cardText(0, '.status-badge.revised')
  check(chip.includes('修订中 · 1 处改动 · 0 条批注'), `改一行后卡片 chip = ${JSON.stringify(chip)}`)
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-7-card-reviewing.png` })
  await clickSel('.prb-bar .prb-primary')
  for (let i = 0; i < 60 && chatBodies.length === chatBase; i++) await sleep(250)
  check(chatBodies.length === chatBase + 1, `卡片入口提交发出 1 次对话（${chatBodies.length - chatBase}）`)
  const body4 = chatBodies[chatBase] || {}
  check(body4.conversationId === SEED_CONV, `回喂落回种入的会话 = ${body4.conversationId}`)
  check(String(body4.displayText || '').startsWith('已按修订版推进'), `displayText = ${body4.displayText}`)
  await page.waitForFunction(() => !document.querySelector('.prb-bar'), { timeout: 10000 }).catch(() => {})
  check(await count('.prb-bar') === 0, '提交后审阅条消失')
  await sleep(800)
  check(!(await cardHas(0, '.btn-revise')), '这张卡提交后不再有按钮')
  const badgesA = await cardText(0, '.status-badge')
  check(badgesA.includes('已确认执行') && badgesA.includes('已修订计划'), `这张卡变成已修订（${JSON.stringify(badgesA)}）`)
  const stA = await api(`/api/projects/${pid}/files/${fid}/review`)
  check(stA === '' || stA == null, '提交后记录已落库（GET review 无 open 记录）')
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-8-card-submitted.png` })

  // I-1：模型重出第二份计划，同名复用 Plan.md（同一 fileId）。回放后第二张卡是最新那条，
  // 点它「打开修订」→ 反查到同一个 fileId → 上一张卡的 submitted 不许把它置为已完成
  historyMode = 2
  const seedRes2 = await loadSeed()
  check(seedRes2 === true, `回放含第二份计划的会话（${seedRes2}）`)
  await page.waitForFunction(() => document.querySelectorAll('.artifact-card').length === 2, { timeout: 15000 }).catch(() => {})
  check(await count('.artifact-card') === 2, `对话里 2 张计划卡（${await count('.artifact-card')}）`)
  check(await cardHas(1, '.btn-revise') && await cardHas(1, '.btn-approve'), '第二张卡有「按此推进 / 打开修订」')
  const resolveBefore = resolveHits
  await clickCard(1, '.btn-revise')
  const seg4Bar2 = await page.waitForSelector('.prb-bar', { visible: true, timeout: 20000 }).then(() => true).catch(() => false)
  check(seg4Bar2, '第二张卡「打开修订」进审阅态')
  check(resolveHits > resolveBefore, `第二张卡也按路径反查（GET files/resolve +${resolveHits - resolveBefore}）`)
  await sleep(1000)
  check(await cardHas(1, '.btn-revise') && await cardHas(1, '.btn-approve'), '同一 fileId 的第二张卡仍有按钮（I-1）')
  const badgesB = await cardText(1, '.status-badge')
  check(!badgesB.includes('已确认执行'), `第二张卡没被上一份的 submitted 置为已完成（${JSON.stringify(badgesB)}）`)
  check(badgesB.includes('修订中 · 0 处改动 · 0 条批注'), '第二张卡显示自己的「修订中」')
  await page.screenshot({ path: `${OUT}/${TAG}-plan-review-9-second-card.png` })
  historyMode = 0
  check(pageErrors.length === 0, `页面未捕获异常 ${pageErrors.length} 条${pageErrors.length ? '：' + pageErrors.slice(0, 3).join(' | ') : ''}`)
} catch (e) {
  console.log('ERROR', e.stack)
  failed = true
  try { const pg = (await browser.pages()).pop(); await pg.screenshot({ path: `${OUT}/${TAG}-error.png` }) } catch (x) { console.log(x.message) }
} finally {
  await browser.close()
  const d = await fetch(BACKEND + '/api/projects/' + pid, { method: 'DELETE' })
  console.log('DELETE project', pid, d.status)
}
console.log(failed ? 'RESULT FAIL' : 'RESULT PASS')
process.exit(failed ? 1 : 0)

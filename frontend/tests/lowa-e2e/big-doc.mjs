#!/usr/bin/env node
// LOWA 大文档基线组 / big-document baseline (dev-board#108)。
//
// 在真引擎上加载 150 页 / 30 表 / 20 图（约 6.7MB）的夹具，对几条「尽调报告工况」
// 里最容易撞超时的命令逐项计时，每项跑 3 轮取中位数，与硬阈比较；未达阈值退出码 1。
// 耗时是 worker 往返（performance.now() 包在 page.evaluate 内部），大字段在页内裁掉
// 再跨界，CDP 序列化不计入。
//
// Run:  npm run test:lowa-big            (from frontend/)
// 夹具：python3 tests/lowa-e2e/fixtures/gen-big-doc.py（依赖 python-docx pillow）
//       默认写到 $TMPDIR/awd-big-doc/big.docx；LOWA_BIG_DOC 可指向别的文件。
// Env:  同 run.mjs（LOWA_ENGINE_DIR / PUPPETEER_EXECUTABLE_PATH / LOWA_E2E_PORT）；
//       LOWA_BIG_RUNS 轮数（默认 3）；LOWA_BIG_QUIET_MS 导出后静默观察窗（默认 30000）。

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { here, preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

const RUNS = Math.max(1, Number(process.env.LOWA_BIG_RUNS || 3))
const QUIET_MS = Number(process.env.LOWA_BIG_QUIET_MS || 30000)
const EXPECTED_HITS = 150
const EXPECTED_PARAS = 920

// ---------- 夹具 ----------
let bigDoc = process.env.LOWA_BIG_DOC || path.join(os.tmpdir(), 'awd-big-doc', 'big.docx')
if (!fs.existsSync(bigDoc)) {
  console.log('夹具不存在，生成中: ' + bigDoc)
  try {
    bigDoc = execFileSync('python3', [path.join(here, 'fixtures/gen-big-doc.py'), '--out', bigDoc], { encoding: 'utf8' }).trim()
  } catch (e) {
    console.error('夹具生成失败：python3 -m pip install --user python-docx pillow 后重试')
    process.exit(2)
  }
}
preflight([['big.docx 夹具 (tests/lowa-e2e/fixtures/gen-big-doc.py)', bigDoc]])
const puppeteer = await loadPuppeteer()

// ---------- 测试专用 worker 动作（内存注入，同 run.mjs 机制） ----------
// debug_modified_count：数 worker 往宿主发了多少次 'modified'——导出后 30s 内必须为 0
// （autosave 死循环回归，见 installModifyListener 的 exportInFlight 注释）。
const DEBUG_ACTIONS = `
  debug_modified_count() { return { success: true, count: MOD_COUNT }; },
`
function patchServed(urlPath, content) {
  if (urlPath === '/office_thread.js') {
    let s = content.toString('utf8')
    for (const anchor of ['const EXEC = {', 'function installModifyListener(model) {', "if (model.isModified()) post('modified');"]) {
      if (!s.includes(anchor)) throw new Error('office_thread.js: anchor missing: ' + anchor)
    }
    s = s.replace('const EXEC = {', 'const EXEC = {\n' + DEBUG_ACTIONS)
      .replace('function installModifyListener(model) {', 'let MOD_COUNT = 0;\nfunction installModifyListener(model) {')
      .replace("if (model.isModified()) post('modified');", "if (model.isModified()) { MOD_COUNT++; post('modified'); }")
    return Buffer.from(s, 'utf8')
  }
  if (/^\/assets\/editor-.*\.js$/.test(urlPath)) {
    const s = content.toString('utf8')
    return Buffer.from(
      s.replace("'get_hyperlink_at_cursor'", "'get_hyperlink_at_cursor','debug_modified_count'")
        .replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_modified_count"'),
      'utf8')
  }
  return content
}

const server = await startServer({ patchServed, extraFiles: { '/big.docx': bigDoc } })

// ---------- 阈值 ----------
// 单位 ms。另做结果形状断言（replaced 数 / truncated 字段 / modified 次数）。全部硬阈。
const ITEMS = [
  { key: 'load_document', label: 'load_document 6.7MB/150 页', max: 15000 },
  { key: 'get_document_text_2nd', label: 'get_document_text 第 2 次（同参数）', max: 300 },
  { key: 'find_replace_150', label: 'find_replace 修订 150 命中', max: 8000 },
  { key: 'apply_house_style', label: 'apply_house_style 920 段 + 30 表', max: 120000 },
  { key: 'export_document', label: 'export_document', max: 10000 },
  { key: 'quiet_after_export', label: '导出后 ' + (QUIET_MS / 1000) + 's 内 modified 次数', max: 0, unit: '次' },
]

const samples = {}       // key -> number[]
const problems = []      // 形状断言失败
function record(key, v) { (samples[key] = samples[key] || []).push(v) }
function median(a) { const s = [...a].sort((x, y) => x - y); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2 }
function fmt(v, unit) { return unit === '次' ? String(v) : (v >= 1000 ? (v / 1000).toFixed(2) + 's' : Math.round(v) + 'ms') }

// ---------- drive ----------
const browser = await launchBrowser(puppeteer)
try {
  const page = await openEditor(browser, { clipboard: false })
  // 页内计时：返回 {ms, r}，r 已裁掉 bytes/paragraphs/matches 等大字段。
  await page.evaluate(() => {
    window.__timed = async (action, params) => {
      const t0 = performance.now()
      let r
      try { r = await window.__loExecutor.executeCommand(action, params || {}) }
      catch (e) { r = { success: false, message: String(e && e.message || e), timeout: true } }
      const ms = performance.now() - t0
      const slim = {}
      for (const k of Object.keys(r || {})) {
        const v = r[k]
        if (k === 'bytes' || k === 'paragraphs' || k === 'matches') slim[k + 'Length'] = v && v.length
        else if (typeof v === 'string') slim[k] = v.slice(0, 120)
        else slim[k] = v
      }
      return { ms, r: slim }
    }
    window.__bigBytes = null
  })
  const timed = (a, p) => page.evaluate((a2, p2) => window.__timed(a2, p2), a, p)
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

  for (let run = 1; run <= RUNS; run++) {
    console.log('\n---- 第 ' + run + '/' + RUNS + ' 轮 ----')
    // load_document：字节在页内取（fetch 同源），计时只包 executeCommand
    const load = await page.evaluate(async () => {
      if (!window.__bigBytes) {
        const buf = await (await fetch('/big.docx')).arrayBuffer()
        window.__bigBytes = new Uint8Array(buf)
      }
      const t0 = performance.now()
      const r = await window.__loExecutor.executeCommand('load_document', { bytes: window.__bigBytes, name: 'big.docx' })
      return { ms: performance.now() - t0, r: { success: r && r.success, kind: r && r.kind, message: r && r.message } }
    })
    console.log('  load_document: ' + fmt(load.ms) + ' ' + JSON.stringify(load.r))
    if (!load.r.success) { problems.push('第 ' + run + ' 轮 load_document 失败: ' + JSON.stringify(load.r)); continue }
    record('load_document', load.ms)

    // get_document_text：第 1 次冷读（只打印），第 2 次同参数计时
    const g1 = await timed('get_document_text', { startParagraph: 0, maxParagraphs: 200 })
    const g2 = await timed('get_document_text', { startParagraph: 0, maxParagraphs: 200 })
    console.log('  get_document_text: 1st ' + fmt(g1.ms) + ', 2nd ' + fmt(g2.ms) + ' total=' + g2.r.totalParagraphs + ' returned=' + g2.r.returned)
    record('get_document_text_2nd', g2.ms)
    if (g2.r.totalParagraphs < EXPECTED_PARAS) problems.push('第 ' + run + ' 轮 totalParagraphs=' + g2.r.totalParagraphs + ' < ' + EXPECTED_PARAS)
    // 跳页读也要走索引（O(窗口) 而非 O(n)），只打印不设阈
    const g3 = await timed('get_document_text', { startParagraph: 800, maxParagraphs: 50 })
    console.log('  get_document_text {start:800}: ' + fmt(g3.ms) + ' returned=' + g3.r.returned)

    // find_replace 修订模式 150 命中（夹具每页首段各一处「目标公司」）
    const fr = await timed('find_replace', { findText: '目标公司', replaceText: '标的公司', replaceAll: true, __agent: true })
    console.log('  find_replace: ' + fmt(fr.ms) + ' ' + JSON.stringify(fr.r))
    record('find_replace_150', fr.ms)
    if (fr.r.replaced !== EXPECTED_HITS) problems.push('第 ' + run + ' 轮 find_replace replaced=' + fr.r.replaced + ' (期望 ' + EXPECTED_HITS + ')')

    // apply_house_style 全文
    const hs = await timed('apply_house_style', { __agent: true })
    console.log('  apply_house_style: ' + fmt(hs.ms) + ' ' + JSON.stringify(hs.r))
    record('apply_house_style', hs.ms)
    if (hs.r.success !== true) problems.push('第 ' + run + ' 轮 apply_house_style 未成功: ' + JSON.stringify(hs.r))
    if (hs.r.truncated !== false) problems.push('第 ' + run + ' 轮 apply_house_style truncated 字段不是 false: ' + JSON.stringify(hs.r.truncated))
    // 改造前 apply_house_style 会在执行器侧超时而 worker 仍在跑：等 worker 真正空闲
    // 再量导出，免得把排队时间算进 export_document。
    const drainT0 = Date.now()
    for (;;) { const k = await timed('get_doc_kind'); if (k.r.success || Date.now() - drainT0 > 600000) break }
    if (hs.r.success !== true) console.log('  （worker 真正跑完 apply_house_style 共约 ' + fmt(hs.ms + (Date.now() - drainT0)) + '）')

    // export_document
    const ex = await timed('export_document', { name: 'big.docx' })
    console.log('  export_document: ' + fmt(ex.ms) + ' size=' + (ex.r.size || ex.r.bytesLength))
    record('export_document', ex.ms)
    if (ex.r.success !== true) problems.push('第 ' + run + ' 轮 export_document 失败: ' + JSON.stringify(ex.r))

    // 导出后静默窗：不该再冒 modified（否则宿主会再排一次保存，形成循环）
    const before = (await timed('debug_modified_count')).r.count
    await sleep(QUIET_MS)
    const after = (await timed('debug_modified_count')).r.count
    console.log('  导出后 ' + QUIET_MS / 1000 + 's modified 次数: ' + (after - before))
    record('quiet_after_export', after - before)
  }

  let mem = null
  try {
    mem = await page.evaluate(async () => performance.measureUserAgentSpecificMemory ? (await performance.measureUserAgentSpecificMemory()).bytes : null)
  } catch (e) { /* 无跨源隔离或不支持 */ }

  // ---------- 汇总 ----------
  let failed = 0
  console.log('\n| 项 | 中位数 | 各轮 | 硬阈 | 结果 |')
  console.log('|---|---|---|---|---|')
  for (const it of ITEMS) {
    const xs = samples[it.key] || []
    if (!xs.length) { failed++; console.log('| ' + it.label + ' | - | 无样本 | ' + fmt(it.max, it.unit) + ' | FAIL |'); continue }
    const med = median(xs)
    const ok = it.unit === '次' ? med <= it.max : med < it.max
    if (!ok) failed++
    console.log('| ' + it.label + ' | ' + fmt(med, it.unit) + ' | ' + xs.map((x) => fmt(x, it.unit)).join(' / ') + ' | ' + (it.unit === '次' ? '= 0' : '< ' + fmt(it.max)) + ' | ' + (ok ? 'PASS' : 'FAIL') + ' |')
  }
  for (const p of problems) { failed++; console.log('  FAIL ' + p) }
  if (mem != null) console.log('\n页面内存（measureUserAgentSpecificMemory）: ' + (mem / 1048576).toFixed(0) + ' MB')
  console.log('\n结果 / result: ' + (failed ? failed + ' 项未达阈值' : '全部达标'))
  process.exitCode = failed ? 1 : 0
} finally {
  await browser.close()
  server.close()
}
process.exit(process.exitCode || 0)

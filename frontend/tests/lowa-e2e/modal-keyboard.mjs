#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真引擎回归（dev-board#883，S1）：宿主弹窗开着时，键盘输入不许进到背景文档。
//
// 真机现象（v0.48.0，两次含冷启动）：版本详情弹窗开着 → 按 Tab → 焦点跳进背景的
// LibreOffice 画布（IME 覆盖层输入框）→ 键入的探针带修订写进文档并被自动保存。
// 根因：宿主自绘弹窗（.awd-mask / .awd-dialog-mask …）只有遮罩没有焦点圈；弹窗里的
// 按钮是 <view>（不可聚焦），Tab 沿宿主文档顺序走到下一个可聚焦元素——编辑器的
// <webview>/<iframe>，焦点就进了客体页。遮罩挡得住鼠标，挡不住键盘。
// 修法在公共层：utils/modalFocusGuard.js（App.vue onLaunch 装一次），凡是「编辑器
// 框体被固定定位的遮罩整个盖住」就把 Tab 圈在遮罩内、把闯进框体的焦点拉回遮罩。
//
// 本用例的宿主页是一张壳：编辑器用真引擎（同源 iframe，即 Web 态的 iframeTransport
// 形态），守卫是 src/utils/modalFocusGuard.js 原文件，弹窗是两种真实结构的复刻
// （版本详情：遮罩 + 不可聚焦的按钮；标签管理：遮罩 + 真 input/button）。
// 桌面端的 <webview> 走同一个守卫，但本用例覆盖不到（无头跑不了 Electron）。
//
// Run:  npm run test:lowa-modal-keyboard   (from frontend/)
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { preflight, loadPuppeteer, startServer, launchBrowser, ORIGIN, here } from './_boot.mjs'

const GUARD_SRC = path.resolve(here, '../../src/utils/modalFocusGuard.js')

const HOST_HTML = `<!doctype html><html><head><meta charset="utf-8"><title>modal host</title>
<style>
  html,body{margin:0;height:100%;font:14px sans-serif}
  uni-view,uni-input{display:block} /* 与 uni-h5 自带样式一致 */
  .rail{position:absolute;left:0;top:0;width:180px;bottom:0;background:#eee}
  .node{margin:20px;padding:8px;background:#fff;cursor:pointer}
  #ed{position:absolute;left:200px;top:10px;width:820px;height:640px;border:0}
  .chat{position:absolute;left:1040px;top:10px;width:220px}
  .awd-mask,.awd-dialog-mask{position:fixed;inset:0;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;z-index:9999}
  .awd-dialog{width:460px;background:#fff;border-radius:12px;padding:16px}
  .btn{display:inline-block;padding:8px 14px;border:1px solid #999;margin:4px;cursor:pointer}
</style></head><body>
<div class="rail"><uni-view class="node" id="node">QA版本闭环-日期改为10月2日</uni-view><uni-view class="node" id="tagOpen">管理标签</uni-view></div>
<iframe id="ed" src="/editor.html?verify=1&lowa=/lowa/" allow="clipboard-read; clipboard-write"></iframe>
<div class="chat"><textarea id="chat" rows="4" placeholder="问点什么"></textarea></div>
<script>
  // 复刻 VersionNodeDetail.vue 的结构：遮罩 + 标题 + <view> 按钮（都不可聚焦）；
  // 「标为重要版本」再开一层带 input 的命名遮罩（嵌套弹窗）。
  function openVersion() {
    const m = document.createElement('uni-view'); m.className = 'awd-mask'; m.id = 'vmask'
    m.innerHTML = '<uni-view class="awd-dialog"><uni-text>QA版本闭环-日期改为10月2日</uni-text><br>'
      + '<uni-view class="btn" id="vclose">关闭</uni-view><uni-view class="btn" id="vmile">标为重要版本</uni-view>'
      + '<uni-view class="btn">从这一版另起一稿</uni-view><uni-view class="btn">退回到这一版</uni-view></uni-view>'
    m.querySelector('#vclose').addEventListener('click', () => m.remove())
    m.querySelector('#vmile').addEventListener('click', () => {
      const n = document.createElement('uni-view'); n.className = 'awd-mask'; n.id = 'nmask'
      n.innerHTML = '<uni-view class="awd-dialog"><uni-input><div><input id="mname" class="uni-input-input"></div></uni-input>'
        + '<uni-view class="btn" id="ncancel">取消</uni-view></uni-view>'
      n.querySelector('#ncancel').addEventListener('click', () => n.remove())
      m.appendChild(n)
    })
    document.body.appendChild(m)
  }
  // 复刻 FileTree.vue 里 TagManager 那层：.awd-dialog-mask(z 3100) + 真 input / button。
  function openTags() {
    const m = document.createElement('uni-view'); m.className = 'awd-dialog-mask'; m.id = 'tmask'; m.style.zIndex = '3100'
    m.innerHTML = '<uni-view class="awd-dialog"><uni-view class="btn" id="tclose">×</uni-view>'
      + '<uni-input><div><input id="tname" class="uni-input-input" placeholder="标签名"></div></uni-input>'
      + '<uni-view class="btn" style="width:20px;height:20px;background:#c33"></uni-view>'
      + '<button id="tadd">添加</button></uni-view>'
    m.querySelector('#tclose').addEventListener('click', () => m.remove())
    document.body.appendChild(m)
  }
  document.getElementById('node').addEventListener('click', openVersion)
  document.getElementById('tagOpen').addEventListener('click', openTags)
  window.__openVersion = openVersion
</script>
<script type="module">
  // 与 App.vue 同一个入口；文件不存在（修复前）时壳照常跑，用来跑出红。
  try { const m = await import('/modalFocusGuard.js'); m.installModalFocusGuard(); window.__guard = 'installed' }
  catch (e) { window.__guard = 'absent: ' + e.message }
</script>
</body></html>`

preflight()
const hostFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'awd-modal-')), 'modal-host.html')
fs.writeFileSync(hostFile, HOST_HTML)
const extraFiles = { '/modal-host.html': hostFile }
if (fs.existsSync(GUARD_SRC)) extraFiles['/modalFocusGuard.js'] = GUARD_SRC
const server = await startServer({ extraFiles })
const browser = await launchBrowser(await loadPuppeteer())
let failures = 0
const check = (label, ok, detail = '') => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  — ' + detail : ''))
  if (!ok) failures++
}
const settle = (ms = 800) => new Promise((r) => setTimeout(r, ms))
try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1280, height: 720 })
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  await page.goto(ORIGIN + '/modal-host.html', { waitUntil: 'domcontentloaded' })
  console.log('booting engine in iframe (~90s)...')
  await page.waitForFunction(() => { const f = document.getElementById('ed'); return !!(f && f.contentWindow && f.contentWindow.__loExecutor) }, { timeout: 240000 })
  console.log('guard: ' + await page.evaluate(() => window.__guard))
  const exec = (a, p = {}) => page.evaluate((a2, p2) => document.getElementById('ed').contentWindow.__loExecutor.executeCommand(a2, p2), a, p)
  const docText = async () => (await exec('get_document_text', {})).paragraphs.map((x) => x.text).join('\n')
  // 焦点落在哪：宿主的 activeElement；落在框体里时再看客体页里是谁
  const where = () => page.evaluate(() => {
    const a = document.activeElement
    if (!a) return 'none'
    if (a.tagName === 'IFRAME') {
      const g = a.contentDocument && a.contentDocument.activeElement
      return 'IFRAME>' + (g ? (g.tagName + (g.hasAttribute('data-lo-ime') ? '[ime]' : '')) : '?')
    }
    return a.tagName + (a.id ? '#' + a.id : '') + (a.closest('.awd-mask,.awd-dialog-mask') ? '(in-mask)' : '')
  })
  const box = await (await page.$('#ed')).boundingBox()
  const clickEditor = async () => {
    await page.mouse.click(box.x + box.width / 2, box.y + 200)
    await settle(600)
  }
  const clickEl = async (sel) => {
    const b = await page.evaluate((s) => { const r = document.querySelector(s).getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 } }, sel)
    await page.mouse.click(b.x, b.y)
    await settle(300)
  }
  const resetDoc = async () => {
    await exec('set_track_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '甲方应于十日内付款。' })
    await exec('goto', { type: 'end' })
    await exec('set_track_changes', { on: true })
  }

  // 0) 防空断言：没有弹窗时，这张壳里真实键盘确实写得进文档——否则下面的
  //    「文档里没有探针」什么都证明不了。
  await resetDoc()
  await clickEditor()
  await page.keyboard.type('SANITY')
  await settle(1200)
  check('前置：无弹窗时键盘能写进文档（壳的键盘链路是通的）', (await docText()).includes('SANITY'), await where())

  // 1) 真机步骤：光标在正文 → 点版本节点开详情弹窗 → Tab → 键入探针 → 关弹窗
  await resetDoc()
  await clickEditor()
  await clickEl('#node')
  check('前置：版本详情弹窗已打开', await page.evaluate(() => !!document.getElementById('vmask')))
  await page.keyboard.press('Tab')
  await settle(300)
  const afterTab = await where()
  check('Tab 之后焦点不在编辑器框体里', !afterTab.startsWith('IFRAME'), afterTab)
  await page.keyboard.type('MODAL-PROBE')
  await page.keyboard.press('Enter')
  await page.keyboard.down('Shift'); await page.keyboard.press('Tab'); await page.keyboard.up('Shift')
  await settle(300)
  const afterShiftTab = await where()
  check('Shift+Tab 之后焦点也不在编辑器框体里', !afterShiftTab.startsWith('IFRAME'), afterShiftTab)
  await page.keyboard.type('SHIFT-PROBE')
  for (let i = 0; i < 4; i++) await page.keyboard.press('Tab')
  await page.keyboard.type('MULTI-PROBE')
  await clickEl('#vclose')
  await settle(1500)
  const t1 = await docText()
  check('版本详情弹窗：Tab/Shift+Tab/字符/Enter 一个都没进背景文档',
    !/PROBE/.test(t1) && t1 === '甲方应于十日内付款。', JSON.stringify(t1))

  // 2) 嵌套弹窗（版本详情 → 标为重要版本的命名框）：Tab 圈在内层，字打进命名框
  await clickEditor()
  await clickEl('#node')
  await clickEl('#vmile')
  await clickEl('#mname')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Tab')
  await settle(200)
  const inNested = await where()
  check('嵌套命名框：连按 Tab 焦点仍在命名框', inNested.startsWith('INPUT#mname'), inNested)
  await page.keyboard.type('NESTED-PROBE')
  check('嵌套命名框：字打进了命名框', await page.evaluate(() => document.getElementById('mname').value) === 'NESTED-PROBE')
  await clickEl('#ncancel')
  await clickEl('#vclose')
  await settle(1200)
  check('嵌套弹窗：背景文档不变', !/PROBE/.test(await docText()), await docText())

  // 3) 标签管理（真 input/button）：Tab 在弹窗内遍历，不溜进编辑器
  await clickEditor()
  await clickEl('#tagOpen')
  await clickEl('#tname')
  const seen = []
  for (let i = 0; i < 5; i++) { await page.keyboard.press('Tab'); await settle(120); seen.push(await where()) }
  check('标签管理：连按 5 次 Tab 焦点始终在弹窗内', seen.every((s) => s.endsWith('(in-mask)')), seen.join(' | '))
  await page.keyboard.type('TAG-PROBE')
  await clickEl('#tclose')
  await settle(1200)
  check('标签管理：背景文档不变', !/PROBE/.test(await docText()), await docText())

  // 4) 弹窗在用户正打字时弹出来（焦点本来就在编辑器里）：焦点被拉出框体，
  //    关掉之后还回编辑器，接着打字照常写进文档。
  await resetDoc()
  await clickEditor()
  await page.evaluate(() => window.__openVersion())
  await settle(400)
  const pulled = await where()
  check('弹窗弹出时焦点被拉出编辑器框体', !pulled.startsWith('IFRAME'), pulled)
  await page.keyboard.type('POPUP-PROBE')
  await clickEl('#vclose')
  await settle(600)
  const restored = await where()
  check('弹窗关闭后焦点还回编辑器', restored.startsWith('IFRAME'), restored)
  await page.keyboard.type('AFTER')
  await settle(1200)
  const t4 = await docText()
  check('弹窗期间的字没进文档，关掉后的字进了文档', !/PROBE/.test(t4) && t4.includes('AFTER'), JSON.stringify(t4))
} finally {
  await browser.close()
  server.close()
}
console.log(failures ? '\n' + failures + ' FAILED' : '\nall passed')
process.exit(failures ? 1 : 0)

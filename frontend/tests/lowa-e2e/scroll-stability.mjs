// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 滚动稳定性（dev-board#604）：在默认的「全部修订」内联视图下往下滚动文档，视口
// 必须停在用户滚到的地方。
//
// WHY：默认视图变成 'all' 之后，即时审校 / 写作补全的高频只读命令
// （FINAL_TEXT_ACTIONS）每次都要临时切到「最终文本语义」的视图再切回来。
// 本引擎（24.2.8-zhcn-r5）上 ShowChangesInMargin 是**控制器的视图设置**，每写一次
// 引擎就把视图滚回光标；用户滚到第三页、光标还在文首，180ms 后视口就被拽回开头。
// 真机实测：切「页边」top 44880 → 0，切「最终稿」（RedlineDisplayType，模型属性）
// top 21840 → 21840 一动不动，两者读回的正文/段号/偏移完全一致。
//
// 视口读数走生产动作 get_review_layout（.view.top 来自 ctrl.getViewData()，单位
// twip），它本身不在 FINAL_TEXT_ACTIONS 里、实测不会挪动视口。
//
// Run:  npm run test:lowa-scroll     (from frontend/)
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const ok = async (a, p) => { const r = await exec(a, p); assert.equal(r?.success, true, a + ': ' + JSON.stringify(r)); return r }

  // ---------- 多页 + 带修订的夹具 ----------
  const run = (t) => `<w:r><w:t>${t}</w:t></w:r>`
  const paras = []
  for (let i = 0; i < 140; i++) {
    paras.push(i % 25 === 7
      ? `<w:p>${run('第' + i + '段前置，')}<w:del w:id="${900 + i}" w:author="甲审阅人" w:date="2026-09-10T08:00:00Z"><w:r><w:delText>旧表述</w:delText></w:r></w:del><w:ins w:id="${800 + i}" w:author="甲审阅人" w:date="2026-09-10T08:00:00Z">${run('新表述')}</w:ins>${run('，后续。')}</w:p>`
      : `<w:p>${run('第' + i + '段：本段为多页文档的填充正文，用于把视口推离文首位置。')}</w:p>`)
  }
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paras.join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
  await ok('load_document', { name: 'scroll-604.docx', bytes: Array.from(await zip.generateAsync({ type: 'uint8array' })) })

  const view = await ok('set_revision_view', {})
  assert.equal(view.mode, 'all', '本组的前提是默认内联视图；默认改了就该重写本组')
  assert.equal((await ok('list_revisions', { limit: 50, locate: false })).count, 12, '夹具带修订')

  // 视口读数（twip）。available:false 说明引擎没有 r5 的审阅几何，本组无从测量。
  async function top() {
    const r = await ok('get_review_layout', { fresh: false })
    assert.equal(r.available, true, 'get_review_layout 不可用（需要 r5 引擎）: ' + JSON.stringify(r))
    assert.equal(typeof r.view?.top, 'number', '拿不到视口: ' + JSON.stringify(r.view))
    return r.view.top
  }

  const box = await page.evaluate(() => { const r = document.getElementById('qtcanvas').getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height } })
  await page.mouse.move(box.x + box.w / 2, box.y + box.h / 2)
  // 真滚轮，和人一样。每次都重新滚——上一步若把视口打回文首，下一步就测不到东西了。
  async function scrollAway(label) {
    for (let i = 0; i < 8; i++) { await page.mouse.wheel({ deltaY: 400 }); await new Promise((r) => setTimeout(r, 100)) }
    await new Promise((r) => setTimeout(r, 400))
    const t = await top()
    // 防空断言：执行前视口必须确实已离开文首，否则「没跳」是假绿。
    assert.ok(t > 5000, label + '：滚轮没把视口推离文首（top=' + t + '），本步测不出回顶')
    return t
  }

  // 光标留在文首（用户只是滚动、没点过画布）——这正是「跳回开头」的现场。
  await ok('select_paragraph', { index: 0 })
  await ok('collapse_selection', { to: 'start' })

  let before = await scrollAway('get_review_context')
  const ctx = await ok('get_review_context')
  assert.equal(await top(), before, '即时审校读上下文后视口被拽回光标（dev-board#604）')
  assert.equal(ctx.paragraphIndex, 0)

  before = await scrollAway('get_completion_context')
  await ok('get_completion_context', { radius: 160 })
  assert.equal(await top(), before, '写作补全读上下文后视口被拽回光标')

  before = await scrollAway('__agent 读取')
  await ok('get_document_text', { __agent: true })
  assert.equal(await top(), before, 'AI 读正文后视口被拽回光标')

  // 真实链路：客体页的即时审校在滚动后 180ms 自己打一条 get_review_context。
  await page.evaluate(() => window.postMessage({ __lo: 'lo-relay', type: 'inline-review-state', session: 'scroll-604',
    enabled: true, writable: true, revision: 0, status: 'ready', deepStatus: 'idle', findings: [] }, location.origin))
  before = await scrollAway('客体页自发的审校刷新')
  await new Promise((r) => setTimeout(r, 1200))
  assert.equal(await top(), before, '滚完静置一秒，视口自己跳回了开头（用户报的现象）')

  // 语义没被改掉：最终文本仍不含被删的旧字，视图也还是用户选的那个。
  await ok('select_paragraph', { index: 7 })
  await ok('collapse_selection', { to: 'end' })
  const revised = await ok('get_review_context')
  assert.equal(revised.text, '第7段前置，新表述，后续。', '只读命令必须仍按最终文本语义返回正文')
  assert.equal((await ok('get_document_text', { __agent: true })).paragraphs[7].text, '第7段前置，新表述，后续。')
  assert.equal((await ok('set_revision_view', {})).mode, 'all', '跑完必须还原用户所选的显示态')
  assert.equal((await ok('list_revisions', { limit: 50, locate: false })).count, 12, '只读命令不得动修订')

  // ---------- 光标不左右横跳（dev-board#725）----------
  // Writer 把光标的**屏幕**坐标缓存着，只在下一次读正文时按当时的视图几何重算。
  // 守卫最后一次读正文发生在「删除文字被藏起来」的最终文本视图里，于是命令返回
  // 之后光标一直画在左边一个删除段的宽度上（真机实测 −2142 twip），用户再敲一个
  // 字才弹回原处——这一左一右就是「光标经常左右横跳」。
  const caretX = async () => (await ok('get_review_layout', { fresh: false })).view.caretX

  // 防空断言：这一段必须真的带删除修订，否则「光标不动」是白拿的。
  const inline7 = (await ok('get_paragraph', { index: 7 })).text
  assert.ok(inline7.includes('旧表述'), '夹具第 7 段必须带删除修订（内联正文里看得见旧字）：' + inline7)

  await ok('select_paragraph', { index: 7 })
  await ok('collapse_selection', { to: 'end' })
  const settled = await caretX()
  const seen = new Set()
  for (let i = 0; i < 20; i++) {
    await ok('get_completion_context', { radius: 160 }); seen.add(await caretX())
    await ok('get_review_context'); seen.add(await caretX())
  }
  assert.deepEqual([...seen], [settled],
    '40 次只读取上下文之后光标横向漂了（dev-board#725）：settle=' + settled + ' 实测=' + JSON.stringify([...seen]))

  // 用户真正的节奏：敲一个字，紧随其后的补全/审校读一次上下文。
  for (let i = 0; i < 3; i++) {
    await ok('insert_at_cursor', { text: '甲' })
    const typed = await caretX()
    await ok('get_completion_context', { radius: 160 })
    assert.equal(await caretX(), typed, '第 ' + i + ' 个字打完，补全读取把光标拽走了（dev-board#725）')
    await ok('get_review_context')
    assert.equal(await caretX(), typed, '第 ' + i + ' 个字打完，审校读取把光标拽走了（dev-board#725）')
  }
  await ok('undo'); await ok('undo'); await ok('undo')

  // 语义护栏：守卫内部不再 refresh()，读到的最终文本必须与「显式切最终稿再读」逐字相等。
  await ok('select_paragraph', { index: 7 })
  await ok('collapse_selection', { to: 'end' })
  const guarded = (await ok('get_review_context')).text
  await ok('set_revision_view', { mode: 'final' })
  const explicitFinal = (await ok('get_paragraph', { index: 7 })).text
  await ok('set_revision_view', { mode: 'all' })
  assert.equal(guarded, explicitFinal, '去掉 refresh() 后最终文本语义变了')
  assert.ok(!guarded.includes('旧表述'), '最终文本里不许混进被删的旧字：' + guarded)

  // 没有任何修订的文档整趟往返都跳过（起草场景）：语义与光标都得照旧。
  assert.equal((await ok('resolve_all_revisions', { action: 'accept' })).success, true)
  assert.equal((await ok('list_revisions', { limit: 50, locate: false })).count, 0, '前提：修订已全部接受')
  await ok('select_paragraph', { index: 7 })
  await ok('collapse_selection', { to: 'end' })
  const bare = await caretX()
  const bareText = (await ok('get_review_context')).text
  assert.equal(bareText, explicitFinal, '无修订文档读到的正文应当就是接受后的正文')
  for (let i = 0; i < 5; i++) { await ok('get_completion_context', { radius: 160 }); await ok('get_review_context') }
  assert.equal(await caretX(), bare, '无修订文档上光标也漂了（守卫本该整段跳过）')
  assert.equal((await ok('set_revision_view', {})).mode, 'all', '跑完仍是用户所选的显示态')

  console.log('PASS 滚动后视口不回顶（只读/补全/AI 读取/客体页自发刷新四条路径），最终文本语义与显示态不变；光标横向不漂（带修订段落 40 次读取 + 逐字键入 + 无修订文档）')
} finally {
  await browser.close()
  await new Promise((r) => server.close(r))
}

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审阅装订线宽度不抖（dev-board#725）：默认的「全部修订」内联视图下，页外审阅区
// 一条卡片都不画，装订线宽度就必须全程为 0——它是 0 / 280 的全有全无量，每换一次
// 整页横向重排一次，用户看到的就是正文（和光标）左右跳。
//
// 病灶：reviewLayout 的 add() 在内联视图下收到的 data 只有 {index}、没有 type，
// `data.type !== 'Insert'` 恒真，于是**每一条还没排版的修订**都被计进 pending；
// 宿主 (zetaOfficeReviewBalloons) 见 pending>0 就占住 280，Writer 在空闲里把后面
// 的页排完之后 pending 归零，宿主又释放成 0。300 段夹具真机实测 pending 100 → 44 → 0。
//
// Run:  npm run test:lowa-review-gutter     (from frontend/)
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
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

  // ---------- 十几页 + 全篇散布修订、零批注 ----------
  const run = (t) => `<w:r><w:t>${t}</w:t></w:r>`
  const paras = []
  for (let i = 0; i < 300; i++) {
    paras.push(i % 5 === 2
      ? `<w:p>${run('第' + i + '段前置，')}<w:del w:id="${9000 + i}" w:author="甲审阅人" w:date="2026-09-10T08:00:00Z"><w:r><w:delText>旧表述</w:delText></w:r></w:del><w:ins w:id="${8000 + i}" w:author="甲审阅人" w:date="2026-09-10T08:00:00Z">${run('新表述')}</w:ins>${run('，后续。')}</w:p>`
      : `<w:p>${run('第' + i + '段：填充正文，把文档撑到十几页，好让 Writer 的远页排版落在空闲里。')}</w:p>`)
  }
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paras.join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
  await ok('load_document', { name: 'gutter-725.docx', bytes: Array.from(await zip.generateAsync({ type: 'uint8array' })) })

  assert.equal((await ok('set_revision_view', {})).mode, 'all', '本组的前提是默认内联视图')
  const revisions = (await ok('list_revisions', { limit: 500, locate: false })).count
  assert.ok(revisions > 60, '夹具要带足够多的修订，才会有一批落在还没排版的页上：' + revisions)

  const first = await ok('get_review_layout', { fresh: true })
  assert.equal(first.available, true, 'get_review_layout 不可用（需要 r5 引擎）: ' + JSON.stringify(first))
  // 防空断言：必须确实有修订还没拿到几何（否则 pending 本来就不会被算错）。
  assert.ok(first.truncated, '前提：读第一遍时应当还有修订没排版（truncated 为真）')
  assert.ok(first.items.length < revisions, '前提：第一遍拿到几何的条目应少于修订总数 ' + revisions + '，实测 ' + first.items.length)
  assert.equal(first.pending, 0,
    '内联视图一张卡片都不画，未排版的修订不该计入 pending（dev-board#725）：pending=' + first.pending)

  // ---------- 边打字边观察：宽度取值集合只能有一个 ----------
  await ok('select_paragraph', { index: 0 })
  await ok('collapse_selection', { to: 'end' })
  const widths = new Set([Number(first.sidebarWidth)])
  const deadline = Date.now() + 5000
  let keys = 0
  while (Date.now() < deadline) {
    if (keys < 12) { await ok('insert_at_cursor', { text: '甲' }); keys++ }
    const r = await ok('get_review_layout', { fresh: false })
    widths.add(Number(r.sidebarWidth))
    await sleep(120)
  }
  assert.deepEqual([...widths], [0],
    '内联视图下装订线宽度抖动了（dev-board#725）：实测取值集合 ' + JSON.stringify([...widths]))

  // 后置复核：页已排完，pending 仍是 0，修订一条没少。
  const settled = await ok('get_review_layout', { fresh: true })
  assert.equal(settled.pending, 0, '排版落定后 pending 仍必须为 0：' + settled.pending)
  assert.equal(Number(settled.sidebarWidth), 0, '内联视图不该占住装订线：' + settled.sidebarWidth)
  // 连着敲在同一处的插入会被引擎并成一条修订，所以只卡区间：一条原有修订都不许
  // 被处置（下界），也不许凭空多出打字以外的修订（上界）。
  const after = (await ok('list_revisions', { limit: 500, locate: false })).count
  assert.ok(after >= revisions && after <= revisions + keys,
    '本组只该多出打字产生的修订：原有 ' + revisions + '，键入 ' + keys + ' 次，实测 ' + after)
  assert.equal((await ok('set_revision_view', {})).mode, 'all', '跑完仍是用户所选的显示态')

  console.log('PASS 内联视图下未排版的修订不计 pending，边打字 5 秒装订线宽度恒为 0（' + keys + ' 次键入，' + revisions + ' 条原有修订）')
} finally {
  await browser.close()
  await new Promise((r) => server.close(r))
}

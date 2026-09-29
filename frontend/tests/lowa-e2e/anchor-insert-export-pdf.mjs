#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真引擎回归（dev-board#1065 T-16 / T-14 / T-25）：
//
// [A] doc_insert_at_cursor(text, anchorId, position) 在后端被拆成三条既有命令：
//     set_selection(anchor) → collapse_selection(start|end) → insert_at_cursor(text)。
//     后端单测只钉「发了哪三条」，这里钉「三条连着发，字真的落在锚点前/后」。
//     地雷：insert_at_cursor 自己第一步就是 vc.collapseToEnd()——before 能成立，靠的是
//     collapse_selection(start) 之后光标已经是收起的，再 collapseToEnd 不会挪走。
// [B] 删除改走「传空字符串」（T-14 让三个删除专用工具退出下发）：find_replace 与
//     replace_at_position 传空串真的把字删掉。
// [C] doc_export_pdf 的前端一半：真 export_pdf 回执 → agentPdfExportResult → base64，
//     node 侧解码回来必须是一份 %PDF 开头、字节数对得上的文件。后端存进项目那一半由
//     DocumentEditToolsAnchorInsertAndPdfExportTest 覆盖（本页没有后端）。
//
// 判据一律是回读文档内容，不是返回值。
//
// Run:  node tests/lowa-e2e/anchor-insert-export-pdf.mjs   (from frontend/)
// Env:  同 _boot.mjs（LOWA_ENGINE_DIR / PUPPETEER_EXECUTABLE_PATH / LOWA_E2E_PORT）
import fs from 'node:fs'
import path from 'node:path'
import { here, preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}
try {
  const page = await openEditor(browser, { clipboard: false })
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a2, p2) => window.__loExecutor.executeCommand(a2, p2), a, p)
  // 「最终稿」视图：删除的字移出正文流，回读的就是用户接受修订后会看到的文字
  const view = await exec('set_revision_view', { mode: 'final' })
  check('准备：切到最终稿视图', view.success === true, JSON.stringify(view))
  const doc = async () => (await exec('get_document_text', { __agent: true })).paragraphs.map((x) => x.text).join('|')
  // 后端 doc_insert_at_cursor 带 anchorId 时下发的三条命令，参数逐字照抄
  // （DocumentEditToolsAnchorInsertAndPdfExportTest 钉着后端那一侧）
  const insertAtAnchor = async (text, anchor, position) => {
    const s1 = await exec('set_selection', { anchor, __agent: true })
    const s2 = await exec('collapse_selection', { to: position === 'before' ? 'start' : 'end', __agent: true })
    const s3 = await exec('insert_at_cursor', { text, __agent: true })
    return [s1, s2, s3]
  }
  const anchorOf = async (keyword) => {
    const r = await exec('find_text_locations', { keyword, matchCase: false, __agent: true })
    return r && r.matches && r.matches[0] ? r.matches[0].anchorId : null
  }

  const BASE = 'ALPHA 甲方应于十日内付款。BETA 乙方应于收款后交货。'
  await exec('insert_at_cursor', { text: BASE })
  check('准备：正文写入', (await doc()) === BASE, await doc())

  console.log('\n[A] 锚点插入：after / before')
  {
    const a1 = await anchorOf('甲方应于十日内付款。')
    check('find_text_locations 给出锚点', !!a1, String(a1))
    const steps = await insertAtAnchor('【新增A】', a1, 'after')
    check('after：三步都成功', steps.every((r) => r && r.success === true), JSON.stringify(steps))
    const t1 = await doc()
    check('after：新文字紧跟在锚点句之后',
      t1 === 'ALPHA 甲方应于十日内付款。【新增A】BETA 乙方应于收款后交货。', t1)

    const b1 = await anchorOf('BETA 乙方')
    const stepsB = await insertAtAnchor('【前置B】', b1, 'before')
    check('before：三步都成功', stepsB.every((r) => r && r.success === true), JSON.stringify(stepsB))
    const t2 = await doc()
    check('before：新文字紧贴在锚点句之前（insert_at_cursor 的 collapseToEnd 没把光标挪走）',
      t2 === 'ALPHA 甲方应于十日内付款。【新增A】【前置B】BETA 乙方应于收款后交货。', t2)

    const bad = await exec('set_selection', { anchor: '__ai_anchor_does_not_exist', __agent: true })
    check('锚点不存在：第 1 步明确失败（后端据此不再发后两步）', bad.success === false && /anchor not found/.test(bad.message || ''),
      JSON.stringify(bad))
    check('锚点不存在时文档一个字没动', (await doc()) === t2, await doc())
  }

  console.log('\n[B] 删除改走传空字符串')
  {
    const before = await doc()
    const fr = await exec('find_replace', { findText: '【新增A】', replaceText: '', replaceAll: true, __agent: true })
    const t3 = await doc()
    check('find_replace 传空串：返回替换了 1 处', fr.success === true && fr.replaced === 1, JSON.stringify(fr))
    check('find_replace 传空串：字真的删掉了', t3 === before.replace('【新增A】', ''), t3)

    const a2 = await anchorOf('【前置B】')
    const rp = await exec('replace_at_position', { anchor: a2, newText: '', __agent: true })
    const t4 = await doc()
    check('replace_at_position 传空串：返回成功', rp.success === true, JSON.stringify(rp))
    check('replace_at_position 传空串：字真的删掉了，回到原文', t4 === BASE, t4)
  }

  console.log('\n[C] 导出 PDF：真 export_pdf 回执 → agentPdfExportResult → base64')
  {
    const helperSrc = fs.readFileSync(path.join(here, '../../src/pages/project-overview/agentPdfExport.js'), 'utf8')
      .replace(/^export /gm, '')
    const out = await page.evaluate(async (src) => {
      // eslint-disable-next-line no-new-func
      const mod = new Function(src + '\nreturn { agentPdfExportResult }')()
      const res = await window.__loExecutor.executeCommand('export_pdf', {})
      const r = mod.agentPdfExportResult(res, 42)
      return { success: r.success, error: r.error, size: r.data && r.data.size, sourceFileId: r.data && r.data.sourceFileId,
        base64: r.data && r.data.base64, keys: r.data ? Object.keys(r.data).sort() : null }
    }, helperSrc)
    check('export_pdf 回执被归一成功', out.success === true, JSON.stringify({ ...out, base64: out.base64 && out.base64.length }))
    const bytes = out.base64 ? Buffer.from(out.base64, 'base64') : Buffer.alloc(0)
    check('解码回来是一份 PDF（%PDF 开头）', bytes.slice(0, 5).toString('latin1') === '%PDF-', bytes.slice(0, 8).toString('latin1'))
    check('字节数与回执里的 size 一致', bytes.length > 1000 && bytes.length === out.size, bytes.length + ' vs ' + out.size)
    check('回传体里只有 base64 / size / sourceFileId / success，没有原始 bytes',
      JSON.stringify(out.keys) === JSON.stringify(['base64', 'size', 'sourceFileId', 'success']) && out.sourceFileId === 42,
      JSON.stringify(out.keys))
    check('导出不改文档', (await doc()) === BASE, await doc())
  }

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

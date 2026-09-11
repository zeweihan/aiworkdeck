// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真引擎：补全位置令牌、原样文本、资料插入以及单步撤销。
import assert from 'node:assert/strict'
import fs from 'node:fs'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

async function blankDocx() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p/></w:body></w:document>')
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}
const DEBUG_ACTION = `debug_completion_failure(p) {
  return completionEdit('补全文字', function () {
    if (p.write) ctrl.getViewCursor().getText().insertString(ctrl.getViewCursor(), '失败残留', false);
    throw new Error('test insertion failure');
  });
},
debug_completion_clean() { xModel.setModified(false); return { success: true }; },
debug_completion_calc() {
  const loaded = desktop.loadComponentFromURL('private:factory/scalc', '_blank', 0, []);
  xModel = loaded; ctrl = loaded.getCurrentController();
  return { success: true };
},`
preflight()
const sourceMode = process.env.LOWA_COMPLETION_SOURCE === '1'
const server = await startServer({ patchServed(url, bytes) {
  if (url === '/office_thread.js') {
    const source = sourceMode ? fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8') : bytes.toString()
    return Buffer.from(source.replace('const EXEC = {', 'const EXEC = {\n' + DEBUG_ACTION))
  }
  if (/^\/assets\/editor-.*\.js$/.test(url)) {
    const extra = ['debug_completion_calc', 'debug_completion_failure', 'debug_completion_clean', ...(sourceMode ? ['get_completion_context', 'accept_completion', 'insert_completion_content'] : [])]
    return Buffer.from(bytes.toString().replace(/(['"])get_hyperlink_at_cursor\1/, match => match + ',' + extra.map(JSON.stringify).join(',')))
  }
  return bytes
} })
if (sourceMode) console.log('Development source override: worker source served independently of the shared dist')
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const ok = async (a, p) => { const r = await exec(a, p); assert.equal(r.success, true, a + ': ' + JSON.stringify(r)); return r }
  const doc = async () => (await ok('get_document_text', { __agent: true })).paragraphs.map(p => p.text).join('\n')
  const prefix = '北京当红', text = '北京当红晴天律师事务所'
  const blank = await blankDocx()
  const reset = async (body = prefix) => {
    await ok('load_document', { name: 'completion.docx', bytes: blank })
    if (body) await ok('insert_at_cursor', { text: body })
  }
  const accept = token => exec('accept_completion', { token, prefix, text })
  await reset()
  let ctx = await ok('get_completion_context')
  assert.equal(ctx.before, prefix)
  const accepted = await accept(ctx.token)
  assert.equal(accepted.success, true, JSON.stringify(accepted))
  assert.ok(accepted.token && accepted.token !== ctx.token)
  assert.equal(await doc(), text)
  assert.equal((await accept(ctx.token)).reason, 'stale', '已消费令牌不可再用')
  await ok('undo')
  assert.equal(await doc(), prefix, '一次撤销仅撤补全文字')
  await ok('redo')
  assert.equal(await doc(), text)
  assert.equal((await exec('debug_completion_failure', { write: false })).success, false)
  assert.equal(await doc(), text, '未写入的失败不能撤销前一个同名补全操作')
  assert.equal((await exec('debug_completion_failure', { write: true })).success, false)
  assert.equal(await doc(), text, '中途失败撤回本次写入')
  console.log('PASS suffix completion, one-step undo/redo, consumed token')

  await reset()
  ctx = await ok('get_completion_context')
  await ok('export_document', { name: 'autosave-before-detail.docx' })
  await ok('insert_completion_content', { token: ctx.token, text: '【资料】' })
  assert.equal(await doc(), prefix + '【资料】', '只读自动保存不应让已有光标令牌失效')
  await reset()
  ctx = await ok('get_completion_context')
  const completedBeforeSave = await ok('accept_completion', { token: ctx.token, prefix, text })
  await ok('export_document', { name: 'autosave-after-completion.docx' })
  await ok('insert_completion_content', { token: completedBeforeSave.token, text: '【资料】' })
  assert.equal(await doc(), text + '【资料】', '补全结果令牌跨自动保存仍能追加资料')
  ctx = await ok('get_completion_context')
  await ok('export_document', { name: 'autosave-before-real-edit.docx' })
  await ok('insert_at_cursor', { text: '继续输入' })
  assert.equal((await exec('insert_completion_content', { token: ctx.token, text: '过期资料' })).reason, 'stale', '导出后真实输入仍使令牌失效')
  await reset(prefix + '错误')
  await ok('find_replace', { findText: '错误', replaceText: '正确', __agent: true })
  await ok('goto', { type: 'end' })
  await ok('debug_completion_clean')
  ctx = await ok('get_completion_context')
  const beforeCleanSave = await doc()
  await ok('export_document', { name: 'clean-tracked-autosave.docx' })
  await ok('insert_completion_content', { token: ctx.token, text: '【资料】' })
  assert.equal(await doc(), beforeCleanSave + '【资料】', '干净态含删除修订的导出也应保留令牌')
  console.log('PASS read-only export preserves context/completion tokens; real edits still invalidate')

  await reset(prefix + '\n' + prefix)
  await ok('select_paragraph', { index: 0 }); await ok('collapse_selection')
  ctx = await ok('get_completion_context')
  await ok('select_paragraph', { index: 1 }); await ok('collapse_selection')
  assert.equal((await accept(ctx.token)).reason, 'stale', '相同前缀在另一段不能接受旧结果')
  ctx = await ok('get_completion_context')
  await ok('insert_at_cursor', { text: '新' })
  assert.equal((await accept(ctx.token)).reason, 'stale', '继续输入后候选过期')
  ctx = await ok('get_completion_context')
  await reset()
  assert.equal((await accept(ctx.token)).reason, 'stale', '重载后令牌过期')
  ctx = await ok('get_completion_context')
  assert.equal((await exec('accept_completion', { token: ctx.token, prefix, text: '别的机构' })).reason, 'invalid-completion')
  await ok('set_revision_view', { mode: 'all' })
  ctx = await ok('get_completion_context')
  assert.equal((await accept(ctx.token)).success, true)
  assert.equal((await exec('set_revision_view')).mode, 'all')
  await ok('set_revision_view', { mode: 'margin' })
  console.log('PASS moved cursor, continued input, reload, invalid prefix, inline revision guard')

  await reset()
  ctx = await ok('get_completion_context')
  const literal = '\n**第一条**\n# 原文保持'
  await ok('insert_completion_content', { token: ctx.token, text: literal })
  assert.equal(await doc(), prefix + literal, '资料文字不解析 Markdown')
  await ok('undo')
  assert.equal(await doc(), prefix, '多段文本一次撤销')
  ctx = await ok('get_completion_context')
  const rows = [['项目', '信息'], ['名称', text], ['备注', '**原样**']]
  const inserted = await ok('insert_completion_content', { token: ctx.token, text: '\n工商基本信息\n', rows })
  assert.ok(inserted.table && inserted.token)
  assert.deepEqual((await ok('table_read', { tableIndex: 0 })).cells, rows)
  assert.equal((await exec('insert_completion_content', { token: ctx.token, rows })).reason, 'stale')
  await ok('undo')
  assert.equal(await doc(), prefix, '文本和表格合为一次撤销')
  assert.equal((await exec('table_read', { tableIndex: 0 })).success, false, '撤销移除整张表')
  ctx = await ok('get_completion_context')
  assert.equal((await exec('insert_completion_content', { token: ctx.token, rows: [['ok'], Array(21).fill('x')] })).reason, 'invalid-table')
  assert.equal((await exec('insert_completion_content', { token: ctx.token, rows: Array(201).fill(['x']) })).reason, 'invalid-table')
  assert.equal(await doc(), prefix)
  console.log('PASS literal text/table insertion, grouped undo, per-row bounds')

  await reset('前文' + prefix + '后文')
  await ok('find_navigate', { keyword: prefix, direction: 'next' })
  ctx = await ok('get_completion_context')
  assert.equal(ctx.hasSelection, true)
  assert.equal(ctx.available, false)
  assert.ok(ctx.token)
  assert.equal(ctx.selectedText, prefix)
  assert.equal((await accept(ctx.token)).reason, 'selection', '选区只允许显式资料插入')
  await ok('insert_completion_content', { token: ctx.token, text: '【外查资料】' })
  assert.equal(await doc(), '前文' + prefix + '【外查资料】后文', '原选区和前后正文均保留')
  await ok('undo')
  assert.equal(await doc(), '前文' + prefix + '后文')
  await ok('find_navigate', { keyword: prefix, direction: 'next' })
  ctx = await ok('get_completion_context')
  await ok('collapse_selection')
  assert.equal((await exec('insert_completion_content', { token: ctx.token, text: '过期' })).reason, 'stale', '仅改变选区末端也拒绝')
  console.log('PASS selection token appends without replacing and rejects changed selection')

  await ok('debug_completion_calc')
  for (const action of ['get_completion_context', 'accept_completion', 'insert_completion_content']) {
    assert.equal((await exec(action, { token: ctx.token, prefix, text })).reason, 'not-writer', action)
  }
  console.log('PASS Calc rejects all Writer completion actions')
} finally {
  await browser.close()
  server.close()
}

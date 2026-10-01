// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Actual LOWA comparison snapshots, editability, configuration restoration, and
// OOXML moves. The host finalizer separately repairs native comment anchors.
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'
const ns = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const r = text => `<w:r><w:t>${text}</w:t></w:r>`
const p = text => `<w:p>${r(text)}</w:p>`
async function docx(body) {
  const z = new JSZip()
  z.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  z.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  z.file('word/document.xml', `<w:document xmlns:w="${ns}"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>`)
  return Array.from(await z.generateAsync({ type: 'uint8array' }))
}
preflight()
const server = await startServer({ patchServed(url, bytes) {
  if (url === '/office_thread.js') return Buffer.from(bytes.toString().replace('const EXEC = {', `const EXEC = { debug_comparison_settings() {
    const provider=context.getServiceManager().createInstanceWithContext('com.sun.star.configuration.ConfigurationProvider',context);
    const config=provider.createInstanceWithArguments('com.sun.star.configuration.ConfigurationAccess',[mkProp('nodepath','/org.openoffice.Office.Writer/Comparison')]);
    return {success:true,mode:config.getByName('Mode'),useRSID:config.getByName('UseRSID'),ignorePieces:config.getByName('IgnorePieces'),author:currentRedlineAuthor};
  },`))
  if (/^\/assets\/editor-.*\.js$/.test(url)) return Buffer.from(bytes.toString().replace(/(['"])get_hyperlink_at_cursor\1/, m => m + ',"debug_comparison_settings"'))
  return bytes
} })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  const exec = (action, params = {}) => page.evaluate(async (action, params) => {
    const result = await window.__loExecutor.executeCommand(action, params)
    if (result.bytes) result.bytes = Array.from(result.bytes)
    return result
  }, action, params)
  const ok = async (action, params) => {
    if (process.env.COMPARISON_DEBUG) console.log('action',action)
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const load = bytes => ok('load_document', { bytes, name: 'comparison.docx', authorName: '测试用户' })
  const text = async () => (await ok('get_document_text')).paragraphs.map(x => x.text)
  const base = await docx(p('甲方在三十日内付款。乙方开票。'))
  const revised = await docx(p('甲方在六十日内付款。乙方开票。'))
  await load(base)
  const settings = await ok('debug_comparison_settings')
  assert.equal((await exec('build_comparison_document', { baseBytes: base })).stage, 'input')
  assert.deepEqual(await text(), ['甲方在三十日内付款。乙方开票。'], 'missing input must not replace current document')
  await ok('build_comparison_document', { baseBytes: base, revisedBytes: revised })
  assert.deepEqual(await ok('debug_comparison_settings'), settings, 'comparison config and author restored')
  const revisions = (await ok('list_revisions')).revisions
  assert.ok(revisions.some(x => x.type === 'Delete' && x.text === '三'))
  assert.ok(revisions.some(x => x.type === 'Insert' && x.text === '六'))
  assert.ok(revisions.every(x => x.author === '版本对比'))
  const compared = (await ok('export_document', { name: 'comparison.docx' })).bytes
  for (const [action, expected] of [['accept', '甲方在六十日内付款。乙方开票。'], ['reject', '甲方在三十日内付款。乙方开票。']]) {
    await load(compared)
    await ok('resolve_all_revisions', { action })
    assert.deepEqual(await text(), [expected], action + ' round-trip projection')
  }
  await ok('build_comparison_document', { baseBytes: base, revisedBytes: revised })
  assert.equal((await ok('resolve_all_revisions', { action: 'accept' })).remaining, 0, 'comparison model remains writable')
  await ok('goto', { type: 'end' })
  await ok('insert_at_cursor', { text: '补充条款。' })
  const edited = (await ok('export_document', { name: 'comparison.docx' })).bytes
  await load(edited)
  assert.ok((await text()).join('\n').includes('补充条款。'), 'edits persist after save/reopen')
  const tracked = await docx(`<w:p>${r('甲方在')}<w:del w:id="1" w:author="旧作者" w:date="2026-10-01T08:00:00Z"><w:r><w:delText>十</w:delText></w:r></w:del><w:ins w:id="2" w:author="旧作者" w:date="2026-10-01T08:00:00Z">${r('三十')}</w:ins>${r('日内付款。乙方开票。')}</w:p>`)
  await ok('build_comparison_document', { baseBytes: tracked, revisedBytes: revised })
  assert.ok((await ok('list_revisions')).revisions.every(x => x.author === '版本对比'), 'source revisions normalized to final snapshot')
  const normalized = (await ok('export_document')).bytes
  await load(normalized)
  await ok('resolve_all_revisions', { action: 'reject' })
  assert.deepEqual(await text(), ['甲方在三十日内付款。乙方开票。'])
  const trackedRevised = await docx(`<w:p>${r('甲方在')}<w:del w:id="1" w:author="新作者" w:date="2026-10-01T08:00:00Z"><w:r><w:delText>五</w:delText></w:r></w:del><w:ins w:id="2" w:author="新作者" w:date="2026-10-01T08:00:00Z">${r('六十')}</w:ins>${r('日内付款。乙方开票。')}</w:p>`)
  await ok('build_comparison_document', { baseBytes: tracked, revisedBytes: trackedRevised })
  assert.ok((await ok('list_revisions')).revisions.every(x => x.author === '版本对比'))
  const bothTracked = (await ok('export_document')).bytes
  for (const [action, expected] of [['accept', '甲方在六十日内付款。乙方开票。'], ['reject', '甲方在三十日内付款。乙方开票。']]) {
    await load(bothTracked)
    await ok('resolve_all_revisions', { action })
    assert.deepEqual(await text(), [expected])
  }
  await load(tracked)
  assert.equal((await ok('list_revisions')).count, 2, 'input bytes still contain original revisions')
  const referenceTarget = await docx(`<w:p><w:bookmarkStart w:id="1" w:name="TargetA"/>${r('付款期限为')}<w:del w:id="1" w:author="比较"><w:r><w:delText>三</w:delText></w:r></w:del><w:ins w:id="2" w:author="比较">${r('六')}</w:ins>${r('十日。')}<w:bookmarkEnd w:id="1"/></w:p><w:p><w:fldSimple w:instr="REF TargetA">${r('付款期限为六三十日。')}</w:fldSimple></w:p>`)
  for (const [action, expected] of [['accept', '付款期限为六十日。'], ['reject', '付款期限为三十日。']]) {
    await load(referenceTarget)
    assert.equal((await ok('resolve_all_revisions', { action })).remaining, 0)
    assert.deepEqual(await text(), [expected, expected], 'reference cache follows resolved target')
    await load((await ok('export_document')).bytes)
    assert.deepEqual(await text(), [expected, expected], 'resolved reference cache survives reopen')
  }
  const blank = await docx('<w:p/>')
  await ok('build_comparison_document', { baseBytes: base, revisedBytes: blank })
  const deleted = (await ok('export_document')).bytes
  await load(deleted)
  await ok('resolve_all_revisions', { action: 'accept' })
  assert.equal((await text()).join(''), '', 'blank revised document is valid')
  await load(deleted)
  await ok('resolve_all_revisions', { action: 'reject' })
  assert.equal((await text()).join(''), '甲方在三十日内付款。乙方开票。')
  await ok('build_comparison_document', { baseBytes: base, revisedBytes: base })
  assert.equal((await ok('list_revisions')).count, 0, 'identical documents have no changes')
  const movement = '保密内容需要移动'
  const attrs = 'w:author="比对" w:date="2026-10-01T08:00:00Z"'
  const moveDoc = await docx(`<w:p>${r('开始内容。')}<w:moveFromRangeStart w:id="10" w:name="move1" ${attrs}/><w:moveFrom w:id="11" ${attrs}><w:r><w:delText>${movement}</w:delText></w:r></w:moveFrom><w:moveFromRangeEnd w:id="10"/>${r('中间保留。')}<w:moveToRangeStart w:id="12" w:name="move1" ${attrs}/><w:moveTo w:id="13" ${attrs}>${r(movement)}</w:moveTo><w:moveToRangeEnd w:id="12"/></w:p>`)
  await load(moveDoc)
  const moves = (await ok('list_revisions')).revisions
  assert.equal(moves.length, 2)
  assert.ok(moves[0].movedId > 1)
  assert.equal(moves[0].movedId, moves[1].movedId)
  const moveBytes = (await ok('export_document')).bytes
  const z = await JSZip.loadAsync(moveBytes)
  const xml = await z.file('word/document.xml').async('string')
  assert.match(xml, /<w:moveFrom\b/)
  assert.match(xml, /<w:moveTo\b/)
  for (const [action, expected] of [['accept', '开始内容。中间保留。'+movement], ['reject', '开始内容。'+movement+'中间保留。']]) {
    await load(moveBytes)
    await ok('resolve_all_revisions', { action })
    assert.deepEqual(await text(), [expected])
  }
  await load(moveDoc)
  await ok('find_replace', { findText: '内容需要', replaceText: '条款应当', replaceAll: true, __agent: true })
  let editedMove
  for (let cycle = 0; cycle < 3; cycle++) {
    await ok('get_review_context')
    await ok('goto', { type: 'end' })
    await ok('insert_at_cursor', { text: '补。' })
    editedMove = (await ok('export_document')).bytes
    await load(editedMove)
    const revisions = (await ok('list_revisions')).revisions
    const moved = revisions.filter(x => x.movedId > 1)
    assert.ok(moved.some(x => x.type === 'Delete') && moved.some(x => x.type === 'Insert'), 'edited move retains both sides')
    assert.equal(new Set(moved.map(x => x.movedId)).size, 1, 'editing within moved text preserves native pair')
  }
  for (const [action, expected] of [['accept', '开始内容。中间保留。保密条款应当移动'+'补。'.repeat(3)], ['reject', '开始内容。'+movement+'中间保留。']]) {
    await load(editedMove)
    await ok('resolve_all_revisions', { action })
    assert.deepEqual(await text(), [expected], 'edited move '+action+' projection')
  }
  // Real regression: final-text reads used to drop/rename private move bookmarks.
  // Preserve a user bookmark, and two native redlines on each side of one move.
  const splitMove = await docx(`<w:p><w:bookmarkStart w:id="99" w:name="UserBookmark"/>${r('开始。')}<w:bookmarkEnd w:id="99"/><w:moveFromRangeStart w:id="10" w:name="splitMove" ${attrs}/><w:moveFrom w:id="11" ${attrs}><w:r><w:delText>移动第一句。</w:delText></w:r></w:moveFrom><w:moveFrom w:id="14" w:author="另一作者" w:date="2026-10-01T09:00:00Z"><w:r><w:delText>移动第二句。</w:delText></w:r></w:moveFrom><w:moveFromRangeEnd w:id="10"/>${r('中间。')}<w:moveToRangeStart w:id="12" w:name="splitMove" ${attrs}/><w:moveTo w:id="13" ${attrs}>${r('移动第一句。')}</w:moveTo><w:moveTo w:id="15" w:author="另一作者" w:date="2026-10-01T09:00:00Z">${r('移动第二句。')}</w:moveTo><w:moveToRangeEnd w:id="12"/></w:p>`)
  await load(splitMove)
  assert.equal((await ok('list_revisions')).revisions.filter(x => x.movedId > 1).length, 4, 'one move can span several redlines')
  {
    await ok('get_review_context')
    await ok('get_document_text', { __agent: true })
    await ok('goto', { type: 'end' })
    await ok('insert_at_cursor', { text: '补。' })
    const saved = (await ok('export_document')).bytes
    const zip = await JSZip.loadAsync(saved)
    const xml = await zip.file('word/document.xml').async('string')
    assert.match(xml, /w:name="UserBookmark"/, 'ordinary bookmark survives')
    const names = [...xml.matchAll(/<w:move(?:From|To)RangeStart[^>]*w:name="([^"]*)"/g)].map(m => m[1])
    assert.equal(names.length, 2, 'exactly one complete move range pair')
    assert.equal(names[0], names[1], 'exported movement names remain paired')
    await load(saved)
    const moved = (await ok('list_revisions')).revisions.filter(x => x.movedId > 1)
    // LOWA itself loses the from-side native ID on this multi-author fixture even
    // without reads/edits. Verify the repair's exported ranges and text, not that
    // unrelated importer limitation (covered by the investigation control).
    for (const side of ['From', 'To']) {
      const range = xml.match(new RegExp('<w:move'+side+'RangeStart[^>]*>([\\s\\S]*?)<w:move'+side+'RangeEnd'))
      assert.ok(range, 'complete split move range '+side)
      assert.equal(range[1].replace(/<[^>]*>/g, ''), '移动第一句。移动第二句。')
    }
    assert.ok(moved.length >= 2)
  }
  const orphanZip = await JSZip.loadAsync(moveDoc)
  const orphanXml = (await orphanZip.file('word/document.xml').async('string')).replace('</w:p>', `<w:moveToRangeStart w:id="50" w:name="orphanMove" ${attrs}/><w:moveTo w:id="51" ${attrs}>${r('独立未配对移动')}</w:moveTo><w:moveToRangeEnd w:id="50"/></w:p>`)
  orphanZip.file('word/document.xml', orphanXml)
  await load(Array.from(await orphanZip.generateAsync({ type: 'uint8array' })))
  await ok('get_review_context')
  const orphanSaved = await JSZip.loadAsync((await ok('export_document')).bytes)
  assert.match(await orphanSaved.file('word/document.xml').async('string'), /w:name="orphanMove"/, 'unpaired movement marker is preserved')
  console.log('PASS: standalone comparison final snapshots, character revisions, editable save/reopen, blank/identical documents, configuration restoration, native movement round-trip')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}

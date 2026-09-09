// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// B7：完整回复插入现有段落中间，原文字的段落/字符格式不能被 Markdown 排版覆盖。
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, ORIGIN } from './_boot.mjs'

async function blankDocx() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p/></w:body></w:document>')
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}
preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
const failures = []
try {
  const page = await browser.newPage()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/&uilang=en-US', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const locale = await exec('get_ui_lang')
  assert.equal(locale.ooLocale, 'en-US')
  console.log('PASS English editor boot', JSON.stringify(locale))
  const selectText = async keyword => {
    const found = await exec('find_text_locations', { keyword })
    assert.equal(found.matches.length, 1, '原文字必须仍然恰好出现一次：' + keyword)
    assert.equal((await exec('set_selection', { anchor: found.matches[0].anchorId })).success, true)
  }
  const format = async keyword => { await selectText(keyword); const r = await exec('get_formatting'); return { paragraph: r.paragraph, character: r.character } }
  for (const [headingLevel, position] of [[1, 'middle'], [0, 'middle'], [0, 'start'], [1, 'end']]) {
    const label = (headingLevel ? '标题段' : '居中正文段') + ' ' + position
    assert.equal((await exec('load_document', { name: 'source.docx', bytes: await blankDocx() })).success, true)
    await exec('insert_at_cursor', { text: '原有前文原有后文\n相邻原段' })
    await exec('select_paragraph', { index: 0 })
    await exec('set_paragraph_format', { headingLevel, alignment: 'center', spaceBeforePt: 9, spaceAfterPt: 7, firstLineIndentPt: 12 })
    await exec('format_selection', { bold: true, italic: true, fontSize: 18, color: '#112233' })
    const beforePrefix = await format('原有前文')
    const beforeSuffix = await format('原有后文')
    const beforeNeighbor = await format('相邻原段')
    await selectText('原有前文')
    const beforeEmpty = await exec('get_document_text', {})
    await exec('stream_insert', { text: '  \n', complete: true })
    assert.deepEqual(await exec('get_document_text', {}), beforeEmpty, '空白回复不应拆段或修改原文')
    if (position === 'start') await exec('goto', { type: 'start' })
    else await selectText(position === 'end' ? '原有后文' : '原有前文')
    const inserted = await exec('stream_insert', { text: '# 回复标题\n\n**答复正文**\n\n|项目|金额|\n|---|---|\n|测试|1|', complete: true })
    assert.equal(inserted.success, true, JSON.stringify(inserted))
    for (const [text, expected] of [['原有前文', beforePrefix], ['原有后文', beforeSuffix], ['相邻原段', beforeNeighbor]]) {
      try { assert.deepEqual(await format(text), expected, label + ' 原文字格式：' + text) }
      catch (e) { failures.push(label + ' ' + text + ': ' + e.message); console.log('FAIL', label, text, e.message) }
    }
    assert.deepEqual((await exec('table_read', { tableIndex: 0 })).cells, [['项目', '金额'], ['测试', '1']])
    assert.equal((await format('答复正文')).character.bold, true)
    const exported = await page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', { name: 'result.docx' })
      if (!r.success) throw new Error(r.error || r.message)
      return Array.from(r.bytes)
    })
    const zip = await JSZip.loadAsync(Buffer.from(exported.map(b => b & 255)))
    const xml = await zip.file('word/document.xml').async('string')
    const text = await page.evaluate(xml => [...new DOMParser().parseFromString(xml, 'application/xml').getElementsByTagNameNS('http://schemas.openxmlformats.org/wordprocessingml/2006/main', 't')].map(t => t.textContent).join(''), xml)
    const prefixAt = text.indexOf('原有前文'), suffixAt = text.indexOf('原有后文'), replyAt = text.indexOf('回复标题')
    assert.ok(position === 'start' ? replyAt < prefixAt : position === 'end' ? suffixAt < replyAt : prefixAt < replyAt && replyAt < suffixAt, '回复保持用户选择的插入位置')
    for (const token of ['原有前文', '原有后文', '相邻原段', '回复标题', '答复正文', '测试']) assert.ok(text.includes(token), '导出保留文字：' + token)
    assert.ok(!text.includes('# 回复标题') && !text.includes('|项目|'), '回复必须成为富文本/真实表格')
    console.log('CHECK', label, '文字、富文本与表格已导出')
  }
} finally {
  await browser.close()
  server.close()
}
assert.deepEqual(failures, [], '完整回复插入不能改变原文字格式')
console.log('PASS 标题/居中段首尾及中间插入：原文字与格式均保留，回复富文本与表格正常')

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real Writer snapshots keep drafts usable across unrelated edits, while changed
// comments, changed anchors and replacement documents cannot consume old cards.
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'

const longAnchor = '完整锚点'.repeat(25) + '锚点末尾'
const run = text => `<w:r><w:t>${text}</w:t></w:r>`
async function fixture() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/_rels/document.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>')
  zip.file('word/comments.xml', `<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">${['第一条批注', '第二条批注'].map((text, id) => `<w:comment w:id="${id}" w:author="审阅人" w:date="2026-09-10T08:00:00Z"><w:p>${run(text)}</w:p></w:comment>`).join('')}</w:comments>`)
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${[longAnchor, '第二条独立锚点'].map((text, id) => `<w:p><w:commentRangeStart w:id="${id}"/>${run(text)}<w:commentRangeEnd w:id="${id}"/><w:r><w:commentReference w:id="${id}"/></w:r></w:p>`).join('')}</w:body></w:document>`)
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await browser.newPage()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const load = async () => {
    await ok('load_document', { name: 'comment-snapshot.docx', bytes: await fixture() })
    return ok('list_comments')
  }
  const expected = (snapshot, target) => ({ id: target.id, index: target.index, revision: snapshot.revision - 1, documentSeq: snapshot.documentSeq, expectedComment: target })
  let snapshot = await load()
  assert.equal(snapshot.count, 2)
  let target = snapshot.comments[0]
  assert.equal(target.anchorText, longAnchor, 'snapshot contains the full anchor, including text after character80')
  assert.equal((await exec('update_comment', { id: target.id, content: '旧序号拒绝', revision: snapshot.revision - 1 })).success, false, 'legacy mutations retain the generation fence')
  await ok('update_comment', { ...expected(snapshot, target), content: '保存草稿' })
  assert.equal((await ok('list_comments')).comments[0].content, '保存草稿')
  assert.equal((await exec('delete_comment', expected(snapshot, target))).success, false, 'changed content cannot be deleted using an old card')
  await ok('goto_comment', { id: target.id, documentSeq: snapshot.documentSeq, revision: snapshot.revision - 1 })
  console.log('PASS unchanged comment permits stale generation; changed content remains protected')

  snapshot = await load()
  target = snapshot.comments[1]
  await ok('delete_comment', { id: snapshot.comments[0].id })
  assert.equal((await ok('list_comments')).comments[0].id, target.id)
  const located = await ok('goto_comment', { id: target.id, index: target.index, documentSeq: snapshot.documentSeq, revision: snapshot.revision - 1 })
  assert.equal(located.index, 0, 'navigation returns the current target index')
  assert.equal((await ok('get_selection')).text, target.anchorText, 'navigation selects the ID-matched anchor despite the old index')
  await ok('set_comment_resolved', { ...expected(snapshot, target), resolved: true })
  assert.equal((await ok('list_comments')).comments[0].resolved, true)
  assert.equal((await exec('update_comment', { ...expected(snapshot, target), content: '旧草稿' })).success, false, 'changed resolved state is protected')
  const resolved = await ok('list_comments')
  await ok('delete_comment', expected(resolved, resolved.comments[0]))
  assert.equal((await ok('list_comments')).count, 0)
  console.log('PASS stable comment identity survives an index shift; resolution and deletion share snapshot protection')

  snapshot = await load()
  target = snapshot.comments[0]
  await ok('find_replace', { findText: '锚点末尾', replaceText: '新的锚点末尾' })
  await ok('resolve_all_revisions', { action: 'accept' })
  const changed = await ok('list_comments')
  assert.equal(changed.comments[0].anchorText.slice(0, 80), target.anchorText.slice(0, 80))
  assert.notEqual(changed.comments[0].anchorText, target.anchorText)
  assert.equal((await exec('update_comment', { ...expected(snapshot, target), content: '锚点已变的旧草稿' })).success, false, 'anchor changes beyond the display preview are protected')
  console.log('PASS full anchor changes remain protected beyond the first80 characters')

  snapshot = await load()
  target = snapshot.comments[0]
  const replacement = await load()
  assert.notEqual(snapshot.documentSeq, replacement.documentSeq)
  for (const action of ['update_comment', 'set_comment_resolved', 'delete_comment']) {
    assert.equal((await exec(action, { ...expected(snapshot, target), content: '旧文档草稿', resolved: true })).success, false, action + ' rejects a previous document')
  }
  assert.equal((await exec('goto_comment', { id: target.id, documentSeq: snapshot.documentSeq })).success, false)
  assert.deepEqual((await ok('list_comments')).comments, replacement.comments)
  console.log('PASS replacement documents reject old comment mutations and navigation')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}

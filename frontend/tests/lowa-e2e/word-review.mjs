// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real Writer import, review geometry, scrolling, long text and table grouping.
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'
import { groupRevisions } from '../../src/utils/reviewGrouping.js'

const deleted = '这一条是完整的删除修订内容，需要保留删除线并允许完整阅读。'.repeat(16)
const comment = '请核对付款金额、履行期限及对应附件。'.repeat(45)
const run = s => `<w:r><w:t>${s}</w:t></w:r>`
const note = (id, s) => `<w:commentRangeStart w:id="${id}"/>${run(s)}<w:commentRangeEnd w:id="${id}"/><w:r><w:commentReference w:id="${id}"/></w:r>`
const table = n => `<w:tbl><w:tblPr><w:tblW w:w="8000" w:type="dxa"/></w:tblPr><w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>${[0,1].map(r=>`<w:tr>${[0,1].map(c=>`<w:tc><w:tcPr><w:tcW w:w="4000" w:type="dxa"/></w:tcPr><w:p><w:ins w:id="${n+r*2+c}" w:author="AI WorkDeck" w:date="2026-09-10T08:00:00Z">${run(`表格项目 ${n+r*2+c}`)}</w:ins></w:p></w:tc>`).join('')}</w:tr>`).join('')}</w:tbl>`
async function fixture() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>')
  zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/_rels/document.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>')
  zip.file('word/comments.xml',`<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">${[comment,'同一条款的第二条批注。','后页条款批注'].map((c,i)=>`<w:comment w:id="${i}" w:author="审阅人" w:date="2026-09-10T08:00:00Z"><w:p>${run(c)}</w:p></w:comment>`).join('')}</w:comments>`)
  zip.file('word/document.xml',`<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>${run('合同审阅测试')}</w:p><w:p>${note(0,'第一条：付款条件。')}${note(1,'付款前需确认附件。')}</w:p><w:p><w:del w:id="1" w:author="审阅人" w:date="2026-09-10T08:00:00Z"><w:r><w:delText>${deleted}</w:delText></w:r></w:del>${run('本条保留的正文。')}</w:p>${table(10)}<w:p>${run('另一张表格，独立处置。')}</w:p>${table(20)}${Array.from({length:70},(_,i)=>`<w:p>${i===35?note(2,'后页的验收条款。'):run('第 '+(i+2)+' 条：双方按照约定履行合同义务，依法承担相应责任。')}</w:p>`).join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
  return Array.from(await zip.generateAsync({type:'uint8array'}))
}
preflight()
const server = await startServer({ patchServed(url,b) {
  if(url==='/office_thread.js')return Buffer.from(b.toString().replace('const EXEC = {',`const EXEC = { debug_deleted_paragraph() { xModel.setPropertyValue('RecordChanges', false); const text=xModel.getText(); text.setString(''); const c=text.createTextCursor(); insertTextAtCursor(c, '一段\\n删除段\\n末段'); xModel.setPropertyValue('RecordChanges', true); c.gotoStart(false); c.goRight(3,false); c.goRight(4,true); c.setString(''); invalidateParaIndex(); return {success:true}; }, debug_review_state() { return {success:true, live:ctrl.getViewData(), selection:ctrl.getViewCursor().getString(), revision:currentReviewRevision(), notes:ctrl.getViewSettings().getPropertyValue('ShowAnnotations'), signature:reviewLayoutCache?.signature}; },`))
  if(/^\/assets\/editor-.*\.js$/.test(url))return Buffer.from(b.toString().replace(/(['"])get_hyperlink_at_cursor\1/,m=>m+',"debug_review_state","debug_deleted_paragraph"'))
  return b
}, extraFiles: Object.fromEntries(['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'].map(f=>['/'+f,'/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/'+f])) })
const browser=await launchBrowser(await loadPuppeteer())
try {
  const page=await browser.newPage();await page.setViewport({width:1360,height:900});await page.goto(ORIGIN+'/editor.html?verify=1&lowa=/lowa/',{waitUntil:'domcontentloaded'});await page.waitForFunction('!!window.__loExecutor',{timeout:240000});console.log('ENGINE READY')
  await page.addStyleTag({content:'#verify,#vlog{display:none!important}'})
  const exec=(a,p={})=>page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p)
  const ok=async(a,p)=>{const r=await exec(a,p);assert.equal(r.success,true,a+': '+JSON.stringify(r));return r}
  await ok('load_document',{name:'word-review.docx',bytes:await fixture()})
  await ok('set_chrome',{all:false})
  assert.equal((await ok('set_revision_view')).mode,'all')
  let rows=(await ok('list_revisions')).revisions
  assert.equal(rows.find(r=>r.type==='Delete').text,deleted)
  assert.equal((await ok('list_comments')).comments[0].content,comment)
  let groups=groupRevisions(rows);const tables=groups.filter(g=>g.operationId)
  assert.equal(tables.length,2,JSON.stringify(rows))
  assert.ok(tables.every(g=>g.items.length===4))
  console.log('PASS full deletion/comment content and two independently grouped tables')
  await page.waitForSelector('.awd-rb-card',{visible:true,timeout:30000})
  await ok('goto_comment',{index:0})
  const before=await ok('debug_review_state'),t=Date.now();const layout=await ok('get_review_layout');const elapsed=Date.now()-t
  const after=await ok('debug_review_state')
  assert.equal(layout.items.length, 12, 'all revisions and comments have anchors')
  const commentPosition = layout.items.find(i=>i.kind==='comment' && i.data.index===0)
  assert.ok(Math.abs(commentPosition.y-layout.view.caretY)<300,'comment connector points to the selected line')
  assert.equal(after.selection,before.selection,'layout preserves the selected text')
  assert.equal(after.revision,before.revision,'layout never modifies document data')
  assert.deepEqual(after.live.split(';').slice(3,7),before.live.split(';').slice(3,7),'layout preserves scroll')
  assert.ok(elapsed<2000,'cached layout query: '+elapsed+'ms')
  assert.equal(after.notes,false,'native narrow note column is replaced')
  await page.waitForFunction(()=>document.querySelector('.awd-rb-lines path'))
  const dom=await page.evaluate(()=>({canvas:document.querySelector('#qtcanvas').getBoundingClientRect().right, rail:document.querySelector('.awd-rb-rail').getBoundingClientRect().left, texts:[...document.querySelectorAll('.awd-rb-content')].map(n=>n.textContent)}))
  assert.ok(dom.rail>dom.canvas,'balloons are outside the document canvas')
  assert.ok(dom.texts.includes(comment),'long comment remains readable in the UI')
  const beforeScroll=await ok('get_review_layout')
  const firstTop=await page.$eval('.awd-rb-card',n=>parseFloat(n.style.top))
  await page.mouse.move(600,650);await page.mouse.wheel({deltaY:450})
  await page.waitForFunction(async top=>(await window.__loExecutor.executeCommand('get_review_layout',{})).view.top>top,{timeout:10000},beforeScroll.view.top)
  try { await page.waitForFunction(top=>parseFloat(document.querySelector('.awd-rb-card').style.top)<top,{timeout:6000},firstTop) } catch(e) { console.log('SCROLL STATE', await exec('get_review_layout'), await page.evaluate(()=>({view:document.querySelector('.awd-review-balloons')?.dataset.viewport,top:document.querySelector('.awd-rb-card')?.style.top})), firstTop);throw e }
  const afterScroll=await ok('get_review_layout')
  assert.ok(afterScroll.view.top>beforeScroll.view.top,'document actually scrolled')
  const secondTop=await page.$eval('.awd-rb-card',n=>parseFloat(n.style.top))
  assert.ok(secondTop<firstTop,'balloons scroll with the document')
  console.log('PASS outside-page column, dashed connectors, selection preservation and scroll synchronization')
  await ok('goto_comment',{index:2});await ok('get_review_layout')
  const zoomBefore=(await ok('get_review_layout')).view
  await ok('set_zoom',{value:80});const zoomAfter=await ok('get_review_layout');assert.ok(zoomAfter.view.right-zoomAfter.view.left>zoomBefore.right-zoomBefore.left)
  await ok('set_zoom',{value:100});await ok('goto_comment',{index:0})
  const screenLayout = await ok('get_review_layout')
  const firstComment = screenLayout.items.find(i=>i.kind==='comment' && i.data.index===0)
  await page.waitForFunction(key=>{
    const card=document.querySelector('[data-key="'+key+'"]')
    return card && card.getBoundingClientRect().top>100 && card.getBoundingClientRect().top<240
  },{timeout:10000},firstComment.key)
  await page.screenshot({path:'/tmp/word-review-586.png'})
  await page.evaluate(key=>[...document.querySelector('[data-key="'+key+'"] .awd-rb-actions').querySelectorAll('button')].find(b=>b.textContent==='编辑').click(), firstComment.key)
  await page.waitForSelector('.awd-rb-editor')
  await page.$eval('.awd-rb-editor',(n,text)=>{n.value=text;n.dispatchEvent(new Event('input',{bubbles:true}))},comment+'界面编辑已保存。')
  await page.evaluate(()=>[...document.querySelectorAll('.awd-rb-card button')].find(b=>b.textContent==='保存').click())
  await page.waitForFunction(async()=> (await window.__loExecutor.executeCommand('list_comments',{})).comments[0].content.endsWith('界面编辑已保存。'))
  console.log('PASS edit/save through the visible balloon controls')
  // Save/import preserves the full review data and imported table grouping.
  const exported=await ok('export_document');const bytes=exported.bytes||exported.data
  assert.ok(bytes,'export returns bytes')
  await ok('load_document',{name:'word-review-roundtrip.docx',bytes})
  rows=(await ok('list_revisions')).revisions;groups=groupRevisions(rows)
  assert.equal(rows.find(r=>r.type==='Delete').text,deleted)
  assert.equal(groups.filter(g=>g.operationId).length,2)
  const chosen=groups.find(g=>g.operationId), originalCount=rows.length
  const version=(await ok('get_review_layout')).revision
  const stale=await exec('resolve_revisions',{indices:chosen.items.map(r=>r.index),action:'accept',revision:version-1})
  assert.equal(stale.success,false)
  assert.equal((await ok('list_revisions')).count,originalCount)
  await ok('resolve_revisions',{indices:chosen.items.map(r=>r.index).sort((a,b)=>b-a),action:'accept',revision:version})
  assert.equal((await ok('list_revisions')).count,originalCount-4)
  rows=(await ok('list_revisions')).revisions
  const deletion=rows.find(r=>r.type==='Delete')
  await ok('resolve_revision',{index:deletion.index,action:'reject'})
  assert.equal((await ok('list_revisions')).revisions.some(r=>r.type==='Delete'),false)
  // Replacement balloons retain comment editing/deletion without opening native notes.
  const originalComment=(await ok('list_comments')).comments[0]
  await ok('update_comment',{id:originalComment.id,content:'已核对的批注内容。',expectedContent:originalComment.content})
  assert.equal((await ok('list_comments')).comments.find(c=>c.id===originalComment.id).content,'已核对的批注内容。')
  assert.equal((await exec('update_comment',{id:originalComment.id,content:'过期修改',expectedContent:originalComment.content})).success,false)
  const countBeforeDelete=(await ok('list_revisions')).count
  await ok('delete_comment',{id:originalComment.id})
  assert.equal((await ok('list_comments')).count,2)
  assert.equal((await ok('list_revisions')).count,countBeforeDelete,'comment removal creates no phantom revision')
  assert.equal((await exec('update_comment',{id:originalComment.id,content:'不能误改下一条'})).success,false)
  await ok('undo');assert.equal((await ok('list_comments')).count,3,'one undo restores the deleted comment')
  await ok('redo');assert.equal((await ok('list_comments')).count,2)
  assert.equal((await ok('set_revision_view')).mode,'all')
  console.log('PASS comment edit/delete and stale edit protection')
  console.log('PASS zoom, docx roundtrip, stale-index protection, group acceptance and inline deletion rejection')
  const oldRevision=(await ok('get_review_layout')).revision
  await ok('load_document',{name:'word-review-replaced.docx',bytes:await fixture()})
  const replacedComment=(await ok('list_comments')).comments[0]
  for (const action of ['update_comment','delete_comment','set_comment_resolved']) {
    assert.equal((await exec(action,{id:replacedComment.id,content:replacedComment.content,revision:oldRevision})).success,false,'old document '+action+' rejected')
  }
  await ok('compare_document',{baseBytes:await fixture()})
  assert.equal((await ok('get_review_layout')).writable,false)
  for (const action of ['update_comment','delete_comment','set_comment_resolved']) {
    assert.equal((await exec(action,{id:replacedComment.id,content:'只读中不能写入'})).success,false,'readonly '+action+' rejected')
  }
  await ok('load_document',{name:'word-review-editable.docx',bytes:await fixture()})
  await ok('debug_deleted_paragraph')
  const paragraphs=async(agent=false)=>(await ok('get_document_text',{__agent:agent})).paragraphs.map(p=>p.text)
  const inline=await paragraphs()
  const final=await paragraphs(true)
  assert.equal(inline.join('|'),'一段|删除段|末段')
  assert.equal(final.join('|'),'一段|末段','final-text cache excludes a whole deleted paragraph')
  assert.deepEqual(await paragraphs(),inline,'returning to inline restores paragraph membership')
  assert.deepEqual(await paragraphs(true),final,'repeated final-text reads retain correct indices')
  console.log('PASS replacement-document fences, readonly comments and whole-paragraph final-text indices')
  await ok('set_track_changes',{on:false});await ok('ui_command',{name:'select_all'})
  await ok('replace_selection',{text:'正文abc'});await ok('set_track_changes',{on:true});await ok('goto',{type:'end'})
  await page.$eval('input[data-lo-ime]',input=>input.focus())
  for (let i=0;i<3;i++) { await page.keyboard.press('Backspace');await new Promise(r=>setTimeout(r,100)) }
  assert.equal((await paragraphs(true)).join('|'),'正文','inline backspace deletes three consecutive original characters')
  assert.equal((await paragraphs()).join('|'),'正文abc','deleted original characters remain inline')
  const cdp=await page.createCDPSession()
  await cdp.send('Input.imeSetComposition',{text:'中文',selectionStart:2,selectionEnd:2})
  await cdp.send('Input.insertText',{text:'中文'});await new Promise(r=>setTimeout(r,300))
  assert.equal((await paragraphs(true)).join('|'),'正文中文','typing continues after inline tracked deletion')
  console.log('PASS real consecutive Backspace and IME typing in default inline mode')


  console.log('Screenshot /tmp/word-review-586.png')
} finally {await browser.close();await new Promise(r=>server.close(r))}

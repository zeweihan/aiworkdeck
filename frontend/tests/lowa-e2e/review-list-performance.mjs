// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import fs from 'node:fs'
import JSZip from 'jszip'
import { fixture } from './_revision-fixture.mjs'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'
const zip=await JSZip.loadAsync(Uint8Array.from(await fixture(false)))
const paragraph=(p,table=false)=>'<w:p>'+([0,24,48].includes(p)?`<w:commentRangeStart w:id="${p}"/>`:'')+Array.from({length:10},(_,n)=>{
 const id=p*10+n,type=n%2?'del':'ins',tag=n%2?'delText':'t'
 return `<w:${type} w:id="${id}" w:author="Reviewer ${n}" w:date="2026-10-08T08:00:00Z"><w:r><w:${tag}>第${id}项${table?'表格':'正文'}约定。</w:${tag}></w:r></w:${type}>`
}).join('')+([0,24,48].includes(p)?`<w:commentRangeEnd w:id="${p}"/><w:r><w:commentReference w:id="${p}"/></w:r>`:'')+'</w:p>'
const body=Array.from({length:49},(_,p)=>paragraph(p)).join('')+'<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="9000"/></w:tblGrid><w:tr><w:tc><w:tcPr/><w:p><w:r><w:t>表格前文。</w:t></w:r></w:p>'+paragraph(49,true)+'</w:tc></w:tr></w:tbl>'
zip.file('word/document.xml',(await zip.file('word/document.xml').async('string')).replace(/<w:body>[\s\S]*?<w:sectPr>/,'<w:body>'+body+'<w:sectPr>'))
zip.file('[Content_Types].xml',(await zip.file('[Content_Types].xml').async('string')).replace('</Types>','<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>'))
zip.file('word/_rels/document.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="comments" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>')
zip.file('word/comments.xml','<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'+[0,24,48].map(p=>`<w:comment w:id="${p}" w:author="Reviewer" w:date="2026-10-08T08:00:00Z"><w:p><w:r><w:t>核对第${p}段修改理由。</w:t></w:r></w:p></w:comment>`).join('')+'</w:comments>')
const bytes=Array.from(await zip.generateAsync({type:'uint8array'}))
const fontRoot=process.env.LOWA_FONT_DIR||'dist/zetaoffice'
const extraFiles=Object.fromEntries(['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'].map(f=>['/'+f,fontRoot+'/'+f]))
if(process.env.REVIEW_WORKER_SOURCE)extraFiles['/office_thread.js']=process.env.REVIEW_WORKER_SOURCE
preflight();const server=await startServer({extraFiles});const browser=await launchBrowser(await loadPuppeteer(), { headed: true })
try{
 const page=await openEditor(browser)
 // A private headed test window avoids headless UNO proxy timing differences.
 // Ignore desktop typing while this read-only benchmark owns the window.
 await page.evaluate(() => {
  for (const type of ['keydown', 'keyup', 'beforeinput', 'input']) {
   window.addEventListener(type, e => { e.preventDefault(); e.stopImmediatePropagation() }, true)
  }
 })
 const ok=async(a,p={})=>{const r=await page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p);assert.equal(r.success,true,a);return r}
 await ok('load_document',{name:'review-list-synthetic.docx',bytes})
 const results={}
 for(const mode of ['all','balloons']){
  await ok('set_revision_view',{mode})
  const t=performance.now(),rv=await ok('list_revisions',{limit:500}),cm=await ok('list_comments',{limit:500})
  const ms=Math.round(performance.now()-t)
  assert.equal(rv.revisions.length,500);assert.equal(cm.comments.length,3)
  assert.equal(new Set(rv.revisions.map(r=>r.identifier)).size,500)
  results[mode]={ms,revisions:rv.revisions.map(({identifier,...r})=>r),comments:cm.comments.map(({id,...c})=>c)}
  assert.ok(rv.revisions.some(r=>r.inTable),'table story retained')
  assert.ok(rv.revisions.filter(r=>r.paraKey>=0).length>=490,'body locators retained')
  assert.ok(rv.revisions.every(r=>r.paragraph),'paragraph context retained')
  console.log(JSON.stringify({mode,ms,revisions:rv.revisions.length,comments:cm.comments.length}))
 }
 if(process.env.REVIEW_SNAPSHOT_OUT)fs.writeFileSync(process.env.REVIEW_SNAPSHOT_OUT,JSON.stringify(results))
 if(process.env.REVIEW_SNAPSHOT_BASE){
  const baseline=JSON.parse(fs.readFileSync(process.env.REVIEW_SNAPSHOT_BASE,'utf8'))
  for(const mode of ['all','balloons']){
   assert.deepEqual(results[mode].revisions,baseline[mode].revisions,mode+' full revision metadata equals pre-fix')
   assert.deepEqual(results[mode].comments,baseline[mode].comments,mode+' comment metadata equals pre-fix')
  }
 }
 // Keep performance assertions after optional baseline capture, for red/green replay.
 for(const mode of ['all','balloons'])assert.ok(results[mode].ms<15000,mode+' panel metadata exceeds 15s: '+results[mode].ms)
}finally{await browser.close();await new Promise(r=>server.close(r))}

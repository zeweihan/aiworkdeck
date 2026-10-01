// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Bounded isolated-browser 2,000-page comparison probe; never opens installed App.
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import JSZip from 'jszip'
import assert from 'node:assert/strict'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'
const out = process.env.COMPARISON_EVIDENCE_DIR || '/tmp/awd-comparison-large'
fs.mkdirSync(out, { recursive:true })
const pages = Number(process.env.COMPARISON_PAGES || 2000)
const result = { pages, stages:[], peakBrowserRssMiB:0 }
const ns='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const escape=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;')
const text=i=>'第'+String(i+1).padStart(4,'0')+'页。'+('双方应当依照本合同约定履行各自义务，相关通知以书面形式送达，并在约定期限内完成核对。').repeat(10)
async function fixture(revised) {
 const zip=new JSZip();zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
 const order=Array.from({length:pages},(_,i)=>i);if(revised && pages>1200)order.splice(1200,0,order.splice(700,1)[0]);
 zip.file('word/document.xml',`<w:document xmlns:w="${ns}"><w:body>${order.map((i,j)=>'<w:p><w:pPr>'+(j?'<w:pageBreakBefore/>':'')+'</w:pPr><w:r><w:rPr><w:sz w:val="20"/></w:rPr><w:t>'+escape(text(i)+(revised&&[5,999,pages-1].includes(i)?'本页新增一句。':''))+'</w:t></w:r></w:p>').join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`);
 return Array.from(await zip.generateAsync({type:'uint8array',compression:'DEFLATE'}))
}
preflight()
const server=await startServer({patchServed(url,b){if(url==='/office_thread.js')return Buffer.from(b.toString().replace('const EXEC = {',`const EXEC = { debug_comparison_pages(){const vc=ctrl.getViewCursor();vc.gotoEnd(false);const pageCount=vc.getPage();return {success:true,pageCount,redlineCount:countRedlines(),paragraphs:bodyParagraphStrings().length};},`));if(/^\/assets\/editor-.*\.js$/.test(url))return Buffer.from(b.toString().replace(/(['"])get_hyperlink_at_cursor\1/,m=>m+',"debug_comparison_pages"'));return b;}})
const browser=await launchBrowser(await loadPuppeteer())
const pid=browser.process().pid
let exceeded=false,observer=null
const save=()=>fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2))
const children=()=>{const rows=execFileSync('ps',['-axo','pid=,ppid=,rss='],{encoding:'utf8'}).trim().split('\n').map(x=>x.trim().split(/\s+/).map(Number));const selected=new Set([pid]);let before;do{before=selected.size;for(const row of rows)if(selected.has(row[1]))selected.add(row[0]);}while(selected.size!==before);return rows.filter(x=>selected.has(x[0]));}
const stop=reason=>{if(exceeded)return;exceeded=true;result.failure=reason;save();for(const row of children().reverse()){try{process.kill(row[0],'SIGKILL')}catch{}}}
const timeout=setTimeout(()=>stop('300s hard time limit exceeded'),300000)
const monitor=setInterval(()=>{const rss=children().reduce((sum,row)=>sum+row[2],0)/1024;result.peakBrowserRssMiB=Math.max(rss,result.peakBrowserRssMiB);if(rss>6144)stop('6GiB browser RSS limit exceeded');save();},2000)
const stage=async(name,fn)=>{const start=Date.now();const value=await fn();result.stages.push({name,ms:Date.now()-start});save();return value;}
try {
 const page=await stage('boot',()=>openEditor(browser));await page.setViewport({width:1280,height:900});
 const exec=(a,p={})=>page.evaluate(async(a,p)=>{const r=await window.__loExecutor.executeCommand(a,p);if(r.bytes)r.bytes=Array.from(r.bytes);return r},a,p)
 const base=await fixture(false),revised=await fixture(true);result.inputBytes=[base.length,revised.length];save()
 fs.writeFileSync(path.join(out,'base.docx'),Buffer.from(base));fs.writeFileSync(path.join(out,'revised.docx'),Buffer.from(revised));
 observer=await browser.newPage();await observer.evaluate(()=>{window.__beat={ticks:0,maxGapMs:0,last:performance.now()};setInterval(()=>{const now=performance.now();window.__beat.ticks++;window.__beat.maxGapMs=Math.max(window.__beat.maxGapMs,now-window.__beat.last);window.__beat.last=now},50)});
 const comparison=await stage('build_comparison_document',()=>exec('build_comparison_document',{baseBytes:base,revisedBytes:revised}));result.comparison=comparison;assert.equal(comparison.success,true,JSON.stringify(comparison))
 const exported=await stage('export',()=>exec('export_document'));assert.equal(exported.success,true);fs.writeFileSync(path.join(out,'native.docx'),Buffer.from(exported.bytes));result.outputBytes=exported.bytes.length
 const loaded=await stage('reopen',()=>exec('load_document',{bytes:exported.bytes,name:'large-comparison.docx'}));assert.equal(loaded.success,true)
 result.layout=await stage('layout-last-page',()=>exec('debug_comparison_pages'));assert.ok(result.layout.pageCount>=pages)
 result.review=await stage('review-list',()=>exec('list_revisions',{limit:100,locate:false}));assert.ok(result.review.count>0)
 await page.addStyleTag({content:'#verify,#vlog{display:none!important}'});await stage('screenshot-last-page',()=>page.screenshot({path:path.join(out,'last-page.png')}))
 result.separatePageHeartbeat=await observer.evaluate(()=>window.__beat);result.success=true;save();console.log(JSON.stringify(result,null,2))
} catch(error) {result.success=false;result.error=String(error);if(observer&&!exceeded){result.separatePageHeartbeat=await Promise.race([observer.evaluate(()=>window.__beat).catch(()=>null),new Promise(r=>setTimeout(()=>r({unavailable:true}),2000))]);}save();throw error}
finally {clearTimeout(timeout);clearInterval(monitor);if(!exceeded)await browser.close();await new Promise(r=>server.close(r))}

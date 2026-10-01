// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Bounded isolated-browser 2,000-page comparison probe; never opens installed App.
import fs from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import JSZip from 'jszip'
import assert from 'node:assert/strict'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor, distDir } from './_boot.mjs'
const out = process.env.COMPARISON_EVIDENCE_DIR || '/tmp/awd-comparison-large'
fs.mkdirSync(out, { recursive:true })
const pages = Number(process.env.COMPARISON_PAGES || 2000)
const dense = process.env.COMPARISON_DENSE === '1'
const result = { pages, dense, stages:[], peakBrowserRssMiB:0 }
const ns='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const escape=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;')
const text=i=>'第'+String(i+1).padStart(4,'0')+'页。'+('双方应当依照本合同约定履行各自义务，相关通知以书面形式送达，并在约定期限内完成核对。').repeat(Number(process.env.COMPARISON_REPEATS || 10))
async function fixture(revised) {
 const zip=new JSZip();zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
 const order=Array.from({length:pages},(_,i)=>i);if(revised && pages>1200)order.splice(1200,0,order.splice(700,1)[0]);
 zip.file('word/document.xml',`<w:document xmlns:w="${ns}"><w:body>${order.map((i,j)=>'<w:p><w:pPr>'+(j?'<w:pageBreakBefore/>':'')+'</w:pPr><w:r><w:rPr><w:sz w:val="20"/></w:rPr><w:t>'+escape((revised&&dense?text(i).replace('双方应当依照','双方须依照'):text(i))+(revised&&!dense&&[5,999,pages-1].includes(i)?'本页新增一句。':''))+'</w:t></w:r></w:p>').join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`);
 return Array.from(await zip.generateAsync({type:'uint8array',compression:'DEFLATE'}))
}
const fontDir = process.env.LOWA_FONTS_DIR || distDir
const fontNames = ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']
preflight(fontNames.map(f => ['CJK font (LOWA_FONTS_DIR)', path.join(fontDir, f)]))
const server=await startServer({extraFiles:Object.fromEntries(fontNames.map(f=>['/'+f,path.join(fontDir,f)])),patchServed(url,b){if(url==='/office_thread.js'){let source=b.toString();return Buffer.from(source.replace('const EXEC = {',`const EXEC = { debug_comparison_pages(){const vc=ctrl.getViewCursor();vc.gotoEnd(false);const pageCount=vc.getPage();const tail=xModel.getText().createTextCursor();tail.gotoEnd(false);tail.goLeft(60,true);return {success:true,pageCount,redlineCount:countRedlines(),endText:tail.getString()};},`));}if(/^\/assets\/editor-.*\.js$/.test(url))return Buffer.from(b.toString().replace(/(['"])get_hyperlink_at_cursor\1/,m=>m+',"debug_comparison_pages"'));return b;}})
const browser=await launchBrowser(await loadPuppeteer())
const browserLog=path.join(out,'browser.log')
fs.writeFileSync(browserLog,'')
const logBrowser=(kind,text)=>fs.appendFileSync(browserLog,`${new Date().toISOString()} ${kind} ${text}\n`)
browser.on('targetcreated',async target=>{
 if(target.type()!=='page')return;
 const page=await target.page();
 if(!page)return;
 page.on('console',message=>logBrowser(message.type(),message.text()));
 page.on('pageerror',error=>logBrowser('pageerror',error.stack||String(error)));
})
const pid=browser.process().pid;result.browserPid=pid;result.cpuSamples=[]
let exceeded=false,observer=null,heartbeatInFlight=false
const save=()=>fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2))
const children=()=>{const rows=execFileSync('ps',['-axo','pid=,ppid=,rss=,time='],{encoding:'utf8'}).trim().split('\n').map(x=>{const p=x.trim().split(/\s+/);return [Number(p[0]),Number(p[1]),Number(p[2]),p[3]]});const selected=new Set([pid]);let before;do{before=selected.size;for(const row of rows)if(selected.has(row[1]))selected.add(row[0]);}while(selected.size!==before);return rows.filter(x=>selected.has(x[0]));}
const stop=reason=>{if(exceeded)return;exceeded=true;result.failure=reason;save();for(const row of children().reverse()){try{process.kill(row[0],'SIGKILL')}catch{}}}
const timeout=setTimeout(()=>stop('300s hard time limit exceeded'),300000)
const monitor=setInterval(()=>{const rows=children();const rss=rows.reduce((sum,row)=>sum+row[2],0)/1024;const cpu=rows.reduce((sum,row)=>sum+String(row[3]).split(':').reduce((s,v)=>s*60+Number(v),0),0);result.cpuSamples.push({at:Date.now(),rssMiB:rss,cpuSeconds:cpu});result.peakBrowserRssMiB=Math.max(rss,result.peakBrowserRssMiB);if(rss>5632)stop('5.5GiB safety stop before 6GiB browser RSS cap');if(observer&&!heartbeatInFlight&&!exceeded){heartbeatInFlight=true;observer.evaluate(()=>window.__beat).then(beat=>{result.separatePageHeartbeat=beat}).catch(()=>{}).finally(()=>{heartbeatInFlight=false})}save();},dense?500:2000)
const stage=async(name,fn)=>{const start=Date.now();result.activeStage=name;save();logBrowser('stage',name);const value=await fn();result.stages.push({name,ms:Date.now()-start});save();return value;}
try {
 const page=await stage('boot',()=>openEditor(browser,{viewport:{width:1280,height:900}}));
 const exec=(a,p={})=>page.evaluate(async(a,p)=>{const r=await window.__loExecutor.executeCommand(a,p);if(r.bytes)r.bytes=Array.from(r.bytes);return r},a,p)
 result.fonts=await stage('fonts',()=>exec('list_fonts'));assert.ok(result.fonts.families.includes('Noto Sans SC'),'real CJK font required');
 const base=await fixture(false),revised=await fixture(true);result.inputBytes=[base.length,revised.length];const hash=b=>createHash('sha256').update(Buffer.from(b)).digest('hex');result.inputHashes=[hash(base),hash(revised)];save()
 fs.writeFileSync(path.join(out,'base.docx'),Buffer.from(base));fs.writeFileSync(path.join(out,'revised.docx'),Buffer.from(revised));
 observer=await browser.newPage();await observer.evaluate(()=>{window.__beat={ticks:0,maxGapMs:0,last:performance.now()};setInterval(()=>{const now=performance.now();window.__beat.ticks++;window.__beat.maxGapMs=Math.max(window.__beat.maxGapMs,now-window.__beat.last);window.__beat.last=now},50)});await page.bringToFront();
 const comparison=await stage(process.env.COMPARISON_BASELINE_ONLY==='1'?'load-baseline':'build_comparison_document',()=>process.env.COMPARISON_BASELINE_ONLY==='1'?exec('load_document',{bytes:base,name:'base.docx'}):exec('build_comparison_document',{baseBytes:base,revisedBytes:revised}));result.comparison=comparison;assert.equal(comparison.success,true,JSON.stringify(comparison));if(dense&&process.env.COMPARISON_BASELINE_ONLY!=='1')assert.ok(comparison.redlineCount>=pages,'tracked changes cover all edited pages')
 const exported=await stage('export',()=>exec('export_document'));result.export={success:exported.success,message:exported.message,size:exported.size,code:exported.code};save();assert.equal(exported.success,true,JSON.stringify(result.export));fs.writeFileSync(path.join(out,'native.docx'),Buffer.from(exported.bytes));result.outputBytes=exported.bytes.length
 // Compare complete text projections, not just revision counts or the last page.
 const projection=async(bytes,finalSide)=>{
  const zip=await JSZip.loadAsync(bytes),xml=await zip.file('word/document.xml').async('string');
  return page.evaluate((xml,finalSide)=>{
   const doc=new DOMParser().parseFromString(xml,'application/xml');
   const walk=node=>{
    if(node.nodeType!==1)return '';
    const tag=node.localName;
    if(['pPr','rPr','trPr','tcPr'].includes(tag)||(finalSide&&['del','moveFrom'].includes(tag))||(!finalSide&&['ins','moveTo'].includes(tag)))return '';
    if(['t','delText'].includes(tag))return node.textContent;
    return Array.from(node.children).map(walk).join('');
   };
   return walk(doc.documentElement);
  },xml,finalSide);
 };
 const expectedFinal=process.env.COMPARISON_BASELINE_ONLY==='1'?base:revised;
 const [baseText,revisedText,originalText,finalText]=await Promise.all([projection(base,true),projection(expectedFinal,true),projection(exported.bytes,false),projection(exported.bytes,true)]);
 result.projections={originalExact:baseText===originalText,finalExact:revisedText===finalText,baseChars:baseText.length,revisedChars:revisedText.length};save();
 assert.ok(result.projections.originalExact,'native original projection must exactly equal baseline');assert.ok(result.projections.finalExact,'native final projection must exactly equal revised');
 const loaded=await stage('reopen',()=>exec('load_document',{bytes:exported.bytes,name:'large-comparison.docx'}));assert.equal(loaded.success,true)
 result.layout=await stage('layout-last-page',()=>exec('debug_comparison_pages'));assert.ok(result.layout.pageCount>=pages)
 result.review=await stage('review-list',()=>exec('list_revisions',{limit:100,locate:false}));assert.ok(process.env.COMPARISON_BASELINE_ONLY==='1'?result.review.count===0:result.review.count>0)
 await page.bringToFront();await page.addStyleTag({content:'#verify,#vlog{display:none!important}'});await exec('set_chrome',{all:false,menubar:false,statusbar:false,toolbars:false,rulers:false});await new Promise(r=>setTimeout(r,1500));await page.screenshot();await new Promise(r=>setTimeout(r,200));await stage('screenshot-last-page',()=>page.screenshot({path:path.join(out,'last-page.png')}));
 const edit=await stage('edit-last-page',()=>exec('insert_at_cursor',{text:'复测追加。'}));assert.equal(edit.success,true);const saved=await stage('save-edited',()=>exec('export_document'));assert.equal(saved.success,true);fs.writeFileSync(path.join(out,'edited.docx'),Buffer.from(saved.bytes));await stage('reopen-edited',()=>exec('load_document',{bytes:saved.bytes,name:'edited.docx'}));result.editedLayout=await exec('debug_comparison_pages');assert.ok(result.editedLayout.endText.includes('复测追加。'));assert.deepEqual([hash(base),hash(revised)],result.inputHashes);
 if(!heartbeatInFlight){heartbeatInFlight=true;result.separatePageHeartbeat=await observer.evaluate(()=>window.__beat);}result.success=true;save();console.log(JSON.stringify(result,null,2))
} catch(error) {result.success=false;result.error=String(error);if(observer&&!exceeded&&!heartbeatInFlight){heartbeatInFlight=true;const beat=await Promise.race([observer.evaluate(()=>window.__beat).catch(()=>null),new Promise(r=>setTimeout(()=>r(null),2000))]);if(beat)result.separatePageHeartbeat=beat;}save();throw error}
finally {clearTimeout(timeout);clearInterval(monitor);if(!exceeded)await browser.close();await new Promise(r=>server.close(r))}

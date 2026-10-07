// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Regression for dev-board#1131/#1132. Use LOWA_ENGINE_DIR and LOWA_FONT_DIR
// for external assets; MODIFY_WORKER_SOURCE can replay the pre-fix worker.
import assert from 'node:assert/strict';
import JSZip from 'jszip';
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs';
const root = process.env.LOWA_FONT_DIR || 'dist/zetaoffice';
const extraFiles={'/office_thread.js':process.env.MODIFY_WORKER_SOURCE || 'src/zetaoffice/public/office_thread.js'};
for(const f of ['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'])extraFiles['/'+f]=root+'/'+f;
preflight(); const server=await startServer({extraFiles});
const browser=await launchBrowser(await loadPuppeteer());
try {
 const page=await openEditor(browser);
 const ok=async(a,p={})=>{const t=Date.now();console.log('BEGIN',a,p.index??p.mode??'');const r=await page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p);console.log('END',a,Date.now()-t,r.success);assert.equal(r.success,true);return r};
 const paragraphs=Array.from({length:60},(_,i)=>`第${i}条 `+('甲方应在三十日内支付合同价款，乙方应当交付全部工作成果并承担相应责任。双方应当履行通知义务，未经书面同意不得转让本协议项下权利。'.repeat(3)));
 const zip=new JSZip();zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');
 zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
 zip.file('word/document.xml',`<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraphs.map(t=>'<w:p><w:r><w:t>'+t+'</w:t></w:r></w:p>').join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`);
 await ok('load_document',{name:'synthetic-freeze.docx',bytes:Array.from(await zip.generateAsync({type:'uint8array'}))});
 const expected = [...paragraphs], timings = [];
 for(let i=0;i<24;i++){
 if(i===8)await ok('set_revision_view',{mode:'balloons'});
 expected[i] = paragraphs[i].replaceAll('三十日','六十日').replaceAll('支付合同价款','支付全部服务费用').replaceAll('交付全部工作成果','按期交付合格工作成果').replaceAll('承担相应责任','依约承担违约责任').replaceAll('通知义务','书面通知义务');
 const started=Date.now();
 const receipt=await ok('modify_paragraph',{index:i,newText:expected[i],__agent:true});
 timings.push(Date.now()-started);
 assert.equal(receipt.paragraphAfterEdit, expected[i].slice(0,200));
 assert.ok(timings.at(-1)<20000, 'one paragraph must not stall the editor for 20s: '+timings.at(-1));
 }

 assert.equal((await ok('set_revision_view')).mode,'balloons');
 // Saving must preserve both the original and final text, including untouched paragraphs.
 const output=await page.evaluate(async()=>Array.from((await window.__loExecutor.executeCommand('export_document',{name:'revisions.docx'})).bytes));
 const saved=await JSZip.loadAsync(Buffer.from(output));
 const xml=await saved.file('word/document.xml').async('string');
 const contents=await page.evaluate(xml=>{
   const W='http://schemas.openxmlformats.org/wordprocessingml/2006/main';
   const document=new DOMParser().parseFromString(xml,'application/xml');
   const paragraphs=[...document.getElementsByTagNameNS(W,'p')];
   const read=original=>paragraphs.map(p=>[...p.getElementsByTagName('*')].filter(n=>{
     if(n.namespaceURI!==W||!['t','delText'].includes(n.localName))return false;
     for(let parent=n.parentElement;parent&&parent!==p;parent=parent.parentElement){
       if(parent.namespaceURI===W&&parent.localName===(original?'ins':'del'))return false;
     }
     return true;
   }).map(n=>n.textContent).join(''));
   return {original:read(true),final:read(false),inserts:document.getElementsByTagNameNS(W,'ins').length,deletions:document.getElementsByTagNameNS(W,'del').length};
 },xml);
 assert.deepEqual(contents.original, paragraphs, 'rejecting all revisions must reconstruct the original');
 assert.deepEqual(contents.final, expected, 'accepting all revisions must give exactly the requested text');
 assert.ok(contents.inserts>24&&contents.deletions>24, 'retain character-level tracked changes');
 assert.equal((await ok('set_revision_view')).mode,'balloons', 'export restores the selected display');
 await ok('load_document',{name:'reopened.docx',bytes:output});
 for(const index of [0,7,8,23,24,59])assert.equal((await ok('get_paragraph',{index,__agent:true})).text,expected[index]);
 // Editing still works after saving/reopening: no leaked controller/action locks.
 await ok('modify_paragraph',{index:59,newText:expected[59]+'已复核。',__agent:true});
 assert.equal((await ok('get_paragraph',{index:59,__agent:true})).text,expected[59]+'已复核。');
 // Exercise the real yielding replace loop, selecting a different view from
 // its progress callback while more edits remain. The chosen view must win.
 for (const [initial, requested] of [['all','balloons'], ['balloons','all']]) {
   await ok('load_document',{name:'async-switch.docx',bytes:Array.from(await zip.generateAsync({type:'uint8array'}))});
   await ok('set_revision_view',{mode:initial});
   const result=await page.evaluate(async requested=>{
     const execute=window.__loExecutor.executeCommand.bind(window.__loExecutor);
     let selection, finished=false, selectedBeforeFinish=false;
     const edited=await execute('find_replace',{findText:'三十日内支付合同价款',replaceText:'六十日内支付服务费用',replaceAll:true,__agent:true},{
       onProgress(p) {
         if (!selection && p.done<p.total) selection=execute('set_revision_view',{mode:requested}).then(r=>{
           selectedBeforeFinish=!finished;return r;
         });
       },
     }).then(r=>{finished=true;return r});
     return {edited,selected:selection?await selection:null,selectedBeforeFinish};
   },requested);
   assert.equal(result.edited.success,true);
   assert.equal(result.edited.replaced,180);
   assert.ok(result.selected, 'view selection was issued during the real batch');
   assert.equal(result.selectedBeforeFinish,false);
   assert.equal(result.selected.mode,requested);
   assert.equal((await ok('set_revision_view')).mode,requested);
   for(const index of [0,30,59])assert.equal((await ok('get_paragraph',{index,__agent:true})).text,
     paragraphs[index].replaceAll('三十日内支付合同价款','六十日内支付服务费用'));
 }
 console.log(JSON.stringify({pass:true,paragraphMs:timings,totalEditMs:timings.reduce((a,b)=>a+b,0),inserts:contents.inserts,deletions:contents.deletions,roundTrip:true}));

}finally{await browser.close();await new Promise(r=>server.close(r));}

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict';
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs';
const root=process.env.LOWA_FONT_DIR || 'dist/zetaoffice';
const extraFiles={'/office_thread.js':process.env.PASTE_WORKER_SOURCE || 'src/zetaoffice/public/office_thread.js'};
for(const f of ['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'])extraFiles['/'+f]=root+'/'+f;
preflight(); const server=await startServer({extraFiles});
const browser=await launchBrowser(await loadPuppeteer());
try {
 const page=await openEditor(browser);
 page.on('pageerror',e=>console.error(e.message));
 const exec=(a,p={})=>page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p);
 await exec('set_chrome',{menubar:false,statusbar:false,toolbars:false,rulers:true});
 const lines=Array.from({length:180},(_,i)=>`第${i+1}段：`+'本段是合成粘贴测试内容，用于核对正文完整性和保存后的段落顺序。'.repeat(3));
 const text=lines.join('\n');
 console.log('PASTE START',text.length,lines.length);
 await page.evaluate(t=>navigator.clipboard.writeText(t),text);
 await page.focus('input[data-lo-ime]');
 await page.evaluate(()=>{const e=window.__loExecutor,run=e.executeCommand.bind(e);e.executeCommand=async(a,p,...rest)=>{const start=performance.now();const r=await run(a,p,...rest);if(a==='replace_selection')window.__paste={ms:performance.now()-start,success:r.success};return r;};});
 const start=Date.now();
 await page.keyboard.down('Meta');await page.keyboard.press('v');await page.keyboard.up('Meta');
 await page.waitForFunction('!!window.__paste',{timeout:120000});
 const receipt=await page.evaluate(()=>window.__paste);
 console.log('PASTE COMMAND',JSON.stringify(receipt));
 assert.equal(receipt.success,true);
 const paragraphs=[];let got;do{got=await exec('get_document_text',{startParagraph:paragraphs.length});paragraphs.push(...got.paragraphs.map(p=>p.text));}while(got.truncated);
 const elapsed=Date.now()-start;
 console.log('PASTE RESULT',elapsed,paragraphs.length);
 assert.deepEqual(paragraphs,lines);
 assert.ok(receipt.ms<10000,`paste blocked for ${elapsed}ms`);
 console.log('PASS real clipboard keyboard paste');
}finally{await browser.close();await new Promise(r=>server.close(r));}

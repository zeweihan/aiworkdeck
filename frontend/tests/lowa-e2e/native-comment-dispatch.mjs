// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Exercise the real UNO interceptor and precise Any marshalling. The served-only
// test switch simulates an active external gutter on older installed engines;
// it does not claim to verify native gutter painting. Source/dist remain intact.
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'
const workerPath = fileURLToPath(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url))
preflight()
const action = `
  native_comment_probe(p) {
    testExternal = p.external !== false;
    const ready = installReviewCommentInterceptor(ctrl);
    const before = testRequests;
    if (p.createField) {
      const field = xModel.createInstance('com.sun.star.text.textfield.Annotation');
      field.setPropertyValue('Author', 'Probe'); field.setPropertyValue('Content', p.text);
      xModel.getText().insertTextContent(xModel.getText().getEnd(), field, false);
    }
    if (p.dispatch) { ctrl.getFrame().getComponentWindow().setFocus(); selectVisibly(xModel.getText().getEnd()); }
    if (p.dispatch) css.frame.DispatchHelper.create(context).executeDispatch(ctrl.getFrame(), '.uno:InsertAnnotation', '', 0,
      p.text != null ? [mkProp('Text', p.text), mkProp('Author', 'Probe')] : []);
    return {success:true,ready,requests:testRequests-before,comments:EXEC.list_comments({limit:500}),documentSeq:docSeq};
  },
`
const extraFiles = {'/office_thread.js': workerPath, ...Object.fromEntries(['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'].map(f=>['/'+f,'/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/'+f]))}
const server=await startServer({extraFiles,patchServed(url,buf){
  let s=buf.toString()
  if(url==='/office_thread.js'){
    s=s.replace('let reviewCommentInterceptor = null;', 'let testExternal = true, testRequests = 0; let reviewCommentInterceptor = null;')
    s=s.replace("external = Number(controller.getPropertyValue('AwdReviewSidebarWidth')) > 0;", 'external = testExternal;')
    s=s.replace("if (external && !hasText) post('comment-request', { documentSeq: docSeq });", "if (external && !hasText) { ++testRequests; post('comment-request', { documentSeq: docSeq }); }")
    s=s.replace('const EXEC = {', 'const EXEC = {'+action)
    for(const probe of ['external = testExternal;','++testRequests;','native_comment_probe(p)'])assert.ok(s.includes(probe),'served worker patch landed: '+probe)
    return Buffer.from(s)
  }
  if(url.startsWith('/assets/')) {s=s.replace('["update_comment",', '["native_comment_probe","update_comment",');return Buffer.from(s)}
  return buf
}})
const browser=await launchBrowser(await loadPuppeteer())
try {
 const page=await browser.newPage();page.on('console',msg=>{if(/interception failed|批注输入通道|boot failed/.test(msg.text()))console.log(msg.text())})
 await page.goto(ORIGIN+'/editor.html?verify=1&lowa=/lowa/',{waitUntil:'domcontentloaded'})
 await page.waitForFunction('!!window.__loExecutor',{timeout:240000})
 const exec=(a,p={})=>page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p)
 let r=await exec('native_comment_probe');console.log('registration',JSON.stringify(r));assert.equal(r.ready,true)
 r=await exec('native_comment_probe',{dispatch:true});console.log('no Text',JSON.stringify(r));assert.equal(r.requests,1);assert.equal(r.comments.count,0)
 r=await exec('native_comment_probe',{dispatch:true,text:''});console.log('empty Text',JSON.stringify(r));assert.equal(r.requests,1);assert.equal(r.comments.count,0)
 r=await exec('native_comment_probe',{dispatch:true,text:'Native bridge confirmed'});console.log('Text',JSON.stringify(r));assert.equal(r.requests,0);assert.equal(r.comments.count,1);assert.equal(r.comments.comments[0].content,'Native bridge confirmed')

 // Create a second native field directly so an older engine's still-focused
 // annotation shell cannot reinterpret a repeated insertion as Reply.
 r=await exec('native_comment_probe',{createField:true,text:'Second fresh comment'});console.log('second comment',JSON.stringify(r));assert.equal(r.success,true,JSON.stringify(r));assert.equal(r.comments.count,2)
 const [first,second]=r.comments.comments
 assert.ok(first.id && second.id && first.id!==second.id);console.log('fresh IDs', first.id, second.id)
 r=await exec('update_comment',{id:first.id,content:'First independently updated',documentSeq:r.documentSeq,expectedComment:first});assert.equal(r.success,true,JSON.stringify(r))
 r=await exec('list_comments');assert.equal(r.comments.find(c=>c.id===first.id).content,'First independently updated');assert.equal(r.comments.find(c=>c.id===second.id).content,'Second fresh comment')
 const currentSecond=r.comments.find(c=>c.id===second.id)
 r=await exec('delete_comment',{id:second.id,documentSeq:r.documentSeq,expectedComment:currentSecond});assert.equal(r.success,true,JSON.stringify(r))
 r=await exec('list_comments');assert.equal(r.count,1);assert.equal(r.comments[0].id,first.id)
 r=await exec('get_ui_state');assert.equal(typeof r.documentSeq,'number');console.log('PASS real engine registration, empty Text interception, two fresh stable IDs, independent update/delete, documentSeq')
}finally{await browser.close();await new Promise(r=>server.close(r))}

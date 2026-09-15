// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Confirmed Chinese must reach both Writer and the canvas without a click.
// LOWA_IME_FIXTURE may point to a private saved copy: it stays local and no
// document text/screenshots are logged or written. Require the new native
// review path with LOWA_REQUIRE_NATIVE_REVIEW=1 when testing that artifact.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import JSZip from 'jszip'
import { PNG } from 'pngjs'
import { fixture } from './_revision-fixture.mjs'
import { preflight, startServer, launchBrowser, loadPuppeteer, ORIGIN } from './_boot.mjs'

async function documentBytes() {
  if (process.env.LOWA_IME_FIXTURE) return Array.from(fs.readFileSync(process.env.LOWA_IME_FIXTURE))
  const zip = await JSZip.loadAsync(Uint8Array.from(await fixture(false)))
  const run = text => `<w:r><w:t>${text}</w:t></w:r>`
  const paragraphs = Array.from({length:25}, (_,i) => `<w:p>${i<3?`<w:commentRangeStart w:id="${i}"/>`:''}<w:ins w:id="${100+i}" w:author="审阅人" w:date="2026-09-10T08:00:00Z">${run('第'+i+'条：双方按照约定核对文件资料和履行事项。')}</w:ins>${i<3?`<w:commentRangeEnd w:id="${i}"/><w:r><w:commentReference w:id="${i}"/></w:r>`:''}</w:p>`).join('')
  zip.file('word/document.xml',(await zip.file('word/document.xml').async('string')).replace('<w:sectPr>',paragraphs+'<w:sectPr>'))
  zip.file('[Content_Types].xml',(await zip.file('[Content_Types].xml').async('string')).replace('</Types>','<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>'))
  zip.file('word/_rels/document.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="comments" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>')
  zip.file('word/comments.xml','<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'+[0,1,2].map(i=>`<w:comment w:id="${i}" w:author="复核人" w:date="2026-09-10T08:00:00Z"><w:p>${run('请核对本条资料。')}</w:p></w:comment>`).join('')+'</w:comments>')
  return Array.from(await zip.generateAsync({type:'uint8array'}))
}

preflight()
const server = await startServer({ extraFiles: Object.fromEntries(['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'].map(f=>['/'+f,'/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/'+f])) })
// Headed on purpose: headless Chrome does not present the engine canvas until a
// capture resizes the view, and that resize repaints Writer by itself. Only a
// real display tells "painted on its own" from "painted because we poked it".
const browser = await launchBrowser(await loadPuppeteer(), { headed: true })
try {
  const page = await browser.newPage()
  await page.setViewport({width:1280,height:900,deviceScaleFactor:Number(process.env.LOWA_E2E_DPR || 1)})
  await page.goto(ORIGIN+'/editor.html?verify=1&lowa=/lowa/',{waitUntil:'domcontentloaded'})
  await page.waitForFunction('!!window.__loExecutor',{timeout:240000})
  await page.addStyleTag({content:'#verify,#vlog{display:none!important}'})
  const exec = (action,params={}) => page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),action,params)
  const ok = async (action,params) => {const result=await exec(action,params);assert.equal(result.success,true,action+' failed');return result}
  await ok('load_document',{name:'ime-review-regression.docx',bytes:await documentBytes()})
  await ok('set_chrome',{all:false})
  const layout = await ok('get_review_layout')
  if (process.env.LOWA_REQUIRE_NATIVE_REVIEW === '1') assert.equal(layout.available,true,'new native review geometry is required')
  await page.evaluate(()=>{
    window.imeCommitCalls=[]
    const executor=window.__loExecutor, original=executor.executeCommand.bind(executor)
    executor.executeCommand=async(action,params,options)=>{
      const row=action==='insert_at_cursor'?{start:performance.now()}:null
      if(row)window.imeCommitCalls.push(row)
      const result=await original(action,params,options)
      if(row){row.elapsed=performance.now()-row.start;row.success=result?.success}
      return result
    }
  })
  const cdp = await page.createCDPSession()
  for (const mode of layout.available ? ['all','balloons'] : ['all']) {
    await ok('set_revision_view',{mode})
    for (const order of ['normal','delayed-end','confirm-then-continue']) {
      const stale=(await ok('get_cursor_rect')).nativeCaret
      await ok('goto',{type:'end'});await ok('insert_paragraph')
      await page.$eval('[data-lo-ime]',e=>e.focus())
      await new Promise(r=>setTimeout(r,300))
      // The native caret geometry lags the model: for a while after
      // insert_paragraph it still reports the end of the PREVIOUS line, and the
      // ink clip derived from it then covers text that is already on the page —
      // a correct commit reads as 0 new pixels. Wait for it to reach the new
      // line instead of trusting a fixed delay.
      let caret=null
      for(let i=0;i<40;i++){
        caret=(await ok('get_cursor_rect')).nativeCaret
        if(caret&&(!stale||caret.x!==stale.x||caret.y!==stale.y))break
        await new Promise(r=>setTimeout(r,100))
      }
      assert.ok(caret,'native caret geometry is required to check the writing line')
      assert.ok(!stale||caret.x!==stale.x||caret.y!==stale.y,'caret geometry must reach the new paragraph before the ink clip is computed')
      const surface=await page.$eval('#qtcanvas',e=>{const r=e.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height}})
      const scale=surface.width/caret.frameWidth, menu=Math.max(0,surface.height-caret.frameHeight*scale)
      const x=Math.max(0,Math.floor(surface.left+caret.x*scale+4)), y=Math.max(0,Math.floor(surface.top+menu+caret.y*scale-2))
      const clip={x,y,width:Math.min(360,1280-x),height:Math.min(Math.ceil(caret.height*scale+8),900-y)}
      const before=PNG.sync.read(await page.screenshot({clip}))
      const count=await page.evaluate(()=>window.imeCommitCalls.length)
      const text=order==='normal'?'文件资料确认':order==='delayed-end'?'候选已经确认':'确认之后继续'
      if(order==='normal'){
        await cdp.send('Input.imeSetComposition',{text:'wenjian',selectionStart:7,selectionEnd:7})
        await cdp.send('Input.insertText',{text})
      }else if(order==='delayed-end')await page.$eval('[data-lo-ime]',(e,text)=>{
        e.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}))
        e.value=text
        e.dispatchEvent(new InputEvent('input',{data:text,inputType:'insertText',isComposing:false,bubbles:true}))
      },text)
      // dev-board#606: the confirmation arrives first AND the system keeps its
      // marked-text session open. Everything after it still belongs to the IME —
      // treating it as plain typing put raw pinyin on the page and let Backspace
      // eat the confirmed phrase.
      else await page.$eval('[data-lo-ime]',(e,text)=>{
        const input=(data,inputType,isComposing)=>e.dispatchEvent(new InputEvent('input',{data,inputType,isComposing,bubbles:true}))
        e.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}))
        e.value=text
        input(text,'insertText',false)
        e.dispatchEvent(new CompositionEvent('compositionupdate',{data:'ziliao',bubbles:true}))
        e.value=text+'ziliao'
        input('ziliao','insertCompositionText',true)
        e.dispatchEvent(new KeyboardEvent('keydown',{key:'Backspace',code:'Backspace',keyCode:8,bubbles:true,cancelable:true}))
        input('ziliao','insertText',false)
        e.dispatchEvent(new CompositionEvent('compositionend',{data:text,bubbles:true}))
      },text)
      await new Promise(r=>setTimeout(r,300))
      const after=PNG.sync.read(await page.screenshot({clip}))
      const calls=await page.evaluate(()=>window.imeCommitCalls)
      assert.equal(calls.length,count+1,'confirmed text dispatches before any click or delayed end')
      assert.equal(calls[count].success,true,'confirmed text finishes without another user action')
      assert.ok(calls[count].elapsed<1000,'input must not wait behind review scans: '+Math.round(calls[count].elapsed)+'ms')
      let addedInk=0
      for(let i=0;i<after.data.length;i+=4)if(Math.min(...after.data.subarray(i,i+3))<180&&Math.min(...before.data.subarray(i,i+3))>230)addedInk++
      assert.ok(addedInk>60,'new glyphs must paint before any click, not just move the caret')
      // Exact, not endsWith: raw preedit letters and a stolen Backspace both show
      // up only as a paragraph that is not exactly the confirmed phrase.
      assert.equal((await ok('get_cursor_context')).paragraph,text,'the paragraph holds the confirmed phrase and nothing else')
      if(order==='delayed-end'){
        await page.$eval('[data-lo-ime]',(e,text)=>e.dispatchEvent(new CompositionEvent('compositionend',{data:text,bubbles:true})),text)
        await new Promise(r=>setTimeout(r,80))
        assert.equal(await page.evaluate(()=>window.imeCommitCalls.length),count+1,'a late end cannot duplicate the phrase')
      }
      console.log('PASS IME '+mode+'/'+order+': '+Math.round(calls[count].elapsed)+'ms, '+addedInk+' newly painted pixels')
    }
  }
} finally { await browser.close();await new Promise(r=>server.close(r)) }

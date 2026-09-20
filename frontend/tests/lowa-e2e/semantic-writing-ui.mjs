// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real browser + LOWA, synthetic service response explicitly labelled in the screenshot.
import assert from 'node:assert/strict'
import path from 'node:path'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'
preflight()
const fontRoot = path.resolve(process.env.LOWA_ENGINE_DIR || 'dist/zetaoffice/lowa', '..')
const server = await startServer({ extraFiles: { '/semantic-host.js': path.resolve('src/composables/semanticWritingHost.js'),
  ...Object.fromEntries(['cjk.ttc','cjk-serif.otf','cjk-kai.ttf','cjk-fangsong.ttf'].map(file=>['/'+file,path.join(fontRoot,file)])) } })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  await page.setViewport({ width: 1440, height: 1100, deviceScaleFactor: 1 })
  const exec = (a,p={}) => page.evaluate((a,p)=>window.__loExecutor.executeCommand(a,p),a,p)
  const original = '法律尽职调查报告\n合成项目：青穗器材股权收购\n本页为本地合成预览，服务响应由测试夹具提供。\n关于业务资质文件，'
  await exec('set_revision_view',{mode:'final'})
  await exec('set_chrome',{all:false})
  await exec('ui_command',{name:'select_all'}); await exec('replace_selection',{text:original}); await exec('collapse_selection',{to:'end'})
  await page.evaluate(async()=>{
    const {createSemanticWritingHost}=await import('/semantic-host.js')
    const banner=document.createElement('div');banner.textContent='本地合成预览 · 真实 LOWA 编辑器 · 非模型实测结果'
    Object.assign(banner.style,{position:'fixed',left:'12px',top:'12px',zIndex:'2147482700',padding:'9px 14px',background:'#fff0ba',color:'#5d4916',font:'13px sans-serif',borderRadius:'6px'});document.body.append(banner)
    let request; window.__semanticTrace=[]
    const view={id:'synthetic-ui-job',status:'READY',profile:'diligence',profileLabel:'尽调报告与法律意见书',facts:[{id:'fixture-fact',sourceId:'fixture-source',label:'材料取得状态',value:'未取得',quote:'材料取得状态：未取得'}],sources:[{id:'fixture-source',name:'合成调查清单.docx',version:'V2',locator:'第4项',text:'核查事项：业务资质文件\n材料取得状态：未取得'}],advice:[{text:'材料取得记录显示尚未取得相关文件，相关事项尚待核实。',factIds:['fixture-fact'],sourceIds:['fixture-source'],explanation:'材料记载仅说明取得状态，不推断资质不存在。'}],warnings:[]}
    const host=createSemanticWritingHost({projectId:999,fileId:999,title:'合成法律尽职调查报告',writable:true,
      execute:async(a,p)=>{const r=await window.__loExecutor.executeCommand(a,p);window.__semanticTrace.push({action:a,revision:r?.revision,success:r?.success,reason:r?.reason,message:r?.message});return r},send:m=>{window.__semanticTrace.push({config:m.config});window.postMessage(m,location.origin)},
      api:{settings:async()=>({stance:'收购方',cutoffDate:'2026-09-15',files:[{id:1000,name:'合成调查清单.docx'},{id:1001,name:'合成材料版本说明.docx'}]}),saveSettings:async(p,d)=>d,
        suggest:async(p,d)=>{request=d;return view},get:async()=>view,cancel:async()=>({status:'CANCELLED'}),accept:async()=>({text:view.advice[0].text,revision:request.context.revision,selection:request.context.selection})}})
    host.bindSession('semantic-ui-fixture')
    window.addEventListener('message',async e=>{const m=e.data;if(m?.type==='modified'){window.__semanticTrace.push({event:'modified'});host.modified();return}if(m?.type!=='writing-request'||!m.action?.startsWith('semantic-')||m.session!=='semantic-ui-fixture')return
      window.__semanticTrace.push({event:m.action})
      try{const result=await host.perform(m.action,m.data);window.postMessage({__lo:'lo-relay',type:'writing-response',session:m.session,id:m.id,result},location.origin)}catch(error){window.postMessage({__lo:'lo-relay',type:'writing-response',session:m.session,id:m.id,error:error.message},location.origin)}})
    // 客体回报的可用/开合态（dev-board#748）——真实宿主据此决定工具栏按钮的显隐与按下态。
    window.addEventListener('message',e=>{if(e.data?.type==='semantic-writing-state')window.__semanticState=e.data})
    window.postMessage({__lo:'lo-relay',type:'writing-config',config:{session:'semantic-ui-fixture',writable:true,enabled:false,learning:false,items:[]}},location.origin)
  })
  const clickText = text => page.evaluate(text=>[...document.querySelectorAll('.awd-semantic button')].find(b=>b.textContent===text)?.click(),text)
  // 画布上不再有那颗固定按钮（dev-board#748）：唯一入口在宿主工具栏，
  // 这里发它那条开合指令代替点击；面板关着时根节点整个不渲染。
  await page.waitForFunction(()=>window.__semanticState?.available===true&&window.__semanticState?.open===false)
  assert.equal(await page.$('.awd-semantic-launch'),null)
  assert.equal(await page.$eval('.awd-semantic',el=>el.hidden),true)
  await page.evaluate(()=>window.postMessage({__lo:'lo-relay',type:'semantic-writing-panel',open:true},location.origin))
  await page.waitForSelector('.awd-semantic-file input')
  await page.waitForFunction(()=>window.__semanticState?.open===true)
  await page.select('.awd-semantic select','diligence')
  await page.click('.awd-semantic-file input')
  await clickText('续写一句')
  await page.waitForFunction(()=>document.querySelector('.awd-semantic-preview')?.textContent.includes('相关事项尚待核实'),{timeout:15000})
  await page.evaluate(()=>{document.querySelector('.awd-semantic-card details').open=true})
  await page.screenshot({path:'/tmp/semantic-writing-preview.png'})
  assert.equal(await page.$eval('.awd-semantic blockquote',el=>el.textContent),'材料取得状态：未取得')
  await clickText('采用这条')
  try { await page.waitForFunction(()=>document.querySelector('.awd-semantic-status')?.textContent.includes('已采用'),{timeout:10000}) }
  catch(error) { console.log('Acceptance status:',await page.$eval('.awd-semantic-status',el=>el.textContent));console.log('Trace:',JSON.stringify(await page.evaluate(()=>window.__semanticTrace)));await page.screenshot({path:'/tmp/semantic-writing-accept-diagnostic.png'});throw error }
  const read = async()=> (await exec('get_document_text')).paragraphs.map(p=>p.text).join('\n')
  assert.ok((await read()).includes('关于业务资质文件，材料取得记录显示尚未取得相关文件，相关事项尚待核实。'))
  await page.screenshot({path:'/tmp/semantic-writing-accepted.png'})
  await exec('undo');assert.equal(await read(),original)
  const snapshot = await exec('capture_writing_context')
  await page.evaluate(()=>document.querySelector('[data-lo-ime]').dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true,data:''})))
  const blocked = await exec('accept_writing_suggestion',{token:snapshot.token,revision:snapshot.revision,text:'不应写入',expectedSelection:''})
  assert.equal(blocked.reason,'composing'); assert.equal((await exec('capture_writing_context')).reason,'composing')
  assert.equal(await read(),original)
  await page.evaluate(()=>document.querySelector('[data-lo-ime]').dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:''})))
  // Resize legitimately changes the canvas cursor coordinates: capture a fresh suggestion afterward.
  await page.setViewport({width:1280,height:800,deviceScaleFactor:1})
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(resolve,500))))) // Settle canvas resize and cursor notifications.
  await clickText('续写一句')
  await page.waitForFunction(()=>!!document.querySelector('.awd-semantic-preview'))
  await page.evaluate(()=>document.querySelector('.awd-semantic-card button').scrollIntoView({block:'nearest'}))
  assert.ok(await page.$eval('.awd-semantic-card button',el=>{const r=el.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight}))
  await page.screenshot({path:'/tmp/semantic-writing-preview-small.png'})
  await page.evaluate(()=>window.postMessage({__lo:'lo-relay',type:'set-theme',theme:'dark'},location.origin))
  await page.waitForFunction(()=>document.documentElement.classList.contains('theme-dark'))
  assert.equal(await page.$eval('.awd-semantic-sheet',el=>getComputedStyle(el).backgroundColor),'rgb(30, 41, 35)')
  await page.screenshot({path:'/tmp/semantic-writing-preview-dark.png'})
  await page.evaluate(()=>window.postMessage({__lo:'lo-relay',type:'set-theme',theme:'light'},location.origin))
  // 客体里的「关闭」要回报给宿主，否则工具栏按下态会停在开着。
  await clickText('关闭')
  await page.waitForFunction(()=>window.__semanticState?.open===false)
  assert.equal(await page.$eval('.awd-semantic',el=>el.hidden),true)
  console.log('PASS real LOWA semantic panel: preview, exact source, server-only acceptance, one undo, IME dispatch guard, small viewport, app theme and host-toolbar-only entry. Screenshot /tmp/semantic-writing-preview.png labelled synthetic.')
} finally { await browser.close(); server.close() }

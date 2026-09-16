// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { createSemanticWritingHost } from '../../src/composables/semanticWritingHost.js'
function fixture() {
  const calls=[], messages=[]
  const state={ revision:7, selection:'', text:'合成案卷正文', contextRevision:7, sectionTitle:'正文', paragraphIndex:0 }
  const api={ settings:async()=>({stance:'收购方',cutoffDate:'2026-09-15',files:[]}), saveSettings:async(p,data)=>data,
    suggest:async(p,data)=>{calls.push(['suggest',p,data]);return {id:'j1',status:'READY',advice:[{text:'真实服务端候选'}]}},
    get:async()=>({id:'j1',status:'READY'}),cancel:async(p,id)=>{calls.push(['cancel',p,id]);return {id,status:'CANCELLED'}},
    accept:async(p,id,data)=>{calls.push(['accept',p,id,data]);return {text:'真实服务端候选',revision:'7',selection:state.selection}} }
  const execute=async(action,params)=>{calls.push([action,params]);
    if(action==='capture_writing_context')return {success:true,available:true,token:'t7',revision:state.contextRevision,before:'原文',after:'后文',selectedText:state.selection,hasSelection:!!state.selection,sectionTitle:state.sectionTitle,paragraphIndex:state.paragraphIndex,scopeKnown:true}
    if(action==='get_document_text')return {success:true,revision:state.revision,paragraphs:[{index:0,text:state.text}]}
    if(action==='get_review_context')return {success:true,revision:state.revision}
    if(action==='accept_writing_suggestion'){state.onApply?.();return {success:true,inserted:true,revision:8}}
  }
  const host=createSemanticWritingHost({projectId:11,fileId:22,title:'合成尽调报告',writable:true,execute,send:m=>messages.push(m),api})
  host.bindSession('s1')
  return {host,api,calls,messages,state,generate:(data={})=>host.perform('semantic-generate',{mode:'sentence',documentType:'diligence',...data})}
}
test('generation captures real context and keeps source count and document scope bounded',async()=>{
  const f=fixture();await f.generate({sourceFileIds:[1,2]});const req=f.calls.find(c=>c[0]==='suggest')
  assert.equal(req[1],11);assert.equal(req[2].context.fileId,22);assert.equal(req[2].context.revision,'7');assert.equal(req[2].context.documentText,'合成案卷正文');assert.deepEqual(req[2].context.sourceFileIds,[1,2])
})
test('IME composition prevents generation before capture',async()=>{const f=fixture();await assert.rejects(f.generate({composing:true}));assert.equal(f.calls.length,0)})
test('non-rewrite cannot overwrite selected text',async()=>{const f=fixture();f.state.selection='保留原选区';await assert.rejects(f.generate());assert.equal(f.calls.some(c=>c[0]==='suggest'),false)})
test('rewrite requires an actual selected range',async()=>{const f=fixture();await assert.rejects(f.generate({mode:'rewrite'}));assert.equal(f.calls.some(c=>c[0]==='suggest'),false)})
test('snapshot revision mismatch never sends mixed document context',async()=>{const f=fixture();f.state.revision=8;await assert.rejects(f.generate());assert.equal(f.calls.some(c=>c[0]==='suggest'),false)})
test('more than four sources is rejected rather than silently changing scope',async()=>{const f=fixture();await assert.rejects(f.generate({sourceFileIds:[1,2,3,4,5]}));assert.equal(f.calls.some(c=>c[0]==='suggest'),false)})
test('accept uses server text and engine token, not guest supplied text',async()=>{const f=fixture();await f.generate();await f.host.perform('semantic-accept',{index:0,text:'伪造内容'});const write=f.calls.find(c=>c[0]==='accept_writing_suggestion');assert.equal(write[1].text,'真实服务端候选');assert.equal(write[1].token,'t7');assert.equal(write[1].expectedSelection,'')})
test('changed document cannot accept stale suggestion or call accept API',async()=>{const f=fixture();await f.generate();f.state.revision=8;await assert.rejects(f.host.perform('semantic-accept',{index:0}));assert.equal(f.calls.some(c=>c[0]==='accept'),false)})
test('edited document cancels in-flight job and publishes stale state',async()=>{const f=fixture();await f.generate();f.host.modified();await new Promise(r=>setTimeout(r,0));assert.ok(f.calls.some(c=>c[0]==='cancel'));assert.ok(f.messages.some(m=>m.config?.semantic?.state==='STALE'));await assert.rejects(f.host.perform('semantic-accept',{index:0}))})
test('late generation response is cancelled after document edit',async()=>{const f=fixture();let resolve;f.api.suggest=()=>new Promise(r=>resolve=r);const work=f.generate();await new Promise(r=>setTimeout(r,0));f.host.modified();resolve({id:'late',status:'RUNNING'});await assert.rejects(work);assert.ok(f.calls.some(c=>c[0]==='cancel'&&c[2]==='late'))})
test('engine rejection never reports applied success',async()=>{const f=fixture();await f.generate();f.api.accept=async()=>({text:'候选',revision:'8',selection:''});await assert.rejects(f.host.perform('semantic-accept',{index:0}));assert.equal(f.calls.some(c=>c[0]==='accept_writing_suggestion'),false)})
test('large body is capped with explicit notice',async()=>{const f=fixture();f.state.text='字'.repeat(17000);const r=await f.generate();assert.equal(f.calls.find(c=>c[0]==='suggest')[2].context.documentText.length,16000);assert.ok(r.notices.some(s=>s.includes('16000')))})
test('own insertion modified notification does not cancel accepted state',async()=>{const f=fixture();await f.generate();f.messages.length=0;f.state.onApply=()=>f.host.modified();const r=await f.host.perform('semantic-accept',{index:0});assert.equal(r.applied,true);assert.equal(f.messages.some(m=>m.config?.semantic?.state==='STALE'),false)})
test('typing during backend acceptance prevents any engine write',async()=>{const f=fixture();await f.generate();let resolve;f.api.accept=()=>new Promise(r=>resolve=r);const work=f.host.perform('semantic-accept',{index:0});await new Promise(r=>setTimeout(r,0));f.host.modified();resolve({text:'过期文本',revision:'7',selection:''});await assert.rejects(work);assert.equal(f.calls.some(c=>c[0]==='accept_writing_suggestion'),false)})
test('destroy suppresses late suggestion and cancels server work',async()=>{const f=fixture();let resolve;f.api.suggest=()=>new Promise(r=>resolve=r);const work=f.generate();await new Promise(r=>setTimeout(r,0));f.host.destroy();resolve({id:'closed',status:'RUNNING'});await assert.rejects(work);assert.ok(f.calls.some(c=>c[0]==='cancel'&&c[2]==='closed'))})
test('settings omit active document from optional project sources',async()=>{const f=fixture();f.api.settings=async()=>({files:[{id:22,name:'当前文书'},{id:23,name:'参考材料'}]});const r=await f.host.perform('semantic-settings');assert.deepEqual(r.files,[{id:23,name:'参考材料'}])})
test('AI generation uses AI revision author while local fields keep normal authorship',async()=>{for(const mode of ['sentence','local']){const f=fixture();await f.generate({mode});await f.host.perform('semantic-accept',{index:0});assert.equal(f.calls.find(c=>c[0]==='accept_writing_suggestion')[1].__agent,mode!=='local')}})
test('late modified notification after acceptance cannot overwrite success with stale',async()=>{const f=fixture();await f.generate();await f.host.perform('semantic-accept',{index:0});f.messages.length=0;f.host.modified();assert.equal(f.messages.some(m=>m.config?.semantic?.state==='STALE'),false)})
test('saving settings invalidates existing suggestions and blocks generation until saved',async()=>{const f=fixture();await f.generate();let resolve;f.api.saveSettings=()=>new Promise(r=>resolve=r);const save=f.host.perform('semantic-save-settings',{stance:'投资方'});await assert.rejects(f.generate(),/保存/);await assert.rejects(f.host.perform('semantic-accept',{index:0}));resolve({stance:'投资方'});await save;await f.generate();assert.equal(f.calls.filter(c=>c[0]==='suggest').length,2)})

test('section is passed only when the captured paragraph is in the supplied body scope',async()=>{const f=fixture();f.state.sectionTitle='附件一 / 第一条';await f.generate();assert.equal(f.calls.find(c=>c[0]==='suggest')[2].context.section,'附件一 / 第一条');const g=fixture();g.state.paragraphIndex=15;const result=await g.generate();assert.equal(g.calls.find(c=>c[0]==='suggest')[2].context.section,'');assert.ok(result.notices.some(s=>s.includes('当前位置')))})
test('document facts are read through final-text agent view like the semantic snapshot',async()=>{const f=fixture();await f.generate();assert.equal(f.calls.find(c=>c[0]==='get_document_text')[1].__agent,true)})

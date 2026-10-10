// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source=readFileSync(new URL('../src/components/project-list/AccountProjectCatalog.vue',import.meta.url),'utf8')
const script=source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'').replace('export default','return')
const component=new Function('requestAccountCatalog',script)(()=>{})
function fixture(request,poll){
  const vm={...component.data(),$t:k=>k,request,poll}
  vm.open=component.methods.open.bind(vm)
  return vm
}
test('one unavailable desktop retains cloud files and exposes incomplete/error',async()=>{
  const vm=fixture(async(method,path)=>{
    if(method==='GET')return{files:[{uid:'file',source:'cloud',name:'a.docx'}],truncated:true}
    throw new Error('desktop offline')
  })
  await vm.open({projectUid:'project',accountScope:'account',locations:[{kind:'desktop',deviceId:'a',key:'1',online:true}]})
  assert.equal(vm.files.length,1);assert.equal(vm.files[0].source,'cloud');assert.equal(vm.error,'desktop offline');assert.equal(vm.incomplete,true)
})
test('complete second source cannot erase incomplete first source',async()=>{
  let id=0
  const vm=fixture(async method=>method==='GET'?{files:[],truncated:true}:{id:++id},async n=>({files:[],truncated:n===1}))
  await vm.open({projectUid:'project',locations:[{kind:'desktop',deviceId:'a',key:'1',online:true},{kind:'desktop',deviceId:'b',key:'1',online:true}]})
  assert.equal(vm.incomplete,true)
})
test('local projects still expose their other storage locations',()=>{
  assert.equal(component.computed.remoteProjects.call({catalog:[{projectUid:'project',localProjectId:1}]}).length,1)
})

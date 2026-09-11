// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { bootZetaOffice } from '../../src/composables/zetaOfficeBoot.js'

test('Qt resize follows actual canvas changes, never an idle heartbeat', async t => {
  const names = ['document', 'Module', 'ResizeObserver', 'dispatchEvent', 'setInterval']
  const saved = new Map(names.map(k=>[k,Object.getOwnPropertyDescriptor(globalThis,k)]))
  t.after(()=>{for(const [k,d] of saved)d?Object.defineProperty(globalThis,k,d):delete globalThis[k]})
  let pulses=0, intervals=0, observe, disconnected=false
  globalThis.setInterval=()=>{intervals++;return 0}
  globalThis.dispatchEvent=e=>{if(e.type==='resize')pulses++}
  globalThis.ResizeObserver=class {
    constructor(fn){observe=fn}
    observe(){}
    disconnect(){disconnected=true}
  }
  const port={onmessage:null}, canvas={clientWidth:800,clientHeight:600,style:{}}
  globalThis.document={createElement:()=>({}),body:{appendChild:s=>{
    globalThis.Module.uno_main=Promise.resolve(port);s.onload()
  }}}
  const boot=await bootZetaOffice({canvas,sofficeBaseUrl:''})
  assert.equal(intervals,0,'idle must not repeatedly send resize into native document transitions')
  assert.equal(typeof observe,'function')
  observe([]);assert.equal(pulses,0,'wait until Qt reports ready')
  port.onmessage({data:{cmd:'ui_ready'}})
  assert.equal(pulses,1,'initial surface is painted once')
  observe([]);observe([]);assert.equal(pulses,1,'same size does not repaint')
  canvas.clientWidth=900;observe([]);assert.equal(pulses,2,'split-pane resize updates Qt')
  canvas.clientWidth=0;canvas.clientHeight=0;observe([]);assert.equal(pulses,2,'hidden canvas does not resize Qt to zero')
  canvas.clientWidth=900;canvas.clientHeight=600;observe([]);assert.equal(pulses,3,'restoring the same-sized keepalive pane repaints')
  boot.dispose();assert.equal(disconnected,true)
  canvas.clientWidth=1000;observe([]);assert.equal(pulses,3,'late callback after disposal is ignored')
})

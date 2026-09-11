// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const implementation = source.slice(source.indexOf('// Geometry comes from Writer'), source.indexOf('\nconst EXEC = {'))
function editor(supported = true) {
  let revision = 7, listReads = 0, geometryReads = 0
  const originalSelection = 'selected original text'
  const native = {version:1,unit:'twip',pages:[{number:1,x:280,y:280,width:12000,height:16800,sidebar:'right',gutterWidth:4200}],
    revisions:[{index:0,id:91,anchor:{x:800,y:1200,width:8,height:200},page:1}],
    comments:[{id:48,name:'comment-b',anchor:{x:900,y:1800,width:10,height:210},page:1},
      {id:17,name:'comment-a',anchor:{x:1100,y:1500,width:10,height:210},page:1}]}
  const ctrl = {
    getPropertySetInfo: () => ({hasPropertyByName: () => supported}),
    getPropertyValue(name) { if(name === 'AwdReviewGeometry') {geometryReads++;return JSON.stringify(native)} return 280 },
    getViewData: () => '100;200;100;0;500;12000;17500;0;0', getFrame: () => ({}),
    getViewCursor() {throw new Error('geometry must not use or move live selection')},
    createTextRangeByPixelPosition() {throw new Error('inverse hit testing must never run')},
  }
  const commentMetadata = [{id:'comment-a',content:'A'},{id:'comment-b',content:'B'}]
  const EXEC = {
    list_revisions() {listReads++;return {success:true,count:1,revisions:[{index:0,type:'Delete',text:'完整删除内容'.repeat(200)}]}},
    list_comments() {listReads++;return {success:true,count:2,comments:commentMetadata}},
  }
  const read = new Function('ctrl','xModel','isWriterDoc','docSeq','currentReviewRevision','revisionViewState','readNativeCaretRect','EXEC','tableFail',
    implementation + ';return reviewLayout')(
    ctrl,{isReadonly:()=>false},()=>true,12,()=>revision,()=>({mode:'balloons'}),
    ()=>({frameWidth:1000,frameHeight:700,viewport:{x:0,y:30,width:1000,height:650}}),EXEC,message=>({success:false,message}))
  return {read,native,commentMetadata,originalSelection,change:()=>revision++,counts:()=>({listReads,geometryReads})}
}
test('reads native anchors by identity and preserves full content without hit testing or selection changes', () => {
  const e=editor(), layout=e.read()
  assert.equal(layout.success,true)
  assert.equal(layout.available,true)
  assert.deepEqual(layout.items.map(i=>[i.key,i.x,i.y,i.page]),[['r0',800,1200,1],['ccomment-a',1100,1500,1],['ccomment-b',900,1800,1]])
  assert.equal(layout.items[0].data.text.length,1200)
  assert.equal(layout.pages[0].gutterWidth,4200)
  assert.equal(layout.view.top,500)
  assert.equal(layout.revision,7)
})
test('scroll/layout reads refresh native positions and reuse metadata until document changes', () => {
  const e=editor(); e.read(); e.native.revisions[0].anchor.y=1400
  assert.equal(e.read().items[0].y,1400)
  assert.deepEqual(e.counts(),{listReads:2,geometryReads:2})
  e.change(); assert.equal(e.read().revision,8)
  assert.deepEqual(e.counts(),{listReads:4,geometryReads:3})
})
test('an older engine keeps native comments and performs no background review scans', () => {
  const e=editor(false)
  assert.equal(e.read().available,false); assert.deepEqual(e.read().items,[])
  assert.deepEqual(e.counts(),{listReads:0,geometryReads:0})
})
test('unavailable anchors are omitted, never guessed at unrelated text', () => {
  const e=editor(); delete e.native.revisions[0].anchor
  const layout=e.read()
  assert.equal(layout.items.length,2); assert.equal(layout.truncated,true)
})

test('two freshly inserted unnamed comments retain distinct native anchors and keys', () => {
  const e=editor()
  e.native.comments[0].name=''; e.native.comments[1].name=''
  e.commentMetadata[0].id='postit:17'; e.commentMetadata[1].id='postit:48'
  assert.deepEqual(e.read().items.filter(i=>i.kind==='comment').map(i=>[i.key,i.x,i.y]),
    [['cpostit:17',1100,1500],['cpostit:48',900,1800]])
})
test('ambiguous native comment identities are omitted rather than assigned an unrelated anchor', () => {
  const e=editor()
  e.native.comments[0].name=''; e.native.comments[1].name='postit:48'
  e.commentMetadata[0].id='postit:48'; e.commentMetadata[1].id='postit:48'
  const layout=e.read()
  assert.equal(layout.items.filter(i=>i.kind==='comment').length,0)
  assert.equal(layout.truncated,true)
})

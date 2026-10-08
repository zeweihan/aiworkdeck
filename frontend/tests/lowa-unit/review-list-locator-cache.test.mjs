// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
const source = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const locators = source.slice(source.indexOf('function paraKeyOf('), source.indexOf('// 把视图光标摆到'))
const compareRanges = new Function(source.slice(source.indexOf('function rangeStartsEqual('), source.indexOf('// ---- 批注↔修订')) + ';return rangeStartsEqual')()
const listing = source.slice(source.indexOf('  list_revisions(p) {'), source.indexOf('  goto_revision(p) {'))

function harness({ extended = false } = {}) {
  const counts = { paragraphReads: 0, spanReads: 0, comparisons: 0, bodyCursors: 0, foreignCursors: 0 }
  const makeStory = (text, foreign = false) => {
    const parts = text.split('\n'), starts = []; let offset = 0
    for (const part of parts) { starts.push(offset); offset += part.length + 1 }
    const story = { text,
      compareRegionStarts(a, b) { counts.comparisons++; if (a.story !== story || b.story !== story) throw Error('foreign story'); return Math.sign(b.a - a.a) },
      compareRegionEnds(a, b) { counts.comparisons++; if (a.story !== story || b.story !== story) throw Error('foreign story'); return Math.sign(b.b - a.b) },
      createTextCursorByRange(r) {
        counts[story === body ? 'bodyCursors' : 'foreignCursors']++;
        let a = r.a, b = r.a, paragraph = false
        const current = () => Math.max(0, starts.findLastIndex(start => start <= a))
        return {
          gotoRange(end, expand) { if (!expand) a = end.a; b = end.a; paragraph = false },
          gotoStartOfParagraph(expand) { const start = starts[current()]; if (expand) b = start; else a = b = start },
          gotoEndOfParagraph() { const i = current(); b = starts[i] + parts[i].length; paragraph = true },
          getString() { counts[paragraph ? 'paragraphReads' : 'spanReads']++; return story.text.slice(Math.min(a,b), Math.max(a,b)) },
        }
      },
    }
    const range = (a, b = a) => ({ story, a, b, getText: () => story,
      getStart: () => range(a), getEnd: () => range(b),
      getString() { counts.paragraphReads++; return story.text.slice(a,b) },
      getPropertyValue: key => { if (key === 'Cell' && foreign) return { getString: () => text, getPropertyValue: () => 'A1' }; return null },
    })
    story.range = range
    story.paragraphs = parts.map((part, i) => range(starts[i], starts[i] + part.length))
    return story
  }
  const body = makeStory('abcdefghijklmnopqrst\nuvwxyzabcdefghijklmn\n01234567890123456789')
  const foreign = makeStory('foreign-context', true)
  const specs = [
    [body, 1, 4], [body, 4, 7], [body, 8, 8, 'hidden-deletion'],
    [body, 21, 24], [body, 25, 28], [body, 42, 45],
    [foreign, 1, 4], [body, 20, 20], [body, 21, 21],
  ]
  if (extended) specs.push([body, 18, 24], [makeStory('header-context'), 2, 8])
  const entries = specs.map(([story, start, end, hidden], index) => ({getPropertyValue(name) {
    return { RedlineIdentifier: 'r'+index, RedlineType: hidden ? 'Delete' : 'Insert', RedlineMovedID: 0,
      RedlineAuthor: 'Reviewer', RedlineComment: '', RedlineDescription: 'description',
      RedlineDateTime: {Year:2026,Month:10,Day:8,Hours:9,Minutes:1,Seconds:2,NanoSeconds:3},
      RedlineText: hidden ? {getString:()=>hidden} : null, RedlineStart:story.range(start), RedlineEnd:story.range(end) }[name]
  }}))
  const xModel = {getText:()=>body, getRedlines:()=>({
    getCount:()=>entries.length, getByIndex:i=>entries[i], createEnumeration() {let i=0;return{hasMoreElements:()=>i<entries.length,nextElement:()=>entries[i++]}}
  })}
  const read = new Function('xModel','withParaIndex','docSeq','currentReviewRevision','pad2','rangeStartsEqual','tableFail','errStr', locators+';return ({'+listing+'}).list_revisions')(
    xModel, fn=>fn({ranges:body.paragraphs,total:body.paragraphs.length}), 4, ()=>8,
    n=>String(n).padStart(2,'0'), compareRanges, message=>({success:false,message}), String)
  return {read,counts,body}
}

function expected() {
  const texts=['bcd','efg','hidden-deletion','uvw','yza','012','ore','','']
  const paragraphs=['abcdefghijklmnopqrst','abcdefghijklmnopqrst','abcdefghijklmnopqrst','uvwxyzabcdefghijklmn','uvwxyzabcdefghijklmn','01234567890123456789','foreign-context','abcdefghijklmnopqrst','uvwxyzabcdefghijklmn']
  const positions=[[0,1,4],[0,4,7],[0,8,8],[1,0,3],[1,4,7],[2,0,3],[-1],[0,20,20],[1,0,0]]
  return {success:true,count:9,revision:8,documentSeq:4,revisions:texts.map((text,index)=>({
    index,identifier:'r'+index,type:index===2?'Delete':'Insert',movedId:0,author:'Reviewer',comment:'',description:'description',
    date:'2026-10-08 09:01',timestamp:'2026-10-08 09:01:02.3',text,contiguous:index===1,inTable:index===6,
    paragraph:paragraphs[index],paraKey:positions[index][0],...(positions[index][0]<0?{}:{start:positions[index][1],end:positions[index][2]}),
  }))}
}

test('review lists preserve all metadata, boundary locators, hidden zero-width deletion, and foreign context', () => {
  const h=harness(); assert.deepEqual(h.read({limit:500}), expected())
})

test('one list reads each body paragraph summary once and reuses already-read inline ranges', () => {
  const h=harness(); assert.deepEqual(h.read({limit:500}), expected())
  assert.equal(h.counts.paragraphReads, 4, 'three body summaries plus the foreign-story fallback')
  // Eight inline ranges + eight body prefix ranges + the hidden deletion body range.
  assert.equal(h.counts.spanReads, 17)
  assert.equal(h.counts.bodyCursors, 1, 'all body text and locator reads reuse one private cursor')
  assert.equal(h.counts.foreignCursors, 2, 'cell text and context retain their own story cursors')
  h.body.text=h.body.text.replace('abcdefghijklmnopqrst','ABCDEFGHIJKLMNOPQRST')
  const changed=h.read({limit:500})
  assert.equal(changed.revisions[0].paragraph,'ABCDEFGHIJKLMNOPQRST','cache belongs to one call only')
})

test('location-free and single-index calls retain their contracts', () => {
  const h=harness(), baseline=expected()
  baseline.revisions.forEach(r=>{delete r.paragraph;delete r.paraKey;delete r.start;delete r.end})
  assert.deepEqual(h.read({limit:500,locate:false}),baseline)
  const single=h.read({index:4,locate:true})
  assert.deepEqual(single.revisions,[expected().revisions[4]])
  assert.equal(single.count,1)
})

test('paragraph boundary proxies are reused and discarded when the index is rebuilt', () => {
  let generation = 1, expired = false, boundaryReads = 0
  const makeIndex = () => ({ total: 4, ranges: [0, 10, 20, 30].map(a => ({
    getStart() { boundaryReads++; return {a,b:a,generation} },
    getEnd() { boundaryReads++; return {a:a+9,b:a+9,generation} },
  })) })
  let ix = makeIndex()
  const body = {
    compareRegionStarts(a,b) { if(expired && a.generation===1) throw Error('stale range'); return Math.sign(b.a-a.a) },
    compareRegionEnds(a,b) { if(expired && a.generation===1) throw Error('stale range'); return Math.sign(b.b-a.b) },
  }
  const withIndex = fn => { try { return fn(ix) } catch { generation++; ix=makeIndex(); return fn(ix) } }
  const locate = new Function('withParaIndex', locators+';return paraKeyOf')(withIndex)
  const cache = {}
  assert.equal(locate(body,{a:2,b:2},cache),0)
  const firstReads=boundaryReads
  assert.equal(locate(body,{a:5,b:5},cache),0)
  assert.equal(boundaryReads,firstReads,'same paragraph creates no new boundary proxies')
  cache.paragraphTexts.set(0,'stale summary')
  expired=true
  assert.equal(locate(body,{a:6,b:6},cache),0)
  assert.equal(generation,2)
  assert.equal(cache.paragraphTexts.size,0,'index retry also discards old paragraph summaries')
  assert.equal(cache.lastParagraph.start.generation,2)
  assert.equal(locate(body,{a:10,b:10},cache),1,'next paragraph boundary is rechecked')
  assert.equal(locate(body,{a:9,b:9},cache),0,'previous paragraph end keeps original lookup semantics')
})


test('cross-paragraph text retains its newline and span offset; header ranges keep context without a body locator', () => {
  const h = harness({ extended: true }), result = h.read({ limit: 500 })
  const baseline = expected()
  assert.deepEqual(result.revisions.slice(0, 9), baseline.revisions)
  assert.equal(result.count, 11)
  assert.deepEqual(result.revisions[9], {
    ...baseline.revisions[0], index: 9, identifier: 'r9', text: 'st\nuvw', contiguous: false,
    start: 18, end: 24,
  })
  assert.equal(result.revisions[9].end - result.revisions[9].start, 'st\nuvw'.length,
    'cached inline span length includes the paragraph separator')
  const header = { ...baseline.revisions[0], index: 10, identifier: 'r10', text: 'ader-c',
    paragraph: 'header-context', paraKey: -1, contiguous: false, inTable: false }
  delete header.start; delete header.end
  assert.deepEqual(result.revisions[10], header,
    'non-cell foreign stories preserve paragraph context but never borrow cached body coordinates')
})


test('contiguous comparisons reuse the body and preserve foreign-story fallback', () => {
  let textReads = 0, ownComparisons = 0
  const body = { compareRegionStarts(a,b) { if (a.foreign || b.foreign) throw Error('foreign'); return Math.sign(b.at-a.at) } }
  const own = { compareRegionStarts(a,b) { ownComparisons++; if (!a.foreign || !b.foreign) throw Error('different story'); return Math.sign(b.at-a.at) } }
  const range = (at, foreign=false) => ({at,foreign,getText(){textReads++;return foreign?own:body}})
  assert.equal(compareRanges(range(3),range(3),body),true)
  assert.equal(compareRanges(range(3),range(4),body),false)
  assert.equal(textReads,0)
  assert.equal(compareRanges(range(3,true),range(3,true),body),true)
  assert.equal(compareRanges(range(3,true),range(4,true),body),false)
  assert.equal(compareRanges(range(3,true),range(3),body),false)
  assert.equal(textReads,3)
  assert.equal(ownComparisons,3)
  assert.equal(compareRanges(range(3),range(3)),true,'callers without a cache retain the original path')
  assert.equal(textReads,4)
})

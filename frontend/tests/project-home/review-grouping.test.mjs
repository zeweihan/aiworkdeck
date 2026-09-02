// dev-board#377：审阅面板补齐成 Word 式审阅窗格。本用例钉住纯函数层的三条判定：
// 作者归类、RedlineType 归一、批注↔修订的位置关联；以及「理由不进合并判据、
// 计数恒按全量算」这两条容易被后来的改动破坏的口径。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  AI_AUTHOR, authorKind, revisionTypeKey, rangesTouch,
  linkCommentsToRevisions, groupRevisions, countByAuthorKind, filterByAuthorKind,
} from '../../src/utils/reviewGrouping.js'

test('authorKind：AI / 我 / 其他人三分，空用户名不许被当成「我」', () => {
  assert.equal(authorKind(AI_AUTHOR, '韩泽伟'), 'ai')
  assert.equal(authorKind('韩泽伟', '韩泽伟'), 'me')
  assert.equal(authorKind(' 韩泽伟 ', '韩泽伟'), 'me', '两端空白不该让人变成「其他人」')
  assert.equal(authorKind('王律师', '韩泽伟'), 'other')
  // 用户未登录 / 拿不到用户名时：未署名的修订不能算到自己头上
  assert.equal(authorKind('', ''), 'other')
  assert.equal(authorKind(null, ''), 'other')
  assert.equal(authorKind(undefined, undefined), 'other')
})

test('revisionTypeKey：格式类不再被当成插入，认不出的落 other', () => {
  assert.equal(revisionTypeKey('Insert'), 'insert')
  assert.equal(revisionTypeKey('Delete'), 'delete')
  assert.equal(revisionTypeKey('Format'), 'format')
  assert.equal(revisionTypeKey('ParagraphFormat'), 'paraFormat')
  // 改造前的实现是「Delete 与其余」，Format 会显示成「插入」——这里锁死不许回退
  assert.notEqual(revisionTypeKey('Format'), 'insert')
  assert.equal(revisionTypeKey('TextTable'), 'other')
  assert.equal(revisionTypeKey(''), 'other')
  assert.equal(revisionTypeKey(undefined), 'other')
})

test('rangesTouch：同段落 + 闭区间相交/相接才算；paraKey -1 一律不关联', () => {
  const a = { paraKey: 3, start: 10, end: 14 }
  assert.equal(rangesTouch(a, { paraKey: 3, start: 12, end: 20 }), true, '相交')
  assert.equal(rangesTouch(a, { paraKey: 3, start: 14, end: 20 }), true, '首尾相接')
  assert.equal(rangesTouch(a, { paraKey: 3, start: 4, end: 10 }), true, '尾首相接')
  // 页边模式下删除型在正文流里是零宽，批注只可能靠相接命中
  assert.equal(rangesTouch({ paraKey: 3, start: 10, end: 10 }, { paraKey: 3, start: 6, end: 10 }), true)
  assert.equal(rangesTouch(a, { paraKey: 3, start: 15, end: 20 }), false, '中间空一格就不算')
  assert.equal(rangesTouch(a, { paraKey: 4, start: 10, end: 14 }), false, '不同段落')
  // worker 定位不到（表格单元格/页眉页脚跨 story）时回 -1：宁可不挂，不许猜
  assert.equal(rangesTouch({ paraKey: -1, start: 0, end: 0 }, { paraKey: -1, start: 0, end: 0 }), false)
})

test('linkCommentsToRevisions：一次替换的删+插共享同一条理由批注', () => {
  // 「三十日」→「六十日」：删除型零宽落在 offset 10，插入型占 10..11
  const revisions = [
    { index: 0, type: 'Delete', paraKey: 2, start: 10, end: 10 },
    { index: 1, type: 'Insert', paraKey: 2, start: 10, end: 11 },
    { index: 2, type: 'Insert', paraKey: 5, start: 3, end: 6 },   // 别的段落，挂不上
  ]
  const comments = [
    { index: 0, id: 'c1', content: '【修訂理由】账期与主协议不一致', paraKey: 2, start: 8, end: 12 },
    { index: 1, id: 'c2', content: '与本次修订无关的提醒', paraKey: 9, start: 0, end: 4 },
  ]
  const { reasons, linked } = linkCommentsToRevisions(revisions, comments)
  assert.deepEqual(reasons.get(0).map((c) => c.id), ['c1'])
  assert.deepEqual(reasons.get(1).map((c) => c.id), ['c1'], '同一条批注同时是删与插的理由')
  assert.equal(reasons.has(2), false, '别的段落的修订不该被挂上理由')
  assert.deepEqual(linked.get(0), [0, 1], '批注侧要知道自己挂到了哪几条修订')
  assert.equal(linked.has(1), false, '挂不上的批注照旧单独列')
})

test('linkCommentsToRevisions：缺坐标（旧 worker / 定位不到）时整条链路静默不关联', () => {
  const revisions = [{ index: 0, type: 'Insert', text: '六' }]
  const comments = [{ index: 0, id: 'c1', content: '理由' }]
  const { reasons, linked } = linkCommentsToRevisions(revisions, comments)
  assert.equal(reasons.size, 0)
  assert.equal(linked.size, 0)
})

test('groupRevisions：不同作者绝不合并，理由不进合并判据但整组取并集', () => {
  const reasons = new Map([
    [1, [{ index: 0, id: 'c1', content: '理由甲' }]],
    [2, [{ index: 0, id: 'c1', content: '理由甲' }]],   // 同一条批注，去重后只留一份
  ])
  const revisions = [
    { index: 0, type: 'Delete', author: 'AI WorkDeck', date: 'd1', text: '三', contiguous: false },
    { index: 1, type: 'Delete', author: 'AI WorkDeck', date: 'd1', text: '十', contiguous: true },
    { index: 2, type: 'Delete', author: 'AI WorkDeck', date: 'd1', text: '日', contiguous: true },
    // 位置上相接、类型/时间也一样，但作者不同：必须另起一张卡
    { index: 3, type: 'Delete', author: '王律师', date: 'd1', text: '整', contiguous: true },
  ]
  const groups = groupRevisions(revisions, { reasons, selfAuthor: '韩泽伟' })
  assert.equal(groups.length, 2)
  assert.deepEqual(groups[0].items.map((r) => r.index), [0, 1, 2])
  assert.equal(groups[0].text, '三十日')
  assert.equal(groups[0].authorKind, 'ai')
  assert.equal(groups[0].typeKey, 'delete')
  assert.deepEqual(groups[0].reasons.map((c) => c.id), ['c1'], '组内两条挂的是同一批注，去重成一条')
  assert.equal(groups[1].author, '王律师')
  assert.equal(groups[1].authorKind, 'other')
  assert.deepEqual(groups[1].reasons, [])
})

test('groupRevisions：类型不同不合并（格式修订不会被并进相邻的插入）', () => {
  const revisions = [
    { index: 0, type: 'Insert', author: 'a', date: 'd', text: '甲', contiguous: false },
    { index: 1, type: 'Format', author: 'a', date: 'd', text: '甲', contiguous: true, description: '属性已更改' },
  ]
  const groups = groupRevisions(revisions, {})
  assert.equal(groups.length, 2)
  assert.equal(groups[1].typeKey, 'format')
  assert.equal(groups[1].description, '属性已更改')
})

test('countByAuthorKind / filterByAuthorKind：计数按全量算，筛选只挑桶', () => {
  const revisions = [
    { index: 0, type: 'Insert', author: 'AI WorkDeck', date: 'd' },
    { index: 1, type: 'Insert', author: '韩泽伟', date: 'd' },
    { index: 2, type: 'Insert', author: '王律师', date: 'd' },
    { index: 3, type: 'Insert', author: 'AI WorkDeck', date: 'd' },
  ]
  const groups = groupRevisions(revisions, { selfAuthor: '韩泽伟' })
  const counts = countByAuthorKind(groups)
  assert.deepEqual(counts, { all: 4, ai: 2, me: 1, other: 1 })
  assert.equal(filterByAuthorKind(groups, 'ai').length, 2)
  assert.equal(filterByAuthorKind(groups, 'me').length, 1)
  assert.equal(filterByAuthorKind(groups, 'other').length, 1)
  assert.equal(filterByAuthorKind(groups, 'all').length, 4)
  // 筛选之后再数一次，四个桶的数字一个都不许变
  assert.deepEqual(countByAuthorKind(groups), counts)
})

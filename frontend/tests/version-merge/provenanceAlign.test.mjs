// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 逐段溯源的段落对齐与文案（dev-board#632）。
 *
 * 为什么测在这一层：后端给的 units 用的是**落版那一刻**的段序，而画布上的段序
 * 随律师本机还没保存的改动漂移。按 key 硬对会把「律师乙改的那一段」的名字贴到
 * 别人头上——溯源贴错名字比不显示更糟。对齐与文案因此全在纯函数里。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import {
  normalizeUnitText, hashUnitText, alignProvenance, provenanceLabel, provenanceSummary,
} from '../../src/utils/provenanceAlign.js'

const t = (key, params) => `${key}(${JSON.stringify(params || {})})`
/** 后端那侧的 units：key + textHash（sha256 hex of norm）。 */
const units = (texts, extra = {}) => texts.map((text, i) => ({
  key: 'p' + i, textHash: hashUnitText(text), sha: 'sha' + i, shortId: 's' + i,
  authorName: '律师乙', self: false, when: '2026-09-13T21:58:00Z', title: '核对注册资本', type: 'session',
  ...(extra[i] || {}),
}))
/** 引擎那侧：get_document_text 的 paragraphs。 */
const paras = (texts) => texts.map((text, index) => ({ index, text }))

// ---------------- 归一与哈希 ----------------

test('归一：NFC + 连续空白折一个 + 去首尾（与后端 Unit.norm 同口径）', () => {
  assert.equal(normalizeUnitText('  甲方   应当\t支付  '), '甲方 应当 支付')
  assert.equal(normalizeUnitText(null), '')
  // NFC：分解式的「é」与合成式的「é」必须归到同一个串
  assert.equal(normalizeUnitText('é'), normalizeUnitText('é'))
})

test('hashUnitText 就是 sha256(norm) 的十六进制——与后端能对上才叫同一个契约', () => {
  const expect = (s) => createHash('sha256').update(normalizeUnitText(s), 'utf8').digest('hex')
  assert.equal(hashUnitText(''), expect(''))
  assert.equal(hashUnitText('abc'), expect('abc'))
  assert.equal(hashUnitText('第一条 甲方应当支付价款。'), expect('第一条 甲方应当支付价款。'))
  assert.equal(hashUnitText('  甲方   应当支付 '), hashUnitText('甲方 应当支付'))
})

// ---------------- 对齐 ----------------

test('一一对应：每个段落都认到自己那一条', () => {
  const u = units(['第一条', '第二条', '第三条'])
  const map = alignProvenance(u, paras(['第一条', '第二条', '第三条']))
  assert.equal(map.get(0).key, 'p0')
  assert.equal(map.get(1).key, 'p1')
  assert.equal(map.get(2).key, 'p2')
})

test('中间插了一段：插进来的那段没有出处，后面的段不许跟着错位', () => {
  const u = units(['第一条', '第二条', '第三条'])
  const map = alignProvenance(u, paras(['第一条', '本机刚敲的一段', '第二条', '第三条']))
  assert.equal(map.get(0).key, 'p0')
  assert.equal(map.get(1), null)
  assert.equal(map.get(2).key, 'p1')
  assert.equal(map.get(3).key, 'p2')
})

test('删了中间一段：剩下的段仍各归各的出处', () => {
  const u = units(['第一条', '第二条', '第三条'])
  const map = alignProvenance(u, paras(['第一条', '第三条']))
  assert.equal(map.get(0).key, 'p0')
  assert.equal(map.get(1).key, 'p2')
})

test('改了一个字：这一段算本机未保存的改动，两边的邻段照旧', () => {
  const u = units(['第一条', '第二条', '第三条'])
  const map = alignProvenance(u, paras(['第一条', '第二条改', '第三条']))
  assert.equal(map.get(0).key, 'p0')
  assert.equal(map.get(1), null)
  assert.equal(map.get(2).key, 'p2')
})

test('重复的空段按位置对齐（同样的文字、同样的语义，对错了也不会说错话）', () => {
  const u = units(['标题', '', '', '正文'])
  const map = alignProvenance(u, paras(['标题', '', '', '正文']))
  assert.equal(map.get(1).key, 'p1')
  assert.equal(map.get(2).key, 'p2')
  assert.equal(map.get(3).key, 'p3')
})

test('每个引擎段落都在 map 里有一条（缺 key 会让界面读成 undefined 而不是「未保存」）', () => {
  const map = alignProvenance([], paras(['甲', '乙']))
  assert.equal(map.size, 2)
  assert.equal(map.get(0), null)
  assert.equal(map.get(1), null)
  assert.deepEqual(Array.from(alignProvenance(null, null).keys()), [])
})

test('引擎段落带自己的 index（分页拼起来的第二页从 200 开始）时按它的 index 落键', () => {
  const u = units(['甲', '乙'])
  const map = alignProvenance(u, [{ index: 200, text: '甲' }, { index: 201, text: '乙' }])
  assert.equal(map.get(200).key, 'p0')
  assert.equal(map.get(201).key, 'p1')
})

// ---------------- 文案 ----------------

test('别人改的：名字 · 日期 · 那一版的标题；**永远不显示 username**', () => {
  const [u] = units(['第一条'])
  const out = provenanceLabel(t, { ...u, username: 'u3f8a' })
  assert.ok(out.includes('律师乙'), out)
  assert.ok(out.includes('version.dayHeader'), out)
  assert.ok(out.includes('核对注册资本'), out)
  assert.ok(!out.includes('u3f8a'), '用户名是 uid，不是名字')
})

test('本人改的说「你」，不说自己的名字', () => {
  const [u] = units(['第一条'], { 0: { self: true, authorName: '韩泽伟' } })
  const out = provenanceLabel(t, u)
  assert.ok(out.includes('version.actorYou'), out)
  assert.ok(!out.includes('韩泽伟'), out)
})

test('本人的自动存档：「你」仍在最前，标题位置补「自动存档」', () => {
  const [u] = units(['第一条'], { 0: { type: 'auto', self: true, title: '' } })
  const out = provenanceLabel(t, u)
  assert.ok(out.includes('version.actorYou'), out)
  assert.ok(out.includes('version.typeAuto'), out)
})

test('同事的自动存档：名字不许被「自动存档」顶掉', () => {
  const [u] = units(['第一条'], { 0: { type: 'auto', self: false, authorName: '律师乙', title: '' } })
  const out = provenanceLabel(t, u)
  assert.ok(out.includes('律师乙'), out)
  assert.ok(out.includes('version.typeAuto'), out)
})

test('带标题的自动存档（罕见）：标题位置显示标题，不说「自动存档」', () => {
  const [u] = units(['第一条'], {
    0: { type: 'auto', self: false, authorName: '律师乙', title: '批量重命名后自动存档' },
  })
  const out = provenanceLabel(t, u)
  assert.ok(out.includes('律师乙'), out)
  assert.ok(out.includes('批量重命名后自动存档'), out)
  assert.ok(!out.includes('version.typeAuto'), out)
})

test('没对上的段落 = 本机未保存的改动；截断到头的段落 = 更早的版本', () => {
  assert.equal(provenanceLabel(t, null), 'version.provenanceUnsaved({})')
  assert.equal(provenanceLabel(t, { key: 'p1', sha: null }), 'version.provenanceEarlier({})')
})

test('没有展示名的同事用占位词，不退回 username', () => {
  const out = provenanceLabel(t, { key: 'p1', sha: 'x', authorName: '', username: 'u3f8a', when: '2026-09-13T21:58:00Z' })
  assert.ok(out.includes('version.unnamedColleague'), out)
  assert.ok(!out.includes('u3f8a'), out)
})

test('摘要行按人合计：本人一档、每个同事一档、未对上与更早各一档', () => {
  const u = units(['一', '二', '三'])
  const rows = [
    { unit: { ...u[0], self: true, authorName: '韩泽伟' } },
    { unit: u[1] },
    { unit: u[2] },
    { unit: null },
    { unit: { key: 'p9', sha: null } },
  ]
  const out = provenanceSummary(t, rows)
  assert.ok(out.includes('version.provenanceSummary'), out)
  assert.ok(out.includes('"total":5'), out)
  assert.ok(out.includes('律师乙'), out)
  assert.ok(out.includes('version.actorYou'), out)
  assert.ok(out.includes('version.provenanceEarlier'), out)
  assert.equal(provenanceSummary(t, []), '')
})

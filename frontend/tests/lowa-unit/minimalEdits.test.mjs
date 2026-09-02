// 修订颗粒度（dev-board#365）：worker 的字符级最小 diff 必须是「删 X 插 Y」的散点片段，
// 不许把整句/整段吞成一块删除重写。用例直接抠 office_thread.js 的纯函数在 node 里跑，
// 真引擎上的修订形态由 lowa-e2e 组 11 兜底。
//   cd frontend && npm run test:lowa-unit
import test from 'node:test'
import assert from 'node:assert/strict'
import { loadWorkerFunctions } from './_workerFns.mjs'

const { minimalEdits, replaceAllIsSingleBlock } = loadWorkerFunctions(['myersEdits', 'minimalEdits', 'replaceAllIsSingleBlock'])

// 按 worker 的应用口径（从右到左、旧串坐标）把编辑脚本套回旧串，必须得到新串。
function applyEdits(oldStr, edits) {
  let s = oldStr
  for (const e of edits) s = s.slice(0, e.start) + e.insText + s.slice(e.start + e.delLen)
  return s
}
function assertDescending(edits) {
  for (let k = 0; k + 1 < edits.length; k++) assert.ok(edits[k].start > edits[k + 1].start, '编辑脚本必须按 start 降序（右到左应用）')
}
// 一段像样的合同正文，足够长（> 500 字）——旧实现的 DP 上限就是在这个长度上把整段吞掉的。
const CLAUSE = '乙方应当按照本合同约定的时间、地点和方式向甲方交付货物，并保证所交付货物的品种、规格、数量、质量符合本合同附件一的要求；'
const LONG_BODY = CLAUSE.repeat(12)
assert.ok(LONG_BODY.length > 600)

test('我爱你 → 我恨你：只删「爱」插「恨」，一条替换型片段', () => {
  const edits = minimalEdits('我爱你', '我恨你')
  assert.deepEqual(edits, [{ start: 1, delLen: 1, insText: '恨' }])
  assert.equal(applyEdits('我爱你', edits), '我恨你')
})

test('一句里三处散点改动 → 三组片段，各自只删一个字', () => {
  const oldS = '甲方应于三日内向乙方支付全部价款。'
  const newS = '乙方应于五日内向甲方支付全部价款。'
  const edits = minimalEdits(oldS, newS)
  assertDescending(edits)
  assert.equal(edits.length, 3, JSON.stringify(edits))
  assert.deepEqual(edits.map((e) => oldS.substr(e.start, e.delLen)), ['乙', '三', '甲'])
  assert.deepEqual(edits.map((e) => e.insText), ['甲', '五', '乙'])
  assert.equal(applyEdits(oldS, edits), newS)
})

test('长段落（> 500 字）首尾各一处一字之差 → 两条片段，绝不把中间整段吞掉', () => {
  const oldS = '甲方' + LONG_BODY + '三十日内付清。'
  const newS = '买方' + LONG_BODY + '六十日内付清。'
  const edits = minimalEdits(oldS, newS)
  assertDescending(edits)
  assert.equal(edits.length, 2, '整段被吞成一块：' + JSON.stringify(edits.map((e) => ({ start: e.start, delLen: e.delLen, ins: e.insText.length }))))
  assert.deepEqual(edits.map((e) => oldS.substr(e.start, e.delLen)), ['三', '甲'])
  assert.deepEqual(edits.map((e) => e.insText), ['六', '买'])
  assert.equal(applyEdits(oldS, edits), newS)
})

test('长段落中部两处改动（离两端都远）→ 仍是两条一字片段', () => {
  const oldS = LONG_BODY.slice(0, 400) + '三十日' + LONG_BODY.slice(0, 300) + '甲方' + LONG_BODY.slice(0, 200)
  const newS = LONG_BODY.slice(0, 400) + '六十日' + LONG_BODY.slice(0, 300) + '买方' + LONG_BODY.slice(0, 200)
  const edits = minimalEdits(oldS, newS)
  assert.equal(edits.length, 2, JSON.stringify(edits))
  assert.ok(edits.every((e) => e.delLen === 1 && e.insText.length === 1), JSON.stringify(edits))
  assert.equal(applyEdits(oldS, edits), newS)
})

test('纯插入（验收后 → 验收合格后）：一条只插不删的片段', () => {
  assert.deepEqual(minimalEdits('验收后', '验收合格后'), [{ start: 2, delLen: 0, insText: '合格' }])
})

test('纯删除：一条只删不插的片段', () => {
  assert.deepEqual(minimalEdits('验收合格后', '验收后'), [{ start: 2, delLen: 2, insText: '' }])
})

test('无差异 → 空脚本（不留任何修订痕迹）', () => {
  assert.deepEqual(minimalEdits('一模一样', '一模一样'), [])
})

test('两处改动只隔一个巧合相同字 → 并成一条，避免碎片化', () => {
  // 「方」是夹在两处改动中间的唯一相同字，单独留它只会让修订看着支离破碎
  const edits = minimalEdits('甲方三', '乙方五')
  assert.deepEqual(edits, [{ start: 0, delLen: 3, insText: '乙方五' }])
})

test('两处改动隔着两个以上相同字 → 保持两条，不把整句吞掉', () => {
  const edits = minimalEdits('甲方应于三日', '乙方应于五日')
  assert.equal(edits.length, 2, JSON.stringify(edits))
})

test('整段完全重写（无共同字）→ 一条覆盖全段的片段，可用不炸', () => {
  const edits = minimalEdits('ABCDEFG', 'xyz')
  assert.deepEqual(edits, [{ start: 0, delLen: 7, insText: 'xyz' }])
})

test('超长且面目全非（超过差异上限）→ 退化成一块整体替换，不抛、不卡', () => {
  const a = [], b = []
  for (let i = 0; i < 3000; i++) { a.push(String.fromCharCode(0x4e00 + (i * 7919) % 20000)); b.push(String.fromCharCode(0x4e00 + (i * 104729 + 13) % 20000)) }
  const oldS = a.join(''), newS = b.join('')
  const t0 = Date.now()
  const edits = minimalEdits(oldS, newS)
  assert.ok(Date.now() - t0 < 3000, '耗时 ' + (Date.now() - t0) + 'ms')
  assert.ok(edits.length >= 1)
  assert.equal(applyEdits(oldS, edits), newS)
})

test('随机小改动：脚本套回旧串恒等于新串（右到左应用不串位）', () => {
  let seed = 42
  const rnd = (n) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % n }
  for (let round = 0; round < 60; round++) {
    const base = LONG_BODY.slice(0, 200 + rnd(400)).split('')
    const mutated = base.slice()
    const k = 1 + rnd(5)
    for (let i = 0; i < k; i++) {
      const pos = rnd(mutated.length)
      const op = rnd(3)
      if (op === 0) mutated.splice(pos, 1)
      else if (op === 1) mutated.splice(pos, 0, '新')
      else mutated[pos] = '改'
    }
    const oldS = base.join(''), newS = mutated.join('')
    const edits = minimalEdits(oldS, newS)
    assertDescending(edits)
    assert.equal(applyEdits(oldS, edits), newS)
    // 散点小改动不许被吞成一大块：单条片段的删除长度不该超过改动数的十倍
    for (const e of edits) assert.ok(e.delLen <= k * 10, '片段过大：' + JSON.stringify(e) + ' k=' + k)
  }
})

test('find_replace 原生 replaceAll 只在差异是单块时启用；多处散点改动要走逐命中字符级路径', () => {
  assert.equal(replaceAllIsSingleBlock('我爱你', '我恨你'), true)
  assert.equal(replaceAllIsSingleBlock('甲方', '买方'), true)
  assert.equal(replaceAllIsSingleBlock('验收后', '验收合格后'), true)
  assert.equal(replaceAllIsSingleBlock('甲方应于三日内向乙方支付', '乙方应于五日内向甲方支付'), false)
  assert.equal(replaceAllIsSingleBlock('甲方应当在收到货物后三日内向乙方支付全部价款', '买方应当在收到货物后十日内向卖方支付全部价款'), false)
})

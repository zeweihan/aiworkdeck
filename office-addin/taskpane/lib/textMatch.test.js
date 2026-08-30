/**
 * 锚点归一化层的回归用例（dev-board#286）：
 *   node --test office-addin/taskpane/lib/textMatch.test.js
 *
 * 每一条都对应一类真实失配——律师核对时"两边一模一样"，逐字比较却必然失败。
 * 这些差异不会报错，只会让用户反复看到「未找到锚点文本，请确认精确一致」。
 * 最关键的一条不变式在最后：**命中区间必须换算回原文坐标**，否则归一化只会
 * 把"找不到"换成"改错地方"，比原来更糟。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizeForMatch, findAllNormalized, equalsNormalized, closestFragment, describeAnchorFailure
} from './textMatch.js'

/** 命中区间必须能从原文里切回来，且切出来的就是文档原文 */
function assertHit(doc, hits, expectedOriginal) {
  assert.equal(hits.length, 1, `应恰好命中一处，实际 ${hits.length}`)
  assert.equal(doc.slice(hits[0].start, hits[0].end), expectedOriginal)
  assert.equal(hits[0].text, expectedOriginal)
}

test('全角半角：模型写半角括号，文档里是全角', () => {
  const doc = '第三条　违约责任（含逾期利息）由甲方承担。'
  assertHit(doc, findAllNormalized(doc, '违约责任(含逾期利息)'), '违约责任（含逾期利息）')
})

test('弯直引号：文档里是中文弯引号，模型给的是直引号', () => {
  const doc = '本合同所称“不可抗力”指……'
  assertHit(doc, findAllNormalized(doc, '所称"不可抗力"指'), '所称“不可抗力”指')
})

test('不间断空格与表意空格都当普通空格', () => {
  const doc = '甲方 （以下　简称"甲方"）'
  const hits = findAllNormalized(doc, '甲方 （以下 简称"甲方"）')
  assert.equal(hits.length, 1)
  assert.equal(doc.slice(hits[0].start, hits[0].end), doc)
})

test('零宽字符与软连字符：PDF 转出来的文书里成片都是，不该影响命中', () => {
  const doc = '第​五​条 保­密义务'
  assertHit(doc, findAllNormalized(doc, '第五条 保密义务'), doc)
})

test('WPS 文字正文里的 \\x07 单元格结束符不该毁掉匹配', () => {
  // doc.Range().Text 会把单元格/行结束符一并交出来，模型照抄进 anchorText
  const doc = '项目\x07金额\x07\r合计\x07100 万元\x07\r'
  const hits = findAllNormalized(doc, '合计 100 万元')
  assert.equal(hits.length, 1)
  assert.ok(doc.slice(hits[0].start, hits[0].end).includes('合计'))
})

test('连续空白折叠：模型多打一个空格也要命中', () => {
  const doc = '第 三 条    违约责任'
  assertHit(doc, findAllNormalized(doc, '第 三 条 违约责任'), doc)
})

test('各式破折号归并到 ASCII 连字符', () => {
  const doc = '合同期限：2026—2027 年'
  assertHit(doc, findAllNormalized(doc, '2026-2027 年'), '2026—2027 年')
})

test('大小写折叠只影响拉丁字母，中文不受牵连', () => {
  const doc = 'Party A（甲方）应于 T+3 日内付款'
  assertHit(doc, findAllNormalized(doc, 'party a(甲方)'), 'Party A（甲方）')
})

test('多处命中逐一给出，且互不重叠', () => {
  const doc = '甲方应通知乙方；甲方并应赔偿乙方损失。'
  const hits = findAllNormalized(doc, '甲方')
  assert.equal(hits.length, 2)
  assert.ok(hits[0].end <= hits[1].start, '命中区间不许重叠')
  for (const h of hits) assert.equal(doc.slice(h.start, h.end), '甲方')
})

test('偏移映射不变式：命中区间切回原文必须逐字等于原文那一段（归一化最容易改错的地方）', () => {
  // 前半段塞满会改变长度的字符：全角、零宽、NBSP、连续空格
  const doc = 'ＡＢＣ​　　（一）  违约金  为  合同总价的  百分之五。'
  const hits = findAllNormalized(doc, '违约金 为 合同总价的 百分之五。')
  assert.equal(hits.length, 1)
  const cut = doc.slice(hits[0].start, hits[0].end)
  assert.equal(cut, '违约金  为  合同总价的  百分之五。')
  // 换算错位的典型症状是把前面的全角字母也圈进来
  assert.ok(!cut.includes('Ａ'), '区间不该把前面的内容圈进来')
})

test('equalsNormalized：逐笔校验用它，而不是原始字符串比较', () => {
  assert.ok(equalsNormalized('（甲方）', '(甲方)'))
  assert.ok(equalsNormalized('“合同”', '"合同"'))
  assert.ok(!equalsNormalized('甲方', '乙方'))
})

test('normalizeForMatch：不折叠空白时映射保持一一对应', () => {
  const r = normalizeForMatch('a b', { collapseSpace: false })
  assert.equal(r.text, 'a b')
  assert.deepEqual(r.starts, [0, 1, 2])
})

test('closestFragment：找不到时给出文档里最接近的一段原文', () => {
  const doc = '第八条 甲方应于每月十五日前向乙方支付服务费人民币壹万元整。'
  const near = closestFragment(doc, '甲方应于每月十日前向乙方支付服务费人民币贰万元整')
  assert.ok(near, '应能给出候选')
  assert.ok(near.text.includes('甲方应于每月'), near.text)
  assert.ok(near.similarity > 0.7, `相似度应偏高，实际 ${near.similarity}`)
})

test('describeAnchorFailure：报错必须带证据与下一步，而不是只说「请确认精确一致」', () => {
  const doc = '第八条 甲方应于每月十五日前向乙方支付服务费。'
  const msg = describeAnchorFailure('插入文本（修订）', '甲方应于每月十日前向乙方支付服务费', doc)
  assert.ok(msg.includes('最接近的一段原文'), msg)
  assert.ok(msg.includes('十五日'), '候选片段要把文档原文摆出来')
  assert.ok(/重试|重新读取/.test(msg), '要告诉模型下一步做什么')
})

test('空锚点不产生命中（避免"空串匹配一切"）', () => {
  assert.deepEqual(findAllNormalized('任意文档', ''), [])
  assert.deepEqual(findAllNormalized('任意文档', '   '), [])
})

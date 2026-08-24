// method 小条不再自动收起（dev-board#138）之后必须补的一条：它得钉在建链的那份文档上。
// 否则换标签/关文档之后，上一份文档的「已关联《x》」还挂在窗格左下角——比不显示更糟，
// 因为它在对另一份文档说谎。判断放在渲染期（isEvidenceBarOnActiveDoc），不是 watch：
// watch 漏一个入口（关标签/分屏挪动/退出项目）就是一条挂错文档的回执。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const VUE = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

// 从 SFC 里抠出这个方法体求值（同本仓 @/ 别名文件的一贯做法）
const m = VUE.match(/isEvidenceBarOnActiveDoc\(activeFile\) \{[\s\S]*?\n    \},/)
assert.ok(m, '没找到 isEvidenceBarOnActiveDoc')
const fn = new Function('return function ' + m[0].replace(/,$/, ''))()

test('活跃文档就是建链那份 → 显示', () => {
  const self = { evidenceMethodBar: { docFileId: 7 } }
  assert.equal(fn.call(self, { id: 7 }), true)
  assert.equal(fn.call(self, { id: '7' }), true, 'id 可能是字符串')
})

test('换到别的文档 → 收起', () => {
  const self = { evidenceMethodBar: { docFileId: 7 } }
  assert.equal(fn.call(self, { id: 8 }), false)
})

test('窗格里没有活跃文档（关掉了）→ 收起', () => {
  const self = { evidenceMethodBar: { docFileId: 7 } }
  assert.equal(fn.call(self, null), false)
})

test('没带 docFileId 的旧状态 → 不收（不因为兼容问题把回执吞掉）', () => {
  const self = { evidenceMethodBar: { docFileId: null } }
  assert.equal(fn.call(self, { id: 8 }), true)
})

test('docFileId 非数值（Number→NaN）按未钉处理 → 不收（NaN===NaN 恒 false 会把小条永久藏死）', () => {
  const self = { evidenceMethodBar: { docFileId: 'not-a-number' } }
  assert.equal(fn.call(self, { id: 8 }), true)
})

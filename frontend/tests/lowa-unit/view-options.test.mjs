// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const method = source.slice(source.indexOf('  set_view_options(p) {'), source.indexOf('  // 修订开关（工具栏'))
function harness({ rejectBoundaryWrite = false } = {}) {
  const values = new Map(Object.entries({
    ShowTextBoundaries: true, ShowNonprintingCharacters: true,
    ShowRulers: true, ShowHoriRuler: true, ShowVertRuler: true,
  }))
  const writes = []
  const view = {
    getPropertyValue: name => values.get(name),
    setPropertyValue(name, value) {
      writes.push([name, value])
      if (name === 'ShowTextBoundaries' && rejectBoundaryWrite) throw Error('rejected')
      values.set(name, value)
    },
  }
  // 只提供视图接口；若实现误写模型/页样式，测试会失败。
  const run = vm.runInNewContext(`({ ${method} }).set_view_options`, {
    ctrl: { getViewSettings: () => view }, errStr: String,
  })
  return { run, values, writes }
}

test('无参数仍为只读查询，返回引擎中的真实边界状态', () => {
  const h = harness()
  assert.equal(h.run().textBoundaries, true)
  assert.equal(h.run({}).textBoundaries, true)
  assert.deepEqual(h.writes, [])
})

test('明确隐藏文本边界可重复调用，不翻转状态、不动格式标记或标尺', () => {
  const h = harness()
  for (let i = 0; i < 2; i++) {
    const result = h.run({ textBoundaries: false })
    assert.equal(result.textBoundaries, false)
    assert.equal(result.formattingMarks, true)
    assert.equal(result.ruler, true)
    assert.equal(result.verticalRuler, true)
  }
  assert.deepEqual(h.writes, [['ShowTextBoundaries', false], ['ShowTextBoundaries', false]])
  assert.equal(h.run({ textBoundaries: true }).textBoundaries, true)
})

test('其它视图选项不隐式重写文本边界', () => {
  const h = harness()
  const result = h.run({ formattingMarks: false, ruler: false })
  assert.equal(result.textBoundaries, true)
  assert.equal(result.formattingMarks, false)
  assert.equal(result.ruler, false)
  assert.equal(result.verticalRuler, false)
  assert.equal(h.writes.some(([name]) => name === 'ShowTextBoundaries'), false)
})

test('设置被引擎拒绝时，读回不谎报已经隐藏', () => {
  const h = harness({ rejectBoundaryWrite: true })
  assert.equal(h.run({ textBoundaries: false }).textBoundaries, true)
})

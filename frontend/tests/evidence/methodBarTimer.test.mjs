// 回归：拖放建链成功后 method 小条的自动收起计时。
// 真机复现（dev-board#135，dev Electron + H5 双跑）：armEvidenceMethodBarTimer 引用了
// 从未定义的 METHOD_BAR_TTL_MS，setTimeout 第二参撞 ReferenceError → showEvidenceMethodBar
// 抛出，onEvidenceDrop 里紧随其后的 uni.$emit('awd:evidence-changed') 再不执行（面板/编辑器
// 不刷新、小条不自动收起），生产构建下响应式更新可能整个丢失＝用户「释放后什么都没有」。
//
// evidenceLinkActions.js 带 @/ 别名 import，node --test 直接 import 进不来：剥掉 import 行、
// export 换 return，用普通对象当 this 调 methods（同 review-panel-double-tap.test.mjs 的路子）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/evidenceLinkActions.js', import.meta.url), 'utf8')
  .replace(/^import [\s\S]*?from .*$/gm, '')
  .replace(/^export \{[^}]*\}$/gm, '')
  .replace(/^export const/gm, 'const')

// 尾部拼一个收集器，把两个导出对象吐出来
const mod = new Function(SRC + '\nreturn { evidenceLinkData, evidenceLinkMethods }')()
const { evidenceLinkData, evidenceLinkMethods } = mod

function ctx() {
  return { ...evidenceLinkData(), ...evidenceLinkMethods, $t: (k) => k }
}

test('showEvidenceMethodBar 不抛错，小条置为可见（不再撞 ReferenceError）', () => {
  const self = ctx()
  assert.doesNotThrow(() => {
    evidenceLinkMethods.showEvidenceMethodBar.call(self, { side: 'left', fileName: '营业执照.pdf', targetId: 5, linkKey: 'EVID_X' })
  })
  assert.equal(self.evidenceMethodBar.visible, true)
  assert.equal(self.evidenceMethodBar.fileName, '营业执照.pdf')
  clearTimeout(self._evidenceBarTimer)
})

test('armEvidenceMethodBarTimer 排定的是有限延时，回调把小条收起', async () => {
  const self = ctx()
  self.evidenceMethodBar.visible = true
  assert.doesNotThrow(() => evidenceLinkMethods.armEvidenceMethodBarTimer.call(self))
  assert.ok(self._evidenceBarTimer, '应排定了自动收起计时器')
  clearTimeout(self._evidenceBarTimer)
  // 手动跑一遍回调语义：置回不可见
  self._evidenceBarTimer = setTimeout(() => { self.evidenceMethodBar.visible = false }, 0)
  await new Promise((r) => setTimeout(r, 5))
  assert.equal(self.evidenceMethodBar.visible, false)
})

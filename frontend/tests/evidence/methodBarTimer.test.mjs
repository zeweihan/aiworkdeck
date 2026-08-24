// 回归：拖放建链成功后 method 小条的显示与收起时机。
//
// 两轮真机反馈叠在这一个文件里：
// ① dev-board#135：armEvidenceMethodBarTimer 引用了从未定义的 METHOD_BAR_TTL_MS，
//    setTimeout 第二参撞 ReferenceError → showEvidenceMethodBar 抛出，onEvidenceDrop 里
//    紧随其后的 uni.$emit('awd:evidence-changed') 再不执行（面板/编辑器不刷新）＝
//    用户「释放后什么都没有」。
// ② dev-board#138：常量补上之后小条确实显示了，但 3 秒自动收起——它同时是「已关联」
//    的回执和「按什么方式核查」的提问，两件事都办不成：用户松手时眼睛在正文落点上，
//    等看向窗格左下角，小条已经没了，于是仍然是「什么都没发生」。改成不自动收起，
//    只由用户动作（点 ×、选方法、建新的一条）与文档切换驱动。
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

const mod = new Function(SRC + '\nreturn { evidenceLinkData, evidenceLinkMethods }')()
const { evidenceLinkData, evidenceLinkMethods } = mod

function ctx() {
  return { ...evidenceLinkData(), ...evidenceLinkMethods, $t: (k) => k }
}

test('showEvidenceMethodBar 不抛错，小条置为可见（#135：不再撞 ReferenceError）', () => {
  const self = ctx()
  assert.doesNotThrow(() => {
    evidenceLinkMethods.showEvidenceMethodBar.call(self,
      { side: 'left', fileName: '营业执照.pdf', targetId: 5, linkKey: 'EVID_X', docFileId: 7 })
  })
  assert.equal(self.evidenceMethodBar.visible, true)
  assert.equal(self.evidenceMethodBar.fileName, '营业执照.pdf')
  assert.equal(self.evidenceMethodBar.docFileId, 7, '小条要记住是给哪份文档建的链')
})

test('#138：小条不再排定自动收起计时，等多久都还在', async () => {
  const self = ctx()
  evidenceLinkMethods.showEvidenceMethodBar.call(self,
    { side: 'left', fileName: '章程.pdf', targetId: 6, linkKey: 'EVID_Y', docFileId: 7 })
  assert.equal(self._evidenceBarTimer, undefined, '不该再有自动收起计时器')
  await new Promise((r) => setTimeout(r, 30))
  assert.equal(self.evidenceMethodBar.visible, true, '过了时间小条仍在（收起只由用户动作驱动）')
})

test('点 × 收起小条', () => {
  const self = ctx()
  evidenceLinkMethods.showEvidenceMethodBar.call(self,
    { side: 'left', fileName: '决议.pdf', targetId: 7, linkKey: 'EVID_Z', docFileId: 7 })
  evidenceLinkMethods.closeEvidenceMethodBar.call(self)
  assert.equal(self.evidenceMethodBar.visible, false)
})

test('再建一条链接：整体替换成新的那条，不叠加', () => {
  const self = ctx()
  evidenceLinkMethods.showEvidenceMethodBar.call(self,
    { side: 'left', fileName: '第一份.pdf', targetId: 1, linkKey: 'EVID_1', docFileId: 7 })
  evidenceLinkMethods.showEvidenceMethodBar.call(self,
    { side: 'right', fileName: '第二份.pdf', targetId: 2, linkKey: 'EVID_2', docFileId: 9 })
  assert.equal(self.evidenceMethodBar.fileName, '第二份.pdf')
  assert.equal(self.evidenceMethodBar.side, 'right')
  assert.equal(self.evidenceMethodBar.docFileId, 9)
  assert.equal(self.evidenceMethodBar.method, 'written_review', '新的一条回到默认方法')
})

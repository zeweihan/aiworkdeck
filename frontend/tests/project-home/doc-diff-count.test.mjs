// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/components/DocDiffViewer.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace('export default', 'return')
const component = new Function('markRaw', script)(value => value)

test('差异异步计算完成后更新计数，关闭比对时释放监听和模型', async () => {
  let changes = null
  let listener
  let listenerDisposed = false
  let editorDisposed = false
  let modelsDisposed = 0
  const editor = {
    setModel() {},
    getLineChanges: () => changes,
    onDidUpdateDiff(fn) { listener = fn; return { dispose() { listenerDisposed = true } } },
    dispose() { editorDisposed = true },
  }
  const vm = {
    ...component.methods,
    $refs: { monacoContainer: {} },
    $nextTick: fn => fn(),
    $t: key => key,
    sourceText: 'A', targetText: '', viewMode: 'side',
    totalDiffs: 0, currentDiffIndex: 0,
    loadMonaco: async () => ({ editor: {
      createDiffEditor: () => editor,
      createModel: () => ({ dispose() { modelsDisposed++ } }),
    } }),
  }
  await vm.initMonacoDiffEditor()
  assert.equal(vm.totalDiffs, 0, '刚 setModel 时计算尚未结束')
  assert.equal(typeof listener, 'function', '必须等待 Monaco 的实际计算完成事件')
  changes = [{}, {}]
  listener()
  assert.equal(vm.totalDiffs, 2)
  changes = []
  listener()
  assert.equal(vm.totalDiffs, 0, '相同文档零差异')
  vm.disposeDiffEditor()
  assert.equal(listenerDisposed, true)
  assert.equal(editorDisposed, true)
  assert.equal(modelsDisposed, 2)
})

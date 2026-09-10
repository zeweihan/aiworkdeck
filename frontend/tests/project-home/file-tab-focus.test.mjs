import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')
const body = source.replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
  .replace('export const fileOpenTabsMethods = {', 'return {')
const methods = new Function('activityTracker', 'uni', body)({ trackActivePage() {} }, { showModal() { throw Error('unexpected unsupported file') } })
function workspace() {
  const vm = {
    project: { id: 1 }, leftPaneKey: 'desensitize', sidebarCollapsed: false,
    leftFiles: [], rightFiles: [], splitMode: false, focusedPane: 'left',
    lastActiveIdsByMode: { left: {}, right: {} }, $refs: {},
    isFileTypeSupported: () => true, isBrowserTab: () => false,
    focusPane(pane) { this.focusedPane = pane }, saveActiveIdsByMode() {},
    triggerWorkbenchResize() {}, $nextTick(fn) { fn() },
  }
  for (const [name, method] of Object.entries(methods)) vm[name] = method.bind(vm)
  vm.isFileTypeSupported = () => true
  return vm
}

test('clicking document tabs preserves both the active sidebar and its collapsed state', () => {
  for (const panel of ['desensitize', 'search', 'voice', 'market']) {
    for (const collapsed of [true, false]) {
      const vm = workspace(); vm.leftPaneKey = panel; vm.sidebarCollapsed = collapsed
      vm.activateTab({ id: 12 }, 'left')
      assert.equal(vm.leftPaneKey, panel); assert.equal(vm.sidebarCollapsed, collapsed)
      assert.equal(vm.activeFileIdLeft, 12)
    }
  }
})

test('the visible explorer still follows the selected document', () => {
  const vm = workspace(); vm.leftPaneKey = 'files'
  let revealed
  vm.$refs.fileTree = { revealFile(id) { revealed = id } }
  vm.activateTab({ id: 13 }, 'left')
  assert.equal(revealed, 13)
})

test('reopening the same file with a string id retains its tab identity and state', () => {
  const vm = workspace(), tab = { id: 12, name: 'old.docx', pendingLocator: { quote: 'keep' }, editorState: 'keep' }
  vm.leftFiles.push(tab)
  vm.openFile({ id: '12', name: 'new.docx' })
  assert.equal(vm.leftFiles.length, 1); assert.equal(vm.leftFiles[0], tab)
  assert.equal(vm.activeFileIdLeft, 12); assert.equal(tab.id, 12)
  assert.equal(tab.name, 'new.docx'); assert.equal(tab.editorState, 'keep')
})

test('reopening a file in the other split focuses its existing tab', () => {
  const vm = workspace(); vm.splitMode = true; vm.focusedPane = 'left'
  vm.rightFiles.push({ id: 14, name: 'sample.docx' })
  vm.openFile({ id: 14, name: 'sample.docx' }, { locator: { quote: 'target' } })
  assert.equal(vm.leftFiles.length, 0); assert.equal(vm.rightFiles.length, 1)
  assert.equal(vm.focusedPane, 'right'); assert.equal(vm.activeFileIdRight, 14)
  assert.equal(vm.rightFiles[0].pendingLocator.quote, 'target')
})

test('a tab in a hidden right split is revealed instead of duplicated', () => {
  const vm = workspace(); vm.rightFiles.push({ id: 15 })
  vm.openFile({ id: 15 })
  assert.equal(vm.leftFiles.length, 0); assert.equal(vm.splitMode, true)
  assert.equal(vm.focusedPane, 'right')
})

test('different files with identical names remain separate; repeated opens are synchronous and idempotent', () => {
  const vm = workspace()
  for (let i = 0; i < 10; i++) vm.openFile({ id: 1, name: 'same.docx' })
  vm.openFile({ id: 2, name: 'same.docx' })
  assert.deepEqual(vm.leftFiles.map(f => f.id), [1, 2])
})

const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const visible = page.slice(page.indexOf('    isTabVisible(file) {'), page.indexOf('    startRenameProject()'))
const isTabVisible = new Function('return ({' + visible + '}).isTabVisible')()
test('ordinary documents stay visible throughout sidebar workflows', () => {
  for (const leftPaneKey of ['desensitize', 'market', 'home', 'version', 'files']) {
    assert.equal(isTabVisible.call({ leftPaneKey, isMovablePanel: () => false }, { id: 1, fileType: 'docx' }), true)
  }
})

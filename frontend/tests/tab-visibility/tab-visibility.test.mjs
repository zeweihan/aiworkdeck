// 标签页常驻契约（dev-board#394）：左栏切到任何面板，中间列已开的标签都不许消失。
// 跑法：cd frontend && npm run test:tab-visibility
import test from 'node:test'
import assert from 'node:assert/strict'
import { isTabVisibleInPane } from '../../src/pages/project-overview/tabVisibility.js'

const PANES = ['files', 'search', 'voice', 'litigation-visual', 'home', 'market', 'dev',
  'desensitize', 'calendar', 'version', 'dd-files', 'favorites', 'clipboard', 'insight',
  'variables', 'some-plugin-key', '', null, undefined]

const TABS = [
  { id: 1, name: '催款函20241108.docx', fileType: 'docx' },
  { id: 'web-1', name: '百度一下', tabType: 'browser' },
  { id: 'cmp-1', tabType: 'version-compare' },
  { id: 'cmp-2', tabType: 'version-text-diff' },
  { id: 'market-x', tabType: 'market-detail' },
  { id: 'admin', tabType: 'admin-settings' },
  { id: 'dd-9', type: 'dd-request', fileType: 'dd' },
  { id: 'plug-1', fileType: 'plugin', pluginId: 'plug-1' },
]

test('任意左栏面板下，任意已开标签都可见', () => {
  for (const pane of PANES) {
    for (const tab of TABS) {
      assert.equal(isTabVisibleInPane(tab, pane), true,
        `面板 ${String(pane)} 下标签 ${tab.name || tab.id} 被藏了`)
    }
  }
})

test('空标签不可见（列表里的空洞不占位）', () => {
  assert.equal(isTabVisibleInPane(null, 'files'), false)
  assert.equal(isTabVisibleInPane(undefined, 'home'), false)
})

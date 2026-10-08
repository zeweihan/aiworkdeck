// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 插件工作区是项目级单例标签；标签只持有身份，运行配置始终取当前插件清单。
export const pluginWorkspaceTabMethods = {
  openPluginTab(pluginId) {
    if (!this.hasProject) return
    const plugin = this.dynamicPlugins.find(p => p.pluginId === pluginId)
    if (!plugin) return
    for (const pane of ['left', 'right']) {
      const existing = this[pane === 'left' ? 'leftFiles' : 'rightFiles'].find(t => t.tabType === 'plugin' && t.pluginId === pluginId)
      if (!existing) continue
      this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
      this.focusedPane = pane
      this.$nextTick(() => this.triggerWorkbenchResize())
      return
    }
    const pane = this.splitMode && this.focusedPane === 'right' ? 'right' : 'left'
    const tab = { id: `plugin-${pluginId}`, tabType: 'plugin', pluginId, name: plugin.label }
    this[pane === 'left' ? 'leftFiles' : 'rightFiles'].push(tab)
    this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = tab.id
    this.focusedPane = pane
    this.$nextTick(() => this.triggerWorkbenchResize())
  },

  // 没有通过服务端清单核验的快照只能留标签占位，不能启动 iframe。
  pluginWorkspaceTabs(files) {
    if (!this.hasProject) return []
    return files.filter(t => t.tabType === 'plugin').flatMap(tab => {
      const plugin = this.dynamicPlugins.find(p => p.pluginId === tab.pluginId)
      return plugin ? [{ tab, plugin }] : []
    })
  },

  reconcilePluginTabs() {
    if (!this.dynamicPluginsLoaded) return
    const valid = new Map((this.hasProject ? this.dynamicPlugins : []).map(p => [p.pluginId, p]))
    const removed = new Set()
    for (const pane of ['left', 'right']) {
      const list = this[pane === 'left' ? 'leftFiles' : 'rightFiles']
      const activeKey = pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      for (let i = list.length - 1; i >= 0; i--) {
        const tab = list[i]
        if (tab.tabType !== 'plugin') continue
        const plugin = valid.get(tab.pluginId)
        if (plugin) {
          tab.name = plugin.label
          continue
        }
        list.splice(i, 1)
        removed.add(tab.id)
        if (this[activeKey] === tab.id) this[activeKey] = list[Math.min(i, list.length - 1)]?.id ?? null
      }
    }
    for (const pane of ['left', 'right']) {
      const memory = this.lastActiveIdsByMode[pane]
      for (const key of Object.keys(memory)) if (removed.has(memory[key])) memory[key] = null
    }
    if (removed.size) this.saveActiveIdsByMode()
  },
}

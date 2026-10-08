// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// project-overview.vue 的左栏面板切换状态机：toggleLeftPane / 模式级 tab 记忆持久化。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。

import { track } from '@/utils/telemetryClient.js'
import { isPaneAllowedWithoutProject, workbenchStorageKey } from './noProjectShell.js'

export const panelSwitchingMethods = {
    toggleLeftPane(key) {
      // 无项目态（dev-board#1047）：只有全局面板能开。项目面板不挂载——它们带着
      // projectId=null 空转会打出 /api/projects/null/... 请求。菜单 / 命令面板 / 旧存量值
      // 都可能把一个项目面板的 key 送进来，这里是唯一的闸。
      if (!this.hasProject && !isPaneAllowedWithoutProject(key)) return
      // 埋点：三分支语义分开记（staging 特殊 / 同 key 收展 / 异 key 真切换），
      // 否则「切面板」数会被「折叠侧栏」污染
      track('ui.nav', {
        panelKey: String(key || ''),
        branch: key === 'staging' ? 'staging'
          : (this.leftPaneKey === key ? 'collapse_toggle' : 'switch')
      })
      if (key === 'staging') {
        // Toggle staging visibility
        if (this.showStagingArea) {
          // Currently showing, collapse it
          this.stagingPinned = false
          this.stagingManuallyCollapsed = true
        } else {
          // Currently hidden, expand it
          this.stagingPinned = true
          this.stagingManuallyCollapsed = false
          this.sidebarCollapsed = false
        }
        return
      }

      // 记录当前活跃 tab 到当前模式
      const oldKey = this.leftPaneKey
      if (oldKey) {
        this.lastActiveIdsByMode.left[oldKey] = this.activeFileIdLeft
        this.lastActiveIdsByMode.right[oldKey] = this.activeFileIdRight
      }

      if (this.leftPaneKey === key) {
        // 收展走统一出口（dev-board#727）：与 rail 底部/顶栏的收起按钮共用同一个
        // toggleSidebar，确保持久化与 triggerWorkbenchResize 两条路径不会各走各的。
        this.toggleSidebar()
      } else {
        this.leftPaneKey = key
        this.sidebarCollapsed = false

        // 「只看《某份文件》的历史」是版本面板的临时过滤态。切到别的面板就清掉，
        // 否则律师下次回到版本面板，还端着上一次右键那份文件的过滤条。
        if (key !== 'version') this.versionFileFilter = null

        // 标签常驻、与左栏面板解耦（dev-board#394）：切面板不再按 lastActiveIdsByMode
        // 换活跃标签，也不再因「新面板下不可见」把 activeFileId 置空——律师点一下
        // 插件中心，正在改的催款函不该凭空没了。lastActiveIdsByMode 仍照记，
        // 关标签时的兜底（fileOpenTabs.js）与存量本地存储都还读它。
      }

      const plugin = this.dynamicPlugins.find(p => p.key === key)
      if (plugin) this.openPluginTab(plugin.pluginId)

      // Persistence：有项目按项目分，无项目落 global_*（此前无项目态写的是 project_null_*）
      uni.setStorageSync(workbenchStorageKey(this.projectId, 'leftPaneKey'), key)
      this.saveActiveIdsByMode()
    },
    saveActiveIdsByMode() {
      uni.setStorageSync(workbenchStorageKey(this.projectId, 'activeTabsByMode'), this.lastActiveIdsByMode)
    },
    onLeftPluginClick(key) {
      // 兼容旧调用（若仍有地方使用）
      this.toggleLeftPane(key)
    },
}

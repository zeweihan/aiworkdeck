// project-overview.vue 的左栏面板切换状态机：toggleLeftPane / 模式级 tab 记忆持久化。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。

import { track } from '@/utils/telemetryClient.js'

export const panelSwitchingMethods = {
    toggleLeftPane(key) {
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
        this.sidebarCollapsed = !this.sidebarCollapsed
      } else {
        this.leftPaneKey = key
        this.sidebarCollapsed = false

        // 「只看《某份文件》的历史」是版本面板的临时过滤态。切到别的面板就清掉，
        // 否则律师下次回到版本面板，还端着上一次右键那份文件的过滤条。
        if (key !== 'version') this.versionFileFilter = null

        // 动态插件（Web 插件 → PluginPane，纯工具/skill 插件 → PluginGuidePane）在
        // 左栏面板区渲染，与诉讼可视化/脱敏等一致（「左栏一个图标 = 一个插件」）。
        // 这里**不再** openFile 一个 fileType:'plugin' 的中栏标签——isFileTypeSupported
        // 没有 'plugin'，那条老路只会弹「无法打开文件」的模态（dev-board#132 真机复现），
        // 从来没渲染出过东西。leftPaneKey 已经切到本插件，左栏 v-else-if 分支负责显示。

        // 标签常驻、与左栏面板解耦（dev-board#394）：切面板不再按 lastActiveIdsByMode
        // 换活跃标签，也不再因「新面板下不可见」把 activeFileId 置空——律师点一下
        // 插件中心，正在改的催款函不该凭空没了。lastActiveIdsByMode 仍照记，
        // 关标签时的兜底（fileOpenTabs.js）与存量本地存储都还读它。
      }

      // Persistence
      if (this.projectId) {
        uni.setStorageSync(`project_${this.projectId}_leftPaneKey`, key)
        this.saveActiveIdsByMode()
      }
    },
    saveActiveIdsByMode() {
      if (this.projectId) {
        uni.setStorageSync(`project_${this.projectId}_activeTabsByMode`, this.lastActiveIdsByMode)
      }
    },
    onLeftPluginClick(key) {
      // 兼容旧调用（若仍有地方使用）
      this.toggleLeftPane(key)
    },
}

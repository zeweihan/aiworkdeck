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

        // Check if it's a dynamic plugin and open its tab
        const plugin = this.dynamicPlugins.find(p => p.key === key)
        if (plugin) {
          // pluginId / permissions 随标签一起带过去：PluginPane 的 postMessage 桥
          // 要用原始插件 id（标签 id 是 plugin-<id> 的面板 key）与 manifest 权限声明
          this.openFile({
            id: plugin.key,
            name: plugin.label,
            fileType: 'plugin',
            pluginId: plugin.pluginId,
            permissions: plugin.permissions || [],
            frontendEntry: plugin.frontendEntry
          })
        }

        // 恢复新模式下的活跃 tab。记忆里的 id 可能已经关掉、也可能在新面板下
        // 根本不可见（版本对比标签只在 version/files 下可见），直接照抄会把律师
        // strand 在一个空白编辑区上——所以先验证「还在且可见」，否则退回下面
        // 「挑第一个可见标签」的兜底逻辑。
        const savedLeftId = this.lastActiveIdsByMode.left[key]
        const savedRightId = this.lastActiveIdsByMode.right[key]
        const savedLeftTab = savedLeftId ? this.leftFiles.find(f => f.id === savedLeftId) : null
        const savedRightTab = savedRightId ? this.rightFiles.find(f => f.id === savedRightId) : null
        const savedLeft = savedLeftTab && this.isTabVisible(savedLeftTab) ? savedLeftId : null
        const savedRight = savedRightTab && this.isTabVisible(savedRightTab) ? savedRightId : null

        if (savedLeft) {
          this.activeFileIdLeft = savedLeft
        } else {
          // 如果新模式没有记录，且当前 active 的 tab 在新模式下不可见，则设为 null
          const curLeft = this.leftFiles.find(f => f.id === this.activeFileIdLeft)
          if (curLeft && !this.isTabVisible(curLeft)) {
            // 尝试找一个在新模式下可见的 tab
            const firstVisible = this.leftFiles.find(f => this.isTabVisible(f))
            this.activeFileIdLeft = firstVisible ? firstVisible.id : null
          }
        }

        if (savedRight) {
          this.activeFileIdRight = savedRight
        } else {
          const curRight = this.rightFiles.find(f => f.id === this.activeFileIdRight)
          if (curRight && !this.isTabVisible(curRight)) {
            const firstVisible = this.rightFiles.find(f => this.isTabVisible(f))
            this.activeFileIdRight = firstVisible ? firstVisible.id : null
          }
        }
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

// project-overview.vue 的左栏面板切换状态机：toggleLeftPane / 模式级 tab 记忆持久化。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。

export const panelSwitchingMethods = {
    toggleLeftPane(key) {
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

        // Check if it's a dynamic plugin and open its tab
        const plugin = this.dynamicPlugins.find(p => p.key === key)
        if (plugin) {
          this.openFile({
            id: plugin.key,
            name: plugin.label,
            fileType: 'plugin',
            frontendEntry: plugin.frontendEntry
          })
        }

        // 恢复新模式下的活跃 tab
        const savedLeft = this.lastActiveIdsByMode.left[key]
        const savedRight = this.lastActiveIdsByMode.right[key]

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

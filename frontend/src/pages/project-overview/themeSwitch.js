// 顶栏外观切换（dev-board#223）：浅色 / 深色 / 跟随系统三选一。
// 位置就是原「已连接账户」chip 那一格（该 chip 已于 dev-board#221 下线）。
//
// 真正的主题状态与持久化在 utils/appTheme.js，这里只负责顶栏那个按钮的开合与
// 取值展示。「跟随系统」时系统外观变化会经 APP_THEME_EVENT 回来，图标要跟着换
// （太阳/月亮显示的是**当前生效**的外观，不是 mode 本身）。
//
// 经展开进组件 data/methods（同 railSort.js 的形制），`this` 即 project-overview 页面实例。

import { track } from '@/utils/telemetryClient.js'
import { getThemeMode, getResolvedTheme, setThemeMode, APP_THEME_EVENT } from '@/utils/appTheme.js'

export const themeSwitchData = () => ({
  themeMode: getThemeMode(),
  resolvedTheme: getResolvedTheme(),
  themeMenuOpen: false,
})

export const themeSwitchMethods = {
  initThemeSwitch() {
    this._onThemeChanged = (resolved) => {
      this.resolvedTheme = resolved || getResolvedTheme()
      this.themeMode = getThemeMode()
    }
    try { uni.$on(APP_THEME_EVENT, this._onThemeChanged) } catch (e) { /* ignore */ }
  },

  disposeThemeSwitch() {
    try { if (this._onThemeChanged) uni.$off(APP_THEME_EVENT, this._onThemeChanged) } catch (e) { /* ignore */ }
    this._onThemeChanged = null
  },

  pickTheme(mode) {
    this.themeMenuOpen = false
    if (mode === this.themeMode) return
    setThemeMode(mode)
    this.themeMode = getThemeMode()
    this.resolvedTheme = getResolvedTheme()
    try { track('ui.themeMode', { mode }) } catch (e) { /* ignore */ }
  },
}

export const themeSwitchComputed = {
  themeOptions() {
    return [
      { value: 'light', label: this.$t('workbench.appearanceLight') },
      { value: 'dark', label: this.$t('workbench.appearanceDark') },
      { value: 'system', label: this.$t('workbench.appearanceSystem') },
    ]
  },
}

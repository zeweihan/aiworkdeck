// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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
import { themeMenuKeyAction } from './themeMenuKeys.js'

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

  // ---- 键盘与辅助技术（v0.49.0 BUG-53）----
  // 菜单项原来是没有角色的 <view>：AXPress 落到可点的祖先（触发按钮的开合开关）上，
  // 菜单收起而主题不变；键盘完全够不着。角色/tabindex 在模板上，这里管按键与焦点。
  // uni-h5 包装后的键盘事件只转发 key/code 与 preventDefault/stopPropagation，够用。
  themeCurrentIndex() {
    const i = (this.themeOptions || []).findIndex((o) => o.value === this.themeMode)
    return i < 0 ? 0 : i
  },

  openThemeMenu(focusIndex) {
    this.themeMenuOpen = true
    this.$nextTick(() => this.focusThemeItem(focusIndex))
  },

  closeThemeMenu() {
    this.themeMenuOpen = false
    this.focusThemeTrigger()
  },

  onThemeTriggerKey(e) {
    const k = e && e.key
    if (k === 'Enter' || k === ' ' || k === 'Spacebar' || k === 'ArrowDown' || k === 'ArrowUp') {
      if (e.preventDefault) e.preventDefault()
      if (this.themeMenuOpen && (k === 'Enter' || k === ' ' || k === 'Spacebar')) {
        this.closeThemeMenu()
        return
      }
      const n = (this.themeOptions || []).length
      this.openThemeMenu(k === 'ArrowUp' ? n - 1 : this.themeCurrentIndex())
    } else if ((k === 'Escape' || k === 'Esc') && this.themeMenuOpen) {
      if (e.preventDefault) e.preventDefault()
      this.closeThemeMenu()
    }
  },

  onThemeItemKey(e, index) {
    const opts = this.themeOptions || []
    const act = themeMenuKeyAction(e && e.key, index, opts.length)
    if (!act) return
    if (!act.keepDefault && e.preventDefault) e.preventDefault()
    if (act.type === 'move') {
      this.focusThemeItem(act.index)
    } else if (act.type === 'pick') {
      const opt = opts[act.index]
      if (opt) this.pickTheme(opt.value)
      this.focusThemeTrigger()
    } else if (act.type === 'close') {
      if (act.keepDefault) this.themeMenuOpen = false
      else this.closeThemeMenu()
    }
  },

  focusThemeItem(index) {
    try {
      const root = (this.$el && this.$el.querySelectorAll) ? this.$el : document
      const items = root.querySelectorAll('.theme-menu .theme-menu-item')
      const el = items[index] || items[0]
      if (el && el.focus) el.focus()
    } catch (e) { /* ignore */ }
  },

  focusThemeTrigger() {
    try {
      const root = (this.$el && this.$el.querySelector) ? this.$el : document
      const el = root.querySelector('.theme-btn')
      if (el && el.focus) el.focus()
    } catch (e) { /* ignore */ }
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

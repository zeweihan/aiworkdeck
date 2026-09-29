// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// welcomeTab.js — 中栏「欢迎」标签（dev-board#1047，spec 2026-09-29-defer-login-welcome-tab-design §6）。
//
// 形制照 insightEntityTab.js：零依赖（不 import Vue / uni / '@/' 别名），方法组里的 this
// 就是 project-overview 页面实例，node --test 拿一个假 this 就能跑（tests/project-home/welcome-tab.test.mjs）。
//
// 契约三条：
//   1. 单例：id 恒为 'welcome'，左右两个窗格任一边开着就只激活，不开第二个；
//   2. 不是文档：tabType 'welcome' 在 fileKind.js 的 NON_FILE_TAB_TYPES 里，id 非数字，
//      activeTabContext.isContextEligibleTab 对它恒为 false（不当活跃文档、不能拖进 AI 上下文）；
//   3. 「启动时显示欢迎页」是本机习惯（不带 projectId，默认开），关掉后启动只进无项目态外壳，
//      中央空态给一行提示 + 「打开欢迎页」链接；菜单「帮助 → 欢迎」随时能开。

export const WELCOME_TAB_ID = 'welcome'
export const WELCOME_TAB_TYPE = 'welcome'

const SHOW_ON_STARTUP_KEY = 'awd_welcome_show_on_startup'
const TELEMETRY_NOTICE_KEY = 'awd_welcome_telemetry_notice_dismissed'

/** 「启动时显示欢迎页」，默认 true。storage 是 uni 的 getStorageSync 子集。 */
export function loadShowWelcomeOnStartup(storage) {
  try {
    const v = storage.getStorageSync(SHOW_ON_STARTUP_KEY)
    return v !== false
  } catch (e) {
    return true
  }
}

export function saveShowWelcomeOnStartup(storage, on) {
  try {
    storage.setStorageSync(SHOW_ON_STARTUP_KEY, Boolean(on))
  } catch (e) {
    // 存储不可用：本次会话内的勾选照样生效，只是不记住
  }
}

/** 欢迎页底部那行匿名统计提示是否已被关掉（关掉后不再出现）。 */
export function loadTelemetryNoticeDismissed(storage) {
  try {
    return storage.getStorageSync(TELEMETRY_NOTICE_KEY) === true
  } catch (e) {
    return false
  }
}

export function saveTelemetryNoticeDismissed(storage) {
  try {
    storage.setStorageSync(TELEMETRY_NOTICE_KEY, true)
  } catch (e) {
    // ignore
  }
}

export const welcomeTabMethods = {
  /**
   * 打开（或激活）欢迎标签。已经开着就只激活——重开一次等于把用户在那一页的滚动与
   * Recent 的加载态抹掉。新开时落在当前焦点窗格（未分屏恒为左侧），同 openSettingsTab。
   */
  openWelcomeTab() {
    for (const pane of ['left', 'right']) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const existing = list.find((f) => f.id === WELCOME_TAB_ID)
      if (existing) {
        this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
        this.focusedPane = pane
        this.$nextTick(() => this.triggerWorkbenchResize())
        return
      }
    }
    const targetPane = this.splitMode ? this.focusedPane : 'left'
    const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
    list.push({
      id: WELCOME_TAB_ID,
      tabType: WELCOME_TAB_TYPE,
      name: this.$t('welcome.tabName'),
    })
    this[targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = WELCOME_TAB_ID
    this.focusedPane = targetPane
    this.$nextTick(() => this.triggerWorkbenchResize())
  },
}

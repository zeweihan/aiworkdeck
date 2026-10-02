// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// tmeetTab.js — 中栏「腾讯会议逐字稿」标签生命周期与方法组。
//
// 形制照 calendarTab.js / welcomeTab.js：零依赖（不 import Vue / uni / '@/' 别名），
// 方法组里的 this 就是 project-overview 页面实例，node --test 拿假 this 即可单测。

export const TMEET_TRANSCRIPT_TAB_TYPE = 'tmeet-transcript'

export function tmeetTabId(recordId) {
  return 'tmeet-' + String(recordId)
}

export const tmeetTabMethods = {
  /** 打开或激活腾讯会议逐字稿标签页 */
  openTmeetTranscriptTab(meeting) {
    if (!meeting || !meeting.id) return
    const id = tmeetTabId(meeting.id)

    // 先检查左右窗格是否已开着该会议标签
    for (const pane of ['left', 'right']) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const existing = list.find((f) => f.id === id)
      if (existing) {
        existing.meetingSpec = meeting
        this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
        this.focusedPane = pane
        this.$nextTick(() => this.triggerWorkbenchResize && this.triggerWorkbenchResize())
        return
      }
    }

    // 未开时，加入当前聚焦的窗格（未分屏时入左窗格）
    const targetPane = this.splitMode ? this.focusedPane : 'left'
    const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
    const tabName = meeting.subject || '腾讯会议逐字稿'
    const tab = {
      id,
      tabType: TMEET_TRANSCRIPT_TAB_TYPE,
      name: tabName,
      meetingSpec: meeting,
    }

    list.push(tab)
    this[targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = tab.id
    this.focusedPane = targetPane
    this.$nextTick(() => this.triggerWorkbenchResize && this.triggerWorkbenchResize())
  },
}

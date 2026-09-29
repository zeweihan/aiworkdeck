<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  日程直链薄壳（dev-board#50 起步，#897 重做，#1048 退成薄壳）。

  日程的本体是工作台里的中栏「日程」标签（components/calendar/CalendarPane.vue，embedded）。
  本路由只留给直链、提醒通知（utils/taskReminders.js 在工作台之外时的落点）与设置薄壳页里的
  「查看日程」：进来即转进工作台并开日程标签，query 原样透传——
    ?focus=<事项 id>                     定位到事项并打开编辑
    ?group=overdue|today|week|later      议程滚到该组
    ?projectId=<id>                      带上就进该项目的工作台（标签本身仍是全局视图），不带进无项目态外壳
  全局返回键在本页豁免（utils/globalBack.js 的 SELF_NAV_ROUTES），本页不渲染内容。
-->
<template>
  <view class="page-calendar"></view>
</template>

<script>
import { isDesktopHost } from '@/services/host.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'

const GROUPS = ['overdue', 'today', 'week', 'later']

/** 纯函数：薄壳 query → 工作台 URL（tests/project-home/calendar-tab.test.mjs 抠出来跑） */
export function calendarShellTarget(query) {
  const q = query || {}
  const params = []
  if (q.projectId !== undefined && q.projectId !== null && /^[1-9][0-9]*$/.test(String(q.projectId))) {
    params.push('id=' + String(q.projectId))
  }
  params.push('tab=calendar')
  if (q.focus !== undefined && q.focus !== null && String(q.focus) !== '') {
    params.push('focus=' + encodeURIComponent(String(q.focus)))
  }
  if (GROUPS.includes(q.group)) params.push('group=' + q.group)
  return '/pages/project-overview/project-overview?' + params.join('&')
}

export default {
  name: 'CalendarPage',
  onLoad(query) {
    // 浏览器端未登录回登录页；桌面 local-mode 免登，跳过该检查（同 project-list 薄壳）
    if (!isDesktopHost() && !(getSessionId() && getCurrentUser())) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    // 工作台参与的跳转一律 reLaunch：栈里若压着一个活着的工作台，redirectTo 会让两个工作台实例并存
    uni.reLaunch({ url: calendarShellTarget(query) })
  },
}
</script>

<style lang="scss" scoped src="./calendar.scss"></style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  全局日程页（薄壳）。dev-board #50 起步，#897 重做，#1048 主体抽成
  components/calendar/CalendarPane.vue（工作台「日程」标签共用同一个组件）。

  本页只做两件事：收 query（?focus=<id> 定位到事项并打开编辑；?group=overdue|today|week|later
  议程滚到该组；?projectId=<id> 只看某个项目），渲染 embedded=false 的 CalendarPane。
  页面形态下的跳转（进入项目 / 打开文件 reLaunch 工作台，返回 navigateBack / redirectTo 项目列表）
  由组件自己保持。直链与 utils/taskReminders.js 的提醒落点仍指向本页。
  全局返回键在本页豁免（utils/globalBack.js 的 SELF_NAV_ROUTES），否则压在组件页头上。
-->
<template>
  <view class="page-calendar">
    <CalendarPane :embedded="false" :project-id="projectId" :focus="focus" :group="group" />
  </view>
</template>

<script>
import CalendarPane from '@/components/calendar/CalendarPane.vue'

export default {
  name: 'CalendarPage',
  components: { CalendarPane },
  data() {
    return {
      projectId: null,
      focus: '',
      group: '',
    }
  },
  onLoad(query) {
    const q = query || {}
    this.projectId = q.projectId ? String(q.projectId) : null
    this.focus = q.focus ? String(q.focus) : ''
    this.group = q.group ? String(q.group) : ''
  },
}
</script>

<style lang="scss" scoped src="./calendar.scss"></style>

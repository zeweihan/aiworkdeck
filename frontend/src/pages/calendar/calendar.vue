<!--
  全局日历页（跨项目日程/截止日）。dev-board #50，spec:
  docs/superpowers/specs/2026-08-20-calendar-view-design.md

  不是工作台，路由用 navigateTo/navigateBack/redirectTo（工作台参与的跳转才 reLaunch，
  「进入项目」按钮除外——那一跳落进工作台）。不自绘顶栏，用默认 38px 拖拽条。

  FullCalendar 集成选型：@fullcalendar/vue3 组件式（<FullCalendar :options="...">），
  不是命令式 Calendar 类。理由：uni-app H5 平台下 .vue 单文件组件本质就是标准 Vue3
  SFC，第三方 Vue 组件按 components 选项注册后可直接当普通标签用，没有 uni 模板编译器
  不认第三方标签的问题（真正的坑只出现在小程序/App 端的 uni 组件编译，本产品只出 H5）。
  组件式还换来了官方文档的响应式契约：options.events 是「复杂选项」，vue3 适配器对它
  做 deep watch + calendar.resetOptions 增量更新（见 node_modules/@fullcalendar/vue3/dist/FullCalendar.js
  的 buildWatchers/OPTION_IS_COMPLEX），直接 this.calendarOptions.events = [...] 赋值即可,
  不需要手动调用 addEvent/removeAllEvents 这类命令式 API。
-->
<template>
  <view class="page-calendar">
    <view class="calendar-container">
      <view class="content-header">
        <text class="header-title">{{ $t('calendar.pageTitle') }}</text>
        <view class="header-actions">
          <button class="btn-secondary-small" @tap="goBack">{{ $t('calendar.backToProjects') }}</button>
        </view>
      </view>

      <view class="calendar-body">
        <view class="calendar-main">
          <FullCalendar ref="fc" class="fc-host" :options="calendarOptions" />
        </view>
        <view class="calendar-sidebar">
          <UpcomingList :tasks="upcomingTasks" @select="onUpcomingSelect" />
        </view>
      </view>
    </view>

    <TaskDialog
      v-model:visible="dialogVisible"
      :task="editingTask"
      :default-date="defaultDate"
      :projects="projects"
      @saved="refresh"
      @deleted="refresh"
      @open-project="goToProject"
    />
  </view>
</template>

<script>
import FullCalendar from '@fullcalendar/vue3'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import listPlugin from '@fullcalendar/list'
import interactionPlugin from '@fullcalendar/interaction'
import zhCnLocale from '@fullcalendar/core/locales/zh-cn'

import { getCalendarTasks, getMyProjects, updateTask } from '@/services/api.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { colorForProject } from '@/components/calendar/eventColors.js'
import { getDayMarkType } from '@/components/calendar/holidayMarks.js'
import { isDone, toEventStart } from '@/components/calendar/taskUtils.js'
import TaskDialog from '@/components/calendar/TaskDialog.vue'
import UpcomingList from '@/components/calendar/UpcomingList.vue'

const DONE_BG = '#F1F3F5'
const DONE_TEXT = '#ADB5BD'

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export default {
  name: 'CalendarPage',
  components: { FullCalendar, TaskDialog, UpcomingList },
  data() {
    return {
      calendarOptions: {},
      tasks: [],
      // 「近期截止」侧栏的数据源，固定锚在「今天起 90 天」，与日历视图区间解耦——
      // 用户翻到别的月份浏览时，侧栏不能跟着漂移（漂了会把本周真正紧迫的截止日藏掉）。
      upcomingTasks: [],
      projects: [],
      dialogVisible: false,
      editingTask: null,
      defaultDate: '',
      currentFrom: '',
      currentTo: '',
    }
  },
  created() {
    this.buildCalendarOptions()
    this.loadProjects()
    this.loadUpcoming()
  },
  methods: {
    buildCalendarOptions() {
      const isZh = getAppLanguage() === 'zh-CN'
      this.calendarOptions = {
        plugins: [dayGridPlugin, timeGridPlugin, listPlugin, interactionPlugin],
        initialView: 'dayGridMonth',
        headerToolbar: {
          left: 'prev,next today',
          center: 'title',
          right: 'dayGridMonth,timeGridWeek,listMonth',
        },
        buttonText: {
          today: this.$t('calendar.today'),
          month: this.$t('calendar.viewMonth'),
          week: this.$t('calendar.viewWeek'),
          list: this.$t('calendar.viewList'),
        },
        locale: isZh ? zhCnLocale : undefined,
        firstDay: isZh ? 1 : 0,
        height: '100%',
        editable: true,
        dayMaxEvents: true,
        events: [],
        datesSet: this.onDatesSet,
        dateClick: this.onDateClick,
        eventClick: this.onEventClick,
        eventDrop: this.onEventDrop,
        eventContent: this.renderEventContent,
        dayCellClassNames: this.dayCellClassNames,
        dayCellContent: this.renderDayCellContent,
        dayHeaderClassNames: this.dayHeaderClassNames,
        dayHeaderContent: this.renderDayHeaderContent,
      }
    },

    async loadProjects() {
      try {
        this.projects = (await getMyProjects()) || []
      } catch (e) {
        console.error('[calendar] 加载项目列表失败', e)
      }
    },

    async loadTasks(from, to) {
      try {
        const res = await getCalendarTasks(from, to)
        this.tasks = (res.data && res.data.tasks) || []
        this.calendarOptions.events = this.buildEvents()
      } catch (e) {
        console.error('[calendar] 加载日程失败', e)
        uni.showToast({ title: this.$t('calendar.loadFailed'), icon: 'none' })
      }
    },

    async loadUpcoming() {
      try {
        const today = new Date()
        const from = today.toISOString().slice(0, 10)
        const to = new Date(today.getTime() + 90 * 86400000).toISOString().slice(0, 10)
        const res = await getCalendarTasks(from, to)
        this.upcomingTasks = (res.data && res.data.tasks) || []
      } catch (e) {
        console.error('[calendar] 加载近期截止失败', e)
      }
    },

    refresh() {
      if (this.currentFrom && this.currentTo) this.loadTasks(this.currentFrom, this.currentTo)
      this.loadUpcoming()
    },

    buildEvents() {
      return this.tasks.map((t) => {
        const done = isDone(t)
        const color = done ? { bg: DONE_BG, text: DONE_TEXT } : colorForProject(t.projectId)
        const classNames = done ? ['fc-event-done'] : []
        return {
          id: String(t.id),
          title: t.title,
          start: toEventStart(t),
          allDay: !t.dueTime,
          backgroundColor: color.bg,
          borderColor: color.bg,
          textColor: color.text,
          classNames,
          extendedProps: { task: t },
        }
      })
    },

    onDatesSet(info) {
      this.currentFrom = info.startStr.slice(0, 10)
      this.currentTo = info.endStr.slice(0, 10)
      this.loadTasks(this.currentFrom, this.currentTo)
    },

    onDateClick(info) {
      this.editingTask = null
      this.defaultDate = info.dateStr.slice(0, 10)
      this.dialogVisible = true
    },

    onEventClick(info) {
      const task = info.event.extendedProps.task
      if (!task) return
      this.editingTask = task
      this.dialogVisible = true
    },

    async onEventDrop(info) {
      const task = info.event.extendedProps.task
      if (!task) return
      const startStr = info.event.startStr || ''
      const newDate = startStr.slice(0, 10)
      // 周视图里纵向拖拽会改时刻，startStr 带 T 时以新时刻为准；
      // 月视图/全天事件的 startStr 只有日期，时刻保持原值。
      const newTime = startStr.length > 10 ? startStr.slice(11, 16) : (task.dueTime || null)
      try {
        await updateTask(task.id, { dueDate: newDate, dueTime: newTime })
        task.dueDate = newDate
        task.dueTime = newTime
        this.loadUpcoming()
      } catch (e) {
        console.error('[calendar] 拖拽改期失败', e)
        uni.showToast({ title: this.$t('calendar.saveFailed'), icon: 'none' })
        info.revert()
      }
    },

    onUpcomingSelect(task) {
      const api = this.$refs.fc && this.$refs.fc.getApi()
      if (api && task.dueDate) api.gotoDate(task.dueDate)
    },

    renderEventContent(arg) {
      const task = arg.event.extendedProps.task || {}
      let html = '<div class="fc-awd-event">'
      if (arg.timeText) html += `<span class="fc-awd-event-time">${escapeHtml(arg.timeText)}</span>`
      html += `<span class="fc-awd-event-title">${escapeHtml(arg.event.title)}</span>`
      if (task.source === 'ai') {
        html += `<span class="fc-awd-event-ai">${escapeHtml(this.$t('calendar.aiSourceTag'))}</span>`
      }
      html += '</div>'
      return { html }
    },

    dayCellClassNames(arg) {
      const mark = getDayMarkType(arg.date)
      if (mark === 'holiday') return ['fc-day-holiday']
      if (mark === 'makeup') return ['fc-day-makeup']
      if (mark === 'weekend') return ['fc-day-weekend']
      return []
    },

    renderDayCellContent(arg) {
      // 周视图的「全天」行也算 day cell，会命中这个钩子；表头（dayHeaderContent）
      // 已经给每一天单独挂了角标，全天行再挂一遍是同一件事说两遍，只在月视图里挂。
      if (arg.view && arg.view.type !== 'dayGridMonth') return true
      const mark = getDayMarkType(arg.date)
      let html = `<span class="fc-daynum">${escapeHtml(arg.dayNumberText)}</span>`
      if (mark === 'holiday') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-rest">${escapeHtml(this.$t('calendar.holidayRest'))}</span>`
      } else if (mark === 'makeup') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-work">${escapeHtml(this.$t('calendar.holidayWork'))}</span>`
      }
      return { html }
    },

    // 月视图的表头只是「周一/周二…」的通用列标，不对应具体某一天（FullCalendar
    // 内部用一个固定参考周取值），套节假日判定会张冠李戴；只在周视图（表头即具体
    // 某一天）里标节假日。
    dayHeaderClassNames(arg) {
      if (!arg.view || arg.view.type !== 'timeGridWeek') return []
      const mark = getDayMarkType(arg.date)
      if (mark === 'holiday') return ['fc-day-holiday']
      if (mark === 'makeup') return ['fc-day-makeup']
      if (mark === 'weekend') return ['fc-day-weekend']
      return []
    },

    renderDayHeaderContent(arg) {
      if (!arg.view || arg.view.type !== 'timeGridWeek') return true
      const mark = getDayMarkType(arg.date)
      let html = `<span class="fc-daynum">${escapeHtml(arg.text)}</span>`
      if (mark === 'holiday') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-rest">${escapeHtml(this.$t('calendar.holidayRest'))}</span>`
      } else if (mark === 'makeup') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-work">${escapeHtml(this.$t('calendar.holidayWork'))}</span>`
      }
      return { html }
    },

    // 工作台参与的跳转一律 reLaunch（与 project-list.vue 的 goToProject 同写法）
    goToProject(projectId) {
      if (!projectId) return
      uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${projectId}` })
    },

    // 本页不是工作台，返回项目列表按栈深度分流：有上一页 navigateBack，
    // 否则本页是栈底（直链/刷新进来），redirectTo 避免压栈。
    goBack() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
      if (pages && pages.length > 1) {
        uni.navigateBack()
      } else {
        uni.redirectTo({ url: '/pages/project-list/project-list' })
      }
    },
  },
}
</script>

<style lang="scss" scoped src="./calendar.scss"></style>

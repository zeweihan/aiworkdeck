<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  全局日程页（跨项目事项）。dev-board #50 起步，#897 重做；
  spec: docs/superpowers/specs/2026-09-25-task-calendar-redesign.md 第三节。

  不是工作台，路由用 navigateTo/navigateBack/redirectTo（工作台参与的跳转才 reLaunch：
  「进入项目」与文件芯片落进工作台）。页头自绘（左返回 + 标题 / 中间翻页 + 月份 /
  右侧视图分段 + 筛选 + 新建），FullCalendar 自带 headerToolbar 关掉，导航调 calendarApi。
  全局返回键在本页豁免（utils/globalBack.js 的 SELF_NAV_ROUTES），否则压在页头上。

  数据：所有读写经 utils/taskStore。进页先全量拉一次（议程要逾期与之后，不跟视图区间走），
  日历翻页时按视图区间 loadGlobal（已覆盖则直接回缓存）；写操作由 TaskDialog / 本页经
  taskStore 完成，本页订阅 store 广播就地重画，不自己重拉。筛选（项目 / 类型 / 含已完成）
  日历与议程共用。

  深链：?focus=<id> 定位到事项所在月并打开编辑；?group=overdue|today|week 议程滚到该组。

  FullCalendar 集成：@fullcalendar/vue3 组件式，options.events 是「复杂选项」，vue3 适配器
  deep watch + resetOptions 增量更新，直接给 calendarOptions.events 赋新数组即可。
-->
<template>
  <view class="page-calendar">
    <view class="calendar-container">
      <view class="content-header cal-header">
        <view class="cal-header-left">
          <view class="cal-back" @tap="goBack">
            <svg viewBox="0 0 24 24" fill="none"><path d="M15 6l-6 6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
            <text>{{ $t('calendar.back') }}</text>
          </view>
          <text class="header-title">{{ $t('calendar.schedulePageTitle') }}</text>
        </view>

        <view class="cal-header-center">
          <view class="cal-nav">
            <view class="cal-nav-btn" :title="$t('calendar.prevPeriod')" @tap="navPrev">
              <svg viewBox="0 0 24 24" fill="none"><path d="M15 6l-6 6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </view>
            <view class="cal-today-btn" @tap="navToday">{{ $t('calendar.today') }}</view>
            <view class="cal-nav-btn" :title="$t('calendar.nextPeriod')" @tap="navNext">
              <svg viewBox="0 0 24 24" fill="none"><path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </view>
          </view>
          <text class="cal-period">{{ periodTitle }}</text>
        </view>

        <view class="header-actions cal-header-right">
          <view class="cal-seg">
            <view
              v-for="v in VIEWS"
              :key="v.type"
              class="cal-seg-btn"
              :class="{ active: viewType === v.type }"
              @tap="changeView(v.type)"
            >{{ $t(v.labelKey) }}</view>
          </view>

          <view class="cal-filter-wrap">
            <view class="cal-filter-btn" :class="{ 'is-active': activeFilterCount > 0, 'is-open': filterOpen }" @tap="filterOpen = !filterOpen">
              <svg viewBox="0 0 24 24" fill="none"><path d="M4 5h16l-6 7.5V19l-4 1.5v-8L4 5Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" /></svg>
              <text>{{ $t('calendar.filter') }}</text>
              <text v-if="activeFilterCount" class="cal-filter-count">{{ activeFilterCount }}</text>
            </view>
            <view v-if="filterOpen" class="cal-filter-mask" @tap="filterOpen = false"></view>
            <view v-if="filterOpen" class="cal-filter-pop" @tap.stop>
              <view class="fp-section">
                <text class="fp-label">{{ $t('calendar.filterProjects') }}</text>
                <view class="fp-list">
                  <view class="fp-option" :class="{ checked: !filter.projectIds.length }" @tap="filter.projectIds = []">
                    <view class="fp-box"><svg viewBox="0 0 24 24" fill="none"><path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                    <text class="fp-option-text">{{ $t('calendar.filterAllProjects') }}</text>
                  </view>
                  <view
                    v-for="p in filterProjects"
                    :key="p.id"
                    class="fp-option"
                    :class="{ checked: filter.projectIds.includes(String(p.id)) }"
                    @tap="toggleProjectFilter(p.id)"
                  >
                    <view class="fp-box"><svg viewBox="0 0 24 24" fill="none"><path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                    <text class="fp-option-text" :title="p.name">{{ p.name }}</text>
                  </view>
                </view>
              </view>
              <view class="fp-section">
                <text class="fp-label">{{ $t('calendar.filterTypes') }}</text>
                <view class="fp-chips">
                  <view
                    v-for="m in typeMetas"
                    :key="m.key"
                    class="fp-chip"
                    :class="{ checked: filter.types.includes(m.key) }"
                    :style="filter.types.includes(m.key) ? { borderColor: m.color, background: m.soft } : null"
                    @tap="toggleTypeFilter(m.key)"
                  >
                    <text class="fp-chip-dot" :style="{ background: m.color }"></text>
                    <text>{{ m.label }}</text>
                  </view>
                </view>
              </view>
              <view class="fp-row">
                <text class="fp-row-label">{{ $t('calendar.filterIncludeDone') }}</text>
                <AwdSwitch :checked="filter.includeDone" @change="setIncludeDone" />
              </view>
              <view class="fp-foot">
                <text class="fp-reset" :class="{ disabled: !activeFilterCount }" @tap="resetFilter">{{ $t('calendar.filterReset') }}</text>
              </view>
            </view>
          </view>

          <view class="cal-primary-btn" @tap="openCreate()">
            <svg viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
            <text>{{ $t('calendar.newTask') }}</text>
          </view>
        </view>
      </view>

      <view class="calendar-body">
        <view class="calendar-main">
          <FullCalendar ref="fc" class="fc-host" :options="calendarOptions" />
        </view>
        <view class="calendar-sidebar">
          <AgendaPanel
            ref="agenda"
            :tasks="agendaTasks"
            :empty="agendaEmpty"
            :today="todayKey"
            @open="openEdit"
            @toggle="onToggle"
            @open-file="onOpenFile"
            @open-project="(t) => goToProject(t && t.projectId)"
            @delete="onDelete"
            @create="openCreate()"
          />
        </view>
      </view>
    </view>

    <TaskDialog
      :visible="dialogVisible"
      :mode="dialogMode"
      :task="editingTask"
      :projects="projects"
      :preset-date="presetDate"
      :preset-time="presetTime"
      @close="dialogVisible = false"
      @open-project="(t) => goToProject(t && t.projectId)"
      @open-file="onOpenFile"
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

import { getTaskProjectOptions } from '@/services/api.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { getDayMarkType } from '@/components/calendar/holidayMarks.js'
import {
  TASK_TYPES, typeMeta, isDone, isHigh, timeOf, toEventStart, taskFiles, addDaysKey, localDateKey,
} from '@/components/calendar/taskUtils.js'
import { taskStore, loadGlobal, updateTask, deleteTask, subscribe } from '@/utils/taskStore.js'
import TaskDialog from '@/components/calendar/TaskDialog.vue'
import AgendaPanel from '@/components/calendar/AgendaPanel.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'

const VIEWS = [
  { type: 'dayGridMonth', labelKey: 'calendar.viewMonth' },
  { type: 'timeGridWeek', labelKey: 'calendar.viewWeek' },
  { type: 'listMonth', labelKey: 'calendar.viewList' },
]

const FLAG_SVG = '<svg class="fc-awd-flag" viewBox="0 0 24 24" fill="none"><path d="M5 21V4" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M5 4h11l-2 4 2 4H5" fill="currentColor" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>'

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function defaultFilter() {
  return { projectIds: [], types: [], includeDone: true }
}

export default {
  name: 'CalendarPage',
  components: { FullCalendar, TaskDialog, AgendaPanel, AwdSwitch },
  data() {
    return {
      VIEWS,
      calendarOptions: {},
      // 当前视图区间内的事项（日历用）；议程直接读 taskStore.global.list
      tasks: [],
      allLoaded: false,
      projects: [],
      filter: defaultFilter(),
      filterOpen: false,
      viewType: 'dayGridMonth',
      periodTitle: '',
      todayKey: '',
      dialogVisible: false,
      dialogMode: 'create',
      editingTask: null,
      presetDate: '',
      presetTime: '',
      currentFrom: '',
      currentTo: '',
      // loadTasks 的请求序号，见该方法里的乱序说明
      loadSeq: 0,
      // 深链参数（onLoad 收）
      focusId: '',
      focusGroup: '',
    }
  },
  computed: {
    typeMetas() {
      return TASK_TYPES.map((k) => typeMeta(k, (key) => this.$t(key)))
    },
    // 筛选里的项目：我的项目 + 事项里出现过但不在清单里的（被移出的项目也能筛）
    filterProjects() {
      const out = this.projects.map((p) => ({ id: p.id, name: p.name }))
      const seen = new Set(out.map((p) => String(p.id)))
      for (const t of taskStore.global.list) {
        if (t && t.projectId != null && !seen.has(String(t.projectId))) {
          seen.add(String(t.projectId))
          out.push({ id: t.projectId, name: t.projectName || String(t.projectId) })
        }
      }
      return out
    },
    activeFilterCount() {
      let n = 0
      if (this.filter.projectIds.length) n++
      if (this.filter.types.length) n++
      if (!this.filter.includeDone) n++
      return n
    },
    agendaTasks() {
      return taskStore.global.list.filter((t) => this.matchesFilter(t))
    },
    agendaEmpty() {
      return this.allLoaded && taskStore.global.list.length === 0
    },
  },
  watch: {
    filter: {
      deep: true,
      handler() { this.rebuildEvents() },
    },
  },
  onLoad(query) {
    const q = query || {}
    this.focusId = q.focus ? String(q.focus) : ''
    this.focusGroup = ['overdue', 'today', 'week', 'later'].includes(q.group) ? q.group : ''
  },
  created() {
    this.todayKey = localDateKey()
    this.buildCalendarOptions()
    this.loadProjects()
    this.loadAll()
    this.unsubscribe = subscribe(this.onStoreEvent)
  },
  beforeUnmount() {
    if (this.unsubscribe) this.unsubscribe()
  },
  methods: {
    buildCalendarOptions() {
      const isZh = getAppLanguage() === 'zh-CN'
      this.calendarOptions = {
        plugins: [dayGridPlugin, timeGridPlugin, listPlugin, interactionPlugin],
        initialView: 'dayGridMonth',
        headerToolbar: false,
        locale: isZh ? zhCnLocale : undefined,
        firstDay: isZh ? 1 : 0,
        height: '100%',
        editable: true,
        eventDurationEditable: false,
        dayMaxEvents: 3,
        eventDisplay: 'block',
        // 月/周视图的时刻由 eventContent 自己画；列表视图用 FullCalendar 的时间列
        displayEventTime: false,
        views: { listMonth: { displayEventTime: true } },
        nowIndicator: true,
        scrollTime: '08:00:00',
        slotLabelFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
        eventTimeFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
        events: [],
        datesSet: this.onDatesSet,
        dateClick: this.onDateClick,
        eventClick: this.onEventClick,
        eventDrop: this.onEventDrop,
        eventContent: this.renderEventContent,
        eventDidMount: this.onEventDidMount,
        dayCellClassNames: this.dayCellClassNames,
        dayCellContent: this.renderDayCellContent,
        dayHeaderClassNames: this.dayHeaderClassNames,
        dayHeaderContent: this.renderDayHeaderContent,
      }
    },

    getApi() {
      return this.$refs.fc && this.$refs.fc.getApi ? this.$refs.fc.getApi() : null
    },

    async loadProjects() {
      try {
        this.projects = (await getTaskProjectOptions()) || []
      } catch (e) {
        console.error('[calendar] 加载项目列表失败', e)
      }
    },

    // 议程要全部事项（逾期、之后、无日期），与日历视图区间解耦：翻到别的月份浏览时
    // 议程不能跟着漂移（漂了会把本周真正紧迫的事项藏掉）。进页强制重拉一次。
    async loadAll() {
      try {
        await loadGlobal({ force: true })
      } catch (e) {
        console.error('[calendar] 加载事项失败', e)
        uni.showToast({ title: this.$t('calendar.loadFailed'), icon: 'none' })
      }
      this.allLoaded = true
      this.syncFromStore()
      this.applyDeepLinks()
    },

    async loadTasks(from, to) {
      // 连点翻月/切视图会连发请求，先发的（旧区间）响应可能后到。FullCalendar 只画
      // 落在当前视图区间内的事件，旧结果落地的表现是当前月份大面积空白（只剩两个
      // 区间重叠的那几条）。只认最后一次请求的结果，被放弃的那次连失败提示也一并咽掉。
      // FullCalendar 的 end 是开区间，loadGlobal 的 to 含端点，减一天。
      const seq = ++this.loadSeq
      try {
        const list = await loadGlobal({ from, to: addDaysKey(to, -1) })
        if (seq !== this.loadSeq) return
        this.tasks = list || []
        this.rebuildEvents()
      } catch (e) {
        if (seq !== this.loadSeq) return
        console.error('[calendar] 加载日程失败', e)
        uni.showToast({ title: this.$t('calendar.loadFailed'), icon: 'none' })
      }
    },

    // store 广播（新建/修改/删除/任一区间加载完）→ 从缓存里重取当前视图区间。
    // 缓存按区间合并，取出来的一定是当前区间的最新状态，不存在乱序问题。
    onStoreEvent(ev) {
      if (!ev || ev.kind === 'summary-loaded' || ev.kind === 'project-loaded') return
      this.syncFromStore()
    },

    syncFromStore() {
      if (!this.currentFrom || !this.currentTo) return
      const from = this.currentFrom
      const to = this.currentTo
      this.tasks = taskStore.global.list.filter((t) => t && t.dueDate && t.dueDate >= from && t.dueDate < to)
      this.rebuildEvents()
    },

    matchesFilter(t) {
      if (!t) return false
      const f = this.filter
      if (!f.includeDone && isDone(t)) return false
      if (f.projectIds.length && !f.projectIds.includes(String(t.projectId))) return false
      if (f.types.length && !f.types.includes(typeMeta(t.type).key)) return false
      return true
    },

    rebuildEvents() {
      this.calendarOptions.events = this.buildEvents()
    },

    buildEvents() {
      return this.tasks
        .filter((t) => t && t.dueDate && this.matchesFilter(t))
        .map((t) => {
          const done = isDone(t)
          const meta = typeMeta(t.type)
          return {
            id: String(t.id),
            title: t.title,
            start: toEventStart(t),
            allDay: !timeOf(t),
            backgroundColor: done ? 'var(--awd-surface-2)' : meta.soft,
            borderColor: done ? 'var(--awd-border-strong)' : meta.color,
            textColor: done ? 'var(--awd-text-3)' : 'var(--awd-text)',
            classNames: done ? ['fc-awd-done'] : [],
            extendedProps: { task: t },
          }
        })
    },

    onDatesSet(info) {
      this.currentFrom = info.startStr.slice(0, 10)
      this.currentTo = info.endStr.slice(0, 10)
      this.viewType = info.view.type
      this.periodTitle = this.formatPeriod(info.view.currentStart)
      this.loadTasks(this.currentFrom, this.currentTo)
    },

    formatPeriod(date) {
      if (!date) return ''
      const isZh = getAppLanguage() === 'zh-CN'
      const month = isZh
        ? date.getMonth() + 1
        : date.toLocaleString('en-US', { month: 'long' })
      return this.$t('calendar.monthTitle', { year: date.getFullYear(), month })
    },

    navPrev() { const api = this.getApi(); if (api) api.prev() },
    navNext() { const api = this.getApi(); if (api) api.next() },
    navToday() { const api = this.getApi(); if (api) api.today() },
    changeView(type) {
      const api = this.getApi()
      if (api && this.viewType !== type) api.changeView(type)
    },

    toggleProjectFilter(id) {
      const key = String(id)
      const list = this.filter.projectIds
      this.filter.projectIds = list.includes(key) ? list.filter((x) => x !== key) : list.concat([key])
    },
    toggleTypeFilter(type) {
      const list = this.filter.types
      this.filter.types = list.includes(type) ? list.filter((x) => x !== type) : list.concat([type])
    },
    setIncludeDone(v) { this.filter.includeDone = !!v },
    resetFilter() { this.filter = defaultFilter() },

    openCreate(date = '', time = '') {
      this.editingTask = null
      this.dialogMode = 'create'
      this.presetDate = date
      this.presetTime = time
      this.dialogVisible = true
    },

    openEdit(task) {
      if (!task) return
      this.editingTask = task
      this.dialogMode = 'edit'
      this.presetDate = ''
      this.presetTime = ''
      this.dialogVisible = true
    },

    onDateClick(info) {
      // 周视图点时间格带时刻；月视图/全天行只有日期
      const time = !info.allDay && info.dateStr.length > 10 ? info.dateStr.slice(11, 16) : ''
      this.openCreate(info.dateStr.slice(0, 10), time)
    },

    onEventClick(info) {
      if (info.jsEvent) info.jsEvent.preventDefault()
      this.openEdit(info.event.extendedProps.task)
    },

    async onEventDrop(info) {
      const task = info.event.extendedProps.task
      if (!task) return
      const startStr = info.event.startStr || ''
      const newDate = startStr.slice(0, 10)
      // 周视图里纵向拖拽会改时刻，startStr 带 T 时以新时刻为准；拖进全天行清掉时刻；
      // 月视图拖拽保持原时刻。
      let newTime = timeOf(task) || null
      if (startStr.length > 10) newTime = startStr.slice(11, 16)
      else if (info.event.allDay && info.oldEvent && !info.oldEvent.allDay && info.view.type === 'timeGridWeek') newTime = null
      try {
        await updateTask(task.id, { dueDate: newDate, dueTime: newTime })
      } catch (e) {
        console.error('[calendar] 拖拽改期失败', e)
        uni.showToast({ title: this.$t('calendar.saveFailed'), icon: 'none' })
        info.revert()
      }
    },

    async onToggle(task) {
      if (!task) return
      try {
        await updateTask(task.id, { status: isDone(task) ? 'OPEN' : 'DONE' })
      } catch (e) {
        console.error('[calendar] 切换完成状态失败', e)
        uni.showToast({ title: this.$t('calendar.saveFailed'), icon: 'none' })
      }
    },

    onDelete(task) {
      if (!task) return
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title: task.title || '' }),
        cancelText: this.$t('calendar.cancel'),
        confirmText: this.$t('calendar.delete'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteTask(task.id)
            uni.showToast({ title: this.$t('calendar.deleted'), icon: 'success' })
          } catch (e) {
            console.error('[calendar] 删除事项失败', e)
            uni.showToast({ title: this.$t('calendar.deleteFailed'), icon: 'none' })
          }
        },
      })
    },

    onOpenFile(payload) {
      const task = payload && payload.task
      const fileId = payload && payload.fileId
      if (!task || !task.projectId || fileId == null) return
      uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${task.projectId}&openFileId=${fileId}` })
    },

    applyDeepLinks() {
      if (this.focusId) {
        const id = this.focusId
        this.focusId = ''
        const task = taskStore.global.list.find((t) => t && String(t.id) === id)
        if (task) {
          const api = this.getApi()
          if (api && task.dueDate) api.gotoDate(task.dueDate)
          this.openEdit(task)
        }
      }
      if (this.focusGroup) {
        const group = this.focusGroup
        this.focusGroup = ''
        this.$nextTick(() => {
          const agenda = this.$refs.agenda
          if (agenda && agenda.scrollToGroup) agenda.scrollToGroup(group)
        })
      }
    },

    renderEventContent(arg) {
      const task = arg.event.extendedProps.task || {}
      const meta = typeMeta(task.type)
      const time = timeOf(task)
      const isList = arg.view && String(arg.view.type).startsWith('list')
      let html = `<div class="fc-awd-ev${isList ? ' is-list' : ''}">`
      if (!isList) html += `<span class="fc-awd-ev-bar" style="background:${meta.color}"></span>`
      if (time && !isList) html += `<span class="fc-awd-ev-time">${escapeHtml(time)}</span>`
      if (isHigh(task)) html += FLAG_SVG
      html += `<span class="fc-awd-ev-title">${escapeHtml(arg.event.title)}</span>`
      if (isList && task.projectName) html += `<span class="fc-awd-ev-project">${escapeHtml(task.projectName)}</span>`
      html += '</div>'
      return { html }
    },

    // hover 原生 title：标题 + 「项目 · 文件名」
    onEventDidMount(info) {
      const task = info.event.extendedProps.task || {}
      const parts = []
      if (task.projectName) parts.push(task.projectName)
      for (const f of taskFiles(task)) parts.push(f.fileName == null ? this.$t('calendar.fileMissing') : f.fileName)
      const meta = typeMeta(task.type, (k) => this.$t(k))
      const head = `[${meta.label}] ${task.title || ''}`
      info.el.title = parts.length ? `${head}\n${parts.join(' · ')}` : head
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
      let html = ''
      if (mark === 'holiday') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-rest">${escapeHtml(this.$t('calendar.holidayRest'))}</span>`
      } else if (mark === 'makeup') {
        html += `<span class="fc-holiday-badge fc-holiday-badge-work">${escapeHtml(this.$t('calendar.holidayWork'))}</span>`
      }
      // zh-cn locale 的 dayNumberText 是「25日」，格子里只要数字
      const num = String(arg.dayNumberText || '').replace(/[^0-9]/g, '') || arg.dayNumberText
      html += `<span class="fc-daynum">${escapeHtml(num)}</span>`
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
      // 「周一 21」：星期 + 日，比 locale 默认的「9/21周一」好扫
      const lang = getAppLanguage() === 'zh-CN' ? 'zh-CN' : 'en-US'
      const weekday = arg.date.toLocaleDateString(lang, { weekday: 'short' })
      let html = `<span class="fc-dh-weekday">${escapeHtml(weekday)}</span><span class="fc-dh-day">${arg.date.getDate()}</span>`
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

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  左栏「日程」面板（dev-board#899 重做，spec 2026-09-25-task-calendar-redesign 第四节 E1）。

  议程式清单：顶部「新建事项」+ 迷你月份条（只是过滤显示范围，不是网格；「全部」= 不按月过滤），
  下面 已逾期 / 今天 / 本周 / 之后 分组的 TaskRow（compact、不显项目），已完成折叠；
  底部「查看全盘日程」。原来的 FullCalendar listMonth 撤掉了——260px 的窄栏里它只是一张
  看不出类型和时间的表，面板也因此不再依赖 FullCalendar。

  数据全部读 utils/taskStore（与 rail 徽标、文件树到期徽标同一份缓存），写操作
  （勾选完成 / 删除）也经它，其余清单与徽标靠它的就地更新同步，不必重拉。
  新建与编辑不在面板里做：emit 给宿主，宿主挂着工作台唯一的 TaskDialog（面板、
  文件树右键、命令面板共用）。

  fileFilter：文件右键「查看事项 (N)」进来时只看这份文件的事项（含已完成），
  顶部显示「仅看：文件名 ×」，× 由宿主清掉。

  面板标题由外壳的 sidebar-header 出（见 sidebar-shell 的统一口径），本组件不重复渲染标题。
-->
<template>
  <view class="pcp">
    <view class="pcp-toolbar">
      <view class="pcp-new-btn" @tap="onNewTask">
        <svg class="pcp-new-icon" viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
        <text>{{ $t('calendar.newTask') }}</text>
      </view>
    </view>

    <view class="pcp-monthbar">
      <view class="pcp-nav-btn" :title="$t('calendar.prevPeriod')" @tap="shiftMonth(-1)">‹</view>
      <text class="pcp-month-title" :class="{ 'is-all': !month }">{{ monthLabel }}</text>
      <view class="pcp-nav-btn" :title="$t('calendar.nextPeriod')" @tap="shiftMonth(1)">›</view>
      <view class="pcp-spacer"></view>
      <view class="pcp-all-btn" :class="{ 'is-active': !month }" @tap="month = null">{{ $t('calendar.paneAll') }}</view>
    </view>

    <view v-if="fileFilter != null && fileFilter !== ''" class="pcp-filter">
      <text class="pcp-filter-text">{{ $t('calendar.paneOnlyFile', { name: fileFilterLabel }) }}</text>
      <view class="pcp-filter-clear" :title="$t('calendar.paneClearFilter')" @tap="$emit('clear-file-filter')">×</view>
    </view>

    <scroll-view scroll-y class="pcp-body">
      <view v-if="loading" class="pcp-hint">{{ $t('calendar.loading') }}</view>
      <view v-else-if="loadFailed" class="pcp-hint">{{ $t('calendar.loadFailed') }}</view>
      <view v-else-if="!visibleTasks.length" class="pcp-empty">
        <text class="pcp-empty-text">{{ emptyText }}</text>
      </view>
      <template v-else>
        <view v-for="g in openGroups" :key="g.key" class="pcp-group">
          <view class="pcp-group-head" :class="'is-' + g.key">
            <text class="pcp-group-name">{{ $t(g.labelKey) }}</text>
            <text class="pcp-group-count">{{ g.list.length }}</text>
          </view>
          <TaskRow
            v-for="task in g.list"
            :key="task.id"
            :task="task"
            :show-project="false"
            density="compact"
            @toggle="onToggle"
            @open="onOpen"
            @open-file="onOpenFile"
            @delete="onDelete"
          />
        </view>
        <view v-if="groups.done.length" class="pcp-group">
          <view class="pcp-group-head pcp-done-toggle" @tap="showDone = !showDone">
            <text class="pcp-caret" :class="{ 'is-open': showDone }">›</text>
            <text class="pcp-group-name">{{ $t('calendar.groupDoneCount', { count: groups.done.length }) }}</text>
          </view>
          <template v-if="showDone">
            <TaskRow
              v-for="task in groups.done"
              :key="task.id"
              :task="task"
              :show-project="false"
              density="compact"
              @toggle="onToggle"
              @open="onOpen"
              @open-file="onOpenFile"
              @delete="onDelete"
            />
          </template>
        </view>
      </template>
    </scroll-view>

    <view class="pcp-footer">
      <text class="pcp-footer-link" @tap="openGlobalCalendar">{{ $t('calendar.viewFullSchedule') }}</text>
    </view>
  </view>
</template>

<script>
import TaskRow from '@/components/calendar/TaskRow.vue'
import { taskStore, loadProjectTasks, updateTask, deleteTask } from '@/utils/taskStore.js'
import { groupByDue, isDone, taskFiles, taskFileIds } from '@/components/calendar/taskUtils.js'
import { isEnglish } from '@/utils/appLanguage.js'

const OPEN_GROUPS = [
  { key: 'overdue', labelKey: 'calendar.groupOverdue' },
  { key: 'today', labelKey: 'calendar.groupToday' },
  { key: 'week', labelKey: 'calendar.groupWeek' },
  { key: 'later', labelKey: 'calendar.groupLater' },
]

export default {
  name: 'ProjectCalendarPane',
  components: { TaskRow },
  props: {
    projectId: { type: [Number, String], required: true },
    /** 只看关联了这份文件的事项（文件右键「查看事项」） */
    fileFilter: { type: [Number, String], default: null },
    /** fileFilter 对应的文件名（宿主知道；不给就从事项的文件芯片里找） */
    fileFilterName: { type: String, default: '' },
  },
  emits: ['leave-workbench', 'new-task', 'open-task', 'open-file', 'clear-file-filter'],
  data() {
    return {
      /** { y, m } 或 null（= 全部，不按月过滤） */
      month: null,
      showDone: false,
      loadFailed: false,
    }
  },
  computed: {
    entry() {
      return taskStore.byProject[String(this.projectId)] || null
    },
    loading() {
      return !this.loadFailed && !(this.entry && this.entry.loadedAt)
    },
    allTasks() {
      return (this.entry && this.entry.list) || []
    },
    visibleTasks() {
      let list = this.allTasks
      if (this.fileFilter != null && this.fileFilter !== '') {
        const target = String(this.fileFilter)
        list = list.filter((t) => taskFileIds(t).includes(target))
      }
      if (this.month) {
        const prefix = `${this.month.y}-${String(this.month.m).padStart(2, '0')}-`
        list = list.filter((t) => t.dueDate && t.dueDate.startsWith(prefix))
      }
      return list
    },
    groups() {
      return groupByDue(this.visibleTasks)
    },
    openGroups() {
      return OPEN_GROUPS
        .map((g) => ({ ...g, list: this.groups[g.key] }))
        .filter((g) => g.list.length > 0)
    },
    monthLabel() {
      if (!this.month) return this.$t('calendar.paneAllTasks')
      const { y, m } = this.month
      const month = isEnglish()
        ? new Date(y, m - 1, 1).toLocaleString('en-US', { month: 'short' })
        : m
      return this.$t('calendar.monthTitle', { year: y, month })
    },
    fileFilterLabel() {
      if (this.fileFilterName) return this.fileFilterName
      const target = String(this.fileFilter)
      for (const t of this.allTasks) {
        const hit = taskFiles(t).find((f) => String(f.fileId) === target && f.fileName)
        if (hit) return hit.fileName
      }
      return '#' + target
    },
    emptyText() {
      if (this.fileFilter != null && this.fileFilter !== '') return this.$t('calendar.paneEmptyFile')
      if (this.month && this.allTasks.length) return this.$t('calendar.paneEmptyMonth')
      return this.$t('calendar.paneEmptyTasks')
    },
  },
  watch: {
    projectId() {
      this.month = null
      this.load()
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      if (!this.projectId) return
      this.loadFailed = false
      try {
        await loadProjectTasks(this.projectId)
      } catch (e) {
        console.warn('[ProjectCalendarPane] 读取事项失败', e)
        this.loadFailed = true
      }
    },
    shiftMonth(step) {
      const now = new Date()
      const base = this.month || { y: now.getFullYear(), m: now.getMonth() + 1 }
      // 从「全部」点箭头：先落到本月，而不是本月的前/后一个月
      if (!this.month) {
        this.month = base
        return
      }
      const d = new Date(base.y, base.m - 1 + step, 1)
      this.month = { y: d.getFullYear(), m: d.getMonth() + 1 }
    },
    onNewTask() {
      this.$emit('new-task', { presetFileIds: this.fileFilter != null && this.fileFilter !== '' ? [this.fileFilter] : [] })
    },
    onOpen(task) {
      this.$emit('open-task', task)
    },
    onOpenFile(payload) {
      this.$emit('open-file', payload)
    },
    async onToggle(task) {
      try {
        await updateTask(task.id, { status: isDone(task) ? 'OPEN' : 'DONE' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      }
    },
    onDelete(task) {
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title: task.title || '' }),
        cancelText: this.$t('calendar.cancel'),
        confirmText: this.$t('calendar.delete'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteTask(task.id)
            uni.showToast({ title: this.$t('calendar.deleted'), icon: 'none' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('calendar.deleteFailed'), icon: 'none' })
          }
        },
      })
    },
    openGlobalCalendar() {
      // 不在这里自己跳页：离开工作台前必须先把编辑器里的未存改动落盘
      // （flushDirtyEditors 吃的是挂在工作台页面实例上的编辑器引用，子组件够不到），
      // 否则律师刚敲的那几秒改动会静默丢失——就是 #489 修过的那一类。
      // 统一交给父页面的 leaveWorkbench：它先落盘再 reLaunch
      // （工作台参与的跳转一律 reLaunch，见 CLAUDE.md 导航总规则）。
      this.$emit('leave-workbench', '/pages/calendar/calendar')
    },
  },
}
</script>

<style lang="scss" scoped src="./project-calendar-pane.scss"></style>

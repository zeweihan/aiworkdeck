<!--
  左栏「日历」面板：project_task 的第三个挂载点（另两个是文件右键设截止日、
  概览页 TaskSchedule）。窄栏（260px 起）装不下月历网格，用 FullCalendar 的
  listMonth 列表视图——月历网格是全局日历页（pages/calendar）的事，那边宽度
  管够；这里只要求「这个月还有什么事」看得清楚。

  面板标题由外壳的 sidebar-header 出（见 sidebar-shell 的统一口径），本组件
  自己只画分组头/工具条，不重复渲染标题。

  数据一次性拉全量（getProjectTasks 不分页也不按日期过滤——项目内任务量级
  small，FullCalendar 自己按当前视图区间在客户端筛选），操作后整体重拉，
  不做增量 patch：数据量小，正确性优先于省一次请求。
-->
<template>
  <view class="pcp">
    <view class="pcp-header">
      <view class="pcp-nav-btn" @tap="goPrev">‹</view>
      <text class="pcp-month-title">{{ monthTitle }}</text>
      <view class="pcp-nav-btn" @tap="goNext">›</view>
      <view class="pcp-spacer"></view>
      <view class="pcp-add-btn" @tap="toggleQuickCreate">
        <text>{{ $t('calendar.addQuick') }}</text>
      </view>
    </view>

    <view v-if="quickCreateOpen" class="pcp-quick-create">
      <input
        v-model="quickTitle"
        class="pcp-quick-input"
        :placeholder="$t('calendar.taskTitlePlaceholder')"
        @confirm="submitQuickCreate"
      />
      <AwdDatePicker v-model="quickDate" type="date" />
      <view class="pcp-quick-actions">
        <view class="pcp-quick-btn" @tap="quickCreateOpen = false">{{ $t('calendar.cancel') }}</view>
        <view class="pcp-quick-btn pcp-quick-btn-primary" @tap="submitQuickCreate">{{ $t('calendar.save') }}</view>
      </view>
    </view>

    <view class="pcp-body">
      <view v-if="loading" class="pcp-hint">{{ $t('calendar.loading') }}</view>
      <FullCalendar v-else ref="fc" :options="calendarOptions" />
    </view>

    <!-- 事件操作条：点某条日程弹出，标记完成/改期/删除都在这（不做拖拽改期，
         窄栏列表视图本来就不支持拖拽） -->
    <view v-if="activeTask" class="pcp-action-bar">
      <view class="pcp-action-header">
        <text class="pcp-action-title">{{ activeTask.title }}</text>
        <view class="pcp-action-close" @tap="activeTask = null">×</view>
      </view>
      <view class="pcp-action-row">
        <text class="pcp-action-label">{{ $t('calendar.dateLabel') }}</text>
        <AwdDatePicker :model-value="activeTask.dueDate" type="date" @update:model-value="handleReschedule" />
      </view>
      <view class="pcp-action-buttons">
        <view class="pcp-action-btn" @tap="toggleActiveDone">
          {{ isDone(activeTask) ? $t('calendar.markOpen') : $t('calendar.markDone') }}
        </view>
        <view class="pcp-action-btn pcp-action-btn-danger" @tap="deleteActiveTask">
          {{ $t('calendar.delete') }}
        </view>
      </view>
    </view>

    <view class="pcp-footer">
      <text class="pcp-footer-link" @tap="openGlobalCalendar">{{ $t('calendar.openGlobalCalendar') }}</text>
    </view>
  </view>
</template>

<script>
import FullCalendar from '@fullcalendar/vue3'
import listPlugin from '@fullcalendar/list'
import zhCnLocale from '@fullcalendar/core/locales/zh-cn'
import AwdDatePicker from '@/components/AwdDatePicker.vue'
import { getProjectTasks, createTask, updateTask, deleteTask } from '@/services/api.js'
import { isEnglish } from '@/utils/appLanguage.js'
import { isDone, toEventStart } from '@/components/calendar/taskUtils.js'

export default {
  name: 'ProjectCalendarPane',
  components: { FullCalendar, AwdDatePicker },
  props: {
    projectId: { type: [Number, String], required: true },
  },
  data() {
    return {
      tasks: [],
      loading: true,
      monthTitle: '',
      quickCreateOpen: false,
      quickTitle: '',
      quickDate: '',
      activeTask: null,
    }
  },
  computed: {
    calendarOptions() {
      return {
        plugins: [listPlugin],
        initialView: 'listMonth',
        headerToolbar: false,
        height: 'auto',
        locale: isEnglish() ? 'en' : zhCnLocale,
        noEventsText: this.$t('calendar.paneEmpty'),
        events: this.tasks.map((t) => ({
          id: String(t.id),
          title: t.title,
          start: toEventStart(t),
          allDay: !t.dueTime,
          classNames: this.isDone(t) ? ['pcp-evt-done'] : [],
          extendedProps: { task: t },
        })),
        eventClick: this.onEventClick,
        datesSet: this.onDatesSet,
      }
    },
  },
  watch: {
    projectId() {
      this.loadTasks()
    },
  },
  mounted() {
    this.loadTasks()
  },
  methods: {
    isDone(task) {
      return isDone(task)
    },
    async loadTasks() {
      if (!this.projectId) return
      this.loading = true
      try {
        const res = await getProjectTasks(this.projectId)
        this.tasks = (res && res.data && res.data.tasks) || []
        // 重拉之后如果操作条还开着，把里面的任务对象换成最新的一份
        if (this.activeTask) {
          const fresh = this.tasks.find((t) => t.id === this.activeTask.id)
          this.activeTask = fresh || null
        }
      } catch (e) {
        console.warn('[ProjectCalendarPane] 读取任务失败', e)
        this.tasks = []
      } finally {
        this.loading = false
      }
    },
    onDatesSet(info) {
      this.monthTitle = info.view.title
    },
    onEventClick(clickInfo) {
      this.activeTask = clickInfo.event.extendedProps.task
    },
    goPrev() {
      const api = this.$refs.fc && this.$refs.fc.getApi()
      if (api) api.prev()
    },
    goNext() {
      const api = this.$refs.fc && this.$refs.fc.getApi()
      if (api) api.next()
    },
    toggleQuickCreate() {
      this.quickCreateOpen = !this.quickCreateOpen
      if (this.quickCreateOpen) {
        this.quickTitle = ''
        this.quickDate = ''
      }
    },
    async submitQuickCreate() {
      const title = (this.quickTitle || '').trim()
      if (!title) {
        uni.showToast({ title: this.$t('calendar.requiredTitle'), icon: 'none' })
        return
      }
      if (!this.quickDate) {
        uni.showToast({ title: this.$t('calendar.requiredDate'), icon: 'none' })
        return
      }
      try {
        await createTask({ projectId: this.projectId, title, dueDate: this.quickDate })
        this.quickCreateOpen = false
        uni.showToast({ title: this.$t('calendar.saved'), icon: 'none' })
        this.loadTasks()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      }
    },
    async toggleActiveDone() {
      if (!this.activeTask) return
      const nextStatus = this.isDone(this.activeTask) ? 'OPEN' : 'DONE'
      try {
        await updateTask(this.activeTask.id, { status: nextStatus })
        this.loadTasks()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      }
    },
    async handleReschedule(newDate) {
      if (!this.activeTask || !newDate || newDate === this.activeTask.dueDate) return
      try {
        await updateTask(this.activeTask.id, { dueDate: newDate })
        this.loadTasks()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      }
    },
    deleteActiveTask() {
      if (!this.activeTask) return
      const title = this.activeTask.title
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title }),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteTask(this.activeTask.id)
            this.activeTask = null
            uni.showToast({ title: this.$t('calendar.deleted'), icon: 'none' })
            this.loadTasks()
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('calendar.deleteFailed'), icon: 'none' })
          }
        },
      })
    },
    openGlobalCalendar() {
      // 工作台参与的跳转一律 reLaunch（见 CLAUDE.md 导航总规则）
      uni.reLaunch({ url: '/pages/calendar/calendar' })
    },
  },
}
</script>

<style lang="scss" scoped src="./project-calendar-pane.scss"></style>

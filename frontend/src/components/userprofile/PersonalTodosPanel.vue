<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「我的待办」栏目（设置页「个人」组，dev-board#872 重做，#898 换统一事项行）。

  - 数据源 utils/taskStore 的跨项目缓存：挂载时 loadGlobal()（不带 from/to = 全量，
    跨当前用户可见的全部项目），读 taskStore.global.list；完成/删除直接调 store，
    写成功后 store 就地更新并广播，日程页、工作台面板、徽标一起跟上。
  - 行是全产品唯一的事项行 components/calendar/TaskRow.vue（显示项目芯片）；点行开
    统一弹窗 TaskDialog 编辑，「新增」开同一个弹窗（项目下拉只给可写项目）。
  - 分组用 taskUtils.groupByDue（已逾期 / 今天 / 本周 / 之后，已完成折叠）——与日程页
    议程同一套口径。
  - 右上角「查看全盘日程」跳全局日程页（与工作台无关的独立页面，直接 navigateTo）。
  加载时机仍是本组件的 mounted——它只在这一栏被选中时渲染。
-->
<template>
  <view class="panel-todos">
    <view class="pt-head">
      <view class="pt-head-text">
        <view class="pt-title-row">
          <text class="pt-title">{{ $t('account.tabTodos') }}</text>
          <text v-if="openCount" class="pt-count">{{ openCount }}</text>
        </view>
        <text class="pt-subtitle">{{ $t('account.todosSubtitle') }}</text>
      </view>
      <view class="pt-actions">
        <view class="pt-link-btn" @tap="openCalendar">
          <text>{{ $t('calendar.viewFullSchedule') }}</text>
          <svg class="pt-link-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.arrowUpRight" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </view>
        <view class="pt-add-btn" @tap="openCreate">
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.plus" :key="gi" :d="d" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <text>{{ $t('account.addTodoAction') }}</text>
        </view>
      </view>
    </view>

    <view v-if="loading && !tasks.length" class="pt-state">
      <text class="pt-state-text">{{ $t('account.loadingEllipsis') }}</text>
    </view>
    <view v-else-if="!tasks.length" class="pt-empty">
      <view class="pt-empty-icon">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.listChecks" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
      <text class="pt-empty-title">{{ $t('account.emptyTodosDesc') }}</text>
      <text class="pt-empty-desc">{{ $t('account.emptyTodosHow') }}</text>
    </view>

    <view v-else class="pt-groups">
      <view v-for="grp in openGroups" :key="grp.key" class="pt-group">
        <view class="pt-group-head">
          <text class="pt-group-name" :class="'kind-' + grp.key">{{ grp.label }}</text>
          <text class="pt-group-count">{{ grp.items.length }}</text>
        </view>
        <view class="pt-rows">
          <TaskRow
            v-for="task in grp.items"
            :key="task.uid || task.id"
            :task="task"
            :show-project="true"
            :show-files="true"
            @toggle="toggleDone"
            @open="openEdit"
            @open-project="openProject"
            @open-file="openFile"
            @delete="requestDelete"
          />
        </view>
      </view>

      <view v-if="doneTasks.length" class="pt-done-toggle" @tap="showDone = !showDone">
        <svg class="pt-done-caret" :class="{ 'is-open': showDone }" viewBox="0 0 24 24" fill="none"><path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
        <text class="pt-done-toggle-label">{{ $t('calendar.groupDoneCount', { count: doneTasks.length }) }}</text>
      </view>

      <view v-if="showDone && doneTasks.length" class="pt-group pt-group-done">
        <view class="pt-rows">
          <TaskRow
            v-for="task in doneTasks"
            :key="task.uid || task.id"
            :task="task"
            :show-project="true"
            :show-files="true"
            @toggle="toggleDone"
            @open="openEdit"
            @open-project="openProject"
            @open-file="openFile"
            @delete="requestDelete"
          />
        </view>
      </view>
    </view>

    <TaskDialog
      :visible="dialogOpen"
      :mode="dialogTask ? 'edit' : 'create'"
      :task="dialogTask"
      :projects="writableMyProjects"
      @open-project="openProject"
      @open-file="openFile"
      @close="closeDialog"
    />
  </view>
</template>

<script>
import { getTaskProjectOptions } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { isDone, groupByDue } from '@/components/calendar/taskUtils.js'
import { writableProjects } from '@/utils/personalCollections.js'
import { taskStore, loadGlobal, updateTask, deleteTask } from '@/utils/taskStore.js'
import TaskDialog from '@/components/calendar/TaskDialog.vue'
import TaskRow from '@/components/calendar/TaskRow.vue'

export default {
  name: 'PersonalTodosPanel',
  components: { TaskDialog, TaskRow },
  data() {
    return {
      loading: false,
      // 全量加载完成之前 taskStore.global.list 可能只是提醒调度拉的一段区间，不能当「全部待办」显示
      loaded: false,
      myProjects: [],
      showDone: false,
      dialogOpen: false,
      dialogTask: null,
    }
  },
  computed: {
    ICONS() { return ICONS },
    tasks() {
      return this.loaded ? taskStore.global.list : []
    },
    writableMyProjects() {
      return writableProjects(this.myProjects)
    },
    groups() {
      return groupByDue(this.tasks)
    },
    openGroups() {
      const g = this.groups
      const defs = [
        { key: 'overdue', label: this.$t('calendar.groupOverdue'), items: g.overdue },
        { key: 'today', label: this.$t('calendar.groupToday'), items: g.today },
        { key: 'week', label: this.$t('calendar.groupWeek'), items: g.week },
        { key: 'later', label: this.$t('calendar.groupLater'), items: g.later },
      ]
      return defs.filter((d) => d.items.length)
    },
    doneTasks() {
      return this.groups.done
    },
    openCount() {
      return this.tasks.length - this.doneTasks.length
    },
  },
  mounted() {
    this.loadAll()
  },
  methods: {
    async loadAll() {
      this.loading = true
      try {
        const [, projects] = await Promise.all([loadGlobal({ force: true }), getTaskProjectOptions()])
        this.loaded = true
        this.myProjects = projects || []
      } catch (e) {
        console.error('加载待办失败:', e)
        uni.showToast({ title: this.$t('account.loadFavoritesFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    openCreate() {
      this.dialogTask = null
      this.dialogOpen = true
    },
    openEdit(task) {
      this.dialogTask = task
      this.dialogOpen = true
    },
    closeDialog() {
      this.dialogOpen = false
      this.dialogTask = null
    },
    // 设置页不在工作台里（没有编辑器要 flush）；进工作台一律 reLaunch
    openProject(task) {
      if (!task || task.projectId == null) return
      this.closeDialog()
      uni.reLaunch({ url: '/pages/project-overview/project-overview?id=' + task.projectId })
    },
    openFile(payload) {
      const task = payload && payload.task
      if (!task || task.projectId == null || payload.fileId == null) return
      this.closeDialog()
      uni.reLaunch({
        url: '/pages/project-overview/project-overview?id=' + task.projectId + '&openFileId=' + payload.fileId,
      })
    },
    openCalendar() {
      uni.navigateTo({ url: '/pages/calendar/calendar' })
    },
    async toggleDone(task) {
      const nextStatus = isDone(task) ? 'OPEN' : 'DONE'
      try {
        await updateTask(task.id, { status: nextStatus })
      } catch (e) {
        console.error('更新待办状态失败:', e)
        uni.showToast({ title: this.$t('account.todoUpdateFailed'), icon: 'none' })
      }
    },
    requestDelete(task) {
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title: task.title || '' }),
        cancelText: this.$t('common.cancel'),
        confirmText: this.$t('common.delete'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteTask(task.id)
            uni.showToast({ title: this.$t('account.deleteSuccessToast'), icon: 'success' })
          } catch (e) {
            console.error('删除待办失败:', e)
            uni.showToast({ title: this.$t('account.deleteFailedToast'), icon: 'none' })
          }
        },
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.panel-todos {
  background: var(--awd-surface);
  border-radius: 8px;
  border: 1px solid var(--awd-border);
  padding: 14px;
  box-sizing: border-box;
  width: 100%;
}

.pt-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.pt-head-text {
  flex: 1 1 320px;
  min-width: 0;
}

.pt-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pt-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.pt-count,
.pt-group-count {
  font-size: 11px;
  line-height: 18px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--awd-surface-2);
  color: var(--awd-text-2);
}

.pt-subtitle {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text-2);
}

.pt-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.pt-link-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--awd-accent-text);
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;

  &:hover {
    background: var(--awd-accent-soft);
  }
}

.pt-link-icon {
  width: 13px;
  height: 13px;
}

.pt-add-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;

  svg {
    width: 14px;
    height: 14px;
  }

  &:hover {
    background: var(--awd-accent-hover);
  }
}

.pt-state {
  padding: 48px 0;
  text-align: center;
}

.pt-state-text {
  font-size: 13px;
  color: var(--awd-text-3);
}

.pt-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 56px 24px;
  text-align: center;
}

.pt-empty-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--awd-accent-wash);
  color: var(--awd-accent-text);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;

  svg {
    width: 26px;
    height: 26px;
  }
}

.pt-empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}

.pt-empty-desc {
  margin-top: 8px;
  max-width: 440px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-text-2);
}

.pt-group + .pt-group {
  margin-top: 20px;
}

.pt-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.pt-group-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text-2);

  &.kind-overdue {
    color: var(--awd-danger-text);
  }

  &.kind-today {
    color: var(--awd-accent-text);
  }
}

.pt-rows {
  display: flex;
  flex-direction: column;
}

















.pt-done-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 20px;
  cursor: pointer;
}

.pt-done-caret {
  width: 12px;
  height: 12px;
  color: var(--awd-text-3);
  transition: transform 0.15s;

  &.is-open {
    transform: rotate(90deg);
  }
}

.pt-done-toggle-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.pt-group-done {
  margin-top: 10px;
}
</style>

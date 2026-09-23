<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「我的待办」栏目（设置页「个人」组，dev-board#872 重做）。2026-08-20 曾是纯占位
  （后端当时还没有待办实体），B 期（dev-board#49）落地 project_task 后这里改为真实数据：

  - 数据源 GET /api/calendar（不传 from/to 取全部，含无日期任务），跨当前用户可见的
    全部项目；写操作复用既有 POST/PUT/DELETE /api/tasks（TaskController）。
  - 完成勾选、删除都是本组件直接调接口的乐观更新；「新增」复用日历页同款的
    components/calendar/TaskDialog.vue（标题 + 项目下拉 + 日期），避免另写一套表单。
  - 分组/排序逻辑是纯函数 utils/personalCollections.js 的 groupTodos/writableProjects，
    与 node --test 用例共用同一份实现。
  - 完成态判定与「剩余天数」徽标复用 components/calendar/taskUtils.js（isDone/dueBadge）——
    这是全站任务展示的唯一出处（project-home/TaskSchedule 等五个消费方共用），这里不再写一份。
  - 右上角「在日历中查看」跳全局日历页（与工作台无关的独立页面，直接 navigateTo）。
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
          <text>{{ $t('calendar.openGlobalCalendar') }}</text>
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
          <view v-for="task in grp.items" :key="task.id" class="pt-row">
            <view class="pt-check" @tap="toggleDone(task)">
              <svg v-if="isTaskDone(task)" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </view>
            <view class="pt-row-main">
              <text class="pt-row-title">{{ task.title }}</text>
              <view class="pt-row-meta">
                <text v-if="task.projectName" class="pt-meta-chip">{{ task.projectName }}</text>
                <text v-if="task.fileName" class="pt-meta-chip pt-meta-file">{{ task.fileName }}</text>
              </view>
            </view>
            <text v-if="badgeOf(task).text" class="pt-due-badge" :class="'is-' + badgeOf(task).kind">{{ badgeOf(task).text }}</text>
            <view class="pt-del-wrap">
              <view class="pt-icon-btn danger" :title="$t('common.delete')" @tap.stop="requestDelete(task.id)">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <view v-if="confirmDeleteId === task.id" class="pt-popover" @tap.stop>
                <text class="pt-pop-text">{{ $t('account.deleteTodoConfirm') }}</text>
                <view class="pt-pop-row">
                  <view class="pt-pop-btn" @tap.stop="cancelDelete">{{ $t('common.cancel') }}</view>
                  <view class="pt-pop-btn danger" @tap.stop="handleDeleteTask(task.id)">{{ $t('common.delete') }}</view>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view v-if="doneTasks.length" class="pt-done-toggle" @tap="showDone = !showDone">
        <AwdSwitch :checked="showDone" @change="showDone = $event" />
        <text class="pt-done-toggle-label">{{ $t('calendar.showDone') }} ({{ doneTasks.length }})</text>
      </view>

      <view v-if="showDone && doneTasks.length" class="pt-group pt-group-done">
        <view class="pt-rows">
          <view v-for="task in doneTasks" :key="task.id" class="pt-row">
            <view class="pt-check is-done" @tap="toggleDone(task)">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </view>
            <view class="pt-row-main">
              <text class="pt-row-title pt-row-title-done">{{ task.title }}</text>
              <view class="pt-row-meta">
                <text v-if="task.projectName" class="pt-meta-chip">{{ task.projectName }}</text>
                <text v-if="task.fileName" class="pt-meta-chip pt-meta-file">{{ task.fileName }}</text>
              </view>
            </view>
            <view class="pt-del-wrap">
              <view class="pt-icon-btn danger" :title="$t('common.delete')" @tap.stop="requestDelete(task.id)">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <view v-if="confirmDeleteId === task.id" class="pt-popover" @tap.stop>
                <text class="pt-pop-text">{{ $t('account.deleteTodoConfirm') }}</text>
                <view class="pt-pop-row">
                  <view class="pt-pop-btn" @tap.stop="cancelDelete">{{ $t('common.cancel') }}</view>
                  <view class="pt-pop-btn danger" @tap.stop="handleDeleteTask(task.id)">{{ $t('common.delete') }}</view>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <TaskDialog
      v-model:visible="createOpen"
      :task="null"
      :projects="writableMyProjects"
      @saved="onTaskSaved"
    />
  </view>
</template>

<script>
import { getCalendarTasks, getMyProjects, updateTask, deleteTask } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { isDone, dueBadge } from '@/components/calendar/taskUtils.js'
import { groupTodos, writableProjects } from '@/utils/personalCollections.js'
import TaskDialog from '@/components/calendar/TaskDialog.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'

export default {
  name: 'PersonalTodosPanel',
  components: { TaskDialog, AwdSwitch },
  data() {
    return {
      loading: false,
      tasks: [],
      myProjects: [],
      showDone: false,
      createOpen: false,
      confirmDeleteId: null,
    }
  },
  computed: {
    ICONS() { return ICONS },
    writableMyProjects() {
      return writableProjects(this.myProjects)
    },
    groups() {
      return groupTodos(this.tasks)
    },
    openGroups() {
      const g = this.groups
      const defs = [
        { key: 'overdue', label: this.$t('account.todosGroupOverdue'), items: g.overdue },
        { key: 'today', label: this.$t('account.todosGroupToday'), items: g.today },
        { key: 'upcoming', label: this.$t('account.todosGroupUpcoming'), items: g.upcoming },
        { key: 'noDate', label: this.$t('account.todosGroupNoDate'), items: g.noDate },
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
  beforeUnmount() {
    if (this._deleteTimer) clearTimeout(this._deleteTimer)
  },
  methods: {
    async loadAll() {
      this.loading = true
      try {
        const [taskRes, projects] = await Promise.all([getCalendarTasks(), getMyProjects()])
        this.tasks = (taskRes && taskRes.data && taskRes.data.tasks) || []
        this.myProjects = projects || []
      } catch (e) {
        console.error('加载待办失败:', e)
        uni.showToast({ title: this.$t('account.loadFavoritesFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    isTaskDone(task) {
      return isDone(task)
    },
    badgeOf(task) {
      return dueBadge(task, (k, p) => this.$t(k, p))
    },
    openCreate() {
      this.createOpen = true
    },
    onTaskSaved() {
      this.loadAll()
    },
    openCalendar() {
      uni.navigateTo({ url: '/pages/calendar/calendar' })
    },
    async toggleDone(task) {
      const nextStatus = isDone(task) ? 'OPEN' : 'DONE'
      const prevStatus = task.status
      task.status = nextStatus
      try {
        await updateTask(task.id, { status: nextStatus })
      } catch (e) {
        console.error('更新待办状态失败:', e)
        task.status = prevStatus
        uni.showToast({ title: this.$t('account.todoUpdateFailed'), icon: 'none' })
      }
    },
    requestDelete(id) {
      if (this.confirmDeleteId === id) {
        this.cancelDelete()
        return
      }
      this.confirmDeleteId = id
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
      // 五秒不点就自己收起，免得气泡一直挂着（同「全部收藏」栏目）
      this._deleteTimer = setTimeout(() => {
        if (this.confirmDeleteId === id) this.confirmDeleteId = null
      }, 5000)
    },
    cancelDelete() {
      this.confirmDeleteId = null
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
    },
    async handleDeleteTask(id) {
      this.cancelDelete()
      try {
        await deleteTask(id)
        this.tasks = this.tasks.filter((t) => t.id !== id)
        uni.showToast({ title: this.$t('account.deleteSuccessToast'), icon: 'success' })
      } catch (e) {
        console.error('删除待办失败:', e)
        uni.showToast({ title: this.$t('account.deleteFailedToast'), icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.panel-todos {
  background: var(--awd-surface);
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.04);
  box-sizing: border-box;
  width: 100%;
}

.pt-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 16px;
  margin-bottom: 20px;
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
  font-size: 16px;
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

.pt-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 4px;
  border-bottom: 1px solid var(--awd-border-subtle);

  &:hover {
    background: var(--awd-bg);
  }
}

.pt-rows .pt-row:last-child {
  border-bottom: none;
}

.pt-check {
  flex: none;
  width: 17px;
  height: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--awd-border-strong);
  border-radius: 4px;
  color: var(--awd-text-on-accent);
  cursor: pointer;

  &:hover {
    border-color: var(--awd-mint);
  }

  &.is-done {
    background: var(--awd-accent);
    border-color: var(--awd-accent);
  }
}

.pt-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.pt-row-title {
  font-size: 13px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 1;
}

.pt-row-title-done {
  color: var(--awd-text-3);
  text-decoration: line-through;
}

.pt-row-meta {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
}

.pt-meta-chip {
  font-size: 11px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--awd-surface-2);
  color: var(--awd-text-3);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pt-meta-file {
  color: var(--awd-text-2);
}

.pt-due-badge {
  flex: none;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);

  &.is-today,
  &.is-soon {
    color: var(--awd-danger-text);
    background: var(--awd-bg);
  }

  &.is-overdue {
    color: var(--awd-text-on-accent);
    background: var(--awd-danger);
  }
}

.pt-icon-btn {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--awd-text-3);
  cursor: pointer;
  flex-shrink: 0;

  svg {
    width: 14px;
    height: 14px;
  }

  &.danger:hover {
    background: var(--awd-danger-soft);
    color: var(--awd-danger-text);
  }
}

.pt-del-wrap {
  position: relative;
  flex: none;
}

.pt-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 6px;
  z-index: 20;
  min-width: 160px;
  padding: 10px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: var(--awd-shadow-md);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pt-pop-text {
  font-size: 12px;
  color: var(--awd-text);
  text-align: center;
}

.pt-pop-row {
  display: flex;
  gap: 8px;
}

.pt-pop-btn {
  flex: 1;
  font-size: 12px;
  padding: 4px 0;
  text-align: center;
  border-radius: 6px;
  cursor: pointer;
  background: var(--awd-bg);
  color: var(--awd-text-2);

  &:hover {
    background: var(--awd-surface-3);
    color: var(--awd-text);
  }

  &.danger {
    background: var(--awd-danger-soft);
    color: var(--awd-danger-text);
  }
}

.pt-done-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  cursor: pointer;
}

.pt-done-toggle-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.pt-group-done {
  margin-top: 10px;
}
</style>

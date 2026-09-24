<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="task-schedule">
    <view class="task-header">
      <view class="task-spacer"></view>
      <view class="task-add-btn" @tap="openCreate">
        <text>+ {{ $t('calendar.addQuick') }}</text>
      </view>
    </view>

    <view v-if="loading" class="task-hint">{{ $t('projects.tasksLoadingHint') }}</view>

    <view v-else-if="!tasks.length" class="task-guide">
      <text class="task-guide-title">{{ $t('projects.noTasksTitle') }}</text>
      <text class="task-guide-desc">{{ $t('projects.noTasksDesc') }}</text>
    </view>

    <template v-else>
      <view v-for="grp in openGroups" :key="grp.key" class="task-group" :class="'task-group-' + grp.key">
        <view class="task-group-head">
          <text class="task-group-name">{{ grp.label }}</text>
          <text class="task-group-count">{{ grp.items.length }}</text>
        </view>
        <TaskRow
          v-for="t in grp.items"
          :key="t.uid || t.id"
          :task="t"
          :show-project="false"
          :show-files="true"
          :density="compact ? 'compact' : 'normal'"
          @toggle="onToggle"
          @open="openEdit"
          @open-file="$emit('open-file', $event)"
          @delete="onDelete"
        />
      </view>

      <view v-if="groups.done.length" class="task-done-toggle" @tap="showDone = !showDone">
        <svg class="task-done-caret" :class="{ 'is-open': showDone }" viewBox="0 0 24 24" fill="none"><path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
        <text class="task-done-toggle-label">{{ $t('calendar.groupDoneCount', { count: groups.done.length }) }}</text>
      </view>

      <view v-if="showDone && groups.done.length" class="task-group task-group-done">
        <TaskRow
          v-for="t in groups.done"
          :key="t.uid || t.id"
          :task="t"
          :show-project="false"
          :show-files="true"
          :density="compact ? 'compact' : 'normal'"
          @toggle="onToggle"
          @open="openEdit"
          @open-file="$emit('open-file', $event)"
          @delete="onDelete"
        />
      </view>
    </template>

    <TaskDialog
      :visible="dialogOpen"
      :mode="dialogTask ? 'edit' : 'create'"
      :task="dialogTask"
      :project-id="projectId"
      @open-file="$emit('open-file', $event)"
      @close="closeDialog"
    />
  </view>
</template>

<script>
import TaskRow from '@/components/calendar/TaskRow.vue'
import TaskDialog from '@/components/calendar/TaskDialog.vue'
import { isDone, groupByDue } from '@/components/calendar/taskUtils.js'
import { taskStore, loadProjectTasks, updateTask, deleteTask } from '@/utils/taskStore.js'

// 概览页「日程与任务」块（dev-board#898，spec 2026-09-25-task-calendar-redesign E2）。
// 数据直接读 utils/taskStore 的项目缓存（taskStore.byProject[projectId].list），
// 完成/删除直接调 store，新建与编辑走统一的 TaskDialog（锁定本项目）——
// 写成功后 store 就地更新并广播，工作台日程面板、文件树徽标、rail 徽标一起跟上，
// 宿主 ProjectHomePane 不再持有 tasks 数组。
//
// 用词边界：项目级里程碑叫「任务 / 事项」，AI 单次工作的步骤条叫「进度」
// （那是 todo_write 的东西），两个词不能混。
export default {
  name: 'TaskSchedule',
  components: { TaskRow, TaskDialog },
  props: {
    projectId: { type: [Number, String], required: true },
    /** 工作台左栏窄栏形态：行用 compact 密度 */
    compact: { type: Boolean, default: false },
  },
  emits: ['open-file'],
  data() {
    return {
      loadingOwn: false,
      showDone: false,
      dialogOpen: false,
      dialogTask: null,
    }
  },
  computed: {
    entry() {
      return taskStore.byProject[String(this.projectId)] || null
    },
    tasks() {
      return (this.entry && this.entry.list) || []
    },
    // 只有「还没拿到过这个项目的数据」时才显示加载态；刷新期间保留旧列表不闪
    loading() {
      return this.loadingOwn && !(this.entry && this.entry.loadedAt)
    },
    groups() {
      return groupByDue(this.tasks)
    },
    openGroups() {
      const g = this.groups
      return [
        { key: 'overdue', label: this.$t('calendar.groupOverdue'), items: g.overdue },
        { key: 'today', label: this.$t('calendar.groupToday'), items: g.today },
        { key: 'week', label: this.$t('calendar.groupWeek'), items: g.week },
        { key: 'later', label: this.$t('calendar.groupLater'), items: g.later },
      ].filter((d) => d.items.length)
    },
  },
  watch: {
    // 工作台里换项目不会重建组件，换了 id 就重取
    projectId() {
      this.reload()
    },
  },
  mounted() {
    this.reload()
  },
  methods: {
    /** 宿主显式刷新的入口（ProjectHomePane.refresh）：强制重拉，列表在此期间不清空 */
    async reload() {
      if (this.projectId == null || this.projectId === '') return
      this.loadingOwn = true
      try {
        await loadProjectTasks(this.projectId, { force: true })
      } catch (e) {
        console.warn('[TaskSchedule] 读取事项失败', e)
      } finally {
        this.loadingOwn = false
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
    async onToggle(task) {
      const nextStatus = isDone(task) ? 'OPEN' : 'DONE'
      try {
        await updateTask(task.id, { status: nextStatus })
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
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('calendar.deleteFailed'), icon: 'none' })
          }
        },
      })
    },
  },
}
</script>

<style scoped>
.task-header {
  display: flex;
  align-items: center;
  margin-bottom: 6px;
}

.task-spacer {
  flex: 1;
}

.task-add-btn {
  flex: none;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--awd-accent-text);
  background: var(--awd-accent-wash);
  cursor: pointer;
}

.task-add-btn:hover {
  background: var(--awd-accent-soft);
}

.task-hint {
  font-size: 13px;
  color: var(--awd-text-2);
}

.task-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
}

.task-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: var(--awd-text-2);
}

.task-group + .task-group {
  margin-top: 10px;
}

.task-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}

.task-group-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-2);
}

.task-group-overdue .task-group-name {
  color: var(--awd-danger-text);
}

.task-group-today .task-group-name {
  color: var(--awd-accent-text);
}

.task-group-count {
  font-size: 11px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--awd-surface-2);
  color: var(--awd-text-3);
}

.task-done-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  cursor: pointer;
}

.task-done-caret {
  width: 12px;
  height: 12px;
  color: var(--awd-text-3);
  transition: transform 0.15s;
}

.task-done-caret.is-open {
  transform: rotate(90deg);
}

.task-done-toggle-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.task-group-done {
  margin-top: 4px;
}

/* 响应祖先 .project-home-pane 的实际渲染宽度，见 project-home-pane.scss 的注释 */
@container home-pane (max-width: 359px) {
  .task-group-name {
    font-size: 11px;
  }
}
</style>

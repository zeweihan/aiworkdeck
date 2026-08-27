<template>
  <view class="task-schedule">
    <view class="task-header">
      <view class="task-spacer"></view>
      <view class="task-add-btn" @tap="toggleQuickCreate">
        <text>{{ $t('calendar.addQuick') }}</text>
      </view>
    </view>

    <view v-if="quickCreateOpen" class="task-quick-create">
      <input
        v-model="quickTitle"
        class="task-quick-input"
        :placeholder="$t('calendar.taskTitlePlaceholder')"
        @confirm="submitQuickCreate"
      />
      <AwdDatePicker v-model="quickDate" type="date" />
      <view class="task-quick-actions">
        <view class="task-quick-btn" @tap="quickCreateOpen = false">{{ $t('calendar.cancel') }}</view>
        <view class="task-quick-btn task-quick-btn-primary" @tap="submitQuickCreate">{{ $t('calendar.save') }}</view>
      </view>
    </view>

    <view v-if="loading" class="task-hint">{{ $t('projects.tasksLoadingHint') }}</view>

    <view v-else-if="!openTasks.length && !doneTasks.length" class="task-guide">
      <text class="task-guide-title">{{ $t('projects.noTasksTitle') }}</text>
      <text class="task-guide-desc">{{ $t('projects.noTasksDesc') }}</text>
    </view>

    <template v-else>
      <view v-if="openTasks.length" class="task-rows">
        <view v-for="t in openTasks" :key="t.uid || t.id" class="task-row">
          <view class="task-check" @tap="$emit('toggle', t)">
            <svg v-if="isDone(t)" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
              <polyline points="20 6 9 17 4 12" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="task-title">{{ t.title }}</text>
          <text v-if="dueBadge(t)" class="task-due-badge" :class="dueBadgeClass(t)">{{ dueBadge(t) }}</text>
        </view>
      </view>

      <view class="task-done-toggle" @tap="showDone = !showDone">
        <AwdSwitch :checked="showDone" @change="showDone = $event" />
        <text class="task-done-toggle-label">{{ $t('calendar.showDone') }}</text>
      </view>

      <view v-if="showDone && doneTasks.length" class="task-rows task-rows-done">
        <view v-for="t in doneTasks" :key="t.uid || t.id" class="task-row">
          <view class="task-check is-done" @tap="$emit('toggle', t)">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
              <polyline points="20 6 9 17 4 12" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="task-title task-title-done">{{ t.title }}</text>
          <text class="task-due">{{ t.dueDate || '' }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script>
import AwdDatePicker from '@/components/AwdDatePicker.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'
import { isDone, dueBadge } from '@/components/calendar/taskUtils.js'

// 概览页「日程与任务」块。B 期起 tasks 是 ProjectHomePane 从真实的
// GET /api/projects/{id}/tasks 拉回来的数据，本组件只管展示与交互，
// 写操作（完成/新建）一律 emit 给宿主——宿主持有 tasks 数组，乐观更新与
// 失败回滚都在那一层做（跟 onProfileSave 同一个套路）。
//
// 用词边界：项目级里程碑叫「任务」，AI 单次工作的步骤条叫「进度」
// （那是 todo_write 的东西），两个词不能混。
export default {
  name: 'TaskSchedule',
  components: { AwdDatePicker, AwdSwitch },
  props: {
    tasks: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
  },
  emits: ['toggle', 'quick-create'],
  data() {
    return {
      showDone: false,
      quickCreateOpen: false,
      quickTitle: '',
      quickDate: '',
    }
  },
  computed: {
    openTasks() {
      return this.tasks
        .filter((t) => !this.isDone(t))
        .slice()
        .sort((a, b) => {
          // 没有日期的排最后，其余按 dueDate 升序（越紧迫越靠前）
          if (!a.dueDate && !b.dueDate) return 0
          if (!a.dueDate) return 1
          if (!b.dueDate) return -1
          return a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : 0
        })
    },
    doneTasks() {
      return this.tasks.filter((t) => this.isDone(t))
    },
  },
  methods: {
    isDone(task) {
      return isDone(task)
    },
    dueBadge(task) {
      return dueBadge(task, (k, p) => this.$t(k, p)).text
    },
    dueBadgeClass(task) {
      const kind = dueBadge(task, (k, p) => this.$t(k, p)).kind
      if (kind === 'overdue') return 'is-overdue'
      if (kind === 'today' || kind === 'soon') return 'is-soon'
      return ''
    },
    toggleQuickCreate() {
      this.quickCreateOpen = !this.quickCreateOpen
      if (this.quickCreateOpen) {
        this.quickTitle = ''
        this.quickDate = ''
      }
    },
    submitQuickCreate() {
      const title = (this.quickTitle || '').trim()
      if (!title) {
        uni.showToast({ title: this.$t('calendar.requiredTitle'), icon: 'none' })
        return
      }
      if (!this.quickDate) {
        uni.showToast({ title: this.$t('calendar.requiredDate'), icon: 'none' })
        return
      }
      this.$emit('quick-create', { title, dueDate: this.quickDate })
      this.quickCreateOpen = false
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

.task-quick-create {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  margin-bottom: 8px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-bg);
}

.task-quick-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  font-size: 12px;
  color: var(--awd-text);
  background: var(--awd-surface);
  box-sizing: border-box;
}

.task-quick-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.task-quick-btn {
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);
  cursor: pointer;
}

.task-quick-btn-primary {
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
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

.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.task-row:last-child {
  border-bottom: none;
}

.task-check {
  flex: none;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--awd-border-strong);
  border-radius: 4px;
  color: var(--awd-text-on-accent);
  cursor: pointer;
}

.task-check:hover {
  border-color: var(--awd-mint);
}

.task-check.is-done {
  background: var(--awd-accent);
  border-color: var(--awd-accent);
}

.task-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-title-done {
  color: var(--awd-text-3);
  text-decoration: line-through;
}

.task-due {
  flex: none;
  font-size: 11px;
  color: var(--awd-text-2);
}

.task-due-badge {
  flex: none;
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 11px;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);
}

.task-due-badge.is-soon {
  color: var(--awd-danger-text);
  background: var(--awd-bg);
}

.task-due-badge.is-overdue {
  color: var(--awd-text-on-accent);
  background: var(--awd-danger);
}

.task-done-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  cursor: pointer;
}

.task-done-toggle-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.task-rows-done {
  margin-top: 6px;
}

/* 响应祖先 .project-home-pane 的实际渲染宽度，见 project-home-pane.scss 的注释 */
@container home-pane (max-width: 359px) {
  .task-row {
    gap: 8px;
    padding: 6px 0;
  }

  .task-title {
    font-size: 12px;
  }

  .task-due,
  .task-due-badge {
    font-size: 10px;
  }
}
</style>

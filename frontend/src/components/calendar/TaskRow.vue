<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  TaskRow — 全产品唯一的事项行（dev-board#896，spec 2026-09-25-task-calendar-redesign 第二节）。
  日程页议程、工作台日程面板、概览页 TaskSchedule、设置页「我的待办」四处清单共用。

  纯展示：不调接口、不持有状态，所有动作 emit 给宿主（宿主经 utils/taskStore 写）。
  结构：勾选框 → 类型色条 + 类型图标 → 标题（重要前置小旗）→ 第二行芯片（项目 / 文件 / 负责人）
  → 右侧到期徽标 + 提醒铃 → hover 露出编辑、删除。
  根类名 task-row 与 data-task-id 是 e2e 锚点，别改。
-->
<template>
  <view
    class="task-row"
    :class="['is-' + density, { 'is-done': done, 'is-high': high }]"
    :data-task-id="task.id"
    @tap="$emit('open', task)"
  >
    <view
      class="tr-check"
      :class="{ 'is-checked': done }"
      role="checkbox"
      :aria-checked="done ? 'true' : 'false'"
      :title="done ? $t('calendar.markOpen') : $t('calendar.markDone')"
      @tap.stop="$emit('toggle', task)"
    >
      <svg class="tr-check-mark" viewBox="0 0 24 24" fill="none"><path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" /></svg>
    </view>

    <view class="tr-type-bar" :style="{ background: meta.color }"></view>
    <view class="tr-type-icon" :style="{ color: meta.color }" :title="meta.label">
      <svg viewBox="0 0 24 24" fill="none"><path v-for="(d, i) in meta.icon" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
    </view>

    <view class="tr-main">
      <view class="tr-title-line">
        <svg v-if="high" class="tr-flag" viewBox="0 0 24 24" fill="none" :aria-label="$t('calendar.priorityHigh')"><path d="M5 21V4" stroke="currentColor" stroke-width="2" stroke-linecap="round" /><path d="M5 4h11l-2 4 2 4H5" fill="currentColor" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" /></svg>
        <text class="tr-title">{{ task.title }}</text>
      </view>
      <view v-if="hasMeta" class="tr-meta">
        <view
          v-if="showProject && task.projectName"
          class="tr-chip tr-chip-project"
          @tap.stop="$emit('open-project', task)"
        >
          <text class="tr-project-dot" :style="{ background: projectColor.text }"></text>
          <text class="tr-chip-text">{{ task.projectName }}</text>
        </view>
        <template v-if="showFiles">
          <view
            v-for="f in visibleFiles"
            :key="f.fileId"
            class="tr-chip tr-chip-file"
            :class="{ 'is-missing': f.fileName == null }"
            :title="f.fileName == null ? $t('calendar.fileMissing') : f.fileName"
            @tap.stop="onFileTap(f)"
          >
            <svg class="tr-chip-icon" viewBox="0 0 24 24" fill="none"><path v-for="(d, i) in docIcon" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
            <text class="tr-chip-text">{{ f.fileName == null ? $t('calendar.fileMissing') : f.fileName }}</text>
          </view>
          <text
            v-if="extraFileCount > 0"
            class="tr-chip tr-chip-more"
            :title="$t('calendar.moreFiles', { count: extraFileCount })"
          >+{{ extraFileCount }}</text>
        </template>
        <view v-if="task.assigneeName" class="tr-chip tr-chip-assignee" :title="$t('calendar.assigneeLabel') + ': ' + task.assigneeName">
          <text class="tr-avatar">{{ assigneeInitial }}</text>
          <text class="tr-chip-text">{{ task.assigneeName }}</text>
        </view>
      </view>
    </view>

    <view class="tr-side">
      <svg v-if="hasReminder" class="tr-bell" viewBox="0 0 24 24" fill="none" :aria-label="$t('calendar.remindSet')"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9Z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
      <text v-if="badge.text" class="tr-due" :class="'is-' + badgeKind">{{ badge.text }}<text v-if="badge.time" class="tr-due-time">{{ badge.time }}</text></text>
    </view>

    <view class="tr-actions">
      <view class="tr-action" :title="$t('calendar.edit')" @tap.stop="$emit('open', task)">
        <svg viewBox="0 0 24 24" fill="none"><path v-for="(d, i) in editIcon" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
      <view class="tr-action is-danger" :title="$t('calendar.delete')" @tap.stop="$emit('delete', task)">
        <svg viewBox="0 0 24 24" fill="none"><path v-for="(d, i) in trashIcon" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
    </view>
  </view>
</template>

<script>
import { ICONS } from '@/config/icons.js'
import { colorForProject } from '@/components/calendar/eventColors.js'
import { isDone, isHigh, typeMeta, dueBadge, taskFiles, formatMonthDay, timeOf } from '@/components/calendar/taskUtils.js'

/** 第二行最多直接显示几个文件芯片，其余折成 +N */
const MAX_VISIBLE_FILES = 2

export default {
  name: 'TaskRow',
  props: {
    task: { type: Object, required: true },
    showProject: { type: Boolean, default: false },
    showFiles: { type: Boolean, default: true },
    /** 'normal' | 'compact'（工作台窄栏用 compact） */
    density: { type: String, default: 'normal' },
  },
  emits: ['toggle', 'open', 'open-file', 'open-project', 'delete'],
  computed: {
    done() {
      return isDone(this.task)
    },
    high() {
      return isHigh(this.task)
    },
    meta() {
      return typeMeta(this.task.type, (k) => this.$t(k))
    },
    badge() {
      const translate = (k, p) => this.$t(k, p)
      // 已完成的事项不再说「逾期 N 天 / 今天」，只给灰色的日期（M月D日，有时间带时间）
      if (this.done) {
        if (!this.task.dueDate) return { text: '', kind: '', time: '' }
        return { text: formatMonthDay(this.task.dueDate, translate), kind: 'later', time: timeOf(this.task) }
      }
      return dueBadge(this.task, translate)
    },
    badgeKind() {
      return this.badge.kind
    },
    files() {
      const files = taskFiles(this.task)
      // 右键「添加事项…」建的事项默认标题就是文件名：只有这一个文件且同名时不再重复显示芯片
      if (files.length === 1 && files[0].fileName != null &&
          String(files[0].fileName).trim() === String(this.task.title || '').trim()) return []
      return files
    },
    visibleFiles() {
      return this.files.slice(0, MAX_VISIBLE_FILES)
    },
    extraFileCount() {
      return Math.max(0, this.files.length - MAX_VISIBLE_FILES)
    },
    hasMeta() {
      return (this.showProject && !!this.task.projectName) ||
        (this.showFiles && this.files.length > 0) ||
        !!this.task.assigneeName
    },
    hasReminder() {
      return this.task.remindBefore !== null && this.task.remindBefore !== undefined && this.task.remindBefore !== ''
    },
    assigneeInitial() {
      return String(this.task.assigneeName || '').slice(0, 1).toUpperCase()
    },
    projectColor() {
      return colorForProject(this.task.projectId)
    },
    docIcon() {
      return ICONS.doc
    },
    editIcon() {
      return ICONS.pencil
    },
    trashIcon() {
      return ICONS.trash
    },
  },
  methods: {
    onFileTap(f) {
      // 悬空关联（文件已删）点了也打不开，不上抛
      if (f.fileName == null) return
      this.$emit('open-file', { task: this.task, fileId: f.fileId })
    },
  },
}
</script>

<style lang="scss" scoped>
.task-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px 8px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s ease, opacity 0.15s ease;

  &:hover {
    background: var(--awd-surface-2);
  }

  &:hover .tr-actions {
    opacity: 1;
    pointer-events: auto;
  }

  &:hover .tr-side {
    opacity: 0;
  }
}

.task-row.is-compact {
  gap: 6px;
  padding: 5px 8px 5px 6px;

  .tr-title {
    font-size: 12px;
  }

  .tr-meta {
    margin-top: 2px;
  }
}

.task-row.is-done {
  opacity: 0.6;

  .tr-title {
    text-decoration: line-through;
    color: var(--awd-text-2);
  }
}

.tr-check {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  border-radius: 50%;
  border: 1.5px solid var(--awd-border-strong);
  background: var(--awd-surface);
  color: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;

  &:hover {
    border-color: var(--awd-accent);
  }

  &.is-checked {
    background: var(--awd-accent);
    border-color: var(--awd-accent);
    color: var(--awd-text-on-accent);
  }
}

.tr-check-mark {
  width: 11px;
  height: 11px;
  transform: scale(0.6);
  transition: transform 0.15s ease;
}

.tr-check.is-checked .tr-check-mark {
  transform: scale(1);
}

.tr-type-bar {
  flex: none;
  align-self: stretch;
  width: 3px;
  border-radius: 2px;
}

.tr-type-icon {
  flex: none;
  width: 15px;
  height: 15px;
  margin-top: 1px;

  svg {
    width: 15px;
    height: 15px;
    display: block;
  }
}

.tr-main {
  flex: 1;
  min-width: 0;
}

.tr-title-line {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.tr-flag {
  flex: none;
  width: 12px;
  height: 12px;
  color: var(--awd-danger);
}

.tr-title {
  font-size: 13px;
  line-height: 18px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tr-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}

.tr-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 180px;
  height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--awd-surface-2);
  font-size: 11px;
  line-height: 18px;
  color: var(--awd-text-2);
  box-sizing: border-box;
}

.tr-chip-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tr-chip-project,
.tr-chip-file {
  cursor: pointer;

  &:hover {
    color: var(--awd-accent-text);
    background: var(--awd-accent-soft);
  }
}

.tr-chip-file.is-missing {
  cursor: default;
  text-decoration: line-through;
  color: var(--awd-text-3);

  &:hover {
    color: var(--awd-text-3);
    background: var(--awd-surface-2);
  }
}

.tr-chip-icon {
  flex: none;
  width: 11px;
  height: 11px;
}

.tr-chip-more {
  color: var(--awd-text-3);
}

.tr-project-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.tr-avatar {
  flex: none;
  width: 14px;
  height: 14px;
  line-height: 14px;
  border-radius: 50%;
  text-align: center;
  font-size: 9px;
  font-weight: 600;
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

.tr-side {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 1px;
  transition: opacity 0.12s ease;
}

.tr-bell {
  width: 12px;
  height: 12px;
  color: var(--awd-text-3);
}

.tr-due {
  font-size: 11px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 4px;
  white-space: nowrap;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);

  &.is-overdue {
    color: var(--awd-danger-text);
    background: var(--awd-danger-soft);
  }

  &.is-today {
    color: var(--awd-accent-text);
    background: var(--awd-accent-soft);
  }

  &.is-soon {
    color: var(--awd-warning-text);
    background: var(--awd-warning-soft);
  }

  &.is-later {
    color: var(--awd-text-2);
    background: var(--awd-surface-2);
  }
}

.tr-due-time {
  margin-left: 3px;
  font-variant-numeric: tabular-nums;
}

.tr-actions {
  position: absolute;
  right: 8px;
  top: 6px;
  display: flex;
  gap: 2px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease;
}

.tr-action {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--awd-text-2);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  box-sizing: border-box;

  svg {
    width: 13px;
    height: 13px;
  }

  &:hover {
    color: var(--awd-accent-text);
    border-color: var(--awd-accent);
  }

  &.is-danger:hover {
    color: var(--awd-danger-text);
    border-color: var(--awd-danger);
  }
}
</style>

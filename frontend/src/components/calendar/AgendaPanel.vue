<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  AgendaPanel — 全局日程页右栏「议程」（dev-board#897，spec 2026-09-25-task-calendar-redesign 第三节）。
  取代旧的 UpcomingList（「近期截止」裸列表）。

  纯展示：宿主传入已按筛选过滤好的事项（与日历共用同一套筛选），这里只负责
  groupByDue 分组、顶部小统计、已完成折叠、空态引导卡与「滚到某组」。
  行一律用 TaskRow（showProject），所有动作 emit 给宿主（宿主经 utils/taskStore 写）。

  empty：宿主告诉这里「整个账户还没有任何事项」→ 显示三种创建方式的引导卡；
  有事项但被筛光了显示「没有符合筛选条件的事项」，两者不混。
-->
<template>
  <view class="agenda-panel">
    <view class="agenda-head">
      <text class="agenda-title">{{ $t('calendar.viewAgenda') }}</text>
      <text v-if="!empty" class="agenda-stats" :class="{ 'has-overdue': stats.overdue > 0 }">{{ statsText }}</text>
    </view>

    <view v-if="empty" class="agenda-guide">
      <text class="guide-title">{{ $t('calendar.emptyGuideTitle') }}</text>
      <view class="guide-item guide-item-action" @tap="$emit('create')">
        <view class="guide-icon">
          <svg viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
        </view>
        <text class="guide-text">{{ $t('calendar.emptyGuideCreate') }}</text>
      </view>
      <view class="guide-item">
        <view class="guide-icon">
          <svg viewBox="0 0 24 24" fill="none"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" /><path d="M14 3v5h5" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" /></svg>
        </view>
        <text class="guide-text">{{ $t('calendar.emptyGuideFile') }}</text>
      </view>
      <view class="guide-item">
        <view class="guide-icon">
          <svg viewBox="0 0 24 24" fill="none"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" /></svg>
        </view>
        <text class="guide-text">{{ $t('calendar.emptyGuideAi') }}</text>
      </view>
    </view>

    <view v-else ref="scroller" class="agenda-scroll">
      <view v-if="!hasVisible" class="agenda-nomatch">
        <text>{{ $t('calendar.agendaNoMatch') }}</text>
      </view>
      <view
        v-for="g in openGroups"
        :key="g.key"
        class="agenda-group"
        :class="['group-' + g.key, { 'is-flash': flashGroup === g.key }]"
        :data-group="g.key"
      >
        <view class="agenda-group-head">
          <text class="agenda-group-title">{{ $t(g.labelKey) }}</text>
          <text class="agenda-group-count">{{ g.list.length }}</text>
        </view>
        <TaskRow
          v-for="t in g.list"
          :key="t.id"
          :task="t"
          show-project
          @toggle="$emit('toggle', $event)"
          @open="$emit('open', $event)"
          @open-file="$emit('open-file', $event)"
          @open-project="$emit('open-project', $event)"
          @delete="$emit('delete', $event)"
        />
      </view>

      <view v-if="groups.done.length" class="agenda-group group-done" data-group="done">
        <view class="agenda-group-head is-toggle" @tap="doneOpen = !doneOpen">
          <svg class="agenda-caret" :class="{ 'is-open': doneOpen }" viewBox="0 0 24 24" fill="none"><path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <text class="agenda-group-title">{{ $t('calendar.groupDoneCount', { count: groups.done.length }) }}</text>
        </view>
        <template v-if="doneOpen">
          <TaskRow
            v-for="t in groups.done"
            :key="t.id"
            :task="t"
            show-project
            @toggle="$emit('toggle', $event)"
            @open="$emit('open', $event)"
            @open-file="$emit('open-file', $event)"
            @open-project="$emit('open-project', $event)"
            @delete="$emit('delete', $event)"
          />
        </template>
      </view>
    </view>
  </view>
</template>

<script>
import TaskRow from '@/components/calendar/TaskRow.vue'
import { groupByDue } from '@/components/calendar/taskUtils.js'

const OPEN_GROUPS = [
  { key: 'overdue', labelKey: 'calendar.groupOverdue' },
  { key: 'today', labelKey: 'calendar.groupToday' },
  { key: 'week', labelKey: 'calendar.groupWeek' },
  { key: 'later', labelKey: 'calendar.groupLater' },
]

export default {
  name: 'AgendaPanel',
  components: { TaskRow },
  props: {
    // 已按页面筛选过滤好的事项
    tasks: { type: Array, default: () => [] },
    // 整个账户一条事项都没有（显示引导卡）
    empty: { type: Boolean, default: false },
    // 今天（YYYY-MM-DD），宿主传入便于跨零点刷新
    today: { type: String, default: '' },
  },
  emits: ['toggle', 'open', 'open-file', 'open-project', 'delete', 'create'],
  data() {
    return { doneOpen: false, flashGroup: '' }
  },
  computed: {
    groups() {
      return this.today ? groupByDue(this.tasks, this.today) : groupByDue(this.tasks)
    },
    openGroups() {
      return OPEN_GROUPS
        .map((g) => ({ ...g, list: this.groups[g.key] }))
        .filter((g) => g.list.length)
    },
    hasVisible() {
      return this.openGroups.length > 0 || this.groups.done.length > 0
    },
    // 统计口径同 /api/calendar/summary：本周 = 今天起 7 天（含今天）
    stats() {
      const g = this.groups
      return { overdue: g.overdue.length, today: g.today.length, week: g.today.length + g.week.length }
    },
    statsText() {
      return this.$t('calendar.agendaStats', this.stats)
    },
  },
  methods: {
    /** 滚到某组（?group= 深链）；该组为空时滚到顶。返回是否找到。 */
    scrollToGroup(key) {
      const root = this.$refs.scroller && (this.$refs.scroller.$el || this.$refs.scroller)
      if (!root || typeof root.querySelector !== 'function') return false
      const el = root.querySelector(`[data-group="${key}"]`)
      if (!el) {
        root.scrollTop = 0
        return false
      }
      root.scrollTop = Math.max(0, el.offsetTop - root.offsetTop - 4)
      this.flashGroup = key
      setTimeout(() => { if (this.flashGroup === key) this.flashGroup = '' }, 1600)
      return true
    },
  },
}
</script>

<style lang="scss" scoped>
.agenda-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  overflow: hidden;
}

.agenda-head {
  flex: none;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.agenda-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.agenda-stats {
  font-size: 12px;
  color: var(--awd-text-2);
  white-space: nowrap;

  &.has-overdue { color: var(--awd-danger-text); }
}

.agenda-scroll {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 8px 12px;
}

.agenda-nomatch {
  padding: 32px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--awd-text-3);
}

.agenda-group {
  padding-top: 8px;
  border-radius: 8px;
  transition: background 0.3s ease;

  &.is-flash { background: var(--awd-accent-wash); }
}

.agenda-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px 6px;

  &.is-toggle {
    cursor: pointer;
    border-radius: 6px;

    &:hover { background: var(--awd-bg); }
  }
}

.agenda-group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-2);
}

.agenda-group-count {
  font-size: 11px;
  color: var(--awd-text-3);
}

.group-overdue .agenda-group-title { color: var(--awd-danger-text); }
.group-today .agenda-group-title { color: var(--awd-accent-text); }

.agenda-caret {
  width: 12px;
  height: 12px;
  color: var(--awd-text-3);
  transition: transform 0.15s ease;

  &.is-open { transform: rotate(90deg); }
}

.agenda-guide {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 24px 16px;
}

.guide-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
  margin-bottom: 4px;
}

.guide-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border: 1px dashed var(--awd-gold-line);
  border-radius: 8px;
  background: var(--awd-bg);
}

.guide-item-action {
  cursor: pointer;
  border-style: solid;
  border-color: var(--awd-border);
  background: var(--awd-surface);

  &:hover {
    border-color: var(--awd-mint);
    background: var(--awd-accent-wash);
  }
}

.guide-icon {
  flex: none;
  width: 18px;
  height: 18px;
  color: var(--awd-accent-text);

  svg { width: 18px; height: 18px; display: block; }
}

.guide-text {
  font-size: 12px;
  line-height: 18px;
  color: var(--awd-text-2);
}
</style>

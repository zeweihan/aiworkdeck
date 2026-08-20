<!--
  「近期截止」侧栏：未完成任务按 dueDate 升序展示，剩余天数徽标（≤7 天红色系 /
  逾期深红 / 其余灰）。点击一条 → 父级 gotoDate 跳到该日期（不在这里跳，
  组件不该知道日历实例）。
-->
<template>
  <view class="upcoming-list">
    <view class="upcoming-header">
      <text class="upcoming-title">{{ $t('calendar.upcomingTitle') }}</text>
    </view>
    <view v-if="!upcoming.length" class="upcoming-empty">
      <text class="upcoming-empty-text">{{ $t('calendar.upcomingEmpty') }}</text>
    </view>
    <scroll-view v-else class="upcoming-scroll" scroll-y>
      <view
        v-for="t in upcoming"
        :key="t.uid || t.id"
        class="upcoming-item"
        @tap="$emit('select', t)"
      >
        <view class="upcoming-item-main">
          <text class="upcoming-item-title">{{ t.title }}</text>
          <text class="upcoming-item-project">{{ t.projectName || '' }}</text>
        </view>
        <text class="upcoming-badge" :class="badgeClass(t)">{{ badgeText(t) }}</text>
      </view>
    </scroll-view>
  </view>
</template>

<script>
export default {
  name: 'UpcomingList',
  props: {
    tasks: { type: Array, default: () => [] },
  },
  emits: ['select'],
  computed: {
    upcoming() {
      return this.tasks
        .filter((t) => String(t.status || '').toUpperCase() !== 'DONE')
        .slice()
        .sort((a, b) => {
          const ka = (a.dueDate || '') + 'T' + (a.dueTime || '00:00')
          const kb = (b.dueDate || '') + 'T' + (b.dueTime || '00:00')
          return ka < kb ? -1 : ka > kb ? 1 : 0
        })
    },
  },
  methods: {
    daysUntil(dueDate) {
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const due = new Date(`${dueDate}T00:00:00`)
      return Math.round((due - today) / 86400000)
    },
    badgeText(t) {
      const diff = this.daysUntil(t.dueDate)
      if (diff === 0) return this.$t('calendar.dueToday')
      if (diff > 0) return this.$t('calendar.daysLeft', { count: diff })
      return this.$t('calendar.overdueDays', { count: -diff })
    },
    badgeClass(t) {
      const diff = this.daysUntil(t.dueDate)
      if (diff < 0) return 'badge-overdue'
      if (diff <= 7) return 'badge-soon'
      return 'badge-normal'
    },
  },
}
</script>

<style lang="scss" scoped>
.upcoming-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
  border: 1px solid #E9ECEF;
  border-radius: 10px;
  overflow: hidden;
}

.upcoming-header {
  padding: 14px 16px;
  border-bottom: 1px solid #E9ECEF;
}

.upcoming-title {
  font-size: 13px;
  font-weight: 700;
  color: #2C3338;
}

.upcoming-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}

.upcoming-empty-text {
  font-size: 12px;
  color: #ADB5BD;
  text-align: center;
}

.upcoming-scroll {
  flex: 1;
  min-height: 0;
}

.upcoming-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid #F1F3F5;
  cursor: pointer;

  &:hover { background: #F8F9FA; }
  &:last-child { border-bottom: none; }
}

.upcoming-item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.upcoming-item-title {
  font-size: 13px;
  color: #2C3338;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upcoming-item-project {
  font-size: 11px;
  color: #ADB5BD;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upcoming-badge {
  flex: none;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
}

.badge-normal {
  background: #F1F3F5;
  color: #6C757D;
}

.badge-soon {
  background: #FDEEEC;
  color: #E74C3C;
}

.badge-overdue {
  background: #E74C3C;
  color: #fff;
}
</style>

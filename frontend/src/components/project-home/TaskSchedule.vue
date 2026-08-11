<template>
  <view class="task-schedule">
    <view v-if="loading" class="task-hint">正在读取任务…</view>

    <view v-else-if="!tasks.length" class="task-guide">
      <text class="task-guide-title">还没有排任务</text>
      <text class="task-guide-desc">交付日期、待办和提醒以后会排在这里。</text>
    </view>

    <view v-else class="task-rows">
      <view v-for="t in tasks" :key="t.uid || t.id" class="task-row">
        <text class="task-status" :class="'task-status-' + statusKey(t.status)">{{ statusLabel(t.status) }}</text>
        <text class="task-title">{{ t.title }}</text>
        <text class="task-due">{{ t.dueDate || '' }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页「日程与任务」块。A 期后端恒返回空数组，实际渲染的只有空态；
// 列表分支同样落地，B 期接上任务系统时父页面与端点一行不改。
//
// 注意：uid / status / dueDate 是 B 期 project_task 的**预期**字段形状，
// 本切片没有任何任务定义这张表，别把它当成已生效的契约往别处引。
//
// 用词边界：项目级里程碑叫「任务」，AI 单次工作的步骤条叫「进度」
// （那是 todo_write 的东西），两个词不能混。
export default {
  name: 'TaskSchedule',
  props: {
    tasks: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
  },
  methods: {
    statusKey(status) {
      return String(status || 'OPEN').toLowerCase()
    },
    statusLabel(status) {
      if (status === 'DOING') return '进行中'
      if (status === 'DONE') return '已完成'
      return '待办'
    },
  },
}
</script>

<style scoped>
.task-hint {
  font-size: 13px;
  color: #6C757D;
}

.task-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
}

.task-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #F1F3F5;
}

.task-row:last-child {
  border-bottom: none;
}

.task-status {
  flex: none;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: #F1F3F5;
  color: #6C757D;
}

.task-status.task-status-doing {
  background: #E6F9F0;
  color: #1A5336;
}

.task-status.task-status-done {
  background: #F8F9FA;
  color: #ADB5BD;
}

.task-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #2C3338;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-due {
  flex: none;
  font-size: 11px;
  color: #6C757D;
}
</style>

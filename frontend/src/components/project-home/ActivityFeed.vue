<template>
  <view class="activity-feed">
    <view v-if="loading" class="activity-hint">{{ $t('projects.activityLoadingHint') }}</view>

    <view v-else-if="unavailable" class="activity-guide">
      <text class="activity-guide-title">{{ $t('projects.noVersionHistoryTitle') }}</text>
      <text class="activity-guide-desc">{{ $t('projects.noVersionHistoryDesc') }}</text>
    </view>

    <view v-else-if="!rows.length" class="activity-guide">
      <text class="activity-guide-title">{{ $t('projects.noActivityTitle') }}</text>
      <text class="activity-guide-desc">{{ $t('projects.noActivityDesc') }}</text>
    </view>

    <view v-else class="activity-rows">
      <view v-for="row in rows" :key="row.key" class="activity-row">
        <view class="activity-dot" :class="row.dotClass"></view>
        <view class="activity-body">
          <text class="activity-title">{{ row.title }}</text>
          <text class="activity-time">{{ row.time }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页动态块。主源是版本时间线，副源是后台 AI 任务。
//
// 两条硬约束：
// 1) 不标注 AI/人：authorName 的 "AI Workdeck" 有两个语义相反的来源，
//    VersionEntry 也不带 email，拿它区分只会误导。
// 2) unavailable=true 不是防御性编程：VersionController.requireMember:562-564 在
//    hasReadPermission 通过后还显式拒 CLIENT，客户身份进概览页一定拿到 {code:1}；
//    另外若后端仍未做「未开仓早退回空 versions」那条修复，未开启版本记录的项目
//    也会走这里。两种情况都必须是中性引导态而不是报错。
//
// 标题原样渲染，能容纳时间线的 6 种文案形状（含带空格的「8 月 8 日下午的工作」）。
import { versionTitle, formatDateTime, runStatusLabel, runStatusDotClass } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ActivityFeed',
  props: {
    versions: { type: Array, default: () => [] },
    backgroundRuns: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    unavailable: { type: Boolean, default: false },
  },
  computed: {
    rows() {
      const runs = (this.backgroundRuns || []).map((r) => ({
        key: 'run-' + r.conversationId,
        title: this.$t('projects.aiTaskLabel', { status: runStatusLabel(r.status) || this.$t('projects.runFinished') }),
        time: formatDateTime(r.updatedAt),
        dotClass: runStatusDotClass(r.status),
      }))
      const vers = (this.versions || []).map((v) => ({
        key: 'ver-' + v.sha,
        title: versionTitle(v),
        time: formatDateTime(v.when),
        dotClass: '',
      }))
      return runs.concat(vers)
    },
  },
}
</script>

<style scoped>
.activity-hint {
  font-size: 13px;
  color: #6C757D;
}

.activity-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
}

.activity-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.activity-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #F1F3F5;
}

.activity-row:last-child {
  border-bottom: none;
}

.activity-dot {
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 50%;
  background: #CED4DA;
  flex: none;
}

.activity-dot.dot-running {
  background: #5BD197;
}

.activity-dot.dot-attention {
  background: #F5B60D;
}

.activity-dot.dot-error {
  background: #E74C3C;
}

.activity-body {
  flex: 1;
  min-width: 0;
}

/* 标题一律不压缩空白：未命名工作的默认名带空格，压掉就成错别字 */
.activity-title {
  display: block;
  font-size: 13px;
  line-height: 20px;
  color: #2C3338;
  white-space: pre-wrap;
  word-break: break-word;
}

.activity-time {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #6C757D;
}
</style>

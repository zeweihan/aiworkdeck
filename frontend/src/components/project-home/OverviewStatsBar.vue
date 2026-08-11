<template>
  <view class="overview-stats-bar">
    <view v-if="loading" class="stats-loading">正在读取项目情况…</view>
    <view v-else class="stats-tiles">
      <view class="stat-tile">
        <text class="stat-value">{{ fileLabel }}</text>
        <text class="stat-caption">{{ fileCaption }}</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ folderCount }} 个文件夹</text>
        <text class="stat-caption">不含系统目录</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ memberCount }} 位参与人</text>
        <text class="stat-caption">含负责人</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ runCount }} 个后台任务</text>
        <text class="stat-caption">{{ runCaption }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页统计条。数据来自 GET /api/projects/{id}/overview/stats，原样透传。
// 根类名 overview-stats-bar 是 e2e 锚点，不许改名。
//
// 刻意不展示「项目大小」与「最近修改」：编辑器保存路径不更新 ProjectFile 的
// fileSize / updatedAt，那两个数是假的。
import { fileCountLabel, runStatusLabel } from '@/utils/projectHomeFormat.js'

export default {
  name: 'OverviewStatsBar',
  props: {
    stats: { type: Object, default: () => ({}) },
    loading: { type: Boolean, default: false },
  },
  computed: {
    fileLabel() {
      return fileCountLabel(this.stats)
    },
    fileCaption() {
      return this.stats.isLocalRoot ? '本机文件夹，取自最近一次对账' : '不含缓存区与 AI 生成目录'
    },
    folderCount() {
      return Number(this.stats.folderCount || 0)
    },
    memberCount() {
      return Number(this.stats.memberCount || 0)
    },
    runs() {
      return Array.isArray(this.stats.backgroundRuns) ? this.stats.backgroundRuns : []
    },
    runCount() {
      return this.runs.length
    },
    runCaption() {
      if (!this.runs.length) return '当前没有在跑的任务'
      const label = runStatusLabel(this.runs[0].status)
      return label ? '最近一个：' + label : '最近一个：已结束'
    },
  },
}
</script>

<style scoped>
.overview-stats-bar {
  background: #FFFFFF;
  border: 1px solid #E9ECEF;
  border-radius: 6px;
  padding: 14px 18px;
}

.stats-loading {
  font-size: 13px;
  color: #6C757D;
}

.stats-tiles {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.stat-tile {
  flex: 1 1 160px;
  min-width: 140px;
  padding: 10px 12px;
  background: #F8F9FA;
  border-left: 3px solid #5BD197;
  border-radius: 4px;
}

.stat-value {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: #1A5336;
  line-height: 22px;
}

.stat-caption {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #6C757D;
  line-height: 16px;
}
</style>

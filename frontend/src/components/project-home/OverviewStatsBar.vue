<template>
  <view class="overview-stats-bar">
    <view v-if="loading" class="stats-loading">{{ $t('projects.statsLoadingHint') }}</view>
    <view v-else class="stats-tiles">
      <view class="stat-tile">
        <text class="stat-value">{{ fileLabel }}</text>
        <text class="stat-caption">{{ fileCaption }}</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ $t('projects.folderCountLabel', { count: folderCount }) }}</text>
        <text class="stat-caption">{{ $t('projects.folderCaption') }}</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ $t('projects.memberCountLabel', { count: memberCount }) }}</text>
        <text class="stat-caption">{{ $t('projects.memberCaption') }}</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ $t('projects.runCountLabel', { count: runCount }) }}</text>
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
      return this.stats.isLocalRoot ? this.$t('projects.localRootCaption') : this.$t('projects.defaultFileCaption')
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
      if (!this.runs.length) return this.$t('projects.noRunningTasks')
      const label = runStatusLabel(this.runs[0].status)
      return this.$t('projects.recentRunPrefix', { status: label || this.$t('projects.runFinished') })
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

/* 响应祖先 .project-home-pane 的实际渲染宽度（container-name: home-pane，
   定义在 project-home-pane.scss），不是靠 compact 布尔值。三档见该文件的注释。 */
@container home-pane (max-width: 359px) {
  .overview-stats-bar {
    padding: 10px 12px;
  }

  .stats-tiles {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .stat-tile {
    flex: none;
    min-width: 0;
    padding: 8px 10px;
  }

  .stat-value {
    font-size: 13px;
    line-height: 19px;
  }

  .stat-caption {
    font-size: 10px;
  }
}

@container home-pane (min-width: 360px) and (max-width: 559px) {
  .overview-stats-bar {
    padding: 14px 16px;
  }

  .stats-tiles {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px 12px;
  }

  .stat-tile {
    min-width: 0;
    padding: 10px 12px;
  }

  .stat-value {
    font-size: 14px;
  }
}
</style>

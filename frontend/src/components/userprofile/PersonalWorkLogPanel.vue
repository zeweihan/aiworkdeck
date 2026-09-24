<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「工作记录」栏目。2026-08-20 从 components/userprofile/UserProfilePane.vue 整块搬出来：
  个人中心并进了统一的「设置」页（AdminPane 的「个人」组），四段内容各自成组件，
  免得 AdminPane 再涨两千行。

  内容一行没改，只有生命周期换了宿主：原来靠 UserProfilePane 的 mounted 补一次
  loadActivityLogs（默认 tab 是懒加载的），现在这个组件只在 activeNav === 'work_log'
  时才渲染，自己的 mounted 就是那一次加载。
-->
<template>
  <SettingsSection :title="$t('account.tabWorkLog')" :description="$t('account.workLogSubtitle')">
      <template #actions>
        <view class="log-filter-bar">
          <input class="filter-input" v-model="activityFilter.date" :placeholder="$t('account.filterDatePlaceholder')" />
          <AwdSelect
            class="filter-project-select"
            :range="projectFilterLabels"
            :value="projectFilterIndex"
            @change="onProjectFilterChange"
          />
          <input class="filter-input" v-model="activityFilter.content" :placeholder="$t('account.filterContentPlaceholder')" />
          <button class="btn-export" @tap="exportLogsToExcel">{{ $t('account.exportExcelBtn') }}</button>
        </view>
      </template>

      <view class="log-table-container">
        <view class="log-table-header">
          <text class="th th-project">{{ $t('account.thProject') }}</text>
          <text class="th th-action">{{ $t('account.thAction') }}</text>
          <text class="th th-object">{{ $t('account.thObject') }}</text>
          <text class="th th-start">{{ $t('account.thStart') }}</text>
          <text class="th th-end">{{ $t('account.thEnd') }}</text>
          <text class="th th-duration">{{ $t('account.thDuration') }}</text>
          <text class="th th-idle">{{ $t('account.thIdle') }}</text>
        </view>
        <view v-if="activityLoading" class="loading-row">{{ $t('account.loadingEllipsis') }}</view>
        <view v-else-if="getFilteredLogs().length === 0" class="empty-row">{{ $t('account.noRecords') }}</view>
        <scroll-view v-else scroll-y class="log-table-body">
          <view v-for="log in getFilteredLogs()" :key="log.id" class="log-table-row">
            <text class="td td-project" :title="getLogProject(log)"><text class="project-badge">{{ getLogProject(log) }}</text></text>
            <text class="td td-action">{{ log.actionType }}</text>
            <text class="td td-object" :title="getLogObject(log)">{{ getLogObject(log) }}</text>
            <text class="td td-start">{{ getLogStartTime(log) }}</text>
            <text class="td td-end">{{ getLogEndTime(log) }}</text>
            <text class="td td-duration">{{ getLogDuration(log) }}</text>
            <text class="td td-idle" :title="getLogIdleTime(log)">{{ getLogIdleTime(log) }}</text>
          </view>
        </scroll-view>
      </view>
  </SettingsSection>
</template>

<script>
import { getUserActivityHistory } from '@/services/api.js'
import AwdSelect from '@/components/AwdSelect.vue'
import SettingsSection from '@/components/settings/SettingsSection.vue'

export default {
  name: 'PersonalWorkLogPanel',
  components: { AwdSelect, SettingsSection },
  data() {
    return {
      activityLogs: [],
      activityLoading: false,
      activityFilter: {
        date: '',
        content: '',
      },
      // 'all' | 'unassociated' | 项目 id 的字符串形式
      projectFilterKey: 'all',
    }
  },
  computed: {
    // 从当前记录里去重出的项目筛选项：全部 + 各项目 + 未关联项目（只在存在时才出现）
    projectFilterOptions() {
      const options = [{ key: 'all', label: this.$t('account.allProjectsOption') }]
      const seen = new Map()
      let hasUnassociated = false
      this.activityLogs.forEach(log => {
        if (log.projectId) {
          if (!seen.has(log.projectId)) {
            seen.set(log.projectId, log.projectName || this.$t('account.unassociatedProjectOption'))
          }
        } else {
          hasUnassociated = true
        }
      })
      seen.forEach((label, id) => options.push({ key: String(id), label }))
      if (hasUnassociated) {
        options.push({ key: 'unassociated', label: this.$t('account.unassociatedProjectOption') })
      }
      return options
    },
    projectFilterLabels() {
      return this.projectFilterOptions.map(o => o.label)
    },
    projectFilterIndex() {
      const idx = this.projectFilterOptions.findIndex(o => o.key === this.projectFilterKey)
      return idx >= 0 ? idx : 0
    },
  },
  mounted() {
    this.loadActivityLogs()
  },
  methods: {
    onProjectFilterChange(index) {
      const option = this.projectFilterOptions[index]
      this.projectFilterKey = option ? option.key : 'all'
    },
    async loadActivityLogs() {
      this.activityLoading = true
      try {
        const res = await getUserActivityHistory()
        this.activityLogs = res.data || []
      } catch (e) {
        console.error('Failed to load activity logs', e)
      } finally {
        this.activityLoading = false
      }
    },
    getFilteredLogs() {
      return this.activityLogs.filter(log => {
        const dateMatch = !this.activityFilter.date || this.formatTime(log.timestamp).includes(this.activityFilter.date)
        const contentMatch = !this.activityFilter.content || (log.metaInfo && log.metaInfo.includes(this.activityFilter.content))
        let projectMatch = true
        if (this.projectFilterKey === 'unassociated') {
          projectMatch = !log.projectId
        } else if (this.projectFilterKey !== 'all') {
          projectMatch = String(log.projectId) === this.projectFilterKey
        }
        return dateMatch && projectMatch && contentMatch
      })
    },
    getLogProject(log) {
      // 结构化项目归属优先；projectId 有值但查不到名字（项目已删除）归「未关联项目」
      if (log.projectId) {
        return log.projectName || this.$t('account.unassociatedProjectOption')
      }
      // 老数据没有 projectId，兼容旧的 metaInfo 自由文本 "Project: 名称"
      if (log.metaInfo && log.metaInfo.includes('Project:')) {
        const match = log.metaInfo.match(/Project:\s*([^,;]+)/)
        if (match) return match[1]
      }
      if (log.actionType === 'WORK' && log.targetName) return log.targetName
      return this.$t('account.unassociatedProjectOption')
    },
    getLogObject(log) {
      if (log.actionType === 'OPEN_FILE' || log.actionType === 'CLOSE_FILE') return log.targetName
      if (log.actionType === 'WORK') return '-'
      return log.targetName || '-'
    },
    getLogStartTime(log) {
      if (log.duration && log.duration > 0) {
        const end = new Date(log.timestamp).getTime()
        const dur = Number(log.duration) || 0
        return this.formatDateTime(new Date(end - dur))
      }
      return this.formatDateTime(log.timestamp)
    },
    getLogEndTime(log) {
      return this.formatDateTime(log.timestamp)
    },
    getLogDuration(log) {
      if (log.duration && log.duration > 0) {
        // Round up to nearest 0.25 minutes (15 seconds)；duration is in ms
        const seconds = log.duration / 1000
        const roundedSeconds = Math.ceil(seconds / 15) * 15
        const minutes = roundedSeconds / 60
        return this.$t('account.minutesSuffix', { count: minutes.toFixed(2) })
      }
      // Fallback for old logs or if duration is 0 (instant actions)
      if (log.metaInfo && log.metaInfo.includes('总时长:')) {
        const match = log.metaInfo.match(/总时长:\s*([\d.]+)分/)
        if (match) return this.$t('account.minutesSuffix', { count: match[1] })
      }
      return '-'
    },
    getLogIdleTime(log) {
      if (!log.metaInfo) return '-'
      if (log.metaInfo.includes('IdleSegments:')) {
        return log.metaInfo.split('IdleSegments:')[1].trim()
      }
      if (log.metaInfo.includes('空闲:')) {
        const idx = log.metaInfo.indexOf('空闲:')
        if (idx >= 0) return log.metaInfo.substring(idx)
      }
      return '-'
    },
    exportLogsToExcel() {
      // Simple CSV export for now
      const logs = this.getFilteredLogs()
      let csvContent = 'data:text/csv;charset=utf-8,\uFEFF' // Add BOM
      csvContent += `${this.$t('account.thProject')},${this.$t('account.thAction')},${this.$t('account.thObject')},${this.$t('account.thStart')},${this.$t('account.thEnd')},${this.$t('account.thDuration')},${this.$t('account.thIdle')}\n`

      logs.forEach(log => {
        const project = (this.getLogProject(log) || '').replace(/,/g, ' ')
        const action = log.actionType
        const object = (this.getLogObject(log) || '').replace(/,/g, ' ')
        const start = this.getLogStartTime(log)
        const end = this.getLogEndTime(log)
        const duration = (this.getLogDuration(log) || '').replace(/,/g, ' ')
        const idle = (this.getLogIdleTime(log) || '').replace(/,/g, ' ').replace(/\n/g, ' ')

        csvContent += `${project},${action},${object},${start},${end},${duration},${idle}\n`
      })

      const encodedUri = encodeURI(csvContent)
      const link = document.createElement('a')
      link.setAttribute('href', encodedUri)
      link.setAttribute('download', `work_log_${new Date().toISOString().slice(0, 10)}.csv`)
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },
    formatDateTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        const hour = String(date.getHours()).padStart(2, '0')
        const minute = String(date.getMinutes()).padStart(2, '0')
        const second = String(date.getSeconds()).padStart(2, '0')
        return `${year}-${month}-${day} ${hour}:${minute}:${second}`
      } catch (e) {
        return timeStr
      }
    },
  },
}
</script>

<style lang="scss" scoped>
@import '@/components/settings/settings.scss';

/* 筛选条并成一条 32px 工具栏，挂在 SettingsSection 的头部 #actions 里 */
.log-filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-input {
  flex: 1;
  height: 30px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 0 10px;
  font-size: 12.5px;
  background: var(--awd-surface);
}

.filter-project-select {
  flex: 1;
}

.btn-export {
  @extend .awd-set-btn-secondary;
  flex-shrink: 0;
}

/* 表格用固定列宽（含末列），配合 overflow-x 横向滚动——容器比列宽总和窄时
   横向滚动，而不是把最后一列挤到逐字竖排换行（dev-board#892 走查）。 */
.log-table-container {
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  /* 容器比列宽总和窄时整块横向滚动（表头与行一起动），而不是把末列挤到
     逐字竖排换行（dev-board#892 走查）。 */
  overflow-x: auto;
}

.log-table-header,
.log-table-row {
  display: flex;
  /* 120(项目) + 96(操作) + 120(对象 min-width) + 150(开始) + 150(结束) +
     80(累计时长) + 150(连续无动作时间) = 866 */
  min-width: 866px;
}

.log-table-header {
  background: var(--awd-bg);
  border-bottom: 1px solid var(--awd-border);
  height: 32px;
  align-items: center;
}

.th {
  flex-shrink: 0;
  box-sizing: border-box;
  padding: 0 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-2);
  white-space: nowrap;
}

.th-project { width: 120px; }
.th-action { width: 96px; }
/* 对象列是唯一允许省略的列——其余列一律定宽 + nowrap 不截断（dev-board#892
   二次走查：开始/结束时间戳被截成「2026-09-23 10:0…」、操作截成「OPEN…」，
   时间戳/枚举值截断等于信息丢失；超宽时整表横向滚动兜底，容器已有 overflow-x）。 */
.th-object { flex: 1 1 120px; width: auto; min-width: 120px; overflow: hidden; text-overflow: ellipsis; }
.th-start, .th-end { width: 150px; }
.th-duration { width: 80px; text-align: right; }
.th-idle { width: 150px; }

.log-table-body {
  max-height: 500px;
}

.log-table-row {
  height: 32px;
  align-items: center;
  border-bottom: 1px solid var(--awd-border-subtle);
  font-size: 12.5px;
  color: var(--awd-text);

  &:last-child {
    border-bottom: none;
  }

  &:nth-child(even) {
    background: var(--awd-bg);
  }

  &:hover {
    background: var(--awd-surface-2);
  }
}

.td {
  flex-shrink: 0;
  box-sizing: border-box;
  padding: 0 12px;
  white-space: nowrap;
}

.td-project { width: 120px; }

.project-badge {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--awd-surface-2);
  color: var(--awd-text);
  font-size: 11px;
  font-weight: 500;
}
.td-action { width: 96px; font-weight: 500; }
.td-object {
  flex: 1 1 120px;
  width: auto;
  min-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--awd-text);
}
.td-start, .td-end {
  width: 150px;
  color: var(--awd-text-2);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.td-duration {
  width: 80px;
  color: var(--awd-text-3);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.td-idle { width: 150px; color: var(--awd-text-2); }

.loading-row, .empty-row {
  padding: 40px;
  text-align: center;
  color: var(--awd-text-3);
  font-size: 13px;
}
</style>

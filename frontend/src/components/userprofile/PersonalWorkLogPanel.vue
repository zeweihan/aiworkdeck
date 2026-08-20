<!--
  「工作记录」栏目。2026-08-20 从 components/userprofile/UserProfilePane.vue 整块搬出来：
  个人中心并进了统一的「设置」页（AdminPane 的「个人」组），四段内容各自成组件，
  免得 AdminPane 再涨两千行。

  内容一行没改，只有生命周期换了宿主：原来靠 UserProfilePane 的 mounted 补一次
  loadActivityLogs（默认 tab 是懒加载的），现在这个组件只在 activeNav === 'work_log'
  时才渲染，自己的 mounted 就是那一次加载。
-->
<template>
  <view class="panel-work-log">
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
  </view>
</template>

<script>
import { getUserActivityHistory } from '@/services/api.js'
import AwdSelect from '@/components/AwdSelect.vue'

export default {
  name: 'PersonalWorkLogPanel',
  components: { AwdSelect },
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
$brand-dark: #212629;

.panel-work-log {
  width: 100%;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.04);
  box-sizing: border-box;
}

.log-filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.filter-input {
  flex: 1;
  height: 36px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 0 12px;
  font-size: 13px;
}

.filter-project-select {
  flex: 1;
}

.btn-export {
  height: 36px;
  line-height: 36px;
  padding: 0 20px;
  background: $brand-dark;
  color: #fff;
  font-size: 13px;
  border-radius: 6px;
  border: none;
  cursor: pointer;

  &:hover {
    background: lighten($brand-dark, 5%);
  }
}

.log-table-container {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}

.log-table-header {
  display: flex;
  background: #f8f9fa;
  border-bottom: 1px solid #e2e8f0;
  padding: 12px 16px;
}

.th {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.th-project { width: 120px; }
.th-action { width: 80px; }
.th-object { width: 150px; }
.th-start { width: 140px; }
.th-end { width: 140px; }
.th-duration { width: 80px; }
.th-idle { flex: 1; }

.log-table-body {
  max-height: 500px;
}

.log-table-row {
  display: flex;
  padding: 12px 16px;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13px;
  color: #334155;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: #f8f9fa;
  }
}

.td {
  overflow: hidden;
  text-overflow: ellipsis;
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
  background: #f1f5f9;
  color: $brand-dark;
  font-size: 12px;
  font-weight: 500;
}
.td-action { width: 80px; font-weight: 500; }
.td-object { width: 150px; color: #334155; }
.td-start { width: 140px; color: #64748b; font-size: 12px; }
.td-end { width: 140px; color: #64748b; font-size: 12px; }
.td-duration { width: 80px; color: #94a3b8; }
.td-idle { flex: 1; color: #64748b; }

.loading-row, .empty-row {
  padding: 40px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}
</style>

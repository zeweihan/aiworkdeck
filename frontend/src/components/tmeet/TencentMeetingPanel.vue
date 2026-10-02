<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="tmeet-panel">
    <!-- 头部连接状态卡片 -->
    <view class="tmeet-card tmeet-auth-card">
      <view class="tmeet-row tmeet-between">
        <view class="tmeet-auth-info">
          <view class="tmeet-status-indicator" :class="{ connected: authStatus.authorized }">
            <text class="tmeet-dot">●</text>
            <text class="tmeet-status-text">
              {{ authStatus.authorized ? $t('tmeet.connected') : $t('tmeet.notConnected') }}
            </text>
          </view>
          <text v-if="authStatus.authorized && authStatus.accountName" class="tmeet-account-name">
            {{ authStatus.accountName }}
          </text>
        </view>
        <view class="tmeet-auth-actions">
          <button
            v-if="!authStatus.authorized"
            class="tmeet-btn tmeet-btn-primary"
            :disabled="loggingIn"
            @tap="onLogin"
          >
            {{ loggingIn ? $t('tmeet.authorizingWait') : $t('tmeet.loginBtn') }}
          </button>
          <button
            v-else
            class="tmeet-btn tmeet-btn-ghost"
            @tap="onLogout"
          >
            {{ $t('tmeet.logoutBtn') }}
          </button>
        </view>
      </view>

      <!-- 扫码 / 授权链接展示（未连接或等待授权中） -->
      <view v-if="authorizing && authStatus.authorizeUrl" class="tmeet-auth-qr-box">
        <text class="tmeet-qr-hint">{{ $t('tmeet.scanQrHint') }}</text>
        <view class="tmeet-qr-actions">
          <button class="tmeet-btn tmeet-btn-sm tmeet-btn-secondary" @tap="openAuthLink">
            {{ $t('tmeet.openAuthUrl') }}
          </button>
          <button class="tmeet-btn tmeet-btn-sm tmeet-btn-secondary" @tap="copyAuthLink">
            {{ $t('tmeet.copyAuthUrl') }}
          </button>
        </view>
      </view>
    </view>

    <!-- 同步配置与操作卡片 -->
    <view class="tmeet-card tmeet-sync-card">
      <view class="tmeet-row tmeet-between tmeet-sync-row">
        <view class="tmeet-label-group">
          <text class="tmeet-label">{{ $t('tmeet.autoSync') }}</text>
        </view>
        <AwdSwitch
          :checked="syncConfig.autoSync"
          @change="onToggleAutoSync"
        />
      </view>

      <view v-if="syncConfig.autoSync" class="tmeet-row tmeet-between tmeet-interval-row">
        <text class="tmeet-label-sub">{{ $t('tmeet.syncInterval') }}</text>
        <AwdSelect
          :range="intervalLabels"
          :value="selectedIntervalIndex"
          @change="onIntervalChange"
        />
      </view>

      <view class="tmeet-sync-action-row">
        <button
          class="tmeet-btn tmeet-btn-primary tmeet-sync-btn"
          :disabled="syncing"
          @tap="onSyncNow"
        >
          <text v-if="syncing" class="tmeet-spinner">↻</text>
          <text>{{ syncing ? $t('tmeet.syncing') : $t('tmeet.syncNow') }}</text>
        </button>
      </view>
    </view>

    <!-- 会议搜索与列表 -->
    <view class="tmeet-search-box">
      <input
        class="tmeet-search-input"
        v-model="searchKeyword"
        :placeholder="$t('tmeet.searchPlaceholder')"
        @confirm="onSearch"
        @input="onSearchInput"
      />
    </view>

    <scroll-view class="tmeet-meeting-list" scroll-y>
      <!-- 空态 -->
      <view v-if="filteredMeetings.length === 0" class="tmeet-empty">
        <text class="tmeet-empty-title">{{ $t('tmeet.noMeetings') }}</text>
        <text class="tmeet-empty-hint">{{ $t('tmeet.noMeetingsHint') }}</text>
      </view>

      <!-- 会议卡片 -->
      <view
        v-for="item in filteredMeetings"
        :key="item.id"
        class="tmeet-card tmeet-meeting-card"
        @tap="onOpenMeeting(item)"
      >
        <view class="tmeet-card-header">
          <text class="tmeet-card-title">{{ item.subject }}</text>
          <view
            class="tmeet-badge"
            :class="{
              'badge-synced': item.status === 'SYNCED',
              'badge-failed': item.status === 'FAILED',
              'badge-none': !item.status
            }"
          >
            {{ getStatusText(item.status) }}
          </view>
        </view>

        <view class="tmeet-card-meta">
          <text v-if="item.meetingCode" class="tmeet-meta-item">
            {{ $t('tmeet.meetingCode') }}: {{ item.meetingCode }}
          </text>
          <text v-if="item.startTime" class="tmeet-meta-item">
            {{ formatTime(item.startTime) }}
          </text>
          <text v-if="item.duration" class="tmeet-meta-item">
            {{ item.duration }}
          </text>
        </view>

        <view v-if="item.speakers" class="tmeet-card-speakers">
          <text class="tmeet-speakers-label">{{ $t('tmeet.speakers') }}:</text>
          <text class="tmeet-speakers-text">{{ parseSpeakers(item.speakersJson) }}</text>
        </view>

        <!-- 卡片操作按钮 -->
        <view class="tmeet-card-actions" @tap.stop>
          <button
            class="tmeet-action-btn primary"
            @tap.stop="onOpenMeeting(item)"
          >
            {{ $t('tmeet.openTranscript') }}
          </button>
          <button
            class="tmeet-action-btn"
            @tap.stop="onGenerateMinutes(item)"
          >
            {{ $t('tmeet.aiMinutes') }}
          </button>
          <button
            class="tmeet-action-btn"
            @tap.stop="onGenerateTodos(item)"
          >
            {{ $t('tmeet.aiTodos') }}
          </button>
          <button
            class="tmeet-action-btn"
            :disabled="exportingId === item.id"
            @tap.stop="onExportDoc(item)"
          >
            {{ $t('tmeet.exportDoc') }}
          </button>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
import AwdSwitch from '@/components/AwdSwitch.vue'
import AwdSelect from '@/components/AwdSelect.vue'
import {
  getTmeetAuthStatus,
  loginTmeet,
  logoutTmeet,
  getTmeetSyncConfig,
  updateTmeetSyncConfig,
  syncTmeetMeetings,
  getTmeetMeetings,
  exportTmeetToDoc,
  getTmeetMinutesPrompt,
  getTmeetTodosPrompt
} from '@/services/api.js'

export default {
  name: 'TencentMeetingPanel',
  components: {
    AwdSwitch,
    AwdSelect
  },
  props: {
    projectId: {
      type: [Number, String],
      default: null
    }
  },
  emits: ['open-transcript', 'generate-minutes', 'generate-todos'],
  data() {
    return {
      authStatus: {
        authorized: false,
        accountName: '',
        authorizeUrl: ''
      },
      loggingIn: false,
      authorizing: false,
      authPollTimer: null,
      syncConfig: {
        autoSync: false,
        syncIntervalMinutes: 30,
        excludeKeywords: ''
      },
      syncing: false,
      meetings: [],
      searchKeyword: '',
      exportingId: null,
      intervalValues: [15, 30, 60, 120]
    }
  },
  computed: {
    intervalLabels() {
      return [
        this.$t('tmeet.interval15m'),
        this.$t('tmeet.interval30m'),
        this.$t('tmeet.interval1h'),
        this.$t('tmeet.interval2h')
      ]
    },
    selectedIntervalIndex() {
      const idx = this.intervalValues.indexOf(this.syncConfig.syncIntervalMinutes)
      return idx >= 0 ? idx : 1
    },
    filteredMeetings() {
      if (!this.searchKeyword) return this.meetings
      const kw = this.searchKeyword.trim().toLowerCase()
      return this.meetings.filter(m =>
        (m.subject && m.subject.toLowerCase().includes(kw)) ||
        (m.meetingCode && m.meetingCode.includes(kw))
      )
    }
  },
  mounted() {
    this.refreshAll()
  },
  beforeUnmount() {
    this.stopAuthPoll()
  },
  methods: {
    async refreshAll() {
      await Promise.all([
        this.fetchAuthStatus(),
        this.fetchSyncConfig(),
        this.fetchMeetings()
      ])
    },

    async fetchAuthStatus() {
      try {
        const res = await getTmeetAuthStatus()
        if (res && res.data) {
          this.authStatus = res.data
        }
      } catch (e) {
        console.warn('获取腾讯会议认证状态失败:', e)
      }
    },

    async onLogin() {
      this.loggingIn = true
      try {
        const res = await loginTmeet()
        if (res && res.data) {
          this.authStatus = res.data
          if (this.authStatus.authorizeUrl) {
            this.authorizing = true
            this.openAuthLink()
            this.startAuthPoll()
          } else if (this.authStatus.authorized) {
            uni.showToast({ title: this.$t('tmeet.connected'), icon: 'success' })
            this.fetchMeetings()
          }
        }
      } catch (e) {
        uni.showToast({ title: e.message || '登录失败', icon: 'none' })
      } finally {
        this.loggingIn = false
      }
    },

    openAuthLink() {
      if (!this.authStatus.authorizeUrl) return
      if (typeof window !== 'undefined' && window.open) {
        window.open(this.authStatus.authorizeUrl, '_blank')
      }
    },

    copyAuthLink() {
      if (!this.authStatus.authorizeUrl) return
      uni.setClipboardData({
        data: this.authStatus.authorizeUrl,
        success: () => {
          uni.showToast({ title: this.$t('tmeet.authUrlCopied'), icon: 'none' })
        }
      })
    },

    startAuthPoll() {
      this.stopAuthPoll()
      let counter = 0
      this.authPollTimer = setInterval(async () => {
        counter++
        if (counter > 30) {
          this.stopAuthPoll()
          this.authorizing = false
          return
        }
        await this.fetchAuthStatus()
        if (this.authStatus.authorized) {
          this.stopAuthPoll()
          this.authorizing = false
          uni.showToast({ title: this.$t('tmeet.connected'), icon: 'success' })
          this.fetchMeetings()
        }
      }, 3000)
    },

    stopAuthPoll() {
      if (this.authPollTimer) {
        clearInterval(this.authPollTimer)
        this.authPollTimer = null
      }
    },

    async onLogout() {
      try {
        await logoutTmeet()
        this.authStatus = { authorized: false, accountName: '', authorizeUrl: '' }
        uni.showToast({ title: this.$t('tmeet.notConnected'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: e.message || '退出失败', icon: 'none' })
      }
    },

    async fetchSyncConfig() {
      try {
        const res = await getTmeetSyncConfig()
        if (res && res.data) {
          this.syncConfig = res.data
        }
      } catch (e) {
        console.warn('获取同步配置失败:', e)
      }
    },

    async onToggleAutoSync(val) {
      this.syncConfig.autoSync = val
      await this.saveConfig()
    },

    async onIntervalChange(idx) {
      this.syncConfig.syncIntervalMinutes = this.intervalValues[idx] || 30
      await this.saveConfig()
    },

    async saveConfig() {
      try {
        await updateTmeetSyncConfig(this.syncConfig)
      } catch (e) {
        console.error('保存同步配置失败:', e)
      }
    },

    async fetchMeetings() {
      try {
        const params = {}
        if (this.projectId) params.projectId = this.projectId
        const res = await getTmeetMeetings(params)
        if (res && res.data) {
          this.meetings = res.data
        }
      } catch (e) {
        console.warn('获取会议列表失败:', e)
      }
    },

    async onSyncNow() {
      this.syncing = true
      try {
        const options = {}
        if (this.projectId) options.projectId = this.projectId
        const res = await syncTmeetMeetings(options)
        if (res && res.data) {
          const s = res.data
          uni.showToast({
            title: this.$t('tmeet.syncSuccess', { added: s.addedCount, skipped: s.skippedCount }),
            icon: 'none'
          })
        }
        await this.fetchMeetings()
      } catch (e) {
        uni.showToast({ title: this.$t('tmeet.syncFailed'), icon: 'none' })
      } finally {
        this.syncing = false
      }
    },

    onSearch() {
      this.fetchMeetings()
    },

    onSearchInput() {
      // 客户端实时过滤 computed
    },

    onOpenMeeting(item) {
      this.$emit('open-transcript', item)
    },

    async onGenerateMinutes(item) {
      try {
        const res = await getTmeetMinutesPrompt(item.id)
        const prompt = res && res.data ? res.data : `腾讯会议纪要：请根据腾讯会议「${item.subject}」（recordId=${item.id}）生成专业会议纪要。`
        this.$emit('generate-minutes', { prompt, meeting: item })
      } catch (e) {
        const prompt = `腾讯会议纪要：请根据腾讯会议「${item.subject}」（recordId=${item.id}）生成专业会议纪要。`
        this.$emit('generate-minutes', { prompt, meeting: item })
      }
    },

    async onGenerateTodos(item) {
      try {
        const res = await getTmeetTodosPrompt(item.id)
        const prompt = res && res.data ? res.data : `腾讯会议待办：请提取腾讯会议「${item.subject}」（recordId=${item.id}）中的所有行动项（Action Items / ToDo List）。`
        this.$emit('generate-todos', { prompt, meeting: item })
      } catch (e) {
        const prompt = `腾讯会议待办：请提取腾讯会议「${item.subject}」（recordId=${item.id}）中的所有行动项（Action Items / ToDo List）。`
        this.$emit('generate-todos', { prompt, meeting: item })
      }
    },

    async onExportDoc(item) {
      if (!this.projectId) {
        uni.showToast({ title: '请先打开一个项目后再导出', icon: 'none' })
        return
      }
      this.exportingId = item.id
      try {
        const res = await exportTmeetToDoc(item.id, this.projectId)
        const name = (res && res.data && res.data.fileName) || item.subject
        uni.showToast({
          title: this.$t('tmeet.exportDocSuccess', { name }),
          icon: 'success'
        })
      } catch (e) {
        uni.showToast({ title: this.$t('tmeet.exportDocFailed'), icon: 'none' })
      } finally {
        this.exportingId = null
      }
    },

    getStatusText(status) {
      if (status === 'SYNCED') return this.$t('tmeet.statusSynced')
      if (status === 'FAILED') return this.$t('tmeet.statusNoTranscript')
      return status || this.$t('tmeet.statusPending')
    },

    formatTime(isoStr) {
      if (!isoStr) return ''
      return isoStr.replace('T', ' ').substring(0, 16)
    },

    parseSpeakers(speakersJson) {
      if (!speakersJson) return '-'
      try {
        const list = JSON.parse(speakersJson)
        return Array.isArray(list) ? list.join('、') : String(speakersJson)
      } catch (e) {
        return String(speakersJson)
      }
    }
  }
}
</script>

<style scoped>
.tmeet-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
  background-color: var(--color-bg-sidebar, #f8fafc);
  color: var(--color-text-main, #0f172a);
  font-size: 13px;
  overflow: hidden;
}

.tmeet-card {
  background: var(--color-bg-card, #ffffff);
  border: 1px solid var(--color-border-card, #e2e8f0);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
}

.tmeet-row {
  display: flex;
  align-items: center;
}

.tmeet-between {
  justify-content: space-between;
}

.tmeet-auth-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tmeet-status-indicator {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #94a3b8;
}

.tmeet-status-indicator.connected {
  color: #10b981;
}

.tmeet-dot {
  font-size: 12px;
  margin-right: 4px;
}

.tmeet-account-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-main, #334155);
}

.tmeet-btn {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: none;
  line-height: 1.5;
  transition: all 0.2s;
}

.tmeet-btn-primary {
  background: #10b981;
  color: #ffffff;
}

.tmeet-btn-primary:hover {
  background: #059669;
}

.tmeet-btn-ghost {
  background: transparent;
  color: #64748b;
  border: 1px solid #cbd5e1;
}

.tmeet-btn-ghost:hover {
  background: #f1f5f9;
  color: #334155;
}

.tmeet-btn-secondary {
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #cbd5e1;
}

.tmeet-btn-secondary:hover {
  background: #e2e8f0;
}

.tmeet-btn-sm {
  font-size: 11px;
  padding: 2px 8px;
}

.tmeet-auth-qr-box {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tmeet-qr-hint {
  font-size: 11px;
  color: #64748b;
}

.tmeet-qr-actions {
  display: flex;
  gap: 8px;
}

.tmeet-sync-row {
  margin-bottom: 8px;
}

.tmeet-interval-row {
  margin-bottom: 8px;
  padding-top: 6px;
  border-top: 1px solid #f1f5f9;
}

.tmeet-label {
  font-size: 13px;
  font-weight: 500;
}

.tmeet-label-sub {
  font-size: 12px;
  color: #64748b;
}

.tmeet-sync-action-row {
  margin-top: 6px;
}

.tmeet-sync-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.tmeet-spinner {
  display: inline-block;
  animation: spin 1s infinite linear;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.tmeet-search-box {
  margin-bottom: 8px;
}

.tmeet-search-input {
  width: 100%;
  box-sizing: border-box;
  background: var(--color-bg-card, #ffffff);
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 12px;
}

.tmeet-meeting-list {
  flex: 1;
  overflow-y: auto;
}

.tmeet-empty {
  text-align: center;
  padding: 30px 10px;
  color: #94a3b8;
}

.tmeet-empty-title {
  display: block;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 4px;
}

.tmeet-empty-hint {
  font-size: 11px;
  color: #94a3b8;
}

.tmeet-meeting-card {
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.tmeet-meeting-card:hover {
  border-color: #10b981;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.tmeet-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 6px;
}

.tmeet-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-main, #1e293b);
  flex: 1;
  margin-right: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tmeet-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  white-space: nowrap;
}

.badge-synced {
  background: #ecfdf5;
  color: #059669;
}

.badge-failed {
  background: #fef2f2;
  color: #dc2626;
}

.badge-none {
  background: #f1f5f9;
  color: #64748b;
}

.tmeet-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
}

.tmeet-card-speakers {
  font-size: 11px;
  color: #475569;
  margin-bottom: 8px;
  line-height: 1.4;
}

.tmeet-speakers-label {
  color: #94a3b8;
  margin-right: 4px;
}

.tmeet-card-actions {
  display: flex;
  gap: 6px;
  border-top: 1px solid #f1f5f9;
  padding-top: 8px;
}

.tmeet-action-btn {
  flex: 1;
  font-size: 11px;
  padding: 3px 0;
  border-radius: 4px;
  text-align: center;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #334155;
  cursor: pointer;
  transition: all 0.15s;
}

.tmeet-action-btn:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
}

.tmeet-action-btn.primary {
  background: #10b981;
  border-color: #10b981;
  color: #ffffff;
}

.tmeet-action-btn.primary:hover {
  background: #059669;
}
</style>

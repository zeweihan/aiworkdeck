<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="tmeet-transcript-pane">
    <!-- 顶部工具栏与会议概要 -->
    <view class="tmeet-pane-header">
      <view class="tmeet-pane-title-row">
        <text class="tmeet-pane-title">{{ record ? record.subject : (meeting ? meeting.subject : '') }}</text>
        <view class="tmeet-pane-actions">
          <button class="tmeet-pane-btn primary" @tap="onGenerateMinutes">
            <text class="btn-icon">✦</text>
            <text>{{ $t('tmeet.aiMinutes') }}</text>
          </button>
          <button class="tmeet-pane-btn accent" @tap="onGenerateTodos">
            <text class="btn-icon">✓</text>
            <text>{{ $t('tmeet.aiTodos') }}</text>
          </button>
          <button class="tmeet-pane-btn" :disabled="exporting" @tap="onExportDoc">
            <text>{{ exporting ? '导出中…' : $t('tmeet.exportDoc') }}</text>
          </button>
          <button class="tmeet-pane-btn" @tap="onCopyTranscript">
            <text>{{ copied ? $t('tmeet.copied') : $t('tmeet.copyTranscript') }}</text>
          </button>
        </view>
      </view>

      <view class="tmeet-pane-meta-row">
        <view v-if="record && record.meetingCode" class="tmeet-meta-chip">
          <text class="chip-label">{{ $t('tmeet.meetingCode') }}:</text>
          <text class="chip-val">{{ record.meetingCode }}</text>
        </view>
        <view v-if="record && record.startTime" class="tmeet-meta-chip">
          <text class="chip-val">{{ formatTime(record.startTime) }}</text>
        </view>
        <view v-if="record && record.duration" class="tmeet-meta-chip">
          <text class="chip-val">{{ record.duration }}</text>
        </view>
        <view v-if="speakersList.length > 0" class="tmeet-meta-chip">
          <text class="chip-label">{{ $t('tmeet.speakers') }}:</text>
          <text class="chip-val">{{ speakersList.join('、') }}</text>
        </view>
      </view>

      <!-- 视图分段切换 -->
      <view class="tmeet-section-tabs">
        <view
          class="tmeet-tab-item"
          :class="{ active: activeSection === 'transcript' }"
          @tap="activeSection = 'transcript'"
        >
          <text>{{ $t('tmeet.tabSectionTranscript') }}</text>
          <text class="tmeet-count-pill">{{ paragraphs.length }}</text>
        </view>
        <view
          class="tmeet-tab-item"
          :class="{ active: activeSection === 'smart-minutes' }"
          @tap="activeSection = 'smart-minutes'"
        >
          <text>{{ $t('tmeet.tabSectionSmartMinutes') }}</text>
          <text v-if="record && record.smartMinutesText" class="tmeet-dot-pill"></text>
        </view>
      </view>
    </view>

    <!-- 主体内容区域 -->
    <view class="tmeet-pane-body">
      <!-- 逐字稿时间轴 -->
      <view v-if="activeSection === 'transcript'" class="tmeet-timeline-container">
        <view class="tmeet-search-bar">
          <input
            class="tmeet-timeline-search"
            v-model="searchQuery"
            :placeholder="$t('tmeet.tabSearchPlaceholder')"
          />
        </view>

        <scroll-view class="tmeet-timeline-scroll" scroll-y>
          <view v-if="filteredParagraphs.length === 0" class="tmeet-pane-empty">
            <text>{{ searchQuery ? '未找到匹配的发言内容' : $t('tmeet.noTranscript') }}</text>
          </view>

          <view class="tmeet-timeline">
            <view
              v-for="(p, idx) in filteredParagraphs"
              :key="idx"
              class="tmeet-timeline-item"
            >
              <view class="tmeet-time-col">
                <text class="tmeet-time-pill">{{ p.startTime || '00:00' }}</text>
              </view>
              <view class="tmeet-speaker-col">
                <view
                  class="tmeet-avatar"
                  :style="{ backgroundColor: getSpeakerColor(p.speakerName) }"
                >
                  {{ getSpeakerInitial(p.speakerName) }}
                </view>
              </view>
              <view class="tmeet-content-col">
                <view class="tmeet-speaker-name">{{ p.speakerName || '发言人' }}</view>
                <view class="tmeet-bubble">
                  <text class="tmeet-text">{{ p.text }}</text>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>

      <!-- 智能纪要视图 -->
      <scroll-view v-else-if="activeSection === 'smart-minutes'" class="tmeet-smart-scroll" scroll-y>
        <view v-if="!record || !record.smartMinutesText" class="tmeet-pane-empty">
          <text>{{ $t('tmeet.noSmartMinutes') }}</text>
        </view>
        <view v-else class="tmeet-smart-content">
          <view class="tmeet-markdown-block">
            <text class="tmeet-markdown-text">{{ record.smartMinutesText }}</text>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script>
import {
  getTmeetMeetingDetail,
  exportTmeetToDoc,
  getTmeetMinutesPrompt,
  getTmeetTodosPrompt
} from '@/services/api.js'

export default {
  name: 'TencentMeetingTranscriptPane',
  props: {
    meeting: {
      type: Object,
      default: () => ({})
    },
    projectId: {
      type: [Number, String],
      default: null
    }
  },
  emits: ['generate-minutes', 'generate-todos'],
  data() {
    return {
      record: null,
      loading: false,
      activeSection: 'transcript',
      searchQuery: '',
      exporting: false,
      copied: false,
      colorPalette: [
        '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6',
        '#ec4899', '#06b6d4', '#14b8a6', '#f97316'
      ]
    }
  },
  computed: {
    paragraphs() {
      if (!this.record || !this.record.transcriptJson) return []
      try {
        const list = JSON.parse(this.record.transcriptJson)
        return Array.isArray(list) ? list : []
      } catch (e) {
        return []
      }
    },
    filteredParagraphs() {
      if (!this.searchQuery) return this.paragraphs
      const q = this.searchQuery.trim().toLowerCase()
      return this.paragraphs.filter(p =>
        (p.text && p.text.toLowerCase().includes(q)) ||
        (p.speakerName && p.speakerName.toLowerCase().includes(q))
      )
    },
    speakersList() {
      if (!this.record || !this.record.speakersJson) return []
      try {
        const list = JSON.parse(this.record.speakersJson)
        return Array.isArray(list) ? list : []
      } catch (e) {
        return []
      }
    }
  },
  watch: {
    meeting: {
      immediate: true,
      handler(newVal) {
        if (newVal && newVal.id) {
          this.loadDetail(newVal.id)
        }
      }
    }
  },
  methods: {
    async loadDetail(recordId) {
      this.loading = true
      try {
        const res = await getTmeetMeetingDetail(recordId)
        if (res && res.data) {
          this.record = res.data
        } else {
          this.record = this.meeting
        }
      } catch (e) {
        console.warn('获取会议逐字稿详情失败:', e)
        this.record = this.meeting
      } finally {
        this.loading = false
      }
    },

    async onGenerateMinutes() {
      const rec = this.record || this.meeting
      try {
        const res = await getTmeetMinutesPrompt(rec.id)
        const prompt = res && res.data ? res.data : `腾讯会议纪要：请根据腾讯会议「${rec.subject}」（recordId=${rec.id}）生成专业会议纪要。`
        this.$emit('generate-minutes', { prompt, meeting: rec })
      } catch (e) {
        const prompt = `腾讯会议纪要：请根据腾讯会议「${rec.subject}」（recordId=${rec.id}）生成专业会议纪要。`
        this.$emit('generate-minutes', { prompt, meeting: rec })
      }
    },

    async onGenerateTodos() {
      const rec = this.record || this.meeting
      try {
        const res = await getTmeetTodosPrompt(rec.id)
        const prompt = res && res.data ? res.data : `腾讯会议待办：请提取腾讯会议「${rec.subject}」（recordId=${rec.id}）中的所有行动项（Action Items / ToDo List）。`
        this.$emit('generate-todos', { prompt, meeting: rec })
      } catch (e) {
        const prompt = `腾讯会议待办：请提取腾讯会议「${rec.subject}」（recordId=${rec.id}）中的所有行动项（Action Items / ToDo List）。`
        this.$emit('generate-todos', { prompt, meeting: rec })
      }
    },

    async onExportDoc() {
      const rec = this.record || this.meeting
      if (!this.projectId) {
        uni.showToast({ title: '请先打开一个项目后再导出', icon: 'none' })
        return
      }
      this.exporting = true
      try {
        const res = await exportTmeetToDoc(rec.id, this.projectId)
        const name = (res && res.data && res.data.fileName) || rec.subject
        uni.showToast({
          title: this.$t('tmeet.exportDocSuccess', { name }),
          icon: 'success'
        })
      } catch (e) {
        uni.showToast({ title: this.$t('tmeet.exportDocFailed'), icon: 'none' })
      } finally {
        this.exporting = false
      }
    },

    onCopyTranscript() {
      if (!this.paragraphs.length) return
      const text = this.paragraphs
        .map(p => `[${p.startTime}] ${p.speakerName}：${p.text}`)
        .join('\n\n')
      uni.setClipboardData({
        data: text,
        success: () => {
          this.copied = true
          uni.showToast({ title: this.$t('tmeet.copied'), icon: 'none' })
          setTimeout(() => { this.copied = false }, 2000)
        }
      })
    },

    formatTime(isoStr) {
      if (!isoStr) return ''
      return isoStr.replace('T', ' ').substring(0, 16)
    },

    getSpeakerInitial(name) {
      if (!name) return '?'
      return name.trim().substring(0, 1).toUpperCase()
    },

    getSpeakerColor(name) {
      if (!name) return this.colorPalette[0]
      let hash = 0
      for (let i = 0; i < name.length; i++) {
        hash = (hash << 5) - hash + name.charCodeAt(i)
        hash |= 0
      }
      const idx = Math.abs(hash) % this.colorPalette.length
      return this.colorPalette[idx]
    }
  }
}
</script>

<style scoped>
.tmeet-transcript-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: var(--color-bg-base, #ffffff);
  color: var(--color-text-main, #0f172a);
  box-sizing: border-box;
  overflow: hidden;
}

.tmeet-pane-header {
  padding: 16px 20px 0;
  border-bottom: 1px solid var(--color-border-card, #e2e8f0);
  background: var(--color-bg-card, #ffffff);
  flex-shrink: 0;
}

.tmeet-pane-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.tmeet-pane-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--color-text-main, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  margin-right: 16px;
}

.tmeet-pane-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.tmeet-pane-btn {
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s;
}

.tmeet-pane-btn:hover {
  background: #f8fafc;
  border-color: #94a3b8;
}

.tmeet-pane-btn.primary {
  background: #10b981;
  border-color: #10b981;
  color: #ffffff;
}

.tmeet-pane-btn.primary:hover {
  background: #059669;
}

.tmeet-pane-btn.accent {
  background: #0ea5e9;
  border-color: #0ea5e9;
  color: #ffffff;
}

.tmeet-pane-btn.accent:hover {
  background: #0284c7;
}

.btn-icon {
  font-size: 13px;
}

.tmeet-pane-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.tmeet-meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  color: #475569;
}

.chip-label {
  color: #94a3b8;
}

.chip-val {
  font-weight: 500;
}

.tmeet-section-tabs {
  display: flex;
  gap: 20px;
}

.tmeet-tab-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 4px;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.tmeet-tab-item:hover {
  color: #1e293b;
}

.tmeet-tab-item.active {
  color: #10b981;
  border-bottom-color: #10b981;
}

.tmeet-count-pill {
  font-size: 10px;
  background: #e2e8f0;
  color: #475569;
  padding: 1px 6px;
  border-radius: 10px;
}

.tmeet-dot-pill {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
}

.tmeet-pane-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-bg-base, #ffffff);
}

.tmeet-timeline-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tmeet-search-bar {
  padding: 12px 20px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
}

.tmeet-timeline-search {
  width: 100%;
  max-width: 480px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  box-sizing: border-box;
}

.tmeet-timeline-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  box-sizing: border-box;
}

.tmeet-pane-empty {
  text-align: center;
  padding: 60px 20px;
  color: #94a3b8;
  font-size: 13px;
}

.tmeet-timeline {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 900px;
  margin: 0 auto;
}

.tmeet-timeline-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.tmeet-time-col {
  width: 52px;
  flex-shrink: 0;
  padding-top: 4px;
}

.tmeet-time-pill {
  font-size: 11px;
  color: #94a3b8;
  font-family: monospace;
}

.tmeet-speaker-col {
  flex-shrink: 0;
}

.tmeet-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

.tmeet-content-col {
  flex: 1;
}

.tmeet-speaker-name {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 4px;
}

.tmeet-bubble {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 14px;
  display: inline-block;
  max-width: 100%;
}

.tmeet-text {
  font-size: 13px;
  line-height: 1.6;
  color: #1e293b;
  word-break: break-word;
}

.tmeet-smart-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  box-sizing: border-box;
}

.tmeet-smart-content {
  max-width: 800px;
  margin: 0 auto;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 24px;
}

.tmeet-markdown-block {
  line-height: 1.8;
  font-size: 14px;
  color: #334155;
  white-space: pre-wrap;
}
</style>

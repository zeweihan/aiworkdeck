<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="artifact-card" :class="[type, effectiveStatus]">
    <!-- Row 1: Title & Actions -->
    <div class="card-row-top">
      <div class="card-title-group">
        <span class="card-icon-box"></span>
        <span class="card-title">{{ typeLabel }}</span>
        <span v-if="effectiveStatus === 'resolved'" class="status-badge resolved">{{ $t('chat.confirmedExecuted') }}</span>
        <span v-if="revisionNote" class="status-badge revised">{{ revisionNote }}</span>
        <span v-if="reviewInProgress" class="status-badge revised">{{ $t('chat.reviewInProgress', { hunks: ownReviewState.hunks || 0, comments: ownReviewState.comments || 0 }) }}</span>
      </div>

      <div class="card-actions">
        <div class="btn-view" @click.stop="handleOpenTab">
          <span>{{ $t('chat.viewContent') }}</span>
        </div>
      </div>
    </div>

    <!-- 计划类：正文内联展示（读态渲染 / 修订态就地编辑），对齐 Antigravity 的
         可编辑计划卡交互。非计划类保持原文件行。 -->
    <div v-if="isPlanType && (planContent || editing)" class="plan-body">
      <textarea
        v-if="editing"
        v-model="draftText"
        class="plan-editor"
        :maxlength="-1"
        :style="{ height: editorHeight }"
      ></textarea>
      <div v-else class="plan-preview">
        <MarkdownPreview :content="planContent" />
      </div>
    </div>

    <!-- Row 2: File Name (Clickable) - 非计划类保留 -->
    <div v-if="!isPlanType" class="card-row-bottom" @click="handleOpenTab">
      <div class="file-info-block">
        <span class="file-label">FILE</span>
        <span class="file-name-text">{{ fileName }}</span>
      </div>
    </div>

    <!-- 审批操作区：显眼的「按此推进 / 修订」，取代靠对话打字确认 -->
    <div v-if="showApprovalBar" class="approval-bar">
      <template v-if="!editing">
        <div class="btn-approve" @click.stop="approvePlain">
          <span>{{ $t('chat.proceedBtn') }}</span>
        </div>
        <div class="btn-revise" @click.stop="openReview">
          <span>{{ $t('chat.openRevisionBtn') }}</span>
        </div>
      </template>
      <template v-else>
        <div class="btn-approve" @click.stop="approveRevised">
          <span>{{ $t('chat.proceedRevisedBtn') }}</span>
        </div>
        <div class="btn-revise" @click.stop="cancelEditing">
          <span>{{ $t('chat.cancel') }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import MarkdownPreview from './MarkdownPreview.vue'
import { lineDiffStats } from '@/utils/lineDiff.js'

export default {
  name: 'ArtifactCard',
  components: { MarkdownPreview },
  props: {
    id: {
      type: String,
      required: true
    },
    type: {
      type: String, // 'task_list' | 'plan' | 'implementation_plan'
      default: 'task_list'
    },
    status: {
      type: String, // 'draft' | 'resolved'
      default: 'draft'
    },
    data: {
      type: Object,
      default: () => ({})
    },
    meta: {
      type: Object,
      default: () => ({})
    },
    fileName: {
      type: String,
      default: ''
    },
    filePath: {
      type: String,
      default: ''
    },
    /** 只有最新一条助手消息里的计划卡才可操作（历史里的计划不再弹按钮） */
    actionable: {
      type: Boolean,
      default: false
    },
    // 计划审阅（dev-board#1022）：计划文件在项目里的 fileId（SSE saved 事件补上）；
    // 历史回放没有 saved 事件时退而用气泡正文里「已保存到项目文件」那行的路径反查。
    fileId: {
      type: [Number, String],
      default: null
    },
    savedPath: {
      type: String,
      default: ''
    },
    // 编辑器里审阅态的回传：{ fileId, artifactId, hunks, comments, status }（按 fileId 索引，
    // 同一文件可能被同会话后续计划复用，所以只认 artifactId 对得上的那份，见 ownReviewState）
    reviewState: {
      type: Object,
      default: null
    }
  },
  emits: ['open-tab', 'approve', 'open-review'],
  data() {
    return {
      editing: false,
      draftText: '',
      localResolved: false,
      revisionNote: ''
    }
  },
  computed: {
    isPlanType() {
      return ['task_list', 'plan', 'implementation_plan'].includes(this.type)
    },
    planContent() {
      return (this.data && this.data.content) ? this.data.content.trim() : ''
    },
    effectiveStatus() {
      return this.localResolved ? 'resolved' : this.status
    },
    // 计划落盘同名复用同一文件（默认 Plan.md），第二份计划的卡拿到的 fileId 与上一份相同；
    // 审阅态带 artifactId 时只采用自己那份，不带（旧回传）时照旧采用
    ownReviewState() {
      const s = this.reviewState
      if (!s) return null
      return !s.artifactId || s.artifactId === this.id ? s : null
    },
    reviewInProgress() {
      return !!this.ownReviewState && this.ownReviewState.status === 'open' && this.effectiveStatus === 'draft'
    },
    showApprovalBar() {
      return this.isPlanType && this.actionable && this.effectiveStatus === 'draft'
    },
    editorHeight() {
      const lines = Math.max(8, this.draftText.split('\n').length + 1)
      return Math.min(lines * 20 + 24, 480) + 'px'
    },
    typeLabel() {
      const labels = {
        'task_list': this.$t('chat.typeTaskList'),
        'plan': this.$t('chat.typePlan'),
        'implementation_plan': this.$t('chat.typeImplPlan'),
        'walkthrough': this.$t('chat.typeWalkthrough')
      }
      return labels[this.type] || this.type
    },
    statusMessage() {
      if (this.effectiveStatus === 'resolved') {
        return this.$t('chat.approvedExecute')
      }
      const typeNames = {
        'task_list': this.$t('chat.typeTaskList'),
        'plan': this.$t('chat.typeNamePlan'),
        'implementation_plan': this.$t('chat.typeImplPlan'),
        'walkthrough': this.$t('chat.typeNameWalkthrough')
      }
      const typeName = typeNames[this.type] || this.$t('chat.typeNamePlan')
      return this.$t('chat.generatedClickView', { name: typeName })
    }
  },
  watch: {
    // 在编辑器里「按修订版推进」之后，卡片跟着置为已推进
    ownReviewState: {
      immediate: true,
      handler(s) {
        if (s && s.status === 'submitted' && !this.localResolved) {
          this.localResolved = true
          this.revisionNote = this.$t('chat.approveDisplayRevised')
        }
      }
    }
  },
  methods: {
    handleOpenTab() {
      console.log('[ArtifactCard] Opening artifact in tab:', this.id)
      this.$emit('open-tab', {
        id: this.id,
        type: this.type,
        fileName: this.fileName,
        filePath: this.filePath,
        content: this.data?.content || ''
      })
    },
    // 「打开修订」：计划文件能定位到就交给宿主在编辑器标签里开审阅态；
    // 定位不到（没有 fileId 也没有保存路径，或宿主反查失败回调 fallback）才退回卡内 textarea。
    openReview() {
      if (!this.fileId && !this.savedPath) {
        this.startEditing()
        return
      }
      this.$emit('open-review', {
        id: this.id,
        type: this.type,
        fileId: this.fileId || null,
        savedPath: this.savedPath,
        content: this.planContent,
        fallback: () => this.startEditing()
      })
    },
    startEditing() {
      this.draftText = this.planContent
      this.editing = true
    },
    cancelEditing() {
      this.editing = false
      this.draftText = ''
    },
    approvePlain() {
      this.localResolved = true
      this.$emit('approve', {
        id: this.id,
        type: this.type,
        fileName: this.fileName,
        filePath: this.filePath,
        revised: false
      })
    },
    approveRevised() {
      const edited = this.draftText.trim()
      if (!edited || edited === this.planContent) {
        // 没有实际改动，按原计划推进
        this.editing = false
        this.approvePlain()
        return
      }
      const stats = lineDiffStats(this.planContent, edited)
      // 修订版写回卡片，「查看内容」与后续展示保持一致
      if (this.data) this.data.content = edited
      this.editing = false
      this.localResolved = true
      this.revisionNote = this.$t('chat.revisionNote', { hunks: stats.hunks, added: stats.added, removed: stats.removed })
      this.$emit('approve', {
        id: this.id,
        type: this.type,
        fileName: this.fileName,
        filePath: this.filePath,
        revised: true,
        content: edited,
        changeCount: stats.hunks,
        diffSummary: `+${stats.added} 行 / -${stats.removed} 行`
      })
    }
  }
}
</script>

<style scoped>
.artifact-card {
  background: var(--awd-surface);
  padding: 12px 16px;
  transition: background 0.15s;
}

.artifact-card:hover {
  background: var(--awd-bg); /* Gray-Pale */
}

/* Row 1 */
.card-row-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* Styled box icon */
.card-icon-box {
  width: 6px;
  height: 6px;
  background: var(--awd-warning);
  border-radius: 50%; /* Circle looks more modern for status-like dots */
  flex-shrink: 0;
}
.implementation_plan .card-icon-box { background: var(--awd-warning); }
.task_list .card-icon-box { background: var(--awd-info); }
.walkthrough .card-icon-box { background: #6b7280; }

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-accent-text); /* Forest Green */
}

.status-badge.resolved {
  font-size: 9px;
  background: var(--awd-accent-soft); /* Mint Lightest */
  color: var(--awd-accent-text); /* Forest Green */
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.status-badge.revised {
  font-size: 9px;
  background: var(--awd-bg);
  color: var(--awd-warning-text);
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.card-actions {
  display: flex;
  gap: 8px;
}

/* 计划正文 */
.plan-body {
  margin-bottom: 10px;
}

.plan-preview {
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 10px 12px;
  max-height: 320px;
  overflow-y: auto;
  background: var(--awd-surface);
}

.plan-preview :deep(.markdown-preview) {
  padding: 0 !important;
  background: transparent !important;
  min-height: auto;
  height: auto;
  margin: 0;
  overflow: visible;
}

.plan-preview :deep(.markdown-body) {
  font-size: 12.5px;
  line-height: 1.55;
  margin: 0;
  padding: 0;
  color: var(--awd-text);
}

.plan-editor {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--awd-mint);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 20px;
  color: var(--awd-text);
  background: var(--awd-surface);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  resize: vertical;
  outline: none;
}

/* 审批操作区 */
.approval-bar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.btn-approve {
  background: var(--awd-accent); /* Forest Green */
  color: var(--awd-text-on-accent);
  font-size: 12px;
  padding: 6px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.15s;
}
.btn-approve:hover { background: var(--awd-accent-hover); } /* Forest Green Darker */

.btn-revise {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  color: var(--awd-text);
  font-size: 12px;
  padding: 5px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.15s;
}
.btn-revise:hover {
  border-color: var(--awd-mint);
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

.btn-view {
  background: transparent;
  color: var(--awd-text-2); /* Gray-Medium */
  font-size: 11px;
  padding: 4px 6px;
  cursor: pointer;
  text-decoration: none;
  font-weight: 500;
}
.btn-view:hover { color: var(--awd-accent-text); text-decoration: underline; }

/* Row 2 */
.card-row-bottom {
  cursor: pointer;
}

.file-info-block {
  background: var(--awd-bg); /* Gray-Pale */
  border: 1px solid var(--awd-border); /* Gray-Light */
  border-radius: 6px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-label {
  font-size: 9px;
  font-weight: 700;
  color: var(--awd-text-3);
  letter-spacing: 0.8px;
}

.file-name-text {
  font-size: 12px;
  color: var(--awd-text); /* Gray-Dark */
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>

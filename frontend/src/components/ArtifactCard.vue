<template>
  <div class="artifact-card" :class="[type, effectiveStatus]">
    <!-- Row 1: Title & Actions -->
    <div class="card-row-top">
      <div class="card-title-group">
        <span class="card-icon-box"></span>
        <span class="card-title">{{ typeLabel }}</span>
        <span v-if="effectiveStatus === 'resolved'" class="status-badge resolved">{{ $t('chat.confirmedExecuted') }}</span>
        <span v-if="revisionNote" class="status-badge revised">{{ revisionNote }}</span>
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
        <div class="btn-revise" @click.stop="startEditing">
          <span>{{ $t('chat.reviseBtn') }}</span>
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

/**
 * 行级 diff 统计：返回 { hunks: 改动处数, added, removed }。
 * 计划文本通常几十行，LCS DP 足够；超大文本退化为整体一处改动。
 */
function lineDiffStats(original, edited) {
  const A = original.split('\n')
  const B = edited.split('\n')
  const n = A.length
  const m = B.length
  if (n * m > 400000) {
    return { hunks: 1, added: Math.max(0, m - n), removed: Math.max(0, n - m) }
  }
  const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  let i = 0
  let j = 0
  let added = 0
  let removed = 0
  let hunks = 0
  let inHunk = false
  while (i < n && j < m) {
    if (A[i] === B[j]) {
      i++
      j++
      inHunk = false
    } else {
      if (!inHunk) {
        hunks++
        inHunk = true
      }
      if (dp[i + 1][j] >= dp[i][j + 1]) {
        removed++
        i++
      } else {
        added++
        j++
      }
    }
  }
  if (i < n || j < m) {
    if (!inHunk) hunks++
    removed += n - i
    added += m - j
  }
  return { hunks, added, removed }
}

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
    }
  },
  emits: ['open-tab', 'approve'],
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

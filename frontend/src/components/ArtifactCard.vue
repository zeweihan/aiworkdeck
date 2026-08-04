<template>
  <div class="artifact-card" :class="[type, status]">
    <!-- Row 1: Title & Actions -->
    <div class="card-row-top">
      <div class="card-title-group">
        <span class="card-icon-box"></span>
        <span class="card-title">{{ typeLabel }}</span>
        <span v-if="status === 'resolved'" class="status-badge resolved">已批准</span>
      </div>
      
      <div class="card-actions">
        <!-- Approve Button -->
        <template v-if="status === 'draft' && type === 'implementation_plan'">
          <div class="btn-approve" @click.stop="handleApprove">
            <span>批准执行</span>
          </div>
        </template>
        <!-- View Button -->
        <template v-else>
          <div class="btn-view" @click.stop="handleOpenTab">
            <span>查看内容</span>
          </div>
        </template>
      </div>
    </div>

    <!-- Row 2: File Name (Clickable) -->
    <div class="card-row-bottom" @click="handleOpenTab">
      <div class="file-info-block">
        <span class="file-label">FILE</span>
        <span class="file-name-text">{{ fileName }}</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ArtifactCard',
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
    }
  },
  emits: ['open-tab', 'approve'],
  computed: {
    typeLabel() {
      const labels = {
        'task_list': '任务清单',
        'plan': '执行方案',
        'implementation_plan': '实施计划',
        'walkthrough': '详细说明'
      }
      return labels[this.type] || this.type
    },
    statusMessage() {
      if (this.status === 'resolved') {
        return '已批准执行'
      }
      const typeNames = {
        'task_list': '任务清单',
        'plan': '工作计划',
        'implementation_plan': '实施计划',
        'walkthrough': '详细内容'
      }
      const typeName = typeNames[this.type] || '工作计划'
      return `已生成${typeName}，点击查看详情`
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
    handleApprove() {
      console.log('[ArtifactCard] Approving artifact:', this.id)
      this.$emit('approve', {
        id: this.id,
        type: this.type,
        fileName: this.fileName,
        filePath: this.filePath
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.artifact-card {
  background: transparent; /* 悬浮在深色气泡卡片内 */
  padding: 12px 16px;
  transition: background 0.15s;
}

.artifact-card:hover {
  background: rgba(255, 255, 255, 0.04);
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
}

/* Styled box icon */
.card-icon-box {
  width: 6px;
  height: 6px;
  background: #d97706;
  border-radius: 50%; /* Circle looks more modern for status-like dots */
  flex-shrink: 0;
}
.implementation_plan .card-icon-box { background: #ea580c; }
.task_list .card-icon-box { background: #3b82f6; }
.walkthrough .card-icon-box { background: #6b7280; }

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: $awd-mint;
}

.status-badge.resolved {
  font-size: 9px;
  background: rgba(91, 209, 151, 0.15);
  color: $awd-mint;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.card-actions {
  display: flex;
  gap: 8px;
}

.btn-approve {
  background: $awd-mint;
  color: $awd-chrome-panel;
  font-size: 11px;
  padding: 4px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.15s;
}
.btn-approve:hover { background: lighten($awd-mint, 8%); }

.btn-view {
  background: transparent;
  color: $awd-text-on-dark-2;
  font-size: 11px;
  padding: 4px 6px;
  cursor: pointer;
  text-decoration: none;
  font-weight: 500;
}
.btn-view:hover { color: $awd-mint; text-decoration: underline; }

/* Row 2 */
.card-row-bottom {
  cursor: pointer;
}

.file-info-block {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-label {
  font-size: 9px;
  font-weight: 700;
  color: $awd-text-on-dark-3;
  letter-spacing: 0.8px;
}

.file-name-text {
  font-size: 12px;
  color: $awd-text-on-dark;
  font-family: $awd-font-mono;
}
</style>

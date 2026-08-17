<template>
  <view v-if="visible" class="file-picker-mask" @tap="handleCancel">
    <view class="file-picker-dialog" @tap.stop>
      <view class="dialog-header">
        <text class="dialog-title">{{ title }}</text>
        <text class="dialog-close" @tap="handleCancel">×</text>
      </view>
      
      <view class="dialog-body">
         <view class="file-tree-container">
            <FileTree
                ref="fileTree"
                :project-id="projectId"
                :show-footer-actions="false"
                :selection-mode="false"
                @file-select="handleFileSelect"
            />
         </view>
         <view class="selected-file-info" v-if="selectedFile">
            <text class="info-label">{{ selectedIsFolder ? $t('files.selectedFolderLabel') : $t('files.selectedLabel') }}</text>
            <text class="info-name">{{ selectedFile.name }}</text>
         </view>
      </view>
      
      <view class="dialog-footer">
        <button class="btn-cancel" @tap="handleCancel">{{ $t('common.cancel') }}</button>
        <button
          class="btn-confirm"
          :class="{ disabled: !selectedFile }"
          @tap="handleConfirm"
        >
          {{ $t('files.confirmImport') }}
        </button>
      </view>
    </view>
  </view>
</template>

<script>
// We assume FileTree is globally registered or we need to import it if it's not. 
// Based on project-overview, it seems locally registered there. 
// Since this is a new component, we should probably import FileTree here to be safe and self-contained, 
// OR register it in the parent. Let's try to import it.
import FileTree from '@/components/FileTree.vue'
import { t } from '@/i18n'

export default {
  name: 'FilePickerDialog',
  components: {
    FileTree
  },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    projectId: {
      type: [String, Number],
      required: true
    },
    // 对话框标题（不同调用方复用）
    title: {
      type: String,
      default: () => t('files.pickerDefaultTitle')
    },
    // 允许选择的扩展名（小写、不带点，如 ['pdf', 'docx']）；空数组不过滤
    accept: {
      type: Array,
      default: () => []
    },
    // 允不允许选中文件夹。默认 false —— 大多数调用方（EasyVoice 导入、脱敏）
    // 要的是一份具体文档。诉讼可视化的「材料范围」例外：律师给材料的自然单位
    // 就是卷宗文件夹，那边传 true。
    allowFolder: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      selectedFile: null
    }
  },
  computed: {
    selectedIsFolder() {
      const f = this.selectedFile
      return !!f && (f.fileType === 'folder' || f.isFolder)
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.selectedFile = null
      }
    }
  },
  methods: {
    handleFileSelect(file) {
      const isFolder = file.fileType === 'folder' || file.isFolder
      // 文件夹默认不可选；allowFolder 打开时可选，且不受 accept 扩展名过滤
      // （文件夹没有扩展名，拿 accept 卡它等于永远选不中）。
      if (isFolder) {
        if (!this.allowFolder) return
        this.selectedFile = file
        return
      }
      if (this.accept.length > 0) {
        const ext = (file.name || '').split('.').pop().toLowerCase()
        if (!this.accept.includes(ext)) {
          uni.showToast({ title: this.$t('files.onlyTypesSupported', { types: this.accept.join('/') }), icon: 'none' })
          return
        }
      }
      this.selectedFile = file
    },
    handleCancel() {
      this.$emit('update:visible', false)
      this.$emit('cancel')
    },
    handleConfirm() {
      if (!this.selectedFile) return
      this.$emit('confirm', this.selectedFile)
      this.$emit('update:visible', false)
    }
  }
}
</script>

<style scoped lang="scss">
.file-picker-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-picker-dialog {
  background: #fff;
  border-radius: 12px;
  width: 500px;
  max-width: 90vw;
  height: 600px; /* Fixed height for tree scrolling */
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
  flex-shrink: 0;
}

.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.dialog-close {
  font-size: 24px;
  color: #9ca3af;
  cursor: pointer;
  line-height: 1;
  padding: 4px;
}

.dialog-close:hover {
  color: #6b7280;
}

.dialog-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0; /* Let FileTree take full width */
  overflow: hidden;
}

.file-tree-container {
  flex: 1;
  overflow-y: auto;
  border-bottom: 1px solid #e5e7eb;
}

.selected-file-info {
    padding: 12px 20px;
    background: #f9fafb;
    border-top: 1px solid #e5e7eb;
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
}

.info-label {
    font-size: 13px;
    color: #6b7280;
}

.info-name {
    font-size: 13px;
    font-weight: 500;
    color: #111827;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #e5e7eb;
  background: #fff;
  flex-shrink: 0;
}

.btn-cancel {
  padding: 8px 20px;
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  color: #374151;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-cancel:hover {
  background: #f9fafb;
  border-color: #9ca3af;
}

.btn-confirm {
  padding: 8px 20px;
  background: linear-gradient(135deg, #1A5336 0%, #14402a 100%); /* AI WorkDeck Green */
  border: none;
  border-radius: 6px;
  font-size: 14px;
  color: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-confirm:hover:not(.disabled) {
  opacity: 0.9;
}

.btn-confirm.disabled {
  background: #d1d5db;
  cursor: not-allowed;
}
</style>

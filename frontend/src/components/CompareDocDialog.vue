<template>
  <view v-if="visible" class="compare-dialog-mask" @tap="handleCancel">
    <view class="compare-dialog" @tap.stop>
      <view class="dialog-header">
        <text class="dialog-title">{{ $t('editor.compare.title') }}</text>
        <text class="dialog-close" @tap="handleCancel">×</text>
      </view>
      
      <view class="dialog-body">
        <text class="dialog-desc">{{ $t('editor.compare.desc') }}</text>
        
        <view class="doc-selection">
          <view class="doc-item">
            <view class="doc-label">
              <svg class="label-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, gi) in ICONS.doc" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <text class="label-text">{{ $t('editor.compare.sourceLabel') }}</text>
            </view>
            <view class="doc-options">
              <view 
                v-for="(doc, index) in documents" 
                :key="doc.id"
                class="doc-option"
                :class="{ selected: sourceIndex === index }"
                @tap="selectSource(index)"
              >
                <view class="option-radio" :class="{ checked: sourceIndex === index }"></view>
                <text class="option-name">{{ doc.name }}</text>
              </view>
            </view>
          </view>
          
          <view class="doc-arrow">
            <text>↓</text>
            <text class="arrow-label">{{ $t('editor.compare.arrowLabel') }}</text>
            <text>↓</text>
          </view>
          
          <view class="doc-item">
            <view class="doc-label">
              <svg class="label-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, gi) in ICONS.docText" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <text class="label-text">{{ $t('editor.compare.targetLabel') }}</text>
            </view>
            <view class="doc-options">
              <view 
                v-for="(doc, index) in documents" 
                :key="doc.id"
                class="doc-option"
                :class="{ selected: targetIndex === index, disabled: sourceIndex === index }"
                @tap="selectTarget(index)"
              >
                <view class="option-radio" :class="{ checked: targetIndex === index, disabled: sourceIndex === index }"></view>
                <text class="option-name" :class="{ disabled: sourceIndex === index }">{{ doc.name }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>
      
      <view class="dialog-footer">
        <button class="btn-cancel" @tap="handleCancel">{{ $t('editor.compare.cancel') }}</button>
        <button
          class="btn-confirm"
          :class="{ disabled: !canConfirm }"
          @tap="handleConfirm"
        >
          {{ $t('editor.compare.start') }}
        </button>
      </view>
    </view>
  </view>
</template>

<script>
import { ICONS } from '@/config/icons.js'
export default {
  name: 'CompareDocDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    documents: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      sourceIndex: 0,
      targetIndex: 1
    }
  },
  computed: {
    ICONS() { return ICONS },
    canConfirm() {
      return this.documents.length === 2 && 
             this.sourceIndex !== this.targetIndex &&
             this.sourceIndex >= 0 && 
             this.targetIndex >= 0
    }
  },
  watch: {
    visible(val) {
      if (val && this.documents.length === 2) {
        // 重置选择
        this.sourceIndex = 0
        this.targetIndex = 1
      }
    }
  },
  methods: {
    selectSource(index) {
      this.sourceIndex = index
      // 如果选择了相同的，自动切换 target
      if (this.targetIndex === index) {
        this.targetIndex = index === 0 ? 1 : 0
      }
    },
    selectTarget(index) {
      if (index === this.sourceIndex) return
      this.targetIndex = index
    },
    handleCancel() {
      this.$emit('cancel')
      this.$emit('update:visible', false)
    },
    handleConfirm() {
      if (!this.canConfirm) return
      
      const sourceDoc = this.documents[this.sourceIndex]
      const targetDoc = this.documents[this.targetIndex]
      
      this.$emit('confirm', {
        source: sourceDoc,
        target: targetDoc
      })
      this.$emit('update:visible', false)
    }
  }
}
</script>

<style scoped lang="scss">
.compare-dialog-mask {
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

.compare-dialog {
  background: #fff;
  border-radius: 12px;
  width: 400px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
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
  padding: 20px;
}

.dialog-desc {
  display: block;
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 20px;
  line-height: 1.5;
}

.doc-selection {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.doc-item {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
}

.doc-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.label-icon {

  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.label-text {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.doc-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.doc-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.doc-option:hover:not(.disabled) {
  border-color: #3b82f6;
  background: #eff6ff;
}

.doc-option.selected {
  border-color: #3b82f6;
  background: #eff6ff;
}

.doc-option.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.option-radio {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid #d1d5db;
  transition: all 0.15s;
  position: relative;
}

.option-radio.checked {
  border-color: #3b82f6;
}

.option-radio.checked::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3b82f6;
}

.option-radio.disabled {
  border-color: #e5e7eb;
}

.option-name {
  font-size: 13px;
  color: #374151;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.option-name.disabled {
  color: #9ca3af;
}

.doc-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: #9ca3af;
  font-size: 14px;
  padding: 4px 0;
}

.arrow-label {
  font-size: 11px;
  color: #9ca3af;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #e5e7eb;
  background: #f9fafb;
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
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  border: none;
  border-radius: 6px;
  font-size: 14px;
  color: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-confirm:hover:not(.disabled) {
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
}

.btn-confirm.disabled {
  background: #d1d5db;
  cursor: not-allowed;
}
</style>





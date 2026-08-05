<template>
  <view 
    v-if="visible" 
    class="drop-zone-container" 
    :class="{ 'is-dragging': isDragOver }"
    ref="container"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- Header / Title -->
    <view class="staging-header">
      <view class="header-left">
        <text class="staging-title">文件暂存区</text>
        <view v-if="files.length > 0" class="staging-actions">
            <text class="clear-btn" @tap.stop="$emit('clear')">清空</text>
        </view>
      </view>
      <view class="header-right">
        <view class="collapse-btn" @tap.stop="$emit('collapse')" title="收起">
          <text class="collapse-icon">▼</text>
        </view>
      </view>
    </view>

    <!-- 免费额度用量条：平时是一条极淡的细线 + 灰字，只有接近上限才转为暖色。
         没解锁但用量很低时不该有存在感——这是常驻面板，不是提醒栏。 -->
    <view v-if="quotaVisible" class="staging-quota" :class="{ 'is-tight': quotaTight }">
      <view class="quota-bar">
        <view class="quota-fill" :style="{ width: quotaPercent + '%' }"></view>
      </view>
      <text class="quota-text">{{ quotaText }}</text>
    </view>
    <UnlockHint
      v-if="quotaTight"
      class="staging-unlock-hint"
      text="缓存区快满了，解锁无限版可不限文件数与容量"
    />

    <!-- Contrast Button (Visible when exactly 2 DOC/DOCX files are selected) -->
    <view v-if="canCompare" class="compare-btn-wrapper">
       <button class="btn-compare" @tap.stop="handleCompare">
         <svg class="compare-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
           <path v-for="(d, gi) in ICONS.compare" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
         </svg>
         <text>对比选中文件</text>
       </button>
    </view>
    
    <!-- Empty State (Drop Zone Hint) -->
    <view v-if="files.length === 0" class="staging-empty">
       <text>拖拽文件到此处暂存</text>
    </view>

    <!-- File List Container -->
    <view 
        v-else 
        class="staging-list-container"
        @mousedown="onMouseDown"
    >
       <scroll-view 
          scroll-y 
          class="staging-list"
          :scroll-top="scrollTop"
       >
           <view 
              v-for="(file, index) in files" 
              :key="file.id" 
              class="staging-item"
              :class="{ selected: selectedIds.includes(file.id) }"
              :data-id="file.id"
              ref="fileItems"
              draggable="true" 
              @dragstart.stop="onDragStart(file, $event)"
              @dragend="onDragEnd"
              @tap.stop="onItemClick(file, $event)"
              @dblclick.stop="handleDoubleClick(file)"
           >
              <view class="checkbox-wrapper" @tap.stop="toggleCheck(file.id)">
                <view class="custom-checkbox" :class="{ checked: selectedIds.includes(file.id) }">
                  <text v-if="selectedIds.includes(file.id)" class="check-mark">√</text>
                </view>
              </view>
              <image class="file-icon" :src="getFileIcon(file)" mode="aspectFit" />
              <text class="file-name" :title="file.name">{{ file.name }}</text>
              <text class="remove-btn" @tap.stop="$emit('remove', file.id)">×</text>
           </view>
       </scroll-view>
      
    </view>

    <!-- Drop Overlay (Only visible when dragging over THIS component) -->
    <view 
      v-if="isDragOver"
      class="drop-overlay"
      @dragenter.prevent
      @dragover.prevent
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
    >
      <text>松手暂存文件</text>
    </view>
    
  </view>
</template>

<script>
import { ICONS } from '@/config/icons.js'
import UnlockHint from '@/components/UnlockHint.vue'
export default {
  name: 'FileStagingArea',
  components: { UnlockHint },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    files: {
      type: Array,
      default: () => []
    },
    // 免费额度用量：{ fileCount, totalBytes, limited, maxFiles, maxBytes }
    // 由 project-overview 从后端拉取；上限只有后端一处定义。
    // limited 为 false（已解锁）或对象为空时整条用量条都不出现。
    usage: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      isDragOver: false,
      selectedIds: [], // Array of file IDs
      // Marquee State

      scrollTop: 0
    }
  },
  computed: {
    ICONS() { return ICONS },
    // 只有「受免费额度约束且上限已知」时才显示用量条
    quotaVisible() {
      const u = this.usage
      return !!(u && u.limited && u.maxFiles && u.maxBytes)
    },
    // 两条额度取更满的那条来画进度——用户关心的是「还能放多少」，而不是两个数字
    quotaPercent() {
      if (!this.quotaVisible) return 0
      const u = this.usage
      const byCount = (u.fileCount || 0) / u.maxFiles
      const byBytes = (u.totalBytes || 0) / u.maxBytes
      return Math.min(100, Math.round(Math.max(byCount, byBytes) * 100))
    },
    // 80% 以上才转为醒目色并给出解锁提示，平时保持安静
    quotaTight() {
      return this.quotaVisible && this.quotaPercent >= 80
    },
    quotaText() {
      if (!this.quotaVisible) return ''
      const u = this.usage
      return `${u.fileCount || 0}/${u.maxFiles} 个文件 · ${this.formatSize(u.totalBytes || 0)}/${this.formatSize(u.maxBytes)}`
    },
    canCompare() {
      if (this.selectedIds.length !== 2) return false
      const selectedFiles = this.files.filter(f => this.selectedIds.includes(f.id))
      return selectedFiles.every(f => this.isWordFile(f))
    },

  },
  mounted() {
      // Marquee listeners removed
  },
  beforeUnmount() {
      // Marquee listeners removed
  },
  methods: {
    formatSize(bytes) {
      const n = Number(bytes) || 0
      if (n >= 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(1) + 'GB'
      if (n >= 1024 * 1024) return Math.round(n / 1024 / 1024) + 'MB'
      if (n >= 1024) return Math.round(n / 1024) + 'KB'
      return n + 'B'
    },
    getFileIcon(file) {
      if (!file) return '/static/file.png'
      const name = file.name.toLowerCase()
      if (name.endsWith('.doc') || name.endsWith('.docx')) return '/static/word.png'
      if (name.endsWith('.pdf')) return '/static/pdf.png'
      return '/static/file.png' // Default
    },
    isWordFile(file) {
      if (!file || !file.name) return false
      const n = file.name.toLowerCase()
      return n.endsWith('.doc') || n.endsWith('.docx') || n.endsWith('.wps')
    },
    onItemClick(file, event) {
      const id = file.id
      // Multi-select with Cmd/Ctrl/Shift
      const isMulti = event.metaKey || event.ctrlKey || event.shiftKey
      
      if (isMulti) {
        this.toggleCheck(id)
      } else {
        // Single select
        this.selectedIds = [id]
      }
    },
    toggleCheck(id) {
      if (this.selectedIds.includes(id)) {
        this.selectedIds = this.selectedIds.filter(pid => pid !== id)
      } else {
        this.selectedIds.push(id)
      }
    },
    clearSelection() {
        this.selectedIds = []
    },
    handleDoubleClick(file) {
      this.$emit('open', file)
    },
    handleCompare() {
        if (!this.canCompare) return
        const selectedFiles = this.files.filter(f => this.selectedIds.includes(f.id))
        this.$emit('compare', selectedFiles)
    },
    
    // Handling Drag Out (Staging -> Editor/Tree)
    // Handling Drag Out (Staging -> Editor/Tree)
    onDragStart(file, event) {
      // Standard data format for internal file drag
      if (event.dataTransfer) {
          event.dataTransfer.effectAllowed = 'copy'
          // Use the same format as FileTree to handle drop in editors
          event.dataTransfer.setData('application/json', JSON.stringify(file))
          // Also text/plain for general use
          event.dataTransfer.setData('text/plain', file.name)
          
          // Internal custom format
          try {
             const fileData = JSON.stringify({
                 fileId: file.id,
                 name: file.name,
                 fileType: file.fileType || 'file',
                 wpsFileId: file.wpsFileId
             })
             event.dataTransfer.setData('application/x-checkba-file', fileData)
          } catch(err) {}
      }
      
      // Global fallback for all envs
      if (typeof document !== 'undefined') {
         document.__checkbaDraggedFile = {
             fileId: file.id,
             name: file.name,
             fileType: file.fileType || 'file',
             wpsFileId: file.wpsFileId
         }
      }
      uni.$emit('file-drag-start')
    },
    onDragEnd() {
      uni.$emit('file-drag-end')
    },
    // Drag & Drop
    onDragEnter() {
      this.isDragOver = true
    },
    onDragOver(e) {
      this.isDragOver = true
      if (e.dataTransfer) {
        e.dataTransfer.dropEffect = 'copy'
      }
    },
    onDragLeave() {
      this.isDragOver = false
    },
    onDrop(e) {
      this.isDragOver = false
      
      let files = []
      
      // 1. Try to get data from dataTransfer (Standard)
      if (e && e.dataTransfer) {
          // Check for internal FileTree drag type
          let rawData = e.dataTransfer.getData('application/x-checkba-file') // Preferred
          if (!rawData) rawData = e.dataTransfer.getData('text/checkba-file-json') // Fallback 1
          if (!rawData) rawData = e.dataTransfer.getData('text/plain') // Fallback 2 (might be just ID or index)

          if (rawData) {
              try {
                  // Try parsing JSON
                  // Note: text/plain might be just an index if from FileTree, so we need validation
                  if (rawData.startsWith('{')) {
                      const data = JSON.parse(rawData)
                      if (data.fileId) {
                          files.push({
                              id: data.fileId,
                              name: data.name,
                              fileType: data.fileType,
                              wpsFileId: data.wpsFileId
                          })
                      }
                  }
              } catch (err) {
                  console.warn('Failed to parse dropped data:', err)
              }
          }
      }
      
      // 2. Fallback: Check global variable (for environments where dataTransfer is restricted/cleared)
      if (files.length === 0 && typeof document !== 'undefined' && document.__checkbaDraggedFile) {
          const data = document.__checkbaDraggedFile
          if (data.fileId) {
             files.push({
                id: data.fileId,
                name: data.name,
                fileType: data.fileType,
                wpsFileId: data.wpsFileId
             })
          }
          // Clear it
          document.__checkbaDraggedFile = null
      }

      if (files.length > 0) {
          this.$emit('drop', files) 
      }
    },
    
    // Marquee Selection
    // Marquee Selection Removed
    onMouseDown(e) {
        // Clear selection if not modified key
        if (!e.metaKey && !e.ctrlKey && !e.shiftKey) {
            this.selectedIds = [];
        }
    }
  }
}
</script>

<style scoped>
.drop-zone-container {
  /* Adapted from FileLinkDropZone but resident */
  position: relative; /* Changed from absolute to flow in flex container */
  /* Remove Left/Right/Bottom/Z-index if relative, or strictly control z-index if needed */
  z-index: 50;
  
  min-height: 100px;
  max-height: 250px;
  flex-shrink: 0; /* Prevent shrinking */
  
  padding: 0;
  display: flex;
  flex-direction: column;
  
  background: #f8fafc;
  border-top: 1px solid #e2e8f0;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.05);
  transition: all 0.2s ease;
}

.drop-zone-container.is-dragging {
    background: #eff6ff;
    border-color: #3b82f6;
}

.staging-header {
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8px 0 12px;
    background: #f1f5f9;
    border-bottom: 1px solid #e2e8f0;
}

.header-left {
    display: flex;
    align-items: center;
    gap: 8px;
}

.staging-title {
    font-size: 12px;
    font-weight: 600;
    color: #475569;
}

.collapse-btn {
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: #94a3b8;
    border-radius: 4px;
}

.collapse-btn:hover {
    background: #e2e8f0;
    color: #64748b;
}

.collapse-icon {
    font-family: "Material Icons"; /* Assuming material icons are available or use text */
    font-size: 16px;
    line-height: 1;
}

.clear-btn {
    font-size: 11px;
    color: #94a3b8;
    cursor: pointer;
}
.clear-btn:hover {
    color: #ef4444;
}

/* 用量条：常态是灰细线，克制到几乎看不见；接近上限才转暖色 */
.staging-quota {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    background: #fff;
    border-bottom: 1px solid #e2e8f0;
}

.quota-bar {
    flex: 1;
    height: 3px;
    border-radius: 2px;
    background: #e2e8f0;
    overflow: hidden;
}

.quota-fill {
    height: 100%;
    background: #cbd5e1;
    border-radius: 2px;
    transition: width 0.2s ease, background 0.2s ease;
}

.quota-text {
    font-size: 11px;
    color: #94a3b8;
    flex-shrink: 0;
}

.staging-quota.is-tight .quota-fill {
    background: #d9a441;
}

.staging-quota.is-tight .quota-text {
    color: #8a6d2f;
}

.staging-unlock-hint {
    margin: 8px 12px 0;
}

.staging-empty {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #94a3b8;
    font-size: 12px;
    border: 2px dashed #e2e8f0;
    margin: 8px;
    border-radius: 6px;
}

.staging-list-container {
    flex: 1;
    position: relative; /* For Marquee absolute positioning */
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.staging-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
    /* Ensure mouse selection works */
}

.staging-item {
    display: flex;
    align-items: center;
    padding: 4px 8px;
    border-radius: 4px;
    cursor: pointer;
    margin-bottom: 2px;
    background: white;
    border: 1px solid transparent;
    user-select: none; /* Prevent text selection */
}

.checkbox-wrapper {
    padding-right: 8px;
    display: flex;
    align-items: center;
}

.custom-checkbox {
    width: 14px;
    height: 14px;
    border: 1px solid #cbd5e1;
    border-radius: 3px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    transition: all 0.2s;
}

.custom-checkbox.checked {
    background: #3b82f6;
    border-color: #3b82f6;
}

.check-mark {
    color: #fff;
    font-size: 10px;
    font-weight: bold;
    line-height: 1;
}

.staging-item:hover {
    background: #f1f5f9;
}
.staging-item.selected {
    background: #e0f2fe;
    border-color: #bae6fd;
}

.file-icon {
    width: 16px;
    height: 16px;
    margin-right: 8px;
    flex-shrink: 0;
}

.file-name {
    font-size: 12px;
    color: #334155;
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.remove-btn {
    margin-left: 8px;
    color: #94a3b8;
    cursor: pointer;
    font-size: 16px;
    line-height: 1;
    padding: 4px;
}
.remove-btn:hover {
    color: #ef4444;
}

.compare-btn-wrapper {
    padding: 8px;
    background: #fff;
    border-bottom: 1px solid #e2e8f0;
    display: flex;
    justify-content: center;
}

.btn-compare {
    background: #3b82f6;
    color: white;
    border: none;
    border-radius: 4px;
    padding: 4px 12px;
    font-size: 12px;
    display: flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    line-height: 1.5;
}
.btn-compare:hover {
    background: #2563eb;
}

.drop-overlay {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(59, 130, 246, 0.1);
    border: 2px dashed #3b82f6;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #2563eb;
    font-weight: 600;
    pointer-events: none; /* Let drag events pass through to layer below if needed, but here overlay is top */
    z-index: 101;
}

.drop-target-layer {
    position: absolute;
    top: 0; left: 0; right: 0; bottom: 0;
    z-index: 99;
}

.marquee-box {
    position: absolute;
    background: rgba(59, 130, 246, 0.2);
    border: 1px solid #3b82f6;
    pointer-events: none;
    z-index: 1000;
}

.compare-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
</style>

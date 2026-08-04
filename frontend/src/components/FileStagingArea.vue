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
export default {
  name: 'FileStagingArea',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    files: {
      type: Array,
      default: () => []
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

<style scoped lang="scss">
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

  background: transparent; /* 继承左栏 $awd-chrome-panel */
  border-top: 1px solid $awd-chrome-line;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.2);
  transition: all 0.2s ease;
}

.drop-zone-container.is-dragging {
    background: rgba($awd-mint, 0.08);
    border-color: $awd-mint;
}

.staging-header {
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8px 0 12px;
    background: $awd-chrome-hover;
    border-bottom: 1px solid $awd-chrome-line;
}

.header-left {
    display: flex;
    align-items: center;
    gap: 8px;
}

.staging-title {
    font-size: 12px;
    font-weight: 600;
    color: $awd-text-on-dark-2;
}

.collapse-btn {
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: $awd-text-on-dark-3;
    border-radius: 4px;
}

.collapse-btn:hover {
    background: $awd-chrome-active;
    color: $awd-text-on-dark;
}

.collapse-icon {
    font-family: "Material Icons"; /* Assuming material icons are available or use text */
    font-size: 16px;
    line-height: 1;
}

.clear-btn {
    font-size: 11px;
    color: $awd-text-on-dark-3;
    cursor: pointer;
}
.clear-btn:hover {
    color: $awd-brick-on-dark;
}

.staging-empty {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $awd-text-on-dark-3;
    font-size: 12px;
    border: 2px dashed $awd-chrome-active;
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
    background: transparent;
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
    border: 1px solid $awd-chrome-active;
    border-radius: 3px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: $awd-chrome-hover;
    transition: all 0.2s;
}

.custom-checkbox.checked {
    background: $awd-mint;
    border-color: $awd-mint;
}

.check-mark {
    color: $awd-chrome-panel;
    font-size: 10px;
    font-weight: bold;
    line-height: 1;
}

.staging-item:hover {
    background: $awd-chrome-hover;
}
.staging-item.selected {
    background: rgba($awd-mint, 0.12);
    border-color: $awd-mint;
}

.file-icon {
    width: 16px;
    height: 16px;
    margin-right: 8px;
    flex-shrink: 0;
}

.file-name {
    font-size: 12px;
    color: $awd-text-on-dark;
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.remove-btn {
    margin-left: 8px;
    color: $awd-text-on-dark-3;
    cursor: pointer;
    font-size: 16px;
    line-height: 1;
    padding: 4px;
}
.remove-btn:hover {
    color: $awd-brick-on-dark;
}

.compare-btn-wrapper {
    padding: 8px;
    background: transparent;
    border-bottom: 1px solid $awd-chrome-line;
    display: flex;
    justify-content: center;
}

.btn-compare {
    background: rgba($awd-mint, 0.12);
    color: $awd-mint;
    border: 1px solid $awd-mint;
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
    background: rgba($awd-mint, 0.2);
}

.drop-overlay {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba($awd-mint, 0.1);
    border: 2px dashed $awd-mint;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $awd-mint;
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
    background: rgba($awd-mint, 0.2);
    border: 1px solid $awd-mint;
    pointer-events: none;
    z-index: 1000;
}

.compare-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
</style>

<template>
  <view v-if="visible" class="drop-zone-container">
    <view class="drop-zone-title">{{ $t('files.dropToLink') }}</view>
    <view class="drop-zone-sub">{{ $t('files.currentFileLabel', { name: fileName }) }}</view>

    <view class="drop-zones">
      <!-- 左侧区域 -->
      <view
        class="drop-zone-item"
        :class="{ hover: hoverSide === 'left' }"
        @dragenter.prevent="onDragEnter('left')"
        @dragover.prevent="onDragOver('left', $event)"
        @dragleave.prevent="onDragLeave('left')"
        @drop.prevent="onDrop('left')"
      >
        {{ $t('files.linkToLeftDoc') }}
      </view>

      <!-- 右侧区域（分屏模式下显示） -->
      <view
        v-if="splitMode"
        class="drop-zone-item"
        :class="{ hover: hoverSide === 'right' }"
        @dragenter.prevent="onDragEnter('right')"
        @dragover.prevent="onDragOver('right', $event)"
        @dragleave.prevent="onDragLeave('right')"
        @drop.prevent="onDrop('right')"
      >
        {{ $t('files.linkToRightDoc') }}
      </view>
    </view>
  </view>
</template>

<script>
export default {
  name: 'FileLinkDropZone',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    fileName: {
      type: String,
      default: ''
    },
    splitMode: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      hoverSide: null // 'left' | 'right' | null
    }
  },
  watch: {
    // 当组件隐藏时，重置内部状态
    visible(val) {
      if (!val) {
        this.hoverSide = null
      }
    }
  },
  methods: {
    onDragEnter(side) {
      this.hoverSide = side
    },
    onDragOver(side, e) {
      if (this.hoverSide !== side) {
        this.hoverSide = side
      }
      // 必须设置 dropEffect 才能允许 drop
      if (e.dataTransfer) {
        e.dataTransfer.dropEffect = 'link'
      }
    },
    onDragLeave(side) {
      if (this.hoverSide === side) {
        this.hoverSide = null
      }
    },
    onDrop(side) {
      console.log('[FileLinkDropZone] Drop on:', side)
      this.hoverSide = null
      this.$emit('drop', { side })
    }
  }
}
</script>

<style scoped>
.drop-zone-container {
  /* Changed from absolute to relative to stack with other components in sidebar */
  position: relative;
  z-index: 100;
  
  width: auto;
  min-height: 100px;
  padding: 8px 12px;
  
  /* 样式调整：适应侧边栏 */
  border-top: 1px solid #e2e8f0;
  background: #fff;
  /* 只有顶部阴影 */
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.05);
  
  display: flex;
  flex-direction: column;
  align-items: center;
  
  transition: all 0.2s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(100%);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.drop-zone-title {
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 4px;
  text-align: center;
}

.drop-zone-sub {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 12px;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
}

.drop-zones {
  width: 100%;
  display: flex;
  flex-direction: column; /* 侧边栏较窄，改为垂直排列 */
  gap: 8px;
}

.drop-zone-item {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: 2px dashed #cbd5e1;
  background: #f8f9fa;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.2s ease;
  cursor: default;
}

/* Hover 状态样式 */
.drop-zone-item.hover {
  border-color: #3b82f6;
  background: #eff6ff;
  color: #2563eb;
  transform: scale(1.02);
  box-shadow: 0 2px 4px rgba(59, 130, 246, 0.1);
}
</style>

<template>
  <view class="clip-panel">
    <scroll-view class="clip-body" :scroll-y="false" :scroll-x="true" show-scrollbar="false">
      <view v-if="loading" class="loading">加载中...</view>
      <view v-else-if="items.length === 0" class="empty">暂无记录</view>
      <view v-else class="list-grid">
        <view v-for="it in items" :key="it.id" class="clip-card" @tap="copy(it.text)">
          <view class="card-header">
            <view class="header-left">
              <view class="type-line">
                <view class="left-badge" :class="'tone-' + getTypeMeta(it.type).tone">
                  <text class="badge-text">{{ formatTypeLabel(it.type) }}</text>
                </view>
                <!-- Source label removed as requested -->
              </view>
              <text class="time-label">{{ formatTime(it.createdAt) }}</text>
            </view>
            <!-- Actions Top Right -->
            <view class="cli-actions-top">
               <view v-if="it.type === 'TEXT'" class="cli-btn" @tap.stop="copy(it.text)" title="复制">
                 <text class="icon">⧉</text>
               </view>
               <!-- Text Insert -->
               <view v-if="it.type === 'TEXT'" class="cli-btn" @tap.stop="$emit('insert', { type: 'TEXT', content: it.text })" title="插入">
                 <text class="icon">⚡</text>
               </view>
               <!-- Image Insert -->
               <view v-if="it.type === 'IMAGE'" class="cli-btn" @tap.stop="$emit('insert', { type: 'IMAGE', content: getImageUrl(it) })" title="插入">
                 <text class="icon">⚡</text>
               </view>
               <view class="del-wrapper" style="position: relative;">
                 <view class="cli-btn danger" @tap.stop="requestDelete(it.id)" title="删除">
                   <text class="icon">×</text>
                 </view>
                 <!-- Inline Confirm Popup -->
                 <view v-if="confirmDeleteId === it.id" class="delete-popover" @tap.stop>
                   <view class="pop-arrow"></view>
                   <text class="pop-text">确认删除?</text>
                   <view class="pop-row">
                     <view class="pop-btn" @tap.stop="cancelDelete">取消</view>
                     <view class="pop-btn danger" @tap.stop="confirmDelete(it.id)">确定</view>
                   </view>
                 </view>
               </view>
            </view>
          </view>
          
          <!-- Content: Horizontal Scroll -->
          <view class="card-content">
            <text v-if="it.type === 'TEXT'" class="content-text">{{ it.text }}</text>
            <image v-else-if="it.type === 'IMAGE'" class="content-image" :src="getImageUrl(it)" mode="aspectFill" @click.stop="previewImage(it)"></image>
            <view v-else class="content-file">
               <text class="file-icon">📄</text>
               <text class="file-name">{{ it.fileName || '未知文件' }}</text>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
/* ... script keeps same logic ... */
import { listClipboard, deleteClipboardItem, getApiBaseUrl } from '@/services/api.js'
import { getClipboardTypeMeta } from '@/config/clipboard.js'
import { getSessionId } from '@/utils/auth.js'

export default {
  name: 'ClipboardPanel',
  /* ... props/data/methods same ... */
  props: {
    query: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      loading: false,
      items: [],
      confirmDeleteId: null
    }
  },
  mounted() {
    this.refresh()
  },
  watch: {
    query() {
      this.refresh()
    }
  },
  methods: {
    getTypeMeta(type) {
      return getClipboardTypeMeta(type)
    },
    formatTypeLabel(type) {
      return (getClipboardTypeMeta(type)?.label || String(type || ''))
    },
    async refresh() {
      this.loading = true
      try {
        const list = await listClipboard(this.query, 80)
        this.items = Array.isArray(list) ? list : (list?.data || [])
      } catch (e) {
        console.error('加载剪贴板失败:', e)
        uni.showToast({ title: '加载剪贴板失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    preview(it) {
      if (it.type === 'TEXT') {
        const t = (it.text || '').trim()
        /* No truncation here if we want scroll, pass full text or reasonably long */
        return t
      }
      return it.meta || '未知内容'
    },
    formatTime(v) {
      if (!v) return ''
      try {
        const d = new Date(v)
        if (Number.isNaN(d.getTime())) return ''
        const Y = d.getFullYear()
        const M = String(d.getMonth() + 1).padStart(2, '0')
        const D = String(d.getDate()).padStart(2, '0')
        const h = String(d.getHours()).padStart(2, '0')
        const m = String(d.getMinutes()).padStart(2, '0')
        return `${Y}-${M}-${D} ${h}:${m}`
      } catch (e) {
        return ''
      }
    },
    async copy(text) {
      const t = (text || '').trim()
      if (!t) return
      // #ifdef H5
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(t)
        } else {
          uni.setClipboardData({ data: t })
        }
        uni.showToast({ title: '已复制', icon: 'success' })
      } catch (e) {
        uni.setClipboardData({ data: t })
      }
      // #endif
      // #ifndef H5
      uni.setClipboardData({ data: t })
      // #endif
    },
    requestDelete(id) {
      if (this.confirmDeleteId === id) {
        this.confirmDeleteId = null
        return
      }
      this.confirmDeleteId = id
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
      this._deleteTimer = setTimeout(() => {
        if (this.confirmDeleteId === id) {
          this.confirmDeleteId = null
        }
      }, 5000)
    },
    
    cancelDelete() {
      this.confirmDeleteId = null
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
    },

    async confirmDelete(id) {
      this.cancelDelete()
      try {
        await deleteClipboardItem(id)
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: '删除失败', icon: 'none' })
      }
    },
    getImageUrl(it) {
      if (it.url) return it.url // backward compat or if set
      if (it.type === 'IMAGE' || it.type === 'FILE') { // FILE might be image too? No, separated.
          // Use /api/clipboard/{id}/file?token=...
           // Since we don't have global baseUrl in component easily without import:
           // But actually relative path works in H5 if proxied. 
           // In App/Electron, we might need full URL.
           // However, let's use a method to get full URL or rely on relative.
           // If we are in Electron shell, relative path might not work if page is file://?
           // Actually page is http://localhost...
           
           // Hack: construct URL.
           // Better: import getApiBaseUrl from api.js. 
           // But let's assume relative path works for now or use /api prefix.
           // Wait, we imported getSessionId.
           const token = getSessionId()
           // Use import { getApiBaseUrl } ... I didn't import it.
           // Let's assume /api/clipboard works (proxied). If not, images won't load in dev.
           // In production, it's same origin.
           // In Electron Dev, it's http://localhost:5173 vs http://localhost:9696.
           // API requests in `api.js` handle base URL.
           // `img src` needs full URL if cross-origin.
           // Let's import getApiBaseUrl.
           return this.getApiUrl(`/api/clipboard/${it.id}/file?token=${token}`)
      }
      return ''
    },
    // Helper to get full API URL
    getApiUrl(path) {
        const base = getApiBaseUrl()
        // Simple concat, assuming path starts with /
        // If base ends with /, remove it? usually base is origin.
        if (base) {
            return `${base}${path}`
        }
        return path
    },
    previewImage(it) {
        const url = this.getImageUrl(it)
        if (url) {
            // 桌面端 BrowserView 会盖住 uni.previewImage 的 DOM 蒙层，
            // 交给 project-overview 用统一守卫(desktopOverlayActive)的预览层展示
            this.$emit('preview-image', url)
        }
    }
  }
}
</script>

<style lang="scss" scoped>
/* Unified AI Workdeck Palette */
$color-primary: #1A5336;
$color-accent: #5BD197;
$color-accent-pale: #E6F9F0;
$color-text-main: #2C3338;
$color-text-light: #6C757D;
$color-border: #E9ECEF;
$bg-pale: #F8F9FA;
$bg-white: #FFFFFF;

.clip-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: $bg-pale;
}

.clip-body {
  flex: 1;
  min-height: 0;
  padding: 16px;
}

/* Horizontal Scroll Layout */
.list-grid {
  display: inline-flex;
  gap: 16px;
  height: 100%;
  align-items: stretch;
  padding: 4px; /* Fix border clipping */
}

.clip-card {
  background: $bg-white;
  border: 1px solid $color-border;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 240px; /* Fixed width */
  flex-shrink: 0;
  transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);
  position: relative;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  /* Fixed Height */
  height: 130px; 
  cursor: default;
  overflow: hidden;
}

.clip-card:hover {
  border-color: $color-accent;
  box-shadow: 0 8px 16px rgba(91, 209, 151, 0.12);
  transform: translateY(-2px);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  flex-shrink: 0;
}

.header-left {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.type-line {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 20px;
}

.badge-text {
  font-size: 12px;
  font-weight: 600;
  color: $color-text-main;
}

.clip-source {
  font-size: 11px;
  color: $color-text-light;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-label {
  font-size: 11px;
  color: #9aa5b1;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Actions Top Right - Horizontal */
.cli-actions-top {
  display: flex;
  flex-direction: row;
  gap: 4px;
  flex-shrink: 0;
  align-items: flex-start;
}

.cli-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
  padding: 0;
  
  .icon {
    font-size: 13px;
    color: $color-text-light;
  }

  &:hover {
    background: $color-accent-pale;
    border-color: transparent;
    .icon { color: $color-primary; }
  }
}

.cli-btn.danger:hover {
  background: #FEF2F2;
  .icon { color: #DC2626; }
}

.card-content {
  flex: 1;
  min-height: 0;
  background: #f1f5f9;
  border-radius: 6px;
  padding: 8px;
  overflow: hidden;
  position: relative;
  display: block; /* Reset flex */
}

/* Content Types */
.content-text {
  font-size: 12px;
  color: $color-text-main;
  line-height: 1.5;
  
  /* Top-Left No Scroll Ellipsis */
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3; 
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: normal;
  word-break: break-all;
}

.content-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 4px;
}

.content-file {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 100%;
}

.file-icon {
  font-size: 24px;
  flex-shrink: 0;
}
/* Inline Delete Popover */
.delete-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  background: #fff;
  border: 1px solid $color-border;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  padding: 8px;
  z-index: 100;
  min-width: 120px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: fadeIn 0.1s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.pop-arrow {
  position: absolute;
  top: -4px;
  right: 10px;
  width: 8px;
  height: 8px;
  background: #fff;
  border-top: 1px solid $color-border;
  border-left: 1px solid $color-border;
  transform: rotate(45deg);
}

.pop-text {
  font-size: 12px;
  color: $color-text-main;
  text-align: center;
  font-weight: 500;
  display: block;
}

.pop-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.pop-btn {
  flex: 1;
  font-size: 11px;
  padding: 4px 0;
  text-align: center;
  border-radius: 4px;
  cursor: pointer;
  background: $bg-pale;
  color: $color-text-light;
  transition: all 0.2s;
  
  &:hover {
    background: #e2e8f0;
    color: $color-text-main;
  }
}

.pop-btn.danger {
  background: #FEF2F2;
  color: #DC2626;
  
  &:hover {
    background: #FEE2E2;
  }
}

.file-name {
  font-size: 12px;
  color: $color-text-main;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

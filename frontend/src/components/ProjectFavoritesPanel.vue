<template>
  <view class="favorites-panel">
    <scroll-view class="favorites-body" scroll-x :show-scrollbar="true" :scroll-into-view="scrollIntoView" scroll-with-animation>
      <view v-if="loading" class="loading">
        <text class="loading-text">{{ $t('panels.pfLoading') }}</text>
      </view>
      <view v-else-if="items.length === 0" class="empty">
        <text class="empty-text">{{ $t('panels.pfEmpty') }}</text>
      </view>
      <view v-else class="list-grid">
        <view v-for="fav in items" :key="fav.id" class="fav-card" :id="getCardDomId(fav.id)" :class="{ 'card--highlight': highlightId === fav.id }">

          <!-- New Header Structure -->
          <view class="card-header">
             <view class="header-left">
               <view class="type-badge" :class="getTypeClass(fav)">{{ getTypeLabel(fav) }}</view>
               <text class="card-time">{{ formatTime(fav.createdAt) }}</text>
             </view>
             <view class="header-right">
                <view v-if="fav.sourceUrl" class="favo-btn" @tap.stop="openUrl(fav.sourceUrl)" :title="$t('panels.pfOpenNewTabTitle')">
                  <svg class="icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, gi) in ICONS.link" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </view>
                <!-- Insert Button -->
                <view class="favo-btn" @tap.stop="insertFav(fav)" :title="$t('panels.pfInsertTitle')">
                  <svg class="icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.bolt" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </view>
                <!-- Delete -->
                <view class="del-wrapper" style="position: relative;">
                  <view class="favo-btn danger" @tap.stop="requestDelete(fav.id)" :title="$t('panels.pfDeleteTitle')">
                    <text class="icon">×</text>
                  </view>
                  <view v-if="confirmDeleteId === fav.id" class="delete-popover" @tap.stop>
                    <view class="pop-arrow"></view>
                    <text class="pop-text">{{ $t('panels.pfConfirmDeleteText') }}</text>
                    <view class="pop-row">
                      <view class="pop-btn" @tap.stop="cancelDelete">{{ $t('panels.pfCancel') }}</view>
                      <view class="pop-btn danger" @tap.stop="confirmDelete(fav.id)">{{ $t('panels.pfConfirm') }}</view>
                    </view>
                  </view>
                </view>
             </view>
          </view>

          <!-- Content Body -->
          <view class="card-body">
             <!-- Prominent Image -->
             <view v-if="fav.imagePath" class="card-cover">
               <image class="cover-img" :src="getFavoriteImageUrl(fav.id)" mode="aspectFill" :lazy-load="true" />
             </view>
             <!-- Or Text Preview -->
             <view v-else class="text-preview">
                <text class="content-text">{{ fav.content || fav.title || $t('panels.pfNoContent') }}</text>
             </view>
             <!-- Source Host (if web) -->
             <view v-if="fav.sourceHost" class="source-host">{{ fav.sourceHost }}</view>
          </view>

        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
import { getProjectFavorites, deleteFavorite, getFavoriteImageUrl } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { isDesktopHost } from '@/services/host.js'

export default {

  computed: {

    ICONS() { return ICONS }

  },
  name: 'ProjectFavoritesPanel',
  props: {
    projectId: {
      type: [Number, String],
      required: true
    },
    query: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      loading: false,
      items: [],
      scrollIntoView: '',
      highlightId: null,
      _lastRefreshAt: 0,
      confirmDeleteId: null
    }
  },
  watch: {
    projectId: {
      immediate: true,
      handler() {
        this.refresh()
      }
    },
    query() {
      this.refresh()
    }
  },
  methods: {
    getTypeLabel(fav) {
      if (fav.sourceUrl) return this.$t('panels.pfTypeWeb')
      if (fav.imagePath) return this.$t('panels.pfTypeImage')
      return this.$t('panels.pfTypeText')
    },
    getTypeClass(fav) {
      if (fav.sourceUrl) return 'type-web'
      if (fav.imagePath) return 'type-image'
      return 'type-text'
    },
    insertFav(fav) {
      // 图片收藏（网页摘录截图）content 为空串，按纯文本 emit 会被上层静默丢弃
      const text = (fav.content || '').trim()
      if (fav.imagePath && !text) {
        this.$emit('insert', { type: 'IMAGE', content: getFavoriteImageUrl(fav.id) })
        return
      }
      this.$emit('insert', { type: 'TEXT', content: fav.content })
    },
    openUrl(url) {
      if (!url) return
      // #ifdef H5
      // 桌面端（Electron，同为 H5 构建）：window.open 会被主进程拦截后丢弃，
      // 必须走 emit 让工作区开网页 tab
      if (isDesktopHost()) {
        this.$emit('open-url', url)
        return
      }
      window.open(url, '_blank')
      // #endif
      // #ifndef H5
      this.$emit('open-url', url)
      // #endif
    },
    getCardDomId(id) {
      return `fav-${id}`
    },
    focusFavorite(id) {
      if (!id) return
      if (this._highlightTimer) clearTimeout(this._highlightTimer)
      this.scrollIntoView = this.getCardDomId(id)
      this.highlightId = id
      this._highlightTimer = setTimeout(() => {
        if (this.highlightId === id) this.highlightId = null
        this._highlightTimer = null
      }, 1800)
    },
    getFavoriteImageUrl(id) {
      return getFavoriteImageUrl(id)
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
        return String(v)
      }
    },
    async refresh() {
      const now = Date.now()
      // query 变化时绕过时间节流（否则搜索框改了结果却不刷新、停留在旧关键字）；仅对相同 query 的高频刷新节流
      if (this._lastRefreshAt && now - this._lastRefreshAt < 1200 && this.query === this._lastRefreshQuery) return
      this._lastRefreshAt = now
      this._lastRefreshQuery = this.query
      this.loading = true
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const list = await getProjectFavorites(pid, this.query, 80)
        this.items = Array.isArray(list) ? list : (list?.data || [])
      } catch (e) {
        console.error('加载项目收藏失败:', e)
        uni.showToast({ title: this.$t('panels.pfLoadFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
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
        await deleteFavorite(id)
        await this.refresh()
        uni.showToast({ title: this.$t('panels.pfDeleteSuccess'), icon: 'success' })
      } catch (e) {
        console.error('删除收藏失败:', e)
        uni.showToast({ title: this.$t('panels.pfDeleteFailed'), icon: 'none' })
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

.favorites-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: $bg-pale;
}

.favorites-body {
  flex: 1;
  height: 100%;
  padding: 16px;
}

.list-grid {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  gap: 16px;
  align-items: stretch;
  padding: 4px 4px 24px 4px; 
}

.fav-card {
  background: $bg-white;
  border: 1px solid $color-border;
  border-radius: 8px;
  padding: 0; 
  display: flex;
  flex-direction: column;
  transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);
  position: relative;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  overflow: hidden;
  width: 260px; /* Fixed width for horizontal scrolling */
  flex-shrink: 0;
  height: 180px; 
}

.fav-card:hover {
  border-color: $color-accent;
  box-shadow: 0 8px 24px rgba(91, 209, 151, 0.15);
  transform: translateY(-2px);
}

.fav-card.card--highlight {
  border-color: $color-accent;
  box-shadow: 0 0 0 2px rgba(91, 209, 151, 0.3);
}

.card-header {
  padding: 10px 12px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  border-bottom: 1px solid #F1F5F9; 
  background: #fff;
  z-index: 2; /* Ensure header stays above content if needed */
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.type-badge {
  font-size: 12px;
  font-weight: 600;
  display: inline-block;
}

.type-web { color: #10B981; } /* Emerald */
.type-image { color: #F59E0B; } /* Amber */
.type-text { color: #64748B; } /* Slate */

.card-time {
  font-size: 11px;
  color: #94A3B8;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.header-right {
  display: flex;
  gap: 4px;
}

.card-body {
  flex: 1;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.card-cover {
  width: 100%;
  height: 100%;
  background: #f1f5f9;
}

.cover-img {
  width: 100%;
  height: 100%;
  display: block;
}

.text-preview {
  padding: 12px;
  flex: 1;
  overflow: hidden;
}

.content-text {
  font-size: 13px;
  color: $color-text-main;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 5;
  line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.source-host {
  position: absolute;
  bottom: 8px;
  right: 8px;
  background: rgba(255,255,255,0.9);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: $color-text-light;
  max-width: 80%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  box-shadow: 0 2px 4px rgba(0,0,0,0.05);
}

.favo-btn {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: transparent;
  border: 1px solid transparent;
  color: $color-text-light;
  cursor: pointer;
  transition: all 0.2s;
  
  .icon {

  width: 14px;
  height: 14px;
  flex-shrink: 0;
  }

  &:hover {
    background: $color-accent-pale;
    color: $color-primary;
  }
}

.favo-btn.danger:hover {
  background: #FEF2F2;
  color: #DC2626;
}

.loading, .empty {
  padding: 20px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
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
  right: 8px; /* Slightly adjusted to align with small button */
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
</style>

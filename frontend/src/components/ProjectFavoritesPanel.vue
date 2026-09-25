<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="favorites-panel">
    <scroll-view class="favorites-body" scroll-x :show-scrollbar="true" :scroll-into-view="scrollIntoView" scroll-with-animation>
      <view v-if="loading" class="loading">
        <text class="loading-text">{{ $t('panels.pfLoading') }}</text>
      </view>
      <!-- BUG-68：改成与文档主区域一致的图标+双行文案空态（见 project-overview.scss 的
           .empty-workspace/.empty-logo-tile 范式），不再是一行纯文字。 -->
      <view v-else-if="items.length === 0" class="empty">
        <view class="empty-logo-tile">
          <image src="/static/iconmark_v2.png" class="empty-state-img" mode="aspectFit" />
        </view>
        <text class="empty-title">{{ $t('panels.pfEmpty') }}</text>
        <text class="empty-sub">{{ $t('panels.pfEmptyHint') }}</text>
      </view>
      <view v-else class="list-grid">
        <view v-for="fav in items" :key="fav.id" class="fav-card" :id="getCardDomId(fav.id)" :class="{ 'card--highlight': highlightId === fav.id }">

          <!-- New Header Structure -->
          <view class="card-header">
             <view class="header-left">
               <view class="type-badge" :class="getTypeClass(fav)">{{ getTypeLabel(fav) }}</view>
               <text class="card-time">{{ formatTime(fav.createdAt) }}</text>
             </view>
             <!-- BUG-67：与 ClipboardPanel 统一为「插入到文档 / 复制 / 删除」的固定顺序，
                  避免两处卡片同样三图标布局但位置语义不同、容易凭记忆点错。
                  「新标签页打开」只在网页收藏上出现，放在统一三件套之后，不占用前三位。 -->
             <view class="header-right">
                <!-- Insert Button -->
                <view class="favo-btn" @tap.stop="insertFav(fav)" :title="$t('panels.pfInsertTitle')">
                  <svg class="icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.bolt" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </view>
                <!-- Copy -->
                <view class="favo-btn" @tap.stop="copyFav(fav)" :title="$t('panels.pfCopyTitle')">
                  <text class="icon">⧉</text>
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
                <view v-if="fav.sourceUrl" class="favo-btn" @tap.stop="openUrl(fav.sourceUrl)" :title="$t('panels.pfOpenNewTabTitle')">
                  <svg class="icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, gi) in ICONS.link" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
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
import { shouldAcceptResponse } from '@/utils/requestGeneration.js'
import { favoriteKind } from '@/utils/personalCollections.js'

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
      _refreshSeq: 0,
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
    // 类型判定与设置页「全部收藏」共用 utils/personalCollections.js 的 favoriteKind
    getTypeLabel(fav) {
      const kind = favoriteKind(fav)
      if (kind === 'web') return this.$t('panels.pfTypeWeb')
      if (kind === 'image') return this.$t('panels.pfTypeImage')
      return this.$t('panels.pfTypeText')
    },
    getTypeClass(fav) {
      return 'type-' + favoriteKind(fav)
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
    // BUG-67：与 ClipboardPanel.copy() 同样的写剪贴板方式——网页收藏复制来源链接，
    // 其它收藏复制正文内容，两者都没有就什么都不做。
    async copyFav(fav) {
      const t = (fav.sourceUrl || fav.content || fav.title || '').trim()
      if (!t) return
      // #ifdef H5
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(t)
        } else {
          uni.setClipboardData({ data: t })
        }
        uni.showToast({ title: this.$t('panels.pfCopied'), icon: 'success' })
      } catch (e) {
        uni.setClipboardData({ data: t })
      }
      // #endif
      // #ifndef H5
      uni.setClipboardData({ data: t })
      // #endif
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
    async refresh(force = false) {
      const now = Date.now()
      // query 变化时绕过时间节流（否则搜索框改了结果却不刷新、停留在旧关键字）；仅对相同 query 的高频刷新节流。
      // force：刚新增了收藏、必须立刻把新卡片刷出来（高亮定位依赖它在列表里）
      if (!force && this._lastRefreshAt && now - this._lastRefreshAt < 1200 && this.query === this._lastRefreshQuery) return
      this._lastRefreshAt = now
      this._lastRefreshQuery = this.query
      // query 绑的是父级搜索框、没有去抖，每敲一下键就发一次请求；不同关键字的
      // 响应到达顺序不保证跟敲键顺序一致。先敲的（陈旧）关键字若后回，会把
      // 已经渲染好的最新搜索结果盖掉。只认"此刻最新一次"发出的那份。
      const seq = ++this._refreshSeq
      this.loading = true
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const list = await getProjectFavorites(pid, this.query, 80)
        if (!shouldAcceptResponse(seq, this._refreshSeq)) return
        this.items = Array.isArray(list) ? list : (list?.data || [])
      } catch (e) {
        if (!shouldAcceptResponse(seq, this._refreshSeq)) return
        console.error('加载项目收藏失败:', e)
        uni.showToast({ title: this.$t('panels.pfLoadFailed'), icon: 'none' })
      } finally {
        if (shouldAcceptResponse(seq, this._refreshSeq)) this.loading = false
      }
    },
    requestDelete(id) {
      if (this.confirmDeleteId === id) {
        this.confirmDeleteId = null
        return
      }
      // 确认态不自动收起（同 ClipboardPanel，dev-board#455 / BUG-63）：超时收起后用户点「确定」
      // 点到的是气泡底下的卡片。取消靠再点一次 ×、点「取消」或点另一张卡片的 ×。
      this.confirmDeleteId = id
    },

    cancelDelete() {
      this.confirmDeleteId = null
    },

    async confirmDelete(id) {
      this.cancelDelete()
      try {
        await deleteFavorite(id)
        // 必须 force：refresh() 默认带 1.2s 节流，删除后的刷新落在节流窗口内会被整个
        // 吞掉——列表不更新但成功提示照弹，用户再点一次删除时后端已无此 id，
        // 弹出的失败提示与刚才的成功提示直接矛盾。
        await this.refresh(true)
        // 浏览器面板的「收藏本页」星形靠它重拉，否则删完仍显示已收藏（BUG-60）
        uni.$emit('awd:favorites-changed', { deletedId: id })
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
/* Unified AI WorkDeck Palette */

.favorites-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--awd-bg);
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
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
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
  border-color: var(--awd-mint);
  box-shadow: 0 8px 24px rgba(137, 168, 160, 0.15);
  transform: translateY(-2px);
}

.fav-card.card--highlight {
  border-color: var(--awd-mint);
  box-shadow: 0 0 0 2px rgba(137, 168, 160, 0.3);
}

.card-header {
  padding: 10px 12px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  border-bottom: 1px solid var(--awd-border-subtle); 
  background: var(--awd-surface);
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

.type-web { color: var(--awd-accent-text); } /* Emerald */
.type-image { color: var(--awd-warning-text); } /* Amber */
.type-text { color: var(--awd-text-2); } /* Slate */

.card-time {
  font-size: 11px;
  color: var(--awd-text-3);
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
  background: var(--awd-surface-2);
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
  color: var(--awd-text);
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
  background: var(--awd-surface);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: var(--awd-text-2);
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
  color: var(--awd-text-2);
  cursor: pointer;
  transition: all 0.2s;
  
  .icon {

  width: 14px;
  height: 14px;
  flex-shrink: 0;
  }

  &:hover {
    background: var(--awd-accent-soft);
    color: var(--awd-accent-text);
  }
}

.favo-btn.danger:hover {
  background: var(--awd-danger-soft);
  color: var(--awd-danger-text);
}

.loading {
  padding: 20px;
  text-align: center;
  color: var(--awd-text-3);
  font-size: 13px;
}

/* 空态（BUG-68）：与文档主区域 .empty-workspace 同一套图标+双行文案范式，
   贴着抽屉尺寸缩小一档。 */
.empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.empty-logo-tile {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 18px;
  box-shadow: 0 1px 2px rgba(33, 38, 41, 0.04), 0 10px 24px rgba(46, 90, 80, 0.08);
}

.empty-state-img {
  width: 32px;
  height: 32px;
}

.empty-title {
  margin-top: 16px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--awd-text);
}

.empty-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--awd-text-2);
}

/* Inline Delete Popover */
.delete-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
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
  background: var(--awd-surface);
  border-top: 1px solid var(--awd-border);
  border-left: 1px solid var(--awd-border);
  transform: rotate(45deg);
}

.pop-text {
  font-size: 12px;
  color: var(--awd-text);
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
  background: var(--awd-bg);
  color: var(--awd-text-2);
  transition: all 0.2s;
  
  &:hover {
    background: var(--awd-surface-3);
    color: var(--awd-text);
  }
}

.pop-btn.danger {
  background: var(--awd-danger-soft);
  color: var(--awd-danger-text);
  
  &:hover {
    background: var(--awd-danger-soft);
  }
}
</style>

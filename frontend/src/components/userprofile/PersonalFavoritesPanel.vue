<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「全部收藏」栏目（设置页「个人」组）。2026-08-20 从个人中心搬来，dev-board#872 重做：
  - 与工作台左栏收藏夹（ProjectFavoritesPanel，只看当前项目、可插入文档）的区别，
    在标题下用一句话说清楚——这里是跨项目的总览，按项目分组。
  - 数据源 GET /api/favorites/my，每条带 projectId + projectName（后端一次批量补齐），
    前端不再为每条收藏单独拉项目名。搜索是本地过滤（utils/personalCollections.js）。
  - 删除用就地确认气泡（同左栏收藏夹），不弹 uni.showModal。
  - 「打开项目」会离开设置页进工作台：宿主是工作台时走注入的 leaveWorkbench（先落盘再 reLaunch），
    否则直接 reLaunch（sidebar-shell.md「离开工作台前必须落盘」）。
  加载时机仍是本组件的 mounted——它只在这一栏被选中时渲染。
-->
<template>
  <view class="panel-favorites">
    <view class="pf-head">
      <view class="pf-head-text">
        <view class="pf-title-row">
          <text class="pf-title">{{ $t('account.tabFavorites') }}</text>
          <text v-if="favorites.length" class="pf-count">{{ favorites.length }}</text>
        </view>
        <text class="pf-subtitle">{{ $t('account.favoritesSubtitle') }}</text>
      </view>
      <view v-if="favorites.length" class="pf-search">
        <svg class="pf-search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.search" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
        <input class="pf-search-input" v-model="query" :placeholder="$t('account.favoritesSearchPlaceholder')" />
      </view>
    </view>

    <view v-if="favoritesLoading && !favorites.length" class="pf-state">
      <text class="pf-state-text">{{ $t('account.loadingEllipsis') }}</text>
    </view>
    <view v-else-if="!favorites.length" class="pf-empty">
      <view class="pf-empty-icon">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.star" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
      <text class="pf-empty-title">{{ $t('account.emptyFavoritesDesc') }}</text>
      <text class="pf-empty-desc">{{ $t('account.emptyFavoritesHow') }}</text>
    </view>
    <view v-else-if="!groups.length" class="pf-state">
      <text class="pf-state-text">{{ $t('account.favoritesNoMatch') }}</text>
    </view>

    <view v-else class="pf-groups">
      <view v-for="group in groups" :key="group.key" class="pf-group">
        <view class="pf-group-head">
          <svg class="pf-group-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.folder" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <text class="pf-group-name">{{ group.projectName || $t('account.favoritesNoProject') }}</text>
          <text class="pf-group-count">{{ group.items.length }}</text>
          <view class="pf-spacer"></view>
          <view v-if="group.projectName" class="pf-link-btn" @tap="openProject(group.projectId)">
            <text>{{ $t('account.openProjectAction') }}</text>
            <svg class="pf-link-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.arrowUpRight" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </view>
        </view>

        <view class="pf-grid">
          <view v-for="fav in group.items" :key="fav.id" class="pf-card">
            <view class="pf-card-top">
              <text class="pf-badge" :class="'kind-' + kindOf(fav)">{{ kindLabel(fav) }}</text>
              <text class="pf-date">{{ formatTime(fav.createdAt) }}</text>
              <view class="pf-spacer"></view>
              <view v-if="fav.sourceUrl" class="pf-icon-btn" :title="$t('account.openSourceAction')" @tap.stop="openSource(fav)">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.link" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <view v-if="fav.content || fav.sourceUrl" class="pf-icon-btn" :title="$t('account.copyContentAction')" @tap.stop="copyFavorite(fav)">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.copyDoc" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <view class="pf-del-wrap">
                <view class="pf-icon-btn danger" :title="$t('common.delete')" @tap.stop="requestDelete(fav.id)">
                  <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </view>
                <view v-if="confirmDeleteId === fav.id" class="pf-popover" @tap.stop>
                  <text class="pf-pop-text">{{ $t('account.deleteFavoriteContent') }}</text>
                  <view class="pf-pop-row">
                    <view class="pf-pop-btn" @tap.stop="cancelDelete">{{ $t('common.cancel') }}</view>
                    <view class="pf-pop-btn danger" @tap.stop="handleDeleteFavorite(fav.id)">{{ $t('common.delete') }}</view>
                  </view>
                </view>
              </view>
            </view>

            <view v-if="fav.imagePath" class="pf-cover">
              <image class="pf-cover-img" mode="aspectFill" :lazy-load="true" :src="getFavoriteImageUrl(fav.id)" />
            </view>
            <view v-else-if="fav.content" class="pf-preview">
              <text class="pf-preview-text">{{ fav.content }}</text>
            </view>

            <view class="pf-card-foot">
              <text class="pf-card-title">{{ fav.title || hostOf(fav) || $t('account.untitledExcerpt') }}</text>
              <text v-if="hostOf(fav)" class="pf-host">{{ hostOf(fav) }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getMyFavorites, deleteFavorite, getFavoriteImageUrl } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import {
  favoriteKind,
  favoriteHost,
  filterFavorites,
  groupFavoritesByProject,
} from '@/utils/personalCollections.js'

export default {
  name: 'PersonalFavoritesPanel',
  // 工作台 provide 的离开出口（先落盘再 reLaunch）；设置页不在工作台里时为 null
  inject: { leaveWorkbench: { default: null } },
  data() {
    return {
      favoritesLoading: false,
      favorites: [],
      query: '',
      confirmDeleteId: null,
    }
  },
  computed: {
    ICONS() { return ICONS },
    groups() {
      return groupFavoritesByProject(filterFavorites(this.favorites, this.query))
    },
  },
  mounted() {
    this.loadFavorites()
  },
  beforeUnmount() {
    if (this._deleteTimer) clearTimeout(this._deleteTimer)
  },
  methods: {
    async loadFavorites() {
      this.favoritesLoading = true
      try {
        const list = await getMyFavorites()
        this.favorites = Array.isArray(list) ? list : (list?.data || [])
      } catch (e) {
        console.error('加载收藏失败:', e)
        uni.showToast({ title: this.$t('account.loadFavoritesFailed'), icon: 'none' })
      } finally {
        this.favoritesLoading = false
      }
    },
    getFavoriteImageUrl(id) {
      return getFavoriteImageUrl(id)
    },
    kindOf(fav) {
      return favoriteKind(fav)
    },
    kindLabel(fav) {
      const kind = favoriteKind(fav)
      if (kind === 'web') return this.$t('panels.pfTypeWeb')
      if (kind === 'image') return this.$t('panels.pfTypeImage')
      return this.$t('panels.pfTypeText')
    },
    hostOf(fav) {
      return favoriteHost(fav)
    },
    openSource(fav) {
      if (fav && fav.sourceUrl) openExternalUrl(fav.sourceUrl)
    },
    copyFavorite(fav) {
      const data = (fav && (fav.content || fav.sourceUrl)) || ''
      if (!data) return
      uni.setClipboardData({
        data,
        showToast: false,
        success: () => uni.showToast({ title: this.$t('account.copiedToast'), icon: 'none' }),
      })
    },
    openProject(projectId) {
      if (projectId == null) return
      const url = `/pages/project-overview/project-overview?id=${projectId}`
      if (this.leaveWorkbench) this.leaveWorkbench(url)
      else uni.reLaunch({ url })
    },
    requestDelete(id) {
      if (this.confirmDeleteId === id) {
        this.cancelDelete()
        return
      }
      this.confirmDeleteId = id
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
      // 五秒不点就自己收起，免得气泡一直挂着（同左栏收藏夹）
      this._deleteTimer = setTimeout(() => {
        if (this.confirmDeleteId === id) this.confirmDeleteId = null
      }, 5000)
    },
    cancelDelete() {
      this.confirmDeleteId = null
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
    },
    async handleDeleteFavorite(id) {
      this.cancelDelete()
      try {
        await deleteFavorite(id)
        this.favorites = this.favorites.filter((f) => f.id !== id)
        uni.showToast({ title: this.$t('account.deleteSuccessToast'), icon: 'success' })
      } catch (e) {
        console.error('删除收藏失败:', e)
        uni.showToast({ title: this.$t('account.deleteFailedToast'), icon: 'none' })
      }
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      const date = new Date(timeStr)
      if (Number.isNaN(date.getTime())) return String(timeStr)
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      return `${year}-${month}-${day}`
    },
  },
}
</script>

<style lang="scss" scoped>
.panel-favorites {
  background: var(--awd-surface);
  border-radius: 8px;
  border: 1px solid var(--awd-border);
  padding: 14px;
  box-sizing: border-box;
  width: 100%;
}

.pf-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.pf-head-text {
  flex: 1 1 320px;
  min-width: 0;
}

.pf-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pf-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.pf-count,
.pf-group-count {
  font-size: 11px;
  line-height: 18px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--awd-surface-2);
  color: var(--awd-text-2);
}

.pf-subtitle {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text-2);
}

.pf-search {
  flex: 0 1 280px;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-bg);
  box-sizing: border-box;

  &:focus-within {
    border-color: var(--awd-accent);
    background: var(--awd-surface);
  }
}

.pf-search-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--awd-text-3);
}

.pf-search-input {
  flex: 1;
  min-width: 0;
  height: 32px;
  font-size: 13px;
  color: var(--awd-text);
}

.pf-state {
  padding: 48px 0;
  text-align: center;
}

.pf-state-text {
  font-size: 13px;
  color: var(--awd-text-3);
}

.pf-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 56px 24px;
  text-align: center;
}

.pf-empty-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--awd-accent-wash);
  color: var(--awd-accent-text);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;

  svg {
    width: 26px;
    height: 26px;
  }
}

.pf-empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}

.pf-empty-desc {
  margin-top: 8px;
  max-width: 440px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-text-2);
}

.pf-group + .pf-group {
  margin-top: 28px;
}

.pf-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.pf-group-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  color: var(--awd-text-3);
}

.pf-group-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.pf-spacer {
  flex: 1;
}

.pf-link-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--awd-accent-text);
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;

  &:hover {
    background: var(--awd-accent-soft);
  }
}

.pf-link-icon {
  width: 13px;
  height: 13px;
}

.pf-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.pf-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  padding: 10px 12px 12px;
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: var(--awd-mint);
    box-shadow: var(--awd-shadow-sm);
  }
}

.pf-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 26px;
}

.pf-badge {
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  padding: 0 7px;
  border-radius: 4px;
  flex-shrink: 0;

  &.kind-web { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
  &.kind-image { background: var(--awd-warning-soft); color: var(--awd-warning-text); }
  &.kind-text { background: var(--awd-surface-2); color: var(--awd-text-2); }
}

.pf-date {
  font-size: 11px;
  color: var(--awd-text-3);
  font-family: var(--awd-font-mono);
}

.pf-icon-btn {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--awd-text-3);
  cursor: pointer;
  flex-shrink: 0;

  svg {
    width: 14px;
    height: 14px;
  }

  &:hover {
    background: var(--awd-accent-soft);
    color: var(--awd-accent-text);
  }

  &.danger:hover {
    background: var(--awd-danger-soft);
    color: var(--awd-danger-text);
  }
}

.pf-del-wrap {
  position: relative;
}

.pf-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 6px;
  z-index: 20;
  min-width: 160px;
  padding: 10px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: var(--awd-shadow-md);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pf-pop-text {
  font-size: 12px;
  color: var(--awd-text);
  text-align: center;
}

.pf-pop-row {
  display: flex;
  gap: 8px;
}

.pf-pop-btn {
  flex: 1;
  font-size: 12px;
  padding: 4px 0;
  text-align: center;
  border-radius: 6px;
  cursor: pointer;
  background: var(--awd-bg);
  color: var(--awd-text-2);

  &:hover {
    background: var(--awd-surface-3);
    color: var(--awd-text);
  }

  &.danger {
    background: var(--awd-danger-soft);
    color: var(--awd-danger-text);
  }
}

.pf-cover {
  margin-top: 8px;
  height: 120px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--awd-surface-2);
  border: 1px solid var(--awd-border-subtle);
}

.pf-cover-img {
  width: 100%;
  height: 100%;
  display: block;
}

.pf-preview {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--awd-bg);
  border-left: 3px solid var(--awd-border-strong);
}

.pf-preview-text {
  font-size: 12px;
  line-height: 1.6;
  color: var(--awd-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

.pf-card-foot {
  margin-top: 10px;
  min-width: 0;
}

.pf-card-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pf-host {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--awd-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

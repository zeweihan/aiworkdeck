<!--
  「我的收藏」栏目。2026-08-20 从 components/userprofile/UserProfilePane.vue 整块搬出来
  （个人中心并进统一「设置」页的「个人」组）。内容一行没改，加载时机从
  switchTab('favorites') 换成本组件自己的 mounted——它只在这一栏被选中时渲染。
-->
<template>
  <view class="panel-favorites">
    <view v-if="favoritesLoading" class="loading">
      <text class="loading-text">{{ $t('account.loadingEllipsis') }}</text>
    </view>
    <view v-else-if="favorites.length === 0" class="empty-state">
      <view class="empty-icon-circle">
        <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.star" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
      <text class="empty-title">{{ $t('account.tabFavorites') }}</text>
      <text class="empty-desc">{{ $t('account.emptyFavoritesDesc') }}</text>
    </view>
    <view v-else class="favorites-list">
      <view v-for="fav in favorites" :key="fav.id" class="favorite-card">
        <view v-if="fav.imagePath" class="favorite-thumb">
          <image class="fav-img" mode="aspectFill" :src="getFavoriteImageUrl(fav.id)" />
        </view>
        <view class="favorite-body">
          <view class="favorite-header">
            <text class="favorite-title">{{ fav.title || (fav.sourceUrl ? fav.sourceUrl : $t('account.untitledExcerpt')) }}</text>
            <button class="btn-danger-outline small" @tap.stop="handleDeleteFavorite(fav.id)">{{ $t('common.delete') }}</button>
          </view>
          <view v-if="fav.sourceUrl" class="favorite-url">
            <text class="url-text">{{ fav.sourceUrl }}</text>
          </view>
          <view v-if="fav.content" class="favorite-content">
            <text class="content-text">{{ fav.content }}</text>
          </view>
          <view class="favorite-footer">
            <text class="time-text">{{ formatTime(fav.createdAt) }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getMyFavorites, deleteFavorite, getFavoriteImageUrl } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'

export default {
  name: 'PersonalFavoritesPanel',
  computed: {
    ICONS() { return ICONS },
  },
  data() {
    return {
      favoritesLoading: false,
      favorites: [],
    }
  },
  mounted() {
    this.loadFavorites()
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
    async handleDeleteFavorite(id) {
      uni.showModal({
        title: this.$t('account.deleteFavoriteTitle'),
        content: this.$t('account.deleteFavoriteContent'),
        cancelText: this.$t('common.cancel'),
        confirmText: this.$t('account.confirmBtn'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteFavorite(id)
            await this.loadFavorites()
            uni.showToast({ title: this.$t('account.deleteSuccessToast'), icon: 'success' })
          } catch (e) {
            console.error('删除收藏失败:', e)
            uni.showToast({ title: this.$t('account.deleteFailedToast'), icon: 'none' })
          }
        },
      })
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },
  },
}
</script>

<style lang="scss" scoped>
$brand-white: #FFFFFF;
$text-main: #2C3338;
$text-secondary: #6C757D;
$text-light: #ADB5BD;

.panel-favorites {
  width: 100%;
}

.favorites-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 10px;
}

// 2026-08-21 卡片压扁：原先单列纵排、截图 widthFix 撑满设置页整宽，一张卡一屏高。
// 现在自适应网格 + 左侧固定缩略图 + 正文两行截断，宽屏一行放两三张。
.favorite-card {
  display: flex;
  gap: 10px;
  background: $brand-white;
  border-radius: 8px;
  border: 1px solid rgba(224, 224, 224, 0.7);
  padding: 10px 12px;
  min-width: 0;
}

.favorite-thumb {
  flex-shrink: 0;
  width: 88px;
  height: 66px;
}

.fav-img {
  width: 88px;
  height: 66px;
  border-radius: 6px;
  border: 1px solid rgba(224, 224, 224, 0.7);
  display: block;
}

.favorite-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.favorite-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.favorite-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-main;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.favorite-url {
  margin-top: 2px;
}

.url-text {
  font-size: 11px;
  color: $text-light;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.favorite-content {
  margin-top: 4px;
}

.content-text {
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

.favorite-footer {
  margin-top: auto;
  padding-top: 4px;
  display: flex;
  justify-content: flex-end;
}

.time-text {
  font-size: 11px;
  color: $text-light;
}

.empty-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  color: #C9D4CE;
}
</style>

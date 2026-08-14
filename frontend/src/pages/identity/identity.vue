<template>
  <!-- 本机工作区选择：仅当本机有多个都带数据的历史账号时出现一次，选完即持久化 -->
  <view class="identity-page">
    <view class="identity-card">
      <image class="identity-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      <text class="identity-title">{{ $t('onboarding.identity.title') }}</text>
      <text class="identity-subtitle">{{ $t('onboarding.identity.subtitle') }}</text>

      <view v-if="loading" class="identity-hint">
        <text class="identity-hint-text">{{ $t('onboarding.identity.reading') }}</text>
      </view>

      <view v-else-if="errorMsg" class="identity-hint">
        <text class="identity-error">{{ errorMsg }}</text>
        <button class="identity-retry-btn" @tap="load">{{ $t('onboarding.identity.retry') }}</button>
      </view>

      <view v-else class="identity-list">
        <view
          v-for="item in candidates"
          :key="item.userId"
          class="identity-item"
          :class="{ 'is-active': selectedId === item.userId }"
          @tap="selectedId = item.userId"
        >
          <view class="identity-item-main">
            <text class="identity-name">{{ item.displayName || item.username }}</text>
            <text class="identity-username">{{ item.username }}</text>
          </view>
          <text class="identity-meta">{{ $t('onboarding.identity.itemMeta', { projects: item.projectCount, files: item.fileCount }) }}</text>
        </view>
      </view>

      <button
        v-if="!loading && !errorMsg"
        class="identity-btn"
        :disabled="!selectedId || submitting"
        @tap="confirm"
      >
        {{ submitting ? $t('onboarding.identity.entering') : $t('onboarding.identity.enter') }}
      </button>
      <text v-if="submitError" class="identity-error">{{ submitError }}</text>
    </view>
  </view>
</template>

<script>
import { getLocalIdentityCandidates, selectLocalIdentity } from '@/services/api.js'

export default {
  name: 'IdentityPage',
  data() {
    return {
      loading: true,
      candidates: [],
      // 默认预选数据量最大的那个（后端已按数据量降序），但仍要用户确认才算数
      selectedId: null,
      errorMsg: '',
      submitError: '',
      submitting: false,
    }
  },
  onLoad() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.errorMsg = ''
      try {
        const res = await getLocalIdentityCandidates()
        this.candidates = (res && res.candidates) || []
        if (!this.candidates.length) {
          // 理论上到不了这里（needsSelection 必然意味着至少两个候选）；
          // 真到了就别把用户困在空页面上，直接回启动链重新分流
          uni.reLaunch({ url: '/pages/launch/launch' })
          return
        }
        this.selectedId = this.candidates[0].userId
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.identity.loadFailed')
      } finally {
        this.loading = false
      }
    },
    async confirm() {
      if (!this.selectedId || this.submitting) return
      this.submitError = ''
      this.submitting = true
      try {
        await selectLocalIdentity(this.selectedId)
        uni.reLaunch({ url: '/pages/launch/launch' })
      } catch (e) {
        this.submitError = (e && e.message) || this.$t('onboarding.identity.selectFailed')
        this.submitting = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.identity-page {
  width: 100vw;
  height: 100vh;
  background: #f8f9fa;
  display: flex;
  align-items: center;
  justify-content: center;
}

.identity-card {
  width: 420px;
  max-height: 84vh;
  padding: 36px 32px 30px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.identity-logo {
  height: 32px;
  margin-bottom: 18px;
}

.identity-title {
  font-size: 17px;
  font-weight: 600;
  color: #0f172a;
}

.identity-subtitle {
  margin-top: 10px;
  font-size: 12px;
  line-height: 19px;
  color: #64748b;
  text-align: center;
}

.identity-hint {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.identity-hint-text {
  font-size: 13px;
  color: #94a3b8;
}

.identity-list {
  width: 100%;
  margin-top: 22px;
  max-height: 46vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.identity-item {
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #ffffff;
  cursor: pointer;

  &:hover {
    border-color: #cbd5e1;
    background: #f8fafc;
  }

  &.is-active {
    border-color: #1a5336;
    background: #f1f7f3;
  }
}

.identity-item-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.identity-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.identity-username {
  font-size: 12px;
  color: #94a3b8;
}

.identity-meta {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.identity-btn {
  width: 100%;
  height: 38px;
  line-height: 36px;
  margin-top: 20px;
  font-size: 13px;
  color: #ffffff;
  background: #1a5336;
  border: none;
  border-radius: 8px;
  cursor: pointer;

  &:hover {
    background: #14422b;
  }

  &[disabled] {
    background: #cbd5e1;
    cursor: default;
  }
}

.identity-retry-btn {
  height: 34px;
  line-height: 32px;
  padding: 0 24px;
  font-size: 13px;
  color: #1a5336;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  cursor: pointer;
}

.identity-error {
  margin-top: 12px;
  font-size: 12px;
  line-height: 18px;
  color: #dc2626;
  text-align: center;
}
</style>

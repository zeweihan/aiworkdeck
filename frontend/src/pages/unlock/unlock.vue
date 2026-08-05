<template>
  <!-- 解锁门：桌面首启的唯一关卡。浅色单卡片居中，试用码（离线）或账户 Key（在线）二选一 -->
  <view class="unlock-page">
    <view class="unlock-card">
      <image class="unlock-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      <text class="unlock-title">AI Workdeck</text>
      <text class="unlock-subtitle">面向法律工作者的 AI 工作台</text>

      <view class="unlock-form">
        <textarea
          class="unlock-input"
          v-model="code"
          placeholder="粘贴试用码或账户 Key"
          placeholder-class="unlock-placeholder"
          :maxlength="-1"
        />
        <!-- 注意：不要在 textarea 上挂 @input 清 errorMsg——uni-textarea 在错误文案渲染
             引发布局变化时会补发一次 input 事件，错误提示会被立刻清掉（联调实测）。
             errorMsg 在每次点击解锁时重置，足够。 -->
        <text v-if="errorMsg" class="unlock-error">{{ errorMsg }}</text>
        <button
          class="unlock-btn"
          :class="{ 'is-busy': unlocking }"
          :disabled="unlocking"
          @tap="handleUnlock"
        >
          {{ unlocking ? '正在解锁' : '解锁' }}
        </button>
      </view>

      <view class="unlock-links">
        <text class="unlock-link" @tap="openTrialCodePage">获取试用码</text>
        <text class="unlock-link-sep">|</text>
        <text class="unlock-link" @tap="openOfficialSite">获取正式版</text>
      </view>
    </view>
  </view>
</template>

<script>
import { activateLicense } from '@/services/api.js'
import { openExternalUrl } from '@/utils/externalLink.js'

const TRIAL_CODE_URL = 'https://github.com/zeweihan/aiworkdeck#readme'
const OFFICIAL_SITE_URL = 'https://www.aiworkdeck.com'

export default {
  name: 'UnlockPage',
  data() {
    return {
      code: '',
      errorMsg: '',
      unlocking: false,
    }
  },
  methods: {
    async handleUnlock() {
      // 自动去掉粘贴带进来的空白与换行
      const code = (this.code || '').replace(/\s+/g, '')
      if (!code) {
        this.errorMsg = '请先粘贴试用码或账户 Key'
        return
      }
      this.errorMsg = ''
      this.unlocking = true
      try {
        const res = await activateLicense(code)
        const mode = res && res.mode
        uni.showToast({
          title: mode === 'trial' ? '已解锁试用版' : '已连接账户，正式版已解锁',
          icon: 'success',
          duration: 1600,
        })
        setTimeout(() => {
          uni.reLaunch({ url: '/pages/launch/launch' })
        }, 800)
      } catch (e) {
        this.errorMsg = (e && e.message) || '解锁失败，请检查后重试'
      } finally {
        this.unlocking = false
      }
    },
    openTrialCodePage() {
      openExternalUrl(TRIAL_CODE_URL)
    },
    openOfficialSite() {
      openExternalUrl(OFFICIAL_SITE_URL)
    },
  },
}
</script>

<style lang="scss" scoped>
.unlock-page {
  width: 100vw;
  height: 100vh;
  background: #f8f9fa;
  display: flex;
  align-items: center;
  justify-content: center;
}

.unlock-card {
  width: 420px;
  max-width: calc(100vw - 48px);
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
  padding: 40px 36px 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.unlock-logo {
  height: 40px;
  margin-bottom: 20px;
}

.unlock-title {
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.5px;
}

.unlock-subtitle {
  margin-top: 8px;
  font-size: 13px;
  color: #64748b;
}

.unlock-form {
  width: 100%;
  margin-top: 28px;
  display: flex;
  flex-direction: column;
}

.unlock-input {
  width: 100%;
  height: 88px;
  box-sizing: border-box;
  padding: 12px 14px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #0f172a;
  background: #ffffff;
  font-family: 'SF Mono', Menlo, Consolas, monospace;

  &:focus {
    border-color: #1a5336;
    box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
  }
}

.unlock-placeholder {
  color: #94a3b8;
  font-size: 13px;
}

.unlock-error {
  margin-top: 10px;
  font-size: 12px;
  color: #dc2626;
}

.unlock-btn {
  margin-top: 18px;
  width: 100%;
  height: 44px;
  line-height: 44px;
  background: #1a5336;
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: #14422b;
  }

  &.is-busy {
    opacity: 0.7;
  }
}

.unlock-links {
  margin-top: 22px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.unlock-link {
  font-size: 12px;
  color: #1a5336;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.unlock-link-sep {
  font-size: 12px;
  color: #cbd5e1;
}
</style>

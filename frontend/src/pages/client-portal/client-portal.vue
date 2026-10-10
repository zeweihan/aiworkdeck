<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  客户门户（dev-board#1050）：案件库在 {server}/client/ 托管的一页，客户凭律师给的访问码进来。
  只在客户门户构建（npm run build:client-portal，条件编译 CLIENT_PORTAL）里注册；桌面端与
  普通 H5 构建里没有这一页。

  访问码经 fragment 预填：律师复制给客户的链接是 {server}/client/#code=<码>。fragment 不随请求
  发给服务器、不进 access log；main.js 在路由起来之前把它取走并用 replaceState 清掉
  （hash 路由会把 #code=... 当成一个不存在的路由），这里从 window.__AWD_PORTAL_CODE__ 拿。

  登录成功落项目列表（reLaunch，导航总规则：进工作台之前的落点一律 reLaunch），列表页与
  工作台都已有 CLIENT 视图（只见被分享的案卷、左栏只有尽调清单）。后端鉴权才是真闸：
  CLIENT 在案件库上文件树、git、尽调写端点一律被拒。
-->
<template>
  <view class="portal-page">
    <view class="portal-card">
      <view class="portal-head">
        <image class="portal-logo" src="/static/iconmark_v2.png" mode="heightFix" />
        <text class="portal-brand">AI WorkDeck</text>
      </view>
      <text class="portal-title">{{ $t('account.portalTitle') }}</text>
      <text class="portal-lead">{{ $t('account.portalLead') }}</text>

      <text class="portal-label">{{ $t('account.caseAccessCodeLabel') }}</text>
      <input
        class="portal-input"
        v-model="code"
        :placeholder="$t('account.caseAccessCodePlaceholder')"
        @confirm="enter"
      />
      <text v-if="errorMessage" class="portal-error">{{ errorMessage }}</text>
      <button class="portal-btn" :disabled="loading" :loading="loading" @tap="enter">{{ $t('account.enterCaseBtn') }}</button>

      <text class="portal-notice">
        {{ $t('account.portalSourceNotice') }}<text class="portal-notice-link" @tap="openSource">{{ SOURCE_URL }}</text>
      </text>
    </view>
  </view>
</template>

<script>
import { clientLogin } from '@/services/api.js'
import { saveSession, getSessionId, getCurrentUser } from '@/utils/auth.js'
import { codeFromHash } from '@/utils/memberLookup.js'

const SOURCE_URL = 'https://github.com/AI-WorkDeck/aiworkdeck'
const LIST_URL = '/pages/project-list/project-list'

export default {
  name: 'ClientPortal',
  data() {
    return {
      code: '',
      loading: false,
      errorMessage: '',
      SOURCE_URL,
    }
  },
  onLoad() {
    let prefill = ''
    try {
      prefill = (typeof window !== 'undefined' && window.__AWD_PORTAL_CODE__) || ''
      if (typeof window !== 'undefined') window.__AWD_PORTAL_CODE__ = ''
      // 兜底：main.js 没来得及取（非门户构建里直链进来）时自己读一次
      if (!prefill && typeof window !== 'undefined') prefill = codeFromHash(window.location.hash)
    } catch (e) {
      prefill = ''
    }
    if (prefill) {
      this.code = prefill
      return
    }
    // 回访：已是客户会话且没带新码，直接回案卷列表
    const user = getCurrentUser()
    if (getSessionId() && user && user.role === 'CLIENT') {
      uni.reLaunch({ url: LIST_URL })
    }
  },
  methods: {
    async enter() {
      if (this.loading) return
      const code = String(this.code || '').trim()
      if (!code) {
        this.errorMessage = this.$t('account.portalCodeRequired')
        return
      }
      this.loading = true
      this.errorMessage = ''
      try {
        const res = await clientLogin(code, null)
        if (res && res.code === 0 && res.data) {
          saveSession(res.data.sessionId, res.data.user)
          uni.reLaunch({ url: LIST_URL })
        } else {
          this.errorMessage = (res && res.message) || this.$t('account.loginFailedToast')
        }
      } catch (e) {
        this.errorMessage = (e && e.message) || this.$t('account.loginFailedToast')
      } finally {
        this.loading = false
      }
    },
    openSource() {
      if (typeof window !== 'undefined') window.open(SOURCE_URL, '_blank', 'noopener')
    },
  },
}
</script>

<style scoped>
.portal-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  box-sizing: border-box;
  background: var(--awd-bg);
}
.portal-card {
  width: 100%;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 32px 28px;
  box-sizing: border-box;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 12px;
}
.portal-head { display: flex; align-items: center; gap: 10px; }
.portal-logo { height: 28px; }
.portal-brand { font-size: 16px; font-weight: 600; color: var(--awd-text); }
.portal-title { font-size: 20px; font-weight: 600; color: var(--awd-text); margin-top: 8px; }
.portal-lead { font-size: 14px; line-height: 1.6; color: var(--awd-text-2); }
.portal-label { font-size: 13px; color: var(--awd-text-2); margin-top: 8px; }
.portal-input {
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  font-size: 15px;
  color: var(--awd-text);
  background: var(--awd-surface);
}
.portal-error { font-size: 13px; color: var(--awd-danger-text); }
.portal-btn {
  margin-top: 8px;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-radius: 8px;
  font-size: 15px;
}
.portal-notice { font-size: 12px; line-height: 1.6; color: var(--awd-text-3); margin-top: 16px; }
.portal-notice-link { color: var(--awd-accent-text); cursor: pointer; word-break: break-all; }
</style>

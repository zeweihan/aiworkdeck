<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「凭访问码进入案卷」表单（客户入口）。两个宿主共用这一份（dev-board#1047 / #1026 协同项）：
    - pages/login/login.vue 的「客户」tab（浏览器访问团队服务器 / 案件库服务器）；
    - 工作台欢迎标签 Start 里的「凭访问码进入案卷（客户）」（components/welcome/WelcomePane.vue）。

  落点：POST /api/auth/client-login → 存会话 → 以 CLIENT 角色 reLaunch 进那份案卷的工作台（?id=<projectId>）。

  **连接目标**（两种宿主不一样，别弄混）：
    - 登录页（浏览器访问团队服务器 / 案件库服务器）：不传 serverUrl，走 services/api.js 的 clientLogin，
      打的就是页面所在的那台服务器，链路完整。
    - 桌面端欢迎标签：客户输码连的是**案件库服务器**，不是本机回环后端。宿主传 serverUrl（默认官方案件库，
      即后端 cloud.collab.base-url 经 /api/cloud/official 给出的地址）并打开 show-server-field 让用户可改成
      自建服务器，本组件直接 POST `${serverUrl}/api/auth/client-login`。
      **依赖 dev-board#1050**：已上云案卷的客户访问码目前仍由本机后端签发，案件库侧兑换不了——在那之前
      这条路多半兑换失败，失败时把服务器返回的原文完整显示在表单下方，不在前端伪造成功。
      兑换成功时（#1050 修好后）桌面端还没有「以远端会话打开案卷」的通道：只如实告知，不写本机会话、
      不把本机工作台切成客户视图（TODO(#1050)）。

  宿主是工作台时，进入案卷那一跳走注入的 leaveWorkbench（先落盘再 reLaunch）；登录页没有注入，直调 uni。
-->
<template>
  <view class="client-access-form" :class="{ 'is-compact': compact }">
    <view v-if="showServerField" class="input-group">
      <text class="label">{{ $t('welcome.caseServerLabel') }}</text>
      <input
        class="glass-input"
        type="text"
        v-model="serverInput"
        :placeholder="$t('welcome.caseServerPlaceholder')"
        placeholder-class="placeholder-style"
      />
    </view>
    <view class="input-group">
      <text class="label">{{ $t('account.caseAccessCodeLabel') }}</text>
      <input
        class="glass-input"
        type="text"
        v-model="accessCode"
        :focus="autofocus"
        @confirm="submit"
        :placeholder="$t('account.caseAccessCodePlaceholder')"
        placeholder-class="placeholder-style"
      />
    </view>
    <button class="action-btn" :disabled="loading" :loading="loading" @tap="submit">{{ $t('account.enterCaseBtn') }}</button>
    <!-- 服务器返回的原文，完整可读（toast 会截断长句） -->
    <text v-if="errorText" class="caf-error">{{ errorText }}</text>
    <text v-if="noticeText" class="caf-notice">{{ noticeText }}</text>
  </view>
</template>

<script>
import { clientLogin } from '@/services/api.js'
import { saveSession } from '@/utils/auth.js'

export default {
  name: 'ClientAccessCodeForm',
  inject: {
    leaveWorkbench: { default: null },
  },
  props: {
    /** 欢迎标签里用紧凑尺寸；登录页用原来的大号表单 */
    compact: { type: Boolean, default: false },
    autofocus: { type: Boolean, default: false },
    /** 案件库服务器地址（桌面端欢迎标签传；登录页不传 = 页面所在服务器） */
    serverUrl: { type: String, default: '' },
    /** 显示可编辑的服务器地址栏（桌面端：官方案件库之外也能填自建服务器） */
    showServerField: { type: Boolean, default: false },
  },
  emits: ['success', 'remote-verified'],
  data() {
    return {
      accessCode: '',
      serverInput: this.serverUrl || '',
      loading: false,
      errorText: '',
      noticeText: '',
    }
  },
  watch: {
    // 宿主异步拿到官方案件库地址时补进来；用户已经改过就不覆盖
    serverUrl(v) {
      if (!this.serverInput) this.serverInput = v || ''
    },
  },
  methods: {
    async submit() {
      if (this.loading) return
      const code = (this.accessCode || '').trim()
      if (!code) {
        uni.showToast({ title: this.$t('account.caseAccessCodePlaceholder'), icon: 'none' })
        return
      }
      this.errorText = ''
      this.noticeText = ''
      if (this.showServerField) {
        await this.submitRemote(code)
        return
      }
      this.loading = true
      try {
        const res = await clientLogin(code, null)
        if (res.code === 0 && res.data) {
          saveSession(res.data.sessionId, res.data.user)
          uni.showToast({ title: this.$t('account.loginSuccessToast'), icon: 'success' })
          const projectId = res.data.projectId
          this.$emit('success', { projectId })
          setTimeout(() => this.enterCase(projectId), 300)
        } else {
          uni.showToast({ title: res.message || this.$t('account.loginFailedToast'), icon: 'none' })
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('account.loginFailedToast'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    /**
     * 桌面端：直接向案件库服务器兑换访问码（不经本机回环后端）。
     * 失败 = 服务器原文完整显示；成功 = 如实告知（见文件头「依赖 dev-board#1050」）。
     */
    async submitRemote(code) {
      const base = String(this.serverInput || '').trim().replace(/\/+$/, '')
      if (!/^https?:\/\/[^\s/]+/i.test(base)) {
        this.errorText = this.$t('welcome.caseServerInvalid')
        return
      }
      this.loading = true
      try {
        const res = await this.requestRemote(base, code)
        if (res && res.code === 0 && res.data) {
          // TODO(#1050): 桌面端以远端会话打开案件库里的案卷。在那之前不写本机会话
          // （saveSession 会把本机工作台整个切成客户视图、请求却还打本机后端），只如实告知。
          this.noticeText = this.$t('welcome.accessCodeRemoteVerified')
          this.$emit('remote-verified', { serverUrl: base, projectId: res.data.projectId })
        } else {
          this.errorText = (res && res.message) || this.$t('account.loginFailedToast')
        }
      } catch (e) {
        this.errorText = (e && e.message) || this.$t('welcome.caseServerUnreachable')
      } finally {
        this.loading = false
      }
    },
    requestRemote(base, code) {
      return new Promise((resolve, reject) => {
        uni.request({
          url: base + '/api/auth/client-login',
          method: 'POST',
          data: { accessCode: code, displayName: null },
          header: { 'Content-Type': 'application/json' },
          timeout: 20000,
          success: (r) => {
            const body = r && r.data
            if (body && typeof body === 'object') resolve(body)
            else reject(new Error(this.$t('welcome.caseServerBadResponse', { status: (r && r.statusCode) || '' })))
          },
          fail: () => reject(new Error(this.$t('welcome.caseServerUnreachable'))),
        })
      })
    },
    // CLIENT 角色进入后落到该案卷的工作台（工作台参与的跳转一律 reLaunch）
    enterCase(projectId) {
      const url = `/pages/project-overview/project-overview?id=${projectId}`
      if (this.leaveWorkbench) {
        this.leaveWorkbench(url)
        return
      }
      uni.reLaunch({ url })
    },
  },
}
</script>

<style lang="scss" scoped>
/* 与登录页原「客户」表单同一套形制（login.vue 的 .input-group / .glass-input / .action-btn），
   组件化之后样式跟着组件走——宿主的 scoped 样式够不到子组件内部。 */
.input-group {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label {
  font-size: 13px;
  color: var(--awd-text);
  font-weight: 500;
}

.glass-input {
  height: 48px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 0 16px;
  font-size: 15px;
  transition: all 0.2s;

  &:focus {
    background: var(--awd-surface);
    border-color: var(--awd-mint);
    box-shadow: 0 0 0 3px rgba(137, 168, 160, 0.2);
  }
}

.placeholder-style {
  color: var(--awd-text-3);
}

.action-btn {
  width: 100%;
  height: 50px;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  cursor: pointer;
  transition: background 0.2s;

  &:active {
    background: var(--awd-accent-hover);
  }

  &::after { border: none; }
}

.caf-error,
.caf-notice {
  display: block;
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.caf-error {
  color: var(--awd-danger-text);
}

.caf-notice {
  color: var(--awd-text-2);
}

.client-access-form.is-compact {
  .input-group {
    margin-bottom: 14px;
    gap: 6px;
  }

  .glass-input {
    height: 36px;
    padding: 0 12px;
    font-size: 13px;
  }

  .action-btn {
    height: 36px;
    font-size: 13px;
  }
}
</style>

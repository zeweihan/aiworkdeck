<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「凭访问码进入案卷」表单（客户入口）。两个宿主共用这一份（dev-board#1047 / #1026 / #1050），按 mode 分两种形态：

  mode="login"（默认；pages/login/login.vue 的「客户」tab，浏览器访问团队服务器）：
    POST /api/auth/client-login（services/api.js 的 clientLogin，打的就是页面所在那台服务器）→ 存会话 →
    以 CLIENT 角色 reLaunch 进那份案卷的工作台（?id=<projectId>）。宿主是工作台时走注入的 leaveWorkbench。

  mode="portal"（桌面端欢迎标签，dev-board#1050 定稿契约）：
    客户不在桌面端里登录、也不经本机回环后端——已上云案卷的客户门户在案件库服务器上。本组件只收一个访问码，
    用系统浏览器打开 `{portalBase}/client/#code=<访问码>`：
      - portalBase = 后端 cloud.collab.base-url，经 GET /api/cloud/official 的 serverUrl 取（宿主负责取，传进来）；
      - 访问码放在 **fragment**（#code=），不是 query：fragment 不随请求发给服务器、不进访问日志与 Referer，
        门户页读 hash 预填后自己把它清掉；
      - 外链走 utils/externalLink.js 的 openExternalUrl（桌面端 = host.shell.openExternal，系统浏览器）。
    不写本机会话、不改路由、不显示服务器地址（界面不给律师 / 客户看案件库地址）。
-->
<template>
  <view class="client-access-form" :class="{ 'is-compact': compact }">
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
    <button class="action-btn" :disabled="loading" :loading="loading" @tap="submit">{{ isPortal ? $t('welcome.portalOpenBtn') : $t('account.enterCaseBtn') }}</button>
    <text v-if="isPortal" class="caf-notice">{{ $t('welcome.portalHint') }}</text>
    <!-- 完整可读的错误（toast 会截断长句） -->
    <text v-if="errorText" class="caf-error">{{ errorText }}</text>
  </view>
</template>

<script>
import { clientLogin } from '@/services/api.js'
import { saveSession } from '@/utils/auth.js'
import { openExternalUrl } from '@/utils/externalLink.js'

/**
 * 纯函数：客户门户地址（tests/project-home/welcome-tab.test.mjs 抠出来跑）。
 * 访问码进 fragment（#code=），不进 query；base 不是 http(s) 时返回空串。
 */
export function clientPortalUrl(base, code) {
  const b = String(base || '').trim().replace(/\/+$/, '')
  const c = String(code || '').trim()
  if (!c || !/^https?:\/\/[^\s/?#]+/i.test(b) || /[?#]/.test(b)) return ''
  return b + '/client/#code=' + encodeURIComponent(c)
}

export default {
  name: 'ClientAccessCodeForm',
  inject: {
    leaveWorkbench: { default: null },
  },
  props: {
    /** login：本服务器 clientLogin 进案卷（登录页）；portal：在系统浏览器打开案件库的客户门户（桌面端欢迎标签） */
    mode: { type: String, default: 'login', validator: (v) => v === 'login' || v === 'portal' },
    /** portal 形态的案件库地址（cloud.collab.base-url）；宿主异步取，取不到为空串 */
    portalBase: { type: String, default: '' },
    /** 欢迎标签里用紧凑尺寸；登录页用原来的大号表单 */
    compact: { type: Boolean, default: false },
    autofocus: { type: Boolean, default: false },
  },
  emits: ['success', 'portal-opened'],
  data() {
    return {
      accessCode: '',
      loading: false,
      errorText: '',
    }
  },
  computed: {
    isPortal() {
      return this.mode === 'portal'
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
      if (this.isPortal) {
        this.openPortal(code)
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
    /** portal 形态：系统浏览器打开客户门户（见文件头契约）。取不到案件库地址时如实说，不拼一个假地址。 */
    openPortal(code) {
      const url = clientPortalUrl(this.portalBase, code)
      if (!url) {
        this.errorText = this.$t('welcome.portalUnavailable')
        return
      }
      openExternalUrl(url)
      this.$emit('portal-opened')
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

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 启动引导页：极简浅色 splash，只做路由分流，不承载任何业务 UI -->
  <view class="launch-page">
    <view class="launch-center">
      <image class="launch-logo awd-brand-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      <view v-if="!failed" class="launch-loading">
        <view class="launch-spinner"></view>
        <text class="launch-status">{{ statusText }}</text>
      </view>
      <view v-else class="launch-error">
        <text class="launch-error-text">{{ errorText }}</text>
        <text v-if="backendDetail" class="launch-error-detail">{{ $t('onboarding.launch.detailLabel') }}{{ backendDetail }}</text>
        <button class="launch-retry-btn" @tap="retry">{{ $t('onboarding.launch.retry') }}</button>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getLicenseStatus,
  getLocalIdentityStatus,
} from '@/services/api.js'
import { isDesktopHost, host } from '@/services/host.js'

export default {
  name: 'LaunchPage',
  data() {
    return {
      failed: false,
      statusText: this.$t('onboarding.launch.starting'),
      errorText: this.$t('onboarding.launch.cannotConnect'),
      // 后端启动失败的诊断信息（desktop 端 backend.onStatus 推送），浏览器态始终为空
      backendDetail: '',
      unsubscribeBackendStatus: null,
    }
  },
  onLoad() {
    this.subscribeBackendStatus()
    this.boot()
  },
  beforeUnmount() {
    if (this.unsubscribeBackendStatus) this.unsubscribeBackendStatus()
  },
  methods: {
    isDesktop() {
      return isDesktopHost()
    },
    // 订阅后端启动状态推送，失败态下作为诊断信息补充展示（dev-board#341）
    subscribeBackendStatus() {
      if (!this.isDesktop() || !(host.backend && host.backend.onStatus)) return
      this.unsubscribeBackendStatus = host.backend.onStatus((data) => {
        if (data && data.ok === false) this.backendDetail = data.message || ''
      })
    },
    // 失败态重试：桌面端先让主进程重启后端服务，再重新走一遍启动分流
    async retry() {
      if (this.isDesktop() && host.backend && host.backend.restart) {
        try { await host.backend.restart() } catch (e) { /* 忽略，照常重新轮询 */ }
      }
      this.boot()
    },
    async boot() {
      this.failed = false
      this.statusText = this.$t('onboarding.launch.starting')

      // 非桌面环境（浏览器访问团队服务器）：走原登录流程，一字不动
      if (!this.isDesktop()) {
        uni.reLaunch({ url: '/pages/login/login' })
        return
      }

      // 桌面环境：等本地服务就绪。授权状态端点在这里只当「后端起来了没有」的探针用——
      // 启动不再设解锁门（dev-board#1027 / #1047：登录后置，需要账户的功能在用到时就地登录），
      // unlocked 的真假不参与分流。
      const status = await this.waitLicenseStatus()
      if (!status) {
        this.failed = true
        return
      }

      // 本机工作区待选定：老安装的库里可能有多个都带数据的历史账号，后端不猜，
      // 在这里拦下来让用户自己选，选完才进工作区（选过一次就不会再走这条分支）。
      try {
        const identity = await getLocalIdentityStatus()
        if (identity && identity.needsSelection) {
          uni.reLaunch({ url: '/pages/identity/identity' })
          return
        }
      } catch (e) {
        // 查询失败不拦路：后端仍会落在数据量最大的候选上，工作区可用
        console.warn('查询本机工作区状态失败（忽略）:', e && e.message)
      }

      // 启动一律落工作台外壳（无项目态，不带 id），中央打开「欢迎」标签（dev-board#1047）。
      // 项目清单不再是分流条件：拉不到也照样进外壳，欢迎标签的 Recent 与左栏「项目」面板
      // 各自处理空态与错误。直达某一个项目的四条出口（应用菜单最近打开、打开本地文件夹/文件、
      // 顶栏最近项目切换器、浏览器态会话恢复）不经过这里，一条没动。
      uni.reLaunch({ url: '/pages/project-overview/project-overview' })
    },
    // 打包版后端随应用启动需要几秒，轮询直到可达（上限 90 秒）。
    // ARM 版 Windows（Mac 虚拟机）转译运行时主进程看门狗已放宽 8 倍（dev-board#340），
    // 这里同步放宽死线，否则后端还在正常预热就会被判超时（dev-board#341）
    async waitLicenseStatus() {
      const emulated = this.isDesktop() && !!host.winEmulated
      const deadline = Date.now() + (emulated ? 90000 * 8 : 90000)
      let shownBooting = false
      while (Date.now() < deadline) {
        try {
          return await getLicenseStatus()
        } catch (e) {
          if (!shownBooting) {
            this.statusText = emulated
              ? this.$t('onboarding.launch.bootingEmulated')
              : this.$t('onboarding.launch.bootingLocal')
            shownBooting = true
          }
          await new Promise((resolve) => setTimeout(resolve, 1500))
        }
      }
      return null
    },
  },
}
</script>

<style lang="scss" scoped>
.launch-page {
  width: 100vw;
  height: 100vh;
  background: var(--awd-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.launch-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 28px;
}

.launch-logo {
  height: 52px;
}

.launch-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  min-height: 70px;
}

.launch-spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--awd-border);
  border-top-color: var(--awd-accent);
  border-radius: 50%;
  animation: launch-spin 0.9s linear infinite;
}

@keyframes launch-spin {
  to {
    transform: rotate(360deg);
  }
}

.launch-status {
  font-size: 13px;
  color: var(--awd-text-3);
}

.launch-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  min-height: 70px;
}

.launch-error-text {
  font-size: 13px;
  color: var(--awd-text-2);
}

.launch-error-detail {
  font-size: 12px;
  color: var(--awd-text-3);
}

.launch-retry-btn {
  height: 36px;
  line-height: 34px;
  padding: 0 28px;
  font-size: 13px;
  color: var(--awd-accent-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border-strong);
  border-radius: 8px;
  cursor: pointer;

  &:hover {
    border-color: var(--awd-accent);
    background: var(--awd-surface-2);
  }
}
</style>

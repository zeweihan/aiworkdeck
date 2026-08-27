<template>
  <!-- 启动引导页：极简浅色 splash，只做路由分流，不承载任何业务 UI -->
  <view class="launch-page">
    <view class="launch-center">
      <image class="launch-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      <view v-if="!failed" class="launch-loading">
        <view class="launch-spinner"></view>
        <text class="launch-status">{{ statusText }}</text>
      </view>
      <view v-else class="launch-error">
        <text class="launch-error-text">{{ errorText }}</text>
        <button class="launch-retry-btn" @tap="boot">{{ $t('onboarding.launch.retry') }}</button>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getLicenseStatus,
  getLocalIdentityStatus,
  getMyProjects,
} from '@/services/api.js'
import { syncRecentToMenu } from '@/utils/recentProjects.js'
import { isDesktopHost } from '@/services/host.js'

export default {
  name: 'LaunchPage',
  data() {
    return {
      failed: false,
      statusText: this.$t('onboarding.launch.starting'),
      errorText: this.$t('onboarding.launch.cannotConnect'),
    }
  },
  onLoad() {
    this.boot()
  },
  methods: {
    isDesktop() {
      return isDesktopHost()
    },
    async boot() {
      this.failed = false
      this.statusText = this.$t('onboarding.launch.starting')

      // 非桌面环境（浏览器访问团队服务器）：走原登录流程，一字不动
      if (!this.isDesktop()) {
        uni.reLaunch({ url: '/pages/login/login' })
        return
      }

      // 桌面环境：等待本地服务就绪后查授权状态
      const status = await this.waitLicenseStatus()
      if (!status) {
        this.failed = true
        return
      }
      if (!status.unlocked) {
        uni.reLaunch({ url: '/pages/unlock/unlock' })
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

      // 首启向导已下线（2026-08-27）：初始化（官方通道 + 跨境同意）由解锁页在登录成功后
      // 一次性提交，这里不再分流，直达上次项目

      try {
        // local-mode 免登录：不需要 session 探活，getMyProjects 探通即视为可用
        const projects = await getMyProjects()
        const list = Array.isArray(projects) ? projects : (projects && projects.data) || []
        syncRecentToMenu(list) // 应用菜单「最近打开」子菜单
        // 启动一律落项目列表页（2026-08 维护者定的落点）。
        // 此前是「有最近项目就直达工作台」，为的是「立刻干活」；代价是开机永远
        // 停在上一个项目里，手上有第二件事的人得先找到出口再切。列表页顶部有
        // 「继续：上次的项目」一键回去，多的那一下点击换来的是每次开机都先看见
        // 自己有哪些案子。**其余四条直达工作台的出口一条没动**（应用菜单最近打开、
        // 打开本地文件夹/文件、顶栏切换器、浏览器态会话恢复）——那几处的用户
        // 意图明确指向某一个项目，强插列表页才是多一跳。
        uni.reLaunch({ url: '/pages/project-list/project-list' })
      } catch (e) {
        console.warn('启动分流失败:', e && e.message)
        this.failed = true
      }
    },
    // 打包版后端随应用启动需要几秒，轮询直到可达（上限 90 秒）
    async waitLicenseStatus() {
      const deadline = Date.now() + 90000
      let shownBooting = false
      while (Date.now() < deadline) {
        try {
          return await getLicenseStatus()
        } catch (e) {
          if (!shownBooting) {
            this.statusText = this.$t('onboarding.launch.bootingLocal')
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
  background: #f8f9fa;
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
  border: 2px solid #e2e8f0;
  border-top-color: #1a5336;
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
  color: #94a3b8;
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
  color: #64748b;
}

.launch-retry-btn {
  height: 36px;
  line-height: 34px;
  padding: 0 28px;
  font-size: 13px;
  color: #1a5336;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  cursor: pointer;

  &:hover {
    border-color: #1a5336;
    background: #f1f5f9;
  }
}
</style>

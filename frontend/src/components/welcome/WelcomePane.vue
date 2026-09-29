<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  中栏「欢迎」标签的内容（dev-board#1047，spec 2026-09-29-defer-login-welcome-tab-design §6；结构对齐 VS Code 的 Welcome）。

    品牌标题 + tagline / lead（文案全部取 design/copy/brand-copy.json 已有的 onboarding.unlock.brand.*，不自创）
    开始：新建项目文件夹 / 打开已有文件夹 / 从团队案件库取一份案卷 / 连接团队服务器 / 凭访问码进入案卷（客户）
    最近：最多 8 条（最近打开优先，不足时按最近活动补齐，规则见 welcomeRecent.js）；「更多…」开左栏「项目」面板
    上手指南：三张卡，指向官网现有页面，在工作台内置浏览器标签里打开
    底部：「启动时显示欢迎页」（本机记忆，默认开）+ 匿名使用统计的一行非阻塞提示（关掉后不再出现）

  动作的归宿：
    - 新建 / 打开文件夹：emit 给宿主，宿主先把编辑器落盘，再跑与菜单「文件」同一条命令（config/commands/file.js）；
    - 取案卷：与项目列表同一个 CloudAcceptDialog；
    - 连接团队服务器：设置标签的「团队」一栏（注入的 openSettingsTab）；
    - 凭访问码进入案卷：与登录页「客户」tab 同一个 ClientAccessCodeForm；
    - 进项目：注入的 leaveWorkbench（先落盘再 reLaunch 进带 id 的工作台）。
  客户视图（CLIENT）只看得到 Recent，建项目 / 取案卷 / 连服务器三项对他收起。
-->
<template>
  <scroll-view scroll-y class="welcome-pane">
    <view class="welcome-inner">
      <view class="welcome-hero">
        <text class="welcome-brand">{{ $t('onboarding.unlock.brand.brand') }}</text>
        <text class="welcome-tagline">{{ $t('onboarding.unlock.brand.tagline') }}</text>
        <text class="welcome-lead">{{ $t('onboarding.unlock.brand.lead') }}</text>
      </view>

      <view class="welcome-columns">
        <view class="welcome-col welcome-col-main">
          <view class="welcome-section welcome-start">
            <text class="welcome-section-title">{{ $t('welcome.start') }}</text>
            <template v-if="!isClientUser">
              <view class="welcome-action" data-action="new-project" @tap="$emit('new-project')">
                <svg class="welcome-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.folderPlus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="welcome-action-text">{{ isDesktopFs ? $t('account.createFolderTitle') : $t('projects.newProject') }}</text>
              </view>
              <view v-if="isDesktopFs" class="welcome-action" data-action="open-folder" @tap="$emit('open-folder')">
                <svg class="welcome-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.folderOpen" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="welcome-action-text">{{ $t('account.openFolderTitle') }}</text>
              </view>
              <view class="welcome-action" data-action="pull-case" @tap="showCloudAccept = true">
                <svg class="welcome-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.download" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="welcome-action-text">{{ $t('projects.pullFromTeamLibrary') }}</text>
              </view>
              <view class="welcome-action" data-action="connect-team" @tap="onConnectTeam">
                <svg class="welcome-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.link" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="welcome-action-text">{{ $t('welcome.connectTeamServer') }}</text>
              </view>
            </template>
            <view class="welcome-action" data-action="access-code" @tap="openAccessCode">
              <svg class="welcome-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, gi) in ICONS.logIn" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <text class="welcome-action-text">{{ $t('welcome.enterWithAccessCode') }}</text>
            </view>
          </view>

          <view class="welcome-section welcome-recent">
            <text class="welcome-section-title">{{ $t('welcome.recent') }}</text>
            <text v-if="recentState === 'loading'" class="welcome-muted">{{ $t('welcome.recentLoading') }}</text>
            <text v-else-if="recentState === 'error'" class="welcome-muted welcome-link" @tap="loadRecent">{{ $t('welcome.recentLoadFailed') }}</text>
            <text v-else-if="!recentProjects.length" class="welcome-muted welcome-recent-empty">{{ $t('welcome.recentEmpty') }}</text>
            <template v-else>
              <view
                v-for="p in recentProjects"
                :key="p.id"
                class="welcome-recent-item"
                :class="{ 'is-current': isCurrent(p) }"
                @tap="openProject(p)"
              >
                <text class="welcome-recent-name">{{ p.name }}</text>
                <text class="welcome-recent-meta">{{ recentMeta(p) }}</text>
              </view>
            </template>
            <text class="welcome-link welcome-more" @tap="$emit('open-projects-pane')">{{ $t('welcome.more') }}</text>
          </view>
        </view>

        <view class="welcome-col welcome-col-side">
          <view class="welcome-section welcome-guides">
            <text class="welcome-section-title">{{ $t('welcome.guides') }}</text>
            <view
              v-for="g in guides"
              :key="g.key"
              class="welcome-guide-card"
              :data-guide="g.key"
              @tap="$emit('open-url', g.url)"
            >
              <text class="welcome-guide-title">{{ g.title }}</text>
              <text class="welcome-guide-desc">{{ g.desc }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="welcome-footer">
        <view class="welcome-startup-toggle" role="checkbox" :aria-checked="showOnStartup ? 'true' : 'false'" @tap="$emit('update:show-on-startup', !showOnStartup)">
          <view class="welcome-checkbox" :class="{ checked: showOnStartup }">
            <svg v-if="showOnStartup" class="welcome-checkbox-mark" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
          <text class="welcome-startup-text">{{ $t('welcome.showOnStartup') }}</text>
        </view>
        <view v-if="telemetryNoticeVisible" class="welcome-telemetry">
          <text class="welcome-link welcome-telemetry-text" @tap="openTelemetrySettings">{{ $t('welcome.telemetryNotice') }}</text>
          <text class="welcome-telemetry-close" :title="$t('welcome.telemetryNoticeDismiss')" @tap="dismissTelemetryNotice">×</text>
        </view>
      </view>
    </view>

    <!-- 凭访问码进入案卷：与登录页「客户」tab 同一个表单组件 -->
    <view v-if="showAccessCode" class="welcome-dialog-mask" @tap.self="showAccessCode = false">
      <view class="welcome-dialog">
        <view class="welcome-dialog-head">
          <text class="welcome-dialog-title">{{ $t('welcome.accessCodeDialogTitle') }}</text>
          <text class="welcome-dialog-close" @tap="showAccessCode = false">×</text>
        </view>
        <text class="welcome-dialog-hint">{{ $t('welcome.accessCodeDialogHint') }}</text>
        <!-- 桌面端：客户输码连的是案件库服务器（默认官方案件库，可改成自建服务器），不是本机回环后端；
             浏览器端（团队服务器上的网页）服务器就是页面所在那台，不给地址栏。依赖 dev-board#1050。 -->
        <ClientAccessCodeForm compact autofocus :server-url="caseServerUrl" :show-server-field="isDesktop" />
      </view>
    </view>

    <CloudAcceptDialog
      v-model:visible="showCloudAccept"
      @accepted="onCloudAccepted"
    />
  </scroll-view>
</template>

<script>
import { getMyProjects, getTelemetrySettings, getOfficialCloud } from '@/services/api.js'
import { getRecentProjectIds } from '@/utils/recentProjects.js'
import { getCurrentUser } from '@/utils/auth.js'
import { isDesktopHost, host } from '@/services/host.js'
import { siteBaseUrl } from '@/utils/siteLinks.js'
import { setGlobalOverlay } from '@/utils/overlayState.js'
import { ICONS } from '@/config/icons.js'
import { buildRecentProjects } from './welcomeRecent.js'
import { loadTelemetryNoticeDismissed, saveTelemetryNoticeDismissed } from '@/pages/project-overview/welcomeTab.js'
import ClientAccessCodeForm from '@/components/account/ClientAccessCodeForm.vue'
import CloudAcceptDialog from '@/components/CloudAcceptDialog.vue'

let overlaySeq = 0

export default {
  name: 'WelcomePane',
  components: { ClientAccessCodeForm, CloudAcceptDialog },
  inject: {
    leaveWorkbench: { default: null },
    openSettingsTab: { default: null },
  },
  props: {
    /** 工作台当前打开的项目（无项目态为 null）：Recent 里标「当前」，点它不重进 */
    currentProjectId: { type: [Number, String], default: null },
    /** 「启动时显示欢迎页」当前值（本机记忆由宿主持有） */
    showOnStartup: { type: Boolean, default: true },
  },
  emits: ['new-project', 'open-folder', 'open-url', 'open-projects-pane', 'update:show-on-startup'],
  data() {
    return {
      recentProjects: [],
      recentState: 'loading', // loading | ready | error
      showAccessCode: false,
      showCloudAccept: false,
      telemetryEnabled: false,
      telemetryDismissed: false,
      // 官方案件库地址（后端 cloud.collab.base-url，经 /api/cloud/official）；访问码表单的默认服务器
      caseServerUrl: '',
    }
  },
  computed: {
    ICONS() {
      return ICONS
    },
    isClientUser() {
      const u = getCurrentUser()
      return !!u && u.role === 'CLIENT'
    },
    // 判据同项目列表：有系统文件夹对话框才给「打开已有文件夹」（浏览器端降级为托管空白项目）
    isDesktop() {
      return isDesktopHost()
    },
    isDesktopFs() {
      return isDesktopHost() && !!(host.fs && host.fs.showOpenDialog)
    },
    guides() {
      const base = siteBaseUrl()
      return [
        { key: 'start', title: this.$t('welcome.guideStart'), desc: this.$t('welcome.guideStartDesc'), url: base + '/start' },
        { key: 'ai-edit', title: this.$t('welcome.guideAiEdit'), desc: this.$t('welcome.guideAiEditDesc'), url: base + '/showcase' },
        { key: 'plugin', title: this.$t('welcome.guidePlugin'), desc: this.$t('welcome.guidePluginDesc'), url: base + '/plugins' },
      ]
    },
    telemetryNoticeVisible() {
      return this.telemetryEnabled && !this.telemetryDismissed
    },
    anyDialogOpen() {
      return this.showAccessCode || this.showCloudAccept
    },
  },
  watch: {
    // 全屏弹窗期间要让工作台把桌面端 BrowserView 藏起来（原生层会盖住 DOM 弹窗）
    anyDialogOpen(open) {
      setGlobalOverlay(!!open, this._overlayHolder)
    },
  },
  created() {
    overlaySeq += 1
    this._overlayHolder = 'welcome-pane-' + overlaySeq
    this.telemetryDismissed = loadTelemetryNoticeDismissed(uni)
  },
  mounted() {
    this.loadRecent()
    this.loadTelemetryState()
  },
  beforeUnmount() {
    setGlobalOverlay(false, this._overlayHolder)
  },
  methods: {
    async loadRecent() {
      this.recentState = 'loading'
      try {
        const res = await getMyProjects()
        const list = Array.isArray(res) ? res : (res && res.data) || []
        this.recentProjects = buildRecentProjects(list, getRecentProjectIds())
        this.recentState = 'ready'
      } catch (e) {
        console.warn('[WelcomePane] 读取项目列表失败', e)
        this.recentState = 'error'
      }
    },
    async loadTelemetryState() {
      if (this.telemetryDismissed) return
      try {
        const res = await getTelemetrySettings()
        const s = (res && res.data) || res || {}
        this.telemetryEnabled = !!(s.rollupEnabled || s.eventsEnabled)
      } catch (e) {
        // 读不到（团队服务器模式 / 旧后端）就不提示，不打扰
        this.telemetryEnabled = false
      }
    },
    isCurrent(p) {
      return this.currentProjectId != null && Number(p.id) === Number(this.currentProjectId)
    },
    recentMeta(p) {
      if (this.isCurrent(p)) return this.$t('welcome.recentCurrent')
      const raw = p.lastActivityAt || p.updatedAt || p.createdAt
      const d = raw ? new Date(raw) : null
      if (!d || Number.isNaN(d.getTime())) return ''
      const pad = (n) => String(n).padStart(2, '0')
      return this.$t('welcome.recentUpdated', { date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` })
    },
    // 进项目 = 离开当前工作台（带 id 重进），走注入的 leaveWorkbench 先落盘
    openProject(p) {
      if (!p || this.isCurrent(p)) return
      const url = `/pages/project-overview/project-overview?id=${p.id}`
      if (this.leaveWorkbench) {
        this.leaveWorkbench(url)
        return
      }
      uni.reLaunch({ url })
    },
    onCloudAccepted(localProjectId) {
      if (localProjectId) this.openProject({ id: localProjectId })
      else this.loadRecent()
    },
    // 凭访问码进入案卷：桌面端先取官方案件库地址作默认服务器（取不到就留空，让用户填自建服务器）
    async openAccessCode() {
      this.showAccessCode = true
      if (!isDesktopHost() || this.caseServerUrl) return
      try {
        const res = await getOfficialCloud()
        const d = (res && res.data) || {}
        if (d.available && d.serverUrl) this.caseServerUrl = String(d.serverUrl)
      } catch (e) {
        // 取不到不拦路：表单里的服务器地址留空，让用户自己填
      }
    },
    onConnectTeam() {
      if (this.openSettingsTab) this.openSettingsTab({ nav: 'team' })
    },
    openTelemetrySettings() {
      if (this.openSettingsTab) this.openSettingsTab({ nav: 'telemetry' })
    },
    dismissTelemetryNotice() {
      this.telemetryDismissed = true
      saveTelemetryNoticeDismissed(uni)
    },
  },
}
</script>

<style lang="scss" scoped>
.welcome-pane {
  height: 100%;
  background: var(--awd-surface);
  color: var(--awd-text);
  container-type: inline-size;
  container-name: welcome-pane;
}

.welcome-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 48px 48px 32px;
  box-sizing: border-box;
}

.welcome-hero {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 36px;
}

.welcome-brand {
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--awd-text);
}

.welcome-tagline {
  font-size: 18px;
  color: var(--awd-accent-text);
}

.welcome-lead {
  font-size: 13px;
  color: var(--awd-text-2);
  line-height: 1.6;
  max-width: 560px;
}

.welcome-columns {
  display: flex;
  flex-direction: row;
  gap: 48px;
}

.welcome-col-main {
  flex: 1 1 0;
  min-width: 0;
}

.welcome-col-side {
  flex: 0 0 300px;
}

.welcome-section {
  display: flex;
  flex-direction: column;
  margin-bottom: 28px;
}

.welcome-section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
  margin-bottom: 10px;
}

.welcome-action {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  cursor: pointer;
  color: var(--awd-accent-text);

  &:hover .welcome-action-text {
    text-decoration: underline;
  }
}

.welcome-action-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.welcome-action-text {
  font-size: 13px;
}

.welcome-muted {
  font-size: 13px;
  color: var(--awd-text-3);
  line-height: 1.6;
}

.welcome-link {
  color: var(--awd-accent-text);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.welcome-recent-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 12px;
  padding: 4px 0;
  cursor: pointer;
  min-width: 0;

  &:hover .welcome-recent-name {
    text-decoration: underline;
  }

  &.is-current {
    cursor: default;

    .welcome-recent-name {
      color: var(--awd-text);
      text-decoration: none;
    }
  }
}

.welcome-recent-name {
  font-size: 13px;
  color: var(--awd-accent-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.welcome-recent-meta {
  font-size: 12px;
  color: var(--awd-text-3);
  white-space: nowrap;
  flex-shrink: 0;
}

.welcome-more {
  margin-top: 6px;
  font-size: 13px;
}

.welcome-guide-card {
  padding: 12px 14px;
  margin-bottom: 10px;
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  background: var(--awd-bg);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: border-color 0.15s;

  &:hover {
    border-color: var(--awd-accent-text);
  }
}

.welcome-guide-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
}

.welcome-guide-desc {
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 1.5;
}

.welcome-footer {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--awd-border-subtle);
}

.welcome-startup-toggle {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.welcome-checkbox {
  width: 14px;
  height: 14px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--awd-surface);
  color: var(--awd-text-on-accent);

  &.checked {
    background: var(--awd-accent);
    border-color: var(--awd-accent);
  }
}

.welcome-checkbox-mark {
  width: 11px;
  height: 11px;
}

.welcome-startup-text {
  font-size: 12px;
  color: var(--awd-text-2);
}

.welcome-telemetry {
  margin-left: auto;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.welcome-telemetry-text {
  font-size: 12px;
}

.welcome-telemetry-close {
  font-size: 14px;
  line-height: 14px;
  color: var(--awd-text-3);
  cursor: pointer;

  &:hover {
    color: var(--awd-text);
  }
}

/* 访问码弹窗：铺满视口的 fixed 浮层，App.vue 的 no-drag 名单里有它 */
.welcome-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(18, 52, 77, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
}

.welcome-dialog {
  width: 360px;
  max-width: calc(100vw - 32px);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  box-shadow: var(--awd-shadow-lg);
  padding: 18px 20px 20px;
  box-sizing: border-box;
}

.welcome-dialog-head {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.welcome-dialog-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}

.welcome-dialog-close {
  font-size: 18px;
  color: var(--awd-text-3);
  cursor: pointer;
}

.welcome-dialog-hint {
  display: block;
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 1.6;
  margin-bottom: 14px;
}

/* 窄中栏（分屏 / 左右栏拖宽）：按中栏实际宽度把两栏叠成一栏 */
@container welcome-pane (max-width: 760px) {
  .welcome-columns {
    flex-direction: column;
    gap: 8px;
  }

  .welcome-col-side {
    flex: 1 1 auto;
  }

  .welcome-inner {
    padding: 32px 28px 24px;
  }
}
</style>

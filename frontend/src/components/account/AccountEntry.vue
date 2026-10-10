<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  顶栏右上角账户入口（「活动记录」右侧；dev-board#1062 从 rail 底部改回顶栏，
  2026-09-29 13:22 维护者：「改回右上角」。#1047 那一版曾把它放在 rail 底部，对应 VS Code 的 Accounts）。

  两态：
    - 未登录（未连接账户）：「登录」按钮。点击 emit('login')，宿主就地
      `await requireAccount({ reason: 'account' })` 弹登录层（dev-board#1046），不离开工作台。
    - 已登录：头像 .avatar-btn，点击展开头像下拉——账户抬头（余额 + 等级）、宽限 / 试用 / 余额不足提示、
      「我的日程」「设置」「退出登录」三个动作。动作只 emit，路由与落盘由宿主做
      （宿主那三个处理器是 check:nav 盯着的 onAvatarMenuSchedule / onAvatarMenuSettings / onAvatarMenuSignOut）。

  设置入口只在这份下拉里（外加应用菜单 ⌘,），rail 上没有齿轮。
  余额不足 / 有宽限提醒时头像右上角挂一个小点，下拉里给出完整文案。
  有无项目两态都渲染、不按 isClientView 收——客户也有自己的账号安全与工作记录。
  顶栏整条是窗口拖拽区：本组件的按钮与下拉都在 App.vue 的 no-drag 名单里（.header-account / .account-entry-btn /
  .avatar-menu），全屏 mask .account-entry-mask 在「全屏浮层」那份名单里。

  **登录 / 退出后即时翻转**：本组件订阅 awd:account-changed（登录弹层成功与 utils/signOut.js 共用的广播），
  收到带 connected 的负载就先按它显示（signedIn），不等宿主重拉授权状态那一趟往返；宿主的 loggedIn
  prop 跟上之后以 prop 为准（watch 里清掉本地覆盖）。余额 / 宽限提示仍由宿主订同一事件后重拉。
-->
<template>
  <view class="header-account">
    <view
      class="account-entry-btn"
      :class="signedIn ? { 'avatar-btn': true, 'is-open': menuOpen } : { 'header-login-btn': true, 'is-signed-out': true }"
      :title="signedIn ? $t('workbench.accountMenu') : $t('welcome.signInTitle')"
      @tap.stop="onTap"
    >
      <template v-if="signedIn">
        <view class="account-avatar">
          <image v-if="avatarUrl" :src="avatarUrl" class="account-avatar-img" />
          <text v-else class="account-avatar-text">{{ initial }}</text>
        </view>
        <view v-if="attention" class="account-attention-dot"></view>
      </template>
      <template v-else>
        <svg class="account-entry-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in ICONS.logIn" :key="gi" :d="d" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <text class="account-entry-label">{{ $t('welcome.signIn') }}</text>
      </template>
    </view>

    <view v-if="menuOpen" class="account-entry-mask" @tap.stop="menuOpen = false"></view>
    <view v-if="menuOpen" class="avatar-menu account-entry-menu">
      <view class="avatar-menu-user">
        <text class="avatar-menu-name">{{ displayName || $t('workbench.me') }}</text>
      </view>
      <!-- 账户抬头：余额 + 等级。整块可点，去「账户与用量」 -->
      <view v-if="walletVisible" class="avatar-menu-wallet" @tap.stop="emitAndClose('account')">
        <view class="avatar-menu-wallet-row">
          <text class="avatar-menu-balance" :class="{ low: walletLow }">{{ walletText }}</text>
          <text v-if="walletTier" class="avatar-menu-tier">{{ walletTier }}</text>
        </view>
        <text class="avatar-menu-wallet-label">{{ $t('workbench.walletMenuLabel') }}</text>
      </view>
      <!-- 试用余额（试用计量 v0.1.1）：剩余天数与次数，权威在账户站 -->
      <view v-if="trialText" class="avatar-menu-trial">
        <text class="avatar-menu-trial-text">{{ trialText }}</text>
        <text class="avatar-menu-wallet-label">{{ $t('account.trialTerms') }}</text>
      </view>
      <!-- 宽限 / 试用提示（原顶栏 chip）：「需联网验证 · 剩 N 天」一类，点开说明弹窗 -->
      <view v-if="noticeText" class="avatar-menu-notice" @tap.stop="emitAndClose('grace-info')">
        <text class="avatar-menu-notice-text">{{ noticeText }}</text>
      </view>
      <!-- 客户视角看不到事项（dev-board#1050 #1051 起的口径），「我的日程」不给入口 -->
      <view v-if="!clientView" class="avatar-menu-item" @tap.stop="emitAndClose('schedule')">
        <text>{{ $t('calendar.mySchedule') }}</text>
      </view>
      <view class="avatar-menu-item" @tap.stop="emitAndClose('settings')">
        <text>{{ $t('workbench.settingsTabName') }}</text>
      </view>
      <view class="avatar-menu-item danger" @tap.stop="emitAndClose('sign-out')">
        <text>{{ $t('account.logoutBtn') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
import { ICONS } from '@/config/icons.js'
import { ACCOUNT_CHANGED_EVENT } from '@/utils/requireAccount.js'

export default {
  name: 'AccountEntry',
  props: {
    loggedIn: { type: Boolean, default: false },
    displayName: { type: String, default: '' },
    avatarUrl: { type: String, default: '' },
    walletVisible: { type: Boolean, default: false },
    walletText: { type: String, default: '' },
    walletLow: { type: Boolean, default: false },
    walletTier: { type: String, default: '' },
    trialText: { type: String, default: '' },
    /** 宽限 / 试用提示文案（「需联网验证 · 剩 N 天」「试用版」）；空串不显示 */
    noticeText: { type: String, default: '' },
    /** 宿主的客户视角（isClientView）：客户看不到事项，下拉里不出「我的日程」 */
    clientView: { type: Boolean, default: false },
  },
  emits: ['login', 'account', 'grace-info', 'schedule', 'settings', 'sign-out'],
  data() {
    return {
      menuOpen: false,
      // awd:account-changed 带来的连接状态；null = 以宿主 prop 为准
      connectedOverride: null,
    }
  },
  computed: {
    ICONS() {
      return ICONS
    },
    signedIn() {
      return this.connectedOverride == null ? !!this.loggedIn : this.connectedOverride
    },
    initial() {
      const n = (this.displayName || '').trim()
      return n ? n.charAt(0).toUpperCase() : 'U'
    },
    // 头像右上角的小点：余额不足或有宽限 / 试用提醒——原来顶栏那两个 chip 的信号
    attention() {
      return !!(this.walletLow || this.noticeText)
    },
  },
  watch: {
    // 宿主重拉授权状态后跟上了：以 prop 为准，本地覆盖作废
    loggedIn() {
      this.connectedOverride = null
    },
  },
  created() {
    // 页面栈多实例：按引用订阅 / 退订，每个实例只管自己
    this._onAccountChanged = (payload) => this.onAccountChanged(payload)
    try { uni.$on(ACCOUNT_CHANGED_EVENT, this._onAccountChanged) } catch (e) { /* 非 uni 环境 */ }
  },
  beforeUnmount() {
    try { uni.$off(ACCOUNT_CHANGED_EVENT, this._onAccountChanged) } catch (e) { /* ignore */ }
  },
  methods: {
    onTap() {
      if (!this.signedIn) {
        // 宿主就地弹登录层（requireAccount({ reason: 'account' })），成功后经 awd:account-changed 翻转
        this.$emit('login')
        return
      }
      this.menuOpen = !this.menuOpen
    },
    onAccountChanged(payload) {
      if (payload && typeof payload.connected === 'boolean') this.connectedOverride = payload.connected
      // 退出登录后下拉若还开着，里面的余额与动作都已不成立
      if (!this.signedIn) this.menuOpen = false
    },
    emitAndClose(event) {
      this.menuOpen = false
      this.$emit(event)
    },
  },
}
</script>

<style lang="scss" scoped>
.header-account {
  position: relative; /* 下拉的定位锚 */
  display: flex;
  align-items: center;
  margin-left: 6px;
}

.account-entry-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

/* 已登录：头像。比 .top-bar-btn 大一圈——它是一个人的身份标识，不是又一个开关。 */
.avatar-btn {
  width: 26px;
  height: 26px;
  border-radius: 50%;
}

/* 未登录：「登录」小按钮，与顶栏工具按钮同一套低调配色 */
.header-login-btn {
  height: 22px;
  padding: 0 8px;
  gap: 4px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  color: var(--awd-text-2);
  background: transparent;
  transition: all 0.2s;

  &:hover {
    color: var(--awd-accent-text);
    border-color: var(--awd-accent);
    background-color: var(--awd-surface-2);
  }
}

.account-entry-icon {
  width: 13px;
  height: 13px;
}

.account-entry-label {
  font-size: 11px;
  line-height: 14px;
  white-space: nowrap;
}

.account-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--awd-accent);
  border: 2px solid transparent;
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.avatar-btn:hover .account-avatar,
.avatar-btn.is-open .account-avatar {
  border-color: var(--awd-mint);
}

.account-avatar-img {
  width: 100%;
  height: 100%;
}

.account-avatar-text {
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-on-accent);
}

.account-attention-dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--awd-warning-text);
  border: 1.5px solid var(--awd-surface);
}

/* 全屏 mask 兜底收起；它是铺满视口的 fixed 浮层，App.vue 的 no-drag 名单里有它 */
.account-entry-mask {
  position: fixed;
  inset: 0;
  z-index: 1198;
}

/* 下拉从头像正下方、右对齐展开（顶栏在窗口最上方，往下有整屏空间） */
.avatar-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: 188px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: var(--awd-shadow-lg);
  padding: 4px;
  z-index: 1199;
}

.avatar-menu-user {
  padding: 6px 10px 4px;
}

.avatar-menu-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
}

.avatar-menu-wallet {
  padding: 8px 10px 9px;
  border-bottom: 1px solid var(--awd-border-subtle);
  margin-bottom: 4px;
  border-radius: 6px 6px 0 0;
  cursor: pointer;

  &:hover {
    background: var(--awd-surface-2);
  }
}

.avatar-menu-wallet-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.avatar-menu-balance {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
  font-variant-numeric: tabular-nums;

  &.low {
    color: var(--awd-warning-text);
  }
}

.avatar-menu-tier {
  font-size: 10px;
  font-weight: 500;
  line-height: 14px;
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-radius: 3px;
  padding: 0 5px;
}

.avatar-menu-trial {
  margin: 0 0 4px;
  padding: 6px 10px;
}

.avatar-menu-trial-text {
  display: block;
  font-size: 12px;
  color: var(--awd-text-2);
}

.avatar-menu-wallet-label {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--awd-text-3);
}

.avatar-menu-notice {
  margin: 0 0 4px;
  padding: 6px 10px;
  border-radius: 5px;
  background: var(--awd-warning-soft, var(--awd-surface-2));
  cursor: pointer;
}

.avatar-menu-notice-text {
  font-size: 12px;
  color: var(--awd-warning-text);
}

.avatar-menu-item {
  padding: 7px 12px;
  border-radius: 5px;
  font-size: 13px;
  color: var(--awd-text);
  cursor: pointer;

  &:hover {
    background: var(--awd-bg);
  }

  &.danger {
    color: var(--awd-danger-text);

    &:hover {
      background: var(--awd-danger-soft);
    }
  }
}
</style>

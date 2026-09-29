<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 解锁页（登录后置之后退成薄壳，设计 2026-09-29 §5.1，dev-board#1046）。
       启动不再经过这里（launch 不设门）；路由保留给直链与 e2e 形态断言。
       左栏品牌展示（BrandShowcase，与云端浏览器登录页共用），右侧是与就地登录弹层
       同一个登录卡组件 components/account/AccountLoginDialog.vue（variant="page"）。
       窄窗口（< 1080px）左栏整块不渲染，卡片居中。 -->
  <view class="unlock-page">
    <view class="unlock-split">
      <view class="unlock-showcase">
        <BrandShowcase :site="site" />
      </view>

      <view class="unlock-panel">
        <AccountLoginDialog
          variant="page"
          captcha-id="unlock-captcha"
          @success="onSuccess"
          @language-changed="onLanguageChanged"
          @site-changed="onSiteChanged"
        />
      </view>
    </view>
  </view>
</template>

<script>
import BrandShowcase from '@/components/BrandShowcase.vue'
import AccountLoginDialog from '@/components/account/AccountLoginDialog.vue'

export default {
  name: 'UnlockPage',
  components: { BrandShowcase, AccountLoginDialog },
  data() {
    return {
      site: '',
      // 本页切过语言：离开时整页重载进启动分流，而不是 reLaunch（见 goLaunch）
      langSwitchedHere: false,
    }
  },
  methods: {
    onSiteChanged(site) {
      this.site = site || ''
    },
    onLanguageChanged() {
      this.langSwitchedHere = true
    },
    /**
     * 登录成功：薄壳页自己回启动分流（弹层形态则是把结果交回调用方、原地继续）。
     * 卡片里的一次性说明（换了账户 / 未绑手机号）已经在组件里弹完了。
     */
    onSuccess() {
      setTimeout(() => this.goLaunch(), 300)
    },
    /**
     * 离开解锁页进启动分流。本页就地切过语言时整页重载：各模块顶层取过的静态文案
     * （i18n/index.js 文件头注释）是按启动时的语言算的，reLaunch 不会重建它们。
     * 用 replaceState 改地址再 reload：直接改 hash 会先触发一次路由跳转。
     */
    goLaunch() {
      if (this.langSwitchedHere) {
        try {
          window.history.replaceState(null, '', window.location.href.split('#')[0] + '#/pages/launch/launch')
          window.location.reload()
          return
        } catch (e) { /* 非浏览器环境退回 reLaunch */ }
      }
      uni.reLaunch({ url: '/pages/launch/launch' })
    },
  },
}
</script>

<style lang="scss" scoped>
/* 整页一块底（2026-09-23 §2.1）：暖底渐变 + 左下一团极淡竹月青光晕，左右两栏之间没有可见分界 */
.unlock-page {
  width: 100vw;
  min-height: 100vh;
  box-sizing: border-box;
  background:
    radial-gradient(ellipse 60% 70% at 22% 55%, var(--awd-accent-soft) 0%, transparent 65%),
    linear-gradient(180deg, color-mix(in srgb, var(--awd-surface) 45%, var(--awd-bg)) 0%, var(--awd-bg) 100%);
  overflow-x: hidden;
}

.unlock-split {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
}

.unlock-showcase {
  position: relative;
  min-width: 0;
}

.unlock-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  box-sizing: border-box;
}

/* 窄窗口降级：左栏整块不渲染，卡片居中 */
@media (max-width: 1080px) {
  .unlock-split {
    grid-template-columns: 1fr;
  }

  .unlock-showcase {
    display: none;
  }
}
</style>

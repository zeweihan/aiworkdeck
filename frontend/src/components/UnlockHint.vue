<template>
  <view class="unlock-hint">
    <text class="unlock-hint-text">{{ text }}</text>
    <text class="unlock-hint-link" @tap.stop="onLearnMore">{{ linkText }}</text>
    <view
      v-if="skuId"
      class="unlock-hint-buy"
      :class="{ 'is-busy': purchasing }"
      @tap.stop="onPurchase"
    >
      <text class="unlock-hint-buy-text">{{ purchasing ? t('onboarding.hint.purchasing') : t('onboarding.hint.unlockNow') }}</text>
    </view>
  </view>
</template>

<script>
// 「解锁」引导：功能触达免费额度上限时的统一行内提示。
// 只提示与引导，不做拦截——是否拦、拦在哪由调用方自己判断（配合 useEntitlement）。
// 语气克制：用户没做错事，只是碰到了额度边界。
//
// 2026-08-27（dev-board#187）应用内化：
// - 默认动作不再外跳官网账户页，改为发 awd:open-settings 打开设置「账户与用量」
//   （project-overview 订阅并 openSettingsTab）；显式传 linkUrl 的调用方保持外链行为。
// - 传 skuId 时多一枚「立即解锁」主按钮：确认后从 Credits 余额扣费购买
//  （purchaseFeatureSku），成功刷新权益 + 通知余额 chip；余额不足时给「去充值」。
import { openExternalUrl } from '@/utils/externalLink.js'
import { purchaseFeatureSku } from '@/services/api.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { t } from '@/i18n'

export default {
  name: 'UnlockHint',
  props: {
    // 提示正文，例如「免费版最多回溯 20 条剪贴记录」
    text: {
      type: String,
      required: true,
    },
    linkText: {
      type: String,
      default: () => t('onboarding.hint.learnMore'),
    },
    // 显式外链地址；走系统浏览器（桌面端 window.open 会被主进程吞掉）。
    // 留空 = 应用内打开设置「账户与用量」（充值与解锁都在那里）。
    linkUrl: {
      type: String,
      default: '',
    },
    // 应用内一键解锁的 SKU（feature:clipboard.unlimited / feature:stage.unlimited）。
    // 留空则不渲染「立即解锁」按钮，行为与从前一致。
    skuId: {
      type: String,
      default: '',
    },
  },
  data() {
    return { purchasing: false }
  },
  methods: {
    t,
    onLearnMore() {
      if (this.linkUrl) {
        openExternalUrl(this.linkUrl)
        return
      }
      uni.$emit('awd:open-settings', { nav: 'account' })
    },
    async onPurchase() {
      if (this.purchasing || !this.skuId) return
      const ok = await new Promise((r) => uni.showModal({
        title: t('onboarding.hint.purchaseConfirmTitle'),
        content: t('onboarding.hint.purchaseConfirmContent'),
        confirmText: t('onboarding.hint.purchaseConfirmOk'),
        success: (res) => r(res.confirm),
        fail: () => r(false),
      }))
      if (!ok) return
      this.purchasing = true
      try {
        await purchaseFeatureSku(this.skuId)
        // 后端已同步刷新自己的权益缓存，这里强制重取让前端单例立即更新
        await refreshEntitlements(true)
        // 花了 Credits：顶栏余额 chip 与账户面板都订着这个事件
        uni.$emit('awd:wallet-refresh')
        uni.showToast({ title: t('onboarding.hint.purchaseSuccess'), icon: 'success' })
      } catch (e) {
        if (e && e.reason === 'insufficient_credits') {
          // 余额不足：指路去充值（设置「账户与用量」里有充值按钮）
          uni.showModal({
            title: t('onboarding.hint.purchaseFailed'),
            content: (e && e.message) || '',
            confirmText: t('onboarding.hint.goRecharge'),
            success: (res) => {
              if (res.confirm) uni.$emit('awd:open-settings', { nav: 'account' })
            },
          })
        } else if (e && e.reason === 'already_owned') {
          // 已拥有：权益缓存可能只是旧了，顺手刷新一次
          refreshEntitlements(true)
          uni.showToast({ title: (e && e.message) || t('onboarding.hint.purchaseFailed'), icon: 'none' })
        } else {
          uni.showToast({ title: (e && e.message) || t('onboarding.hint.purchaseFailed'), icon: 'none' })
        }
      } finally {
        this.purchasing = false
      }
    },
  },
}
</script>

<style scoped>
/* 浅色体系：与顶栏「试用版」chip 同一族暖色，避免做成刺眼的警告条 */
.unlock-hint {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 10px;
  background: #fdf7ec;
  border: 1px solid #ecdfc3;
  border-radius: 4px;
}

.unlock-hint-text {
  font-size: 12px;
  line-height: 18px;
  color: #8a6d2f;
}

.unlock-hint-link {
  font-size: 12px;
  line-height: 18px;
  font-weight: 500;
  color: #1a5336;
  cursor: pointer;
}

.unlock-hint-link:hover {
  color: #2d7a52;
  text-decoration: underline;
}

/* 应用内一键解锁：品牌绿实心小按钮 */
.unlock-hint-buy {
  background: #1a5336;
  border-radius: 4px;
  padding: 2px 10px;
  cursor: pointer;
}

.unlock-hint-buy:hover {
  background: #2d7a52;
}

.unlock-hint-buy.is-busy {
  opacity: 0.5;
  pointer-events: none;
}

.unlock-hint-buy-text {
  font-size: 12px;
  line-height: 18px;
  font-weight: 500;
  color: #fff;
}
</style>

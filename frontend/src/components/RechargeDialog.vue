<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog recharge-dialog">
      <view class="awd-header recharge-header">
        <text class="awd-title">{{ $t('admin.rechargeTitle') }}</text>
        <text class="recharge-subtitle">{{ $t('admin.rechargeSubtitle') }}</text>
      </view>
      <view class="awd-body">
        <!-- 步骤一：选金额 -->
        <template v-if="step === 'pick'">
          <text class="recharge-label">{{ $t('admin.rechargeAmountLabel') }}</text>
          <view class="recharge-presets">
            <view
              v-for="cents in presetCents"
              :key="cents"
              class="recharge-preset"
              :class="{ checked: selectedCents === cents && !customInput.trim() }"
              @tap="pickPreset(cents)"
            >
              <text class="recharge-preset-cur">{{ currencySymbol }}</text>
              <text class="recharge-preset-text">{{ (cents / 100).toFixed(0) }}</text>
            </view>
          </view>
          <text class="recharge-label recharge-custom-label">{{ $t('admin.rechargeCustomLabel') }}</text>
          <view class="recharge-custom-row" :class="{ filled: customInput.trim() }">
            <text class="recharge-custom-prefix">{{ currencySymbol }}</text>
            <input
              v-model="customInput"
              class="recharge-custom-input"
              type="digit"
              :placeholder="$t('admin.rechargeCustomPlaceholder')"
            />
          </view>
          <text v-if="inputError" class="recharge-error">{{ inputError }}</text>
        </template>

        <!-- 步骤二 A：站内二维码（微信站） -->
        <template v-else-if="step === 'qrcode'">
          <view class="recharge-qr-wrap">
            <view class="recharge-qr-frame">
              <image v-if="qrDataUrl" :src="qrDataUrl" class="recharge-qr" mode="aspectFit" />
            </view>
            <text class="recharge-hint">{{ $t('admin.rechargeQrHint', { amount: amountText }) }}</text>
            <view class="recharge-waiting-row">
              <view v-if="!pollTimedOut" class="recharge-waiting-dot"></view>
              <text class="recharge-waiting">{{ pollHint }}</text>
            </view>
          </view>
        </template>

        <!-- 步骤二 B：外跳浏览器（Stripe 站） -->
        <template v-else-if="step === 'redirect'">
          <view class="recharge-qr-wrap">
            <text class="recharge-hint">{{ $t('admin.rechargeRedirectHint', { amount: amountText }) }}</text>
            <view class="recharge-waiting-row">
              <view v-if="!pollTimedOut" class="recharge-waiting-dot"></view>
              <text class="recharge-waiting">{{ pollHint }}</text>
            </view>
          </view>
        </template>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="close">{{ $t('common.close') }}</view>
        <view
          v-if="step === 'pick'"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': submitting }"
          @tap="submit"
        >{{ submitLabel }}</view>
      </view>
    </view>
  </view>
</template>

<script>
// 充值弹窗（dev-board#184）：档位按站点（cn ¥50/¥100/¥300，intl $10/$20/$50，与官网
// RechargeDialog 一致）+ 自定义金额；微信站站内渲染二维码，Stripe 站外跳浏览器。
// 两种形态都轮询 getRechargeStatus，paid 即成功。
//
// 轮询清理红线（本仓踩过：Stripe 回跳轮询被 cleanup 掐死）：定时器句柄存在组件实例上，
// beforeUnmount 与「关闭弹窗」都只清**自己的**定时器，不写任何全局 [open] 态互斥逻辑。
import { createAccountRecharge, getRechargeStatus } from '@/services/api.js'
import { siteLinks } from '@/utils/siteLinks.js'
import { openExternalUrl } from '@/utils/externalLink.js'

// 与官网 RechargeDialog 一致的档位（单位：分）
const PRESETS_CN = [5000, 10000, 30000]
const PRESETS_INTL = [1000, 2000, 5000]
// 自定义金额上限：1 万元（与后端 RECHARGE_MAX_CENTS 同）
const MAX_YUAN = 10000
// 轮询：3 秒一次，上限 5 分钟
const POLL_INTERVAL_MS = 3000
const POLL_MAX_MS = 5 * 60 * 1000

export default {
  name: 'RechargeDialog',
  props: {
    visible: { type: Boolean, default: false },
  },
  emits: ['update:visible'],
  data() {
    return {
      step: 'pick', // pick | qrcode | redirect
      selectedCents: 0,
      customInput: '',
      inputError: '',
      submitting: false,
      qrDataUrl: '',
      outTradeNo: '',
      amountCents: 0,
      pollTimedOut: false,
    }
  },
  computed: {
    isCnSite() {
      return siteLinks().current === 'cn'
    },
    currencySymbol() {
      return this.isCnSite ? '¥' : '$'
    },
    presetCents() {
      return this.isCnSite ? PRESETS_CN : PRESETS_INTL
    },
    amountText() {
      return this.currencySymbol + (this.amountCents / 100).toFixed(2)
    },
    pollHint() {
      return this.pollTimedOut
        ? this.$t('admin.rechargeTimeout')
        : this.$t('admin.rechargeWaiting')
    },
    /** 主按钮带上当前金额（自定义金额非法时退回不带金额的文案，错误提示交给 submit）。 */
    submitLabel() {
      if (this.submitting) return this.$t('admin.rechargeSubmitting')
      let cents = this.selectedCents
      if (this.customInput.trim()) cents = this.parseCustomCents(this.customInput)
      if (!cents) return this.$t('admin.rechargeSubmit')
      return this.$t('admin.rechargeSubmit') + ' ' + this.currencySymbol + (cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2))
    },
  },
  watch: {
    visible(v) {
      if (v) {
        this.resetState()
      } else {
        // 弹窗被宿主关掉（v-model）也要停轮询——只清自己的定时器
        this.stopPolling()
      }
    },
  },
  beforeUnmount() {
    this.stopPolling()
  },
  methods: {
    resetState() {
      this.stopPolling()
      this.step = 'pick'
      this.selectedCents = this.presetCents[0]
      this.customInput = ''
      this.inputError = ''
      this.submitting = false
      this.qrDataUrl = ''
      this.outTradeNo = ''
      this.amountCents = 0
      this.pollTimedOut = false
    },
    pickPreset(cents) {
      this.selectedCents = cents
      this.customInput = ''
      this.inputError = ''
    },
    /** 自定义金额（元）→ 分。正数、最多两位小数、不超过 1 万元；非法返回 null。 */
    parseCustomCents(raw) {
      const s = String(raw || '').trim()
      if (!s) return null
      if (!/^\d+(\.\d{1,2})?$/.test(s)) return null
      const yuan = Number(s)
      if (!(yuan > 0) || yuan > MAX_YUAN) return null
      return Math.round(yuan * 100)
    },
    async submit() {
      if (this.submitting) return
      let cents = this.selectedCents
      if (this.customInput.trim()) {
        const parsed = this.parseCustomCents(this.customInput)
        if (parsed == null) {
          this.inputError = this.$t('admin.rechargeInvalidAmount')
          return
        }
        cents = parsed
      }
      if (!cents) {
        this.inputError = this.$t('admin.rechargeInvalidAmount')
        return
      }
      this.inputError = ''
      this.submitting = true
      try {
        const res = await createAccountRecharge(cents)
        this.amountCents = (res && res.amount) || cents
        this.outTradeNo = (res && res.outTradeNo) || ''
        if (res && res.present === 'redirect' && res.redirectUrl) {
          this.step = 'redirect'
          openExternalUrl(res.redirectUrl)
        } else {
          // 微信站：codeUrl（weixin:// 支付串）优先转二维码；官网也可能直接给
          // qrCode（已是 dataURL 图片），有现成图片就直接用
          const qrCode = res && res.qrCode
          const codeUrl = res && res.codeUrl
          if (qrCode && String(qrCode).startsWith('data:')) {
            this.qrDataUrl = qrCode
          } else if (codeUrl || qrCode) {
            // qrcode 库懒加载（照 PersonalSettingsPanel TOTP 的用法），主包不背这个体积
            const QRCode = (await import('qrcode')).default
            this.qrDataUrl = await QRCode.toDataURL(String(codeUrl || qrCode), { margin: 1, width: 200 })
          }
          this.step = 'qrcode'
        }
        if (this.outTradeNo) this.startPolling()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('admin.rechargeCreateFailed'), icon: 'none' })
      } finally {
        this.submitting = false
      }
    },
    startPolling() {
      this.stopPolling()
      this.pollTimedOut = false
      this._pollStartedAt = Date.now()
      this._pollTimer = setInterval(() => this.pollOnce(), POLL_INTERVAL_MS)
    },
    async pollOnce() {
      if (Date.now() - this._pollStartedAt > POLL_MAX_MS) {
        this.stopPolling()
        this.pollTimedOut = true
        return
      }
      try {
        const res = await getRechargeStatus(this.outTradeNo)
        const order = res && res.order
        if (order && order.status === 'paid') {
          this.stopPolling()
          uni.showToast({ title: this.$t('admin.rechargePaid'), icon: 'success' })
          // 余额变了：顶栏 chip 与账户面板都订着这个事件
          uni.$emit('awd:wallet-refresh')
          this.close()
        }
      } catch (e) {
        // 单次查询失败不终止轮询（网络抖动 / 官网慢），超时上限兜底
      }
    },
    stopPolling() {
      if (this._pollTimer) {
        clearInterval(this._pollTimer)
        this._pollTimer = null
      }
    },
    close() {
      this.stopPolling()
      this.$emit('update:visible', false)
    },
  },
}
</script>

<style scoped>
/* 视觉对齐 AwdDialog.vue（dev-board#849）的 mask/panel/按钮令牌与间距——
   同一套弹窗质感，两处各自 scoped 不合并成一个组件。
   dev-board#890：单位统一改用 px（本仓 uni-h5 rpx 恒按 375 设计宽换算，
   1rpx=0.5px；rpx 混着写在这个弹窗上正是自定义金额输入框被裁掉的病灶之一，
   见 .recharge-custom-input 的说明）。 */
.awd-mask {
  position: fixed; inset: 0; z-index: 999;
  display: flex; align-items: center; justify-content: center;
  padding: 16px; box-sizing: border-box;
  background: var(--awd-overlay);
  -webkit-backdrop-filter: blur(3px); backdrop-filter: blur(3px);
}
.awd-dialog {
  width: 420px; max-width: calc(100vw - 32px); max-height: calc(100vh - 64px);
  display: flex; flex-direction: column;
  background: var(--awd-surface); color: var(--awd-text);
  border-radius: 16px; overflow: hidden;
  box-shadow: var(--awd-shadow-lg);
}
.awd-header { padding: 22px 24px 16px; border-bottom: 1px solid var(--awd-border); }
.recharge-header { display: flex; flex-direction: column; gap: 4px; }
.awd-title { font: 600 17px/1.35 var(--awd-font-sans); letter-spacing: -0.01em; color: var(--awd-text); }
.recharge-subtitle { font: 400 13px/1.5 var(--awd-font-sans); color: var(--awd-text-2); }
.awd-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 16px 24px 20px; border-top: 1px solid var(--awd-border);
}
.awd-btn {
  height: 38px; min-width: 88px; box-sizing: border-box;
  padding: 0 18px; border: 1px solid transparent; border-radius: 9px;
  font: 500 14px/36px var(--awd-font-sans); text-align: center; white-space: nowrap;
  cursor: pointer; transition: background .15s ease, border-color .15s ease;
}
.awd-btn-primary { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.awd-btn-primary:hover { background: var(--awd-accent-hover); }
.awd-btn-secondary { background: transparent; color: var(--awd-text); border-color: var(--awd-border); }
.awd-btn-secondary:hover { background: var(--awd-surface-2); }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.recharge-label { font: 400 13px/1.5 var(--awd-font-sans); color: var(--awd-text-2); }
.recharge-presets { display: flex; gap: 10px; margin: 10px 0 18px; }
.recharge-preset {
  flex: 1; display: flex; align-items: baseline; justify-content: center; gap: 2px;
  padding: 16px 0; border: 1.5px solid var(--awd-border); border-radius: 10px; cursor: pointer;
  transition: border-color .15s ease, background .15s ease, box-shadow .15s ease;
}
.recharge-preset:hover { border-color: var(--awd-mint); }
.recharge-preset.checked {
  border-color: var(--awd-accent); background: var(--awd-accent-soft);
  box-shadow: 0 0 0 1px var(--awd-accent) inset;
}
.recharge-preset-cur { font: 600 13px var(--awd-font-sans); color: var(--awd-accent-text); }
.recharge-preset-text { font: 700 22px var(--awd-font-sans); color: var(--awd-accent-text); font-variant-numeric: tabular-nums; }
.recharge-custom-label { display: block; margin-bottom: 6px; }
.recharge-custom-row {
  display: flex; align-items: center; gap: 6px;
  height: 38px; box-sizing: border-box; padding: 0 12px;
  border: 1.5px solid var(--awd-border); border-radius: 9px;
  transition: border-color .15s ease;
}
.recharge-custom-row:focus-within, .recharge-custom-row.filled { border-color: var(--awd-accent); }
.recharge-custom-prefix { flex: none; font: 600 14px var(--awd-font-sans); color: var(--awd-text-2); }
.recharge-custom-row.filled .recharge-custom-prefix { color: var(--awd-accent-text); }
/* dev-board#890 根因：<input> 被 uni-h5 编译成 <uni-input> 宿主元素，
   @dcloudio/uni-components/style/input.css 给 uni-input 写死
   `height:1.4em; min-height:1.4em; overflow:hidden`。旧样式把
   `box-sizing:border-box` + `padding:18rpx 0`（=9px 上下）也落在这同一个宿主上：
   18px padding 吃掉 21px 上下（10000 情形下宿主净高约 18.9px 已经 <18px padding），
   overflow:hidden 一裁，输入的数字只剩不到 1px 的细边，看起来就是一排灰点
   （已用真实浏览器复现：BEFORE 内容区仅 0.9px 高）。
   修法：把高度与内边距移到父级 `.recharge-custom-row`（普通 view，不受
   uni-input.css 影响）；这里只留 `flex:1` + `height:100%`——
   `.recharge-custom-input` 的类选择器 + scoped 属性选择器（specificity 0,2,0）
   本就压得过 `uni-input` 的类型选择器（0,0,1），height:100% 直接覆盖掉
   uni-input.css 的 1.4em，撑满 row 的 38px 高度，不再依赖 border-box 去抵消
   自身 padding。同时给数字一个明确的 color 与等宽数字，别再指望继承。 */
.recharge-custom-input {
  flex: 1; height: 100%;
  border: none; background: transparent;
  font: 400 14px var(--awd-font-sans);
  color: var(--awd-text);
  font-variant-numeric: tabular-nums;
}
.recharge-error { display: block; margin-top: 6px; font: 400 12px var(--awd-font-sans); color: var(--awd-danger-text); }
.recharge-qr-wrap { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 8px 0 4px; }
.recharge-qr-frame {
  padding: 10px; background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 12px;
  box-shadow: var(--awd-shadow-md);
}
.recharge-qr { width: 170px; height: 170px; display: block; }
.recharge-hint { font: 400 14px/1.5 var(--awd-font-sans); color: var(--awd-text); text-align: center; }
.recharge-waiting-row { display: flex; align-items: center; gap: 6px; }
.recharge-waiting-dot {
  width: 6px; height: 6px; border-radius: 50%; background: var(--awd-mint);
  animation: recharge-pulse 1.2s ease-in-out infinite;
}
@keyframes recharge-pulse {
  0%, 100% { opacity: .35; transform: scale(.85); }
  50% { opacity: 1; transform: scale(1); }
}
.recharge-waiting { font: 400 12px var(--awd-font-sans); color: var(--awd-info-text); }

@media (prefers-reduced-motion: reduce) {
  .recharge-waiting-dot { animation: none; }
}
</style>

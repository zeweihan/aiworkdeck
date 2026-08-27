<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog recharge-dialog">
      <view class="awd-header"><text class="awd-title">{{ $t('admin.rechargeTitle') }}</text></view>
      <view class="awd-body">
        <!-- 步骤一：选金额 -->
        <template v-if="step === 'pick'">
          <text class="recharge-label">{{ $t('admin.rechargeAmountLabel') }}</text>
          <view class="recharge-presets">
            <view
              v-for="cents in presetCents"
              :key="cents"
              class="recharge-preset"
              :class="{ checked: selectedCents === cents && !customInput }"
              @tap="pickPreset(cents)"
            >
              <text class="recharge-preset-text">{{ currencySymbol }}{{ (cents / 100).toFixed(0) }}</text>
            </view>
          </view>
          <view class="recharge-custom-row">
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
            <image v-if="qrDataUrl" :src="qrDataUrl" class="recharge-qr" mode="aspectFit" />
            <text class="recharge-hint">{{ $t('admin.rechargeQrHint', { amount: amountText }) }}</text>
            <text class="recharge-waiting">{{ pollHint }}</text>
          </view>
        </template>

        <!-- 步骤二 B：外跳浏览器（Stripe 站） -->
        <template v-else-if="step === 'redirect'">
          <view class="recharge-qr-wrap">
            <text class="recharge-hint">{{ $t('admin.rechargeRedirectHint', { amount: amountText }) }}</text>
            <text class="recharge-waiting">{{ pollHint }}</text>
          </view>
        </template>
      </view>
      <view class="awd-footer">
        <view
          v-if="step === 'pick'"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': submitting }"
          @tap="submit"
        >{{ submitting ? $t('admin.rechargeSubmitting') : $t('admin.rechargeSubmit') }}</view>
        <view class="awd-btn awd-btn-secondary" @tap="close">{{ $t('common.close') }}</view>
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
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; max-height: 74vh; display: flex; flex-direction: column; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; cursor: pointer; }
.awd-btn-primary { background: #1A5336; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.recharge-label { font-size: 24rpx; color: #666; }
.recharge-presets { display: flex; gap: 16rpx; margin: 16rpx 0; }
.recharge-preset {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 20rpx 0; border: 1px solid #ddd; border-radius: 8rpx; cursor: pointer;
}
.recharge-preset.checked { border-color: #1A5336; background: #E6F9F0; }
.recharge-preset-text { font-size: 30rpx; font-weight: 600; color: #1A5336; }
.recharge-custom-row { margin-top: 8rpx; }
.recharge-custom-input {
  width: 100%; box-sizing: border-box; padding: 14rpx 16rpx;
  border: 1px solid #ddd; border-radius: 8rpx; font-size: 26rpx;
}
.recharge-error { display: block; margin-top: 10rpx; font-size: 22rpx; color: #A03B2C; }
.recharge-qr-wrap { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 12rpx 0; }
.recharge-qr { width: 360rpx; height: 360rpx; }
.recharge-hint { font-size: 26rpx; color: #333; text-align: center; }
.recharge-waiting { font-size: 22rpx; color: #999; }
</style>

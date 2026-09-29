<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  登录卡（登录后置设计 2026-09-29 §5.1，dev-board#1046）。从 pages/unlock/unlock.vue 抽出，两处复用：

  - variant="dialog"：就地登录弹层。由 utils/requireAccount.js 在 <body> 下单独 createApp 挂载，
    需要账户的功能（AI、广场付费项、团队、手机同步、会议转写、平台服务）用到时才弹；
    成功 emit('success', 回包)、取消 emit('cancel')，调用方继续原动作，不 reLaunch、不刷新页面。
  - variant="page"：pages/unlock/unlock.vue 这个薄壳页的右侧卡片（直链与 e2e 形态断言用）。

  **标签选择不是风格问题**（同 FeedbackWidget.vue / AwdDialog.vue）：弹层形态挂在页面树之外的
  独立 app 上，uni 的 H5 编译器会把 button / input / textarea / image 改写成 uni 内置组件，
  而那些组件在独立 app 里根本不存在。所以一律 div / span / img，按钮与输入框写成
  `<component :is="'button'">` / `<component :is="'input'">` 拿真原生元素；事件用 @click 不用 @tap。
  v-model 在 `<component :is>` 上会被编译成组件 v-model（modelValue），所以输入框一律 :value + @input。

  站点分段控件、手机号/邮箱验证码、两枚同意勾选、语言切换、试用码/粘 Key（仅 trialCodeEnabled）
  的行为与原解锁页逐条一致，改动只有三处：加「暂不登录」出口；两项同意在提交前记下
  （utils/accountConsent.js，原 completeSetup 的那一半）；首启向导提交整段删掉（后端 DataInitializer 接管）。
-->
<template>
  <div :class="variant === 'dialog' ? 'awd-login-mask' : 'awd-login-inline'" @keydown.esc="onEsc">
    <div
      ref="card"
      class="unlock-card"
      :class="{ 'is-dialog': variant === 'dialog' }"
      :role="variant === 'dialog' ? 'dialog' : null"
      :aria-modal="variant === 'dialog' ? 'true' : null"
      tabindex="-1"
    >
      <div v-if="variant === 'dialog'" class="awd-login-close" role="button" :title="$t('account.loginDialog.close')" @click="cancel">&#x2715;</div>

      <!-- Logo 图片自带 AI WorkDeck 字标，下面不再重复写一行文字标题 -->
      <img class="unlock-logo awd-brand-logo" src="/static/logo_full_v2.png" alt="AI WorkDeck" />

      <!-- 弹层形态：顶部一句说明是哪个功能要账户 -->
      <div v-if="variant === 'dialog'" class="awd-login-reason">{{ reasonText }}</div>

      <!-- 站点分段控件（2026-09-23 §2.2）。单站形态（multiSite=false）整个不渲染；被配置钉定时渲染但不可点。 -->
      <div
        v-if="siteStatus.multiSite"
        class="unlock-site-seg"
        :class="{ 'is-disabled': siteStatus.pinned, 'is-busy': siteBusy || rescueBusy }"
      >
        <span
          v-for="s in siteStatus.sites"
          :key="s.id"
          class="unlock-site-seg-item"
          :class="{ 'is-active': s.id === siteStatus.current }"
          role="button"
          @click="onSiteSegTap(s)"
        >{{ siteSegLabel(s) }}</span>
      </div>

      <!-- 登录与注册是同一条链路（官网验证码端点「不存在即注册」）。用 v-show：
           人机验证控件挂在这块里，切到 Key 模式再切回来时 DOM 不能被销毁重建 -->
      <div v-show="mode !== 'code'" class="unlock-main">
        <div class="unlock-title">{{ $t('onboarding.unlock.title') }}</div>
        <div class="unlock-desc">{{ isPhoneSite ? $t('onboarding.unlock.descPhone') : $t('onboarding.unlock.descEmail') }}</div>
        <div v-if="promoActive" class="unlock-promo">{{ $t('onboarding.unlock.promoLine', { amount: promoAmount }) }}</div>

        <div class="unlock-form">
          <!-- 标识符按站点取：cn 是手机号，intl 是邮箱。两个字段各自保留输入。 -->
          <div v-if="isPhoneSite" class="unlock-field-box">
            <span class="unlock-field-prefix">+86</span>
            <component
              :is="'input'"
              class="unlock-field"
              type="tel"
              inputmode="numeric"
              autocomplete="tel"
              :value="phone"
              :placeholder="$t('onboarding.unlock.phonePlaceholder')"
              @input="phone = $event.target.value"
              @keydown.enter="handlePrimary"
            />
          </div>
          <div v-else class="unlock-field-box">
            <component
              :is="'input'"
              class="unlock-field"
              type="email"
              autocomplete="email"
              :value="email"
              :placeholder="$t('onboarding.unlock.emailPlaceholder')"
              @input="email = $event.target.value"
              @keydown.enter="handlePrimary"
            />
          </div>
          <div class="unlock-code-row">
            <div class="unlock-field-box unlock-field-inline">
              <component
                :is="'input'"
                class="unlock-field"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                :value="smsCode"
                :placeholder="isPhoneSite ? $t('onboarding.unlock.smsPlaceholder') : $t('onboarding.unlock.emailCodePlaceholder')"
                @input="smsCode = $event.target.value"
                @keydown.enter="handlePrimary"
              />
            </div>
            <component
              :is="'button'"
              type="button"
              class="unlock-code-btn"
              :disabled="sendingCode || cooldown > 0 || !codeIdentifier"
              @click="handleSendCode"
            >{{ codeBtnLabel }}</component>
          </div>
          <!-- 人机验证控件挂点。阿里云（大陆站）点了才弹拼图，平时不占版面；
               Turnstile（国际站）走官网托管页，is-embed 给它留位置。未启用时整块不渲染。 -->
          <div
            v-show="captcha"
            class="unlock-captcha-holder"
            :class="{ 'is-embed': captcha && captcha.provider === 'turnstile' }"
          >
            <div :id="captchaId"></div>
            <!-- 阿里云 SDK 要一个它能挂点击事件的元素；Turnstile 用不到但留着无害 -->
            <component :is="'button'" :id="captchaId + '-trigger'" class="unlock-captcha-trigger" type="button"></component>
          </div>
        </div>
      </div>

      <!-- 试用码 / 手工粘 Key：trialCodeEnabled 为真时才有（商业版 / 私有部署 / 自行构建） -->
      <div v-if="mode === 'code'" class="unlock-main">
        <div class="unlock-title">{{ $t('onboarding.unlock.codeTitle') }}</div>
        <div class="unlock-form">
          <component
            :is="'textarea'"
            class="unlock-input"
            :value="code"
            :placeholder="$t('onboarding.unlock.codePlaceholder')"
            @input="code = $event.target.value"
          />
          <div class="unlock-code-links">
            <span v-if="canRescue" class="unlock-link" role="button" @click="handleRescue">
              {{ rescueBusy ? $t('onboarding.unlock.rescueSwitching') : rescueLabel }}
            </span>
            <span class="unlock-link" role="button" @click="openTrialCodePage">{{ $t('onboarding.unlock.getTrialCode') }}</span>
          </div>
        </div>
      </div>

      <!-- 共用提交区。两项同意分两枚勾选框且都不预勾选：《服务条款》《隐私政策》是合同同意；
           跨境传输是个保法第三十九条的「单独同意」，绝不能并进协议一揽子打包。 -->
      <div v-if="errorMsg" class="unlock-error">{{ errorMsg }}</div>
      <component
        :is="'button'"
        type="button"
        class="unlock-btn"
        :class="{ 'is-busy': submitBusy }"
        :disabled="submitBusy"
        @click="handlePrimary"
      >{{ footerLabel }}</component>
      <div class="unlock-consent">
        <div class="consent-row" role="checkbox" :aria-checked="agreementChecked ? 'true' : 'false'" @click="agreementChecked = !agreementChecked">
          <span class="consent-mark" :class="{ checked: agreementChecked }"></span>
          <span class="consent-text">
            {{ $t('onboarding.unlock.agreePrefix') }}
            <span class="unlock-link" @click.stop="openLegalDoc('terms')">{{ $t('onboarding.unlock.termsName') }}</span>
            {{ $t('onboarding.unlock.agreeAnd') }}
            <span class="unlock-link" @click.stop="openLegalDoc('privacy')">{{ $t('onboarding.unlock.privacyName') }}</span>
          </span>
        </div>
        <div class="consent-row" role="checkbox" :aria-checked="crossBorderChecked ? 'true' : 'false'" @click="crossBorderChecked = !crossBorderChecked">
          <span class="consent-mark" :class="{ checked: crossBorderChecked }"></span>
          <span class="consent-text">
            {{ $t('onboarding.unlock.crossBorderLabel') }}
            <span class="unlock-link" @click.stop="showCrossBorderNotice">{{ $t('onboarding.unlock.crossBorderView') }}</span>
          </span>
        </div>
      </div>

      <!-- 底部一行：左边语言切换（语言名用各自的语言写，不翻译），右边 Key 入口 / 返回 / 暂不登录 -->
      <div class="unlock-card-foot">
        <div class="unlock-lang">
          <span class="unlock-lang-item" :class="{ 'is-active': !isEn }" role="button" @click="pickLanguage('zh-CN')">中文</span>
          <span class="unlock-lang-sep">·</span>
          <span class="unlock-lang-item" :class="{ 'is-active': isEn }" role="button" @click="pickLanguage('en-US')">English</span>
        </div>
        <div class="unlock-foot-right">
          <span v-if="mode === 'code'" class="unlock-foot-link" role="button" @click="switchMode('login')">{{ $t('onboarding.unlock.back') }}</span>
          <span v-else-if="trialCodeEnabled" class="unlock-foot-link" role="button" @click="switchMode('code')">{{ $t('onboarding.unlock.useKeyLink') }}</span>
          <span v-if="variant === 'dialog'" class="unlock-foot-link awd-login-cancel" role="button" @click="cancel">{{ $t('account.loginDialog.cancel') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { activateLicense, getLicenseStatus, getSiteStatus, selectSite, sendAccountLoginCode, loginAccount, getAccountCaptchaConfig } from '@/services/api.js'
import { setupCaptcha, teardownCaptcha } from '@/utils/captcha.js'
import { captchaFailureNotice, toCaptchaFailure } from '@/utils/captchaFailure.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { loadSiteLinks, siteBaseUrl, resetSiteLinks } from '@/utils/siteLinks.js'
import { getAppLanguage, setAppLanguage, isLanguageManuallyChosen } from '@/utils/appLanguage.js'
import { siteLanguageToApply } from '@/utils/siteLanguage.js'
import { applyI18nLocale } from '@/i18n/index.js'
import { recordAccountConsents } from '@/utils/accountConsent.js'
import { showDialog } from '@/utils/dialog.js'

// 与站点无关（GitHub README），不走 siteBaseUrl()
const TRIAL_CODE_URL = 'https://github.com/zeweihan/aiworkdeck#readme'

// 共创开发者计划注册赠金的窗口末端：北京时间 2026-10-01 00:00（= 2026-09-30 16:00 UTC）。
const PROMO_END_TS = Date.parse('2026-09-30T16:00:00Z')

// 首装按界面语言预选站点只做一次，落本机标记（与原解锁页同一个键）
const SITE_PRESELECT_KEY = 'awd_site_preselected'

export default {
  name: 'AccountLoginDialog',
  props: {
    // 'dialog'：就地登录弹层；'page'：解锁页薄壳里的卡片
    variant: { type: String, default: 'dialog' },
    // 哪个功能要账户（utils/requireAccountCore.js 的 REASONS），决定弹层顶部那句说明
    reason: { type: String, default: '' },
    // 人机验证挂点 id。弹层与解锁页各用各的，免得两套控件挂进同一个元素
    captchaId: { type: String, default: 'awd-login-captcha' },
  },
  emits: ['success', 'cancel', 'language-changed', 'site-changed'],
  data() {
    return {
      code: '',
      errorMsg: '',
      unlocking: false,
      // 判据只有后端一处（security.license.trial-code.enabled）；查不到时按 true 渲染
      trialCodeEnabled: true,
      siteStatus: { current: '', pinned: false, multiSite: false, sites: [] },
      siteBusy: false,
      rescueBusy: false,
      // 只用来判断切站要不要二次确认。读不到时按「可能有东西会被清掉」处理
      licenseKnown: false,
      licenseMode: '',
      licenseAccountConnected: false,
      mode: 'login',
      phone: '',
      email: '',
      smsCode: '',
      sendingCode: false,
      loggingIn: false,
      cooldown: 0,
      cooldownTimer: null,
      // 人机验证控件。null 且 captchaFailure 为空 = 本站未启用，此时照常发码（官网那边也不会校验）
      captcha: null,
      // 官网说启用了、控件却装不出来：{ provider, reason }。此时绝不盲发码（必 403），
      // 点「获取验证码」先重试一次装配，仍失败就说清楚要放行哪些地址（dev-board#1056）
      captchaFailure: null,
      // 在途的装配（promise）。点按钮时它还没落地就先等它，别趁控件没装好盲发
      captchaSetup: null,
      // 装配代次：切站后重新装配时，先发出的那次若后返回，不许覆盖新站的控件
      captchaGen: 0,
      // 两项同意都绝不预勾选：预勾选的同意无效（跨境那枚还是个保法 39 条的单独同意）
      agreementChecked: false,
      crossBorderChecked: false,
      // 界面语言的响应式副本（appLanguage 的缓存不是响应式的）
      uiLang: getAppLanguage(),
      done: false,
    }
  },
  computed: {
    isPhoneSite() {
      return this.siteStatus.current !== 'intl'
    },
    codeIdentifier() {
      return this.isPhoneSite ? (this.phone || '').trim() : (this.email || '').trim()
    },
    promoActive() {
      return Date.now() < PROMO_END_TS
    },
    promoAmount() {
      return this.isPhoneSite ? '¥99.99' : '$9.90'
    },
    isEn() {
      return this.uiLang === 'en-US'
    },
    reasonText() {
      const key = `account.loginDialog.reason.${this.reason || 'generic'}`
      const text = this.$t(key)
      return text === key ? this.$t('account.loginDialog.reason.generic') : text
    },
    codeBtnLabel() {
      if (this.cooldown > 0) return this.$t('onboarding.unlock.resendIn', { n: this.cooldown })
      return this.sendingCode ? this.$t('onboarding.unlock.sendingCode') : this.$t('onboarding.unlock.sendCode')
    },
    otherSites() {
      return (this.siteStatus.sites || []).filter((s) => s && s.id !== this.siteStatus.current)
    },
    needsSwitchConfirm() {
      return !this.licenseKnown || this.licenseAccountConnected || this.licenseMode === 'account'
    },
    canRescue() {
      return !!this.errorMsg && this.siteStatus.multiSite === true
        && this.otherSites.length > 0 && !!this.normalizedCode
    },
    rescueLabel() {
      return this.otherSites.length === 1
        ? this.$t('onboarding.unlock.rescueToOne', { name: this.otherSites[0].displayName })
        : this.$t('onboarding.unlock.rescueGeneric')
    },
    normalizedCode() {
      return (this.code || '').replace(/\s+/g, '')
    },
    footerLabel() {
      if (this.mode === 'code') {
        return this.unlocking ? this.$t('onboarding.unlock.unlocking') : this.$t('onboarding.unlock.unlock')
      }
      return this.loggingIn ? this.$t('onboarding.unlock.loggingIn') : this.$t('onboarding.unlock.continue')
    },
    submitBusy() {
      return this.loggingIn || this.unlocking
    },
  },
  mounted() {
    loadSiteLinks()
    this.bootstrapSite()
    this.setupCaptchaWidget()
    if (this.variant === 'dialog') {
      // 焦点进弹层：Esc 才进得来，工作台的快捷键也不会被这次输入误触
      this.$nextTick(() => {
        try { this.$refs.card && this.$refs.card.focus() } catch (e) { /* ignore */ }
      })
    }
  },
  beforeUnmount() {
    if (this.cooldownTimer) clearInterval(this.cooldownTimer)
    // 托管页 iframe 的 message 监听挂在 window 上，走了也要摘
    teardownCaptcha()
  },
  methods: {
    onEsc() {
      if (this.variant === 'dialog') this.cancel()
    },
    cancel() {
      if (this.done) return
      this.done = true
      this.$emit('cancel')
    },
    async bootstrapSite() {
      await Promise.all([this.refreshSiteStatus(), this.refreshTrialGate()])
      await this.maybePreselectSite()
    },
    /** 首装按界面语言预选站点：非中文 → 国际站，否则大陆站。只做一次，且只在切站不会清掉任何东西时做。 */
    async maybePreselectSite() {
      const st = this.siteStatus
      if (!st.multiSite || st.pinned || !st.current) return
      try {
        if (uni.getStorageSync(SITE_PRESELECT_KEY)) return
      } catch (e) { /* 读不到按没做过处理 */ }
      try { uni.setStorageSync(SITE_PRESELECT_KEY, '1') } catch (e) { /* ignore */ }
      if (this.needsSwitchConfirm) return
      const want = getAppLanguage() === 'zh-CN' ? 'cn' : 'intl'
      if (want === st.current) return
      const target = (st.sites || []).find((s) => s && s.id === want)
      if (target) await this.switchSite(target)
    },
    /**
     * 装配人机验证控件。**配置读不到只是不装**，不拦路——
     * 官网没启用时本来就不校验，而配置读不到时为此把人挡在门外不划算
     * （发码本身还有官网的 IP 限流与全局熔断兜着）。
     * 但配置说启用了（provider 非空）、控件却装不出来，就记进 captchaFailure，
     * 不能再按「未启用」处理：那样会不带 token 盲发，官网必 403（dev-board#1056）。
     * setupCaptcha 回 null 只有两种情形：官网未启用；或装配被更新的一次取代 / 挂点已随弹层卸载
     * （这两种情形本组件的代次检查或卸载已经让结果无人消费），都不算「装不出来」。
     */
    setupCaptchaWidget() {
      const run = this.runCaptchaSetup()
      this.captchaSetup = run
      return run
    },
    async runCaptchaSetup() {
      const gen = ++this.captchaGen
      this.captcha = null
      this.captchaFailure = null
      // #ifdef H5
      teardownCaptcha()
      try {
        const holder = document.getElementById(this.captchaId)
        if (holder) holder.innerHTML = ''
      } catch (e) { /* ignore */ }
      // #endif
      let config = null
      try {
        config = await getAccountCaptchaConfig()
      } catch (e) {
        console.warn('人机验证配置读取失败（按未启用处理）:', e && e.message)
        return
      }
      if (gen !== this.captchaGen) return
      try {
        const widget = await setupCaptcha(config, this.captchaId)
        if (gen === this.captchaGen) this.captcha = widget
      } catch (e) {
        console.warn('人机验证组件加载失败:', e && e.message)
        if (gen === this.captchaGen) this.captchaFailure = toCaptchaFailure(e, config && config.provider)
      }
    },
    /** 已知装不出来：装配时就失败了，或控件装上之后才报失败（阿里云 onError、托管页一直不 ready）。 */
    currentCaptchaFailure() {
      if (this.captchaFailure) return this.captchaFailure
      const w = this.captcha
      return (w && typeof w.loadError === 'function' && w.loadError()) || null
    },
    /**
     * 取发码要带的 token。回 `{ token }`（未启用时 token 为空串）、
     * `{ failure }`（组件加载失败）或 `{ rejected: true }`（控件在、但没通过）。
     */
    async acquireCaptchaToken() {
      // 装配还在路上：等它落地，别趁控件没装好不带 token 发出去
      if (this.captchaSetup) await this.captchaSetup
      // 已知装不出来：先自动重试一次装配（网络可能已经放行、托管页可能只是慢）
      if (this.currentCaptchaFailure()) await this.setupCaptchaWidget()
      const failure = this.currentCaptchaFailure()
      if (failure) return { failure }
      const widget = this.captcha
      if (!widget) return { token: '' }
      const token = await widget.getToken()
      if (token) return { token }
      const late = typeof widget.loadError === 'function' ? widget.loadError() : null
      return late ? { failure: late } : { rejected: true }
    },
    captchaFailureMessage(failure) {
      const { key, params } = captchaFailureNotice({ provider: failure && failure.provider, siteBaseUrl: siteBaseUrl() })
      return this.$t(key, params)
    },
    async refreshTrialGate() {
      try {
        const s = await getLicenseStatus()
        this.trialCodeEnabled = !(s && s.trialCodeEnabled === false)
        this.licenseMode = (s && s.mode) || ''
        this.licenseAccountConnected = !!(s && s.accountConnected)
        this.licenseKnown = true
        if (!this.trialCodeEnabled && this.mode === 'code') this.mode = 'login'
      } catch (e) {
        console.warn('读取解锁门配置失败（按试用码可用渲染）:', e && e.message)
      }
    },
    async refreshSiteStatus() {
      try {
        const s = await getSiteStatus()
        this.siteStatus = {
          current: (s && s.current) || '',
          pinned: !!(s && s.pinned),
          multiSite: !!(s && s.multiSite),
          sites: (s && s.sites) || [],
        }
        // 解锁页的品牌展示区按站点换文案，跟着这里走
        this.$emit('site-changed', this.siteStatus.current)
      } catch (e) {
        // 拿不到就当单站，站点入口不渲染
      }
    },
    switchMode(next) {
      if (this.mode === next) return
      this.mode = next
      this.errorMsg = ''
    },
    async handleSendCode() {
      if (this.sendingCode || this.cooldown > 0) return
      const identifier = this.codeIdentifier
      if (!identifier) {
        this.errorMsg = this.isPhoneSite
          ? this.$t('onboarding.unlock.phoneFirst')
          : this.$t('onboarding.unlock.emailFirst')
        return
      }
      this.errorMsg = ''
      this.sendingCode = true
      try {
        // 先取人机验证 token 再发。拿不到就别发——发了必被官网 403，白让用户等一轮。
        const got = await this.acquireCaptchaToken()
        if (got.failure) {
          this.errorMsg = this.captchaFailureMessage(got.failure)
          this.sendingCode = false
          return
        }
        if (got.rejected) {
          this.errorMsg = this.$t('onboarding.unlock.captchaFailed')
          this.sendingCode = false
          return
        }
        const captchaToken = got.token
        await sendAccountLoginCode(identifier, captchaToken, this.isPhoneSite)
        uni.showToast({ title: this.$t('onboarding.unlock.codeSent'), icon: 'none', duration: 1600 })
        this.startCooldown(60)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.loginFailed')
      } finally {
        this.sendingCode = false
      }
    },
    stopCooldown() {
      if (this.cooldownTimer) clearInterval(this.cooldownTimer)
      this.cooldownTimer = null
      this.cooldown = 0
    },
    startCooldown(seconds) {
      this.cooldown = seconds
      if (this.cooldownTimer) clearInterval(this.cooldownTimer)
      this.cooldownTimer = setInterval(() => {
        this.cooldown -= 1
        if (this.cooldown <= 0) {
          clearInterval(this.cooldownTimer)
          this.cooldownTimer = null
          this.cooldown = 0
        }
      }, 1000)
    },
    handlePrimary() {
      if (this.submitBusy) return
      if (this.mode === 'code') this.handleUnlock()
      else this.handleLogin()
    },
    /** 两项同意是提交前置：不满足时把提示给在勾选框旁边，而不是提交失败之后。 */
    consentGatePassed() {
      if (!this.agreementChecked) {
        this.errorMsg = this.$t('onboarding.unlock.agreementRequired')
        return false
      }
      if (!this.crossBorderChecked) {
        this.errorMsg = this.$t('onboarding.unlock.crossBorderRequired')
        return false
      }
      return true
    },
    openLegalDoc(kind) {
      const locale = (this.$i18n && this.$i18n.locale) || 'zh'
      const lang = String(locale).toLowerCase().startsWith('en') ? 'en' : 'zh'
      openExternalUrl(`${siteBaseUrl()}/${lang}/legal/${kind}`)
    },
    showCrossBorderNotice() {
      uni.showModal({
        title: this.$t('onboarding.unlock.crossBorderNoticeTitle'),
        content: this.$t('onboarding.unlock.crossBorderNoticeBody'),
        showCancel: false,
        confirmText: this.$t('onboarding.unlock.gotIt'),
      })
    },
    async handleLogin() {
      if (!this.consentGatePassed()) return
      const identifier = this.codeIdentifier
      const smsCode = (this.smsCode || '').trim()
      if (!identifier) {
        this.errorMsg = this.isPhoneSite
          ? this.$t('onboarding.unlock.phoneFirst')
          : this.$t('onboarding.unlock.emailFirst')
        return
      }
      if (!smsCode) {
        this.errorMsg = this.isPhoneSite
          ? this.$t('onboarding.unlock.smsCodeFirst')
          : this.$t('onboarding.unlock.emailCodeFirst')
        return
      }
      const payload = this.isPhoneSite
        ? { phone: identifier, code: smsCode }
        : { email: identifier, code: smsCode }
      this.errorMsg = ''
      this.loggingIn = true
      try {
        // 同意落在连接之前：连上账户就是内容开始能出境的时点（accountConsent.js 文件头）
        await recordAccountConsents()
        const res = await loginAccount(payload)
        await this.finishLogin(res, {
          title: res && res.isNewUser
            ? this.$t('onboarding.unlock.registered')
            : this.$t('onboarding.unlock.loggedIn'),
        })
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.loginFailed')
      } finally {
        this.loggingIn = false
      }
    },
    async handleUnlock() {
      if (!this.consentGatePassed()) return
      const code = this.normalizedCode
      if (!code) {
        this.errorMsg = this.$t('onboarding.unlock.pasteFirst')
        return
      }
      this.errorMsg = ''
      this.unlocking = true
      try {
        await recordAccountConsents()
        const res = await activateLicense(code)
        await this.finishUnlock(res)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.unlockFailed')
      } finally {
        this.unlocking = false
      }
    },
    async finishUnlock(res) {
      const mode = res && res.mode
      // 粘 awdk_ Key 时解锁与账户连接是两件事，后者失败要让用户看见
      const accountNotice = (res && res.accountNotice) || ''
      uni.showToast({
        title: mode === 'trial' ? this.$t('onboarding.unlock.trialUnlocked')
          : accountNotice ? this.$t('onboarding.unlock.fullUnlocked') : this.$t('onboarding.unlock.accountAndUnlocked'),
        icon: 'success',
        duration: 1600,
      })
      if (accountNotice) {
        await showDialog({
          title: this.$t('onboarding.unlock.accountNoticeTitle'),
          content: accountNotice,
          showCancel: false,
          confirmText: this.$t('onboarding.unlock.gotIt'),
        })
      }
      await this.finishLogin({ ...(res || {}), connected: !!(res && res.accountConnected) }, null)
    },
    /**
     * 登录（或粘 Key）成功后的收尾：一次性说明 → emit('success')。
     * 两条说明都**不阻断**：关掉就继续原动作。
     */
    async finishLogin(res, toast) {
      if (toast) uni.showToast({ title: toast.title, icon: 'success', duration: 1600 })
      // 换了一个账户（后端 previousAccountDiffers，一次性）：维护者拍板「本机项目属于这台电脑」
      if (res && res.previousAccountDiffers) {
        await showDialog({
          title: this.$t('account.loginDialog.switchedTitle'),
          content: this.$t('account.loginDialog.switchedBody'),
          showCancel: false,
          confirmText: this.$t('account.loginDialog.gotIt'),
        })
      }
      // 存量账号还没绑手机号：提示去官网绑定。**不阻断**——补绑硬期限之前照常能用
      if (res && res.mustBindPhone) {
        const r = await showDialog({
          title: this.$t('onboarding.unlock.mustBindTitle'),
          content: this.$t('onboarding.unlock.mustBindBody'),
          confirmText: this.$t('onboarding.unlock.openWebsite'),
          cancelText: this.$t('onboarding.unlock.gotIt'),
        })
        if (r && r.confirm) this.openOfficialSite()
      }
      if (this.done) return
      this.done = true
      this.$emit('success', res || {})
    },
    siteSegLabel(site) {
      if (site && site.id === 'cn') return this.$t('onboarding.unlock.siteCn')
      if (site && site.id === 'intl') return this.$t('onboarding.unlock.siteIntl')
      return (site && site.displayName) || ''
    },
    onSiteSegTap(site) {
      if (!site || site.id === this.siteStatus.current) return
      if (this.siteStatus.pinned || this.siteBusy || this.rescueBusy) return
      if (this.needsSwitchConfirm) this.confirmSwitchSite(site)
      else this.switchSite(site)
    },
    confirmSwitchSite(target) {
      uni.showModal({
        title: this.$t('onboarding.unlock.switchSiteTitle'),
        content: this.$t('onboarding.unlock.switchSiteContent', { name: target.displayName }),
        confirmText: this.$t('onboarding.unlock.switch'),
        cancelText: this.$t('onboarding.unlock.cancel'),
        success: (res) => {
          if (res.confirm) this.switchSite(target)
        },
      })
    },
    async switchSite(target) {
      this.siteBusy = true
      this.errorMsg = ''
      try {
        await selectSite(target.id)
        resetSiteLinks()
        await this.refreshSiteStatus()
        await this.refreshTrialGate()
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.switchFailed')
        return
      } finally {
        this.siteBusy = false
      }
      // 验证码只对发给的那个手机号/邮箱有效，换了站就是换了收件目标
      this.smsCode = ''
      this.stopCooldown()
      // 先切语言再装配：国际站托管页的语言随 URL 参数定
      this.followSiteLanguage(target.id)
      this.setupCaptchaWidget()
    },
    /** 切站后界面语言随动（dev-board#864）；用户亲手选过语言就尊重用户。 */
    followSiteLanguage(siteId) {
      const lang = siteLanguageToApply({ siteId, current: this.uiLang, manual: isLanguageManuallyChosen() })
      if (lang) this.applyLanguage(lang, { auto: true })
    },
    pickLanguage(lang) {
      if (lang === this.uiLang) return
      this.applyLanguage(lang)
      if (this.captcha && this.captcha.provider === 'turnstile') this.setupCaptchaWidget()
    },
    /** 就地切语言：持久化 + 广播、vue-i18n 全局 locale、本组件的响应式副本三处一起改。 */
    applyLanguage(lang, opts = {}) {
      setAppLanguage(lang, opts)
      applyI18nLocale(lang)
      this.uiLang = lang
      this.$emit('language-changed', lang)
    },
    handleRescue() {
      if (this.rescueBusy || this.siteBusy) return
      const others = this.otherSites
      if (others.length === 1) {
        this.switchSiteAndRetry(others[0])
        return
      }
      uni.showActionSheet({
        itemList: others.map((s) => s.displayName),
        success: (res) => {
          const target = others[res.tapIndex]
          if (target) this.switchSiteAndRetry(target)
        },
        fail: () => {},
      })
    },
    async switchSiteAndRetry(target) {
      this.rescueBusy = true
      try {
        await selectSite(target.id)
        resetSiteLinks()
        await this.refreshSiteStatus()
        const res = await activateLicense(this.normalizedCode)
        this.errorMsg = ''
        await this.finishUnlock(res)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.rescueFailed')
      } finally {
        this.rescueBusy = false
      }
    },
    openTrialCodePage() {
      openExternalUrl(TRIAL_CODE_URL)
    },
    openOfficialSite() {
      openExternalUrl(siteBaseUrl())
    },
  },
}
</script>

<style lang="scss" scoped>
/* ---------- 弹层形态：遮罩 + 居中卡片 ----------
   z-index 9990：低于 AwdDialog 宿主（10000）——卡片里弹的「跨境说明」「换了账户」要盖在它上面；
   高于工作台自家的 .awd-mask（9999 以下的业务浮层），并且 requireAccount.js 持有全局浮层让
   BrowserView 让开（overlayState.js）。 */
.awd-login-mask {
  position: fixed;
  inset: 0;
  z-index: 9990;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  box-sizing: border-box;
  overflow-y: auto;
  background: var(--awd-overlay, rgba(35, 32, 26, 0.38));
  backdrop-filter: blur(3px);
  font-family: var(--awd-font-sans, inherit);
}

.awd-login-inline {
  display: contents;
}

.unlock-card.is-dialog {
  position: relative;
  margin: auto;
  box-shadow: 0 24px 64px rgba(35, 32, 26, 0.28);
  animation: awd-login-in 160ms ease-out;
}

@keyframes awd-login-in {
  from { opacity: 0; transform: scale(0.97); }
  to { opacity: 1; transform: scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .unlock-card.is-dialog { animation: none; }
}

.unlock-card:focus {
  outline: none;
}

.awd-login-close {
  position: absolute;
  top: 14px;
  right: 16px;
  width: 28px;
  height: 28px;
  line-height: 28px;
  text-align: center;
  border-radius: 8px;
  font-size: 14px;
  color: var(--awd-text-3);
  cursor: pointer;

  &:hover {
    background: var(--awd-surface-2);
    color: var(--awd-text);
  }
}

.awd-login-reason {
  margin-top: 18px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--awd-accent-soft);
  color: var(--awd-text);
  font-size: 13px;
  line-height: 1.6;
}

/* ---------- 以下与原解锁页卡片逐条一致（原生元素的重置补在各自选择器里） ---------- */

.unlock-captcha-trigger {
  width: 0;
  height: 0;
  padding: 0;
  border: 0;
  opacity: 0;
  position: absolute;
}

.unlock-captcha-holder.is-embed {
  margin-top: 12px;
  min-height: 65px;
  max-width: 100%;
  overflow: hidden;
}

.unlock-card {
  width: 400px;
  max-width: calc(100vw - 48px);
  box-sizing: border-box;
  background: var(--awd-surface);
  border: 1px solid var(--awd-glass-border);
  border-radius: 18px;
  box-shadow: var(--awd-shadow-sm), var(--awd-shadow-lg);
  padding: 36px 40px 28px;
  display: flex;
  flex-direction: column;
  text-align: left;
}

.unlock-logo {
  height: 30px;
  width: auto;
  align-self: flex-start;
}

.unlock-site-seg {
  display: flex;
  padding: 3px;
  margin: 26px 0 22px;
  background: var(--awd-surface-2);
  border-radius: 10px;

  &.is-disabled .unlock-site-seg-item {
    cursor: default;
  }

  &.is-busy {
    opacity: 0.7;
  }
}

.awd-login-reason + .unlock-site-seg {
  margin-top: 16px;
}

.unlock-site-seg-item {
  flex: 1;
  text-align: center;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  line-height: 30px;
  color: var(--awd-text-2);
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;

  &.is-active {
    background: var(--awd-surface);
    color: var(--awd-text);
    border-color: var(--awd-border);
    box-shadow: var(--awd-shadow-sm);
  }
}

/* 单站形态没有分段控件，标题与上方之间补回同样的间距 */
.unlock-logo + .unlock-main,
.awd-login-reason + .unlock-main {
  margin-top: 26px;
}

.unlock-main {
  display: flex;
  flex-direction: column;
}

.unlock-title {
  font-size: 20px;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--awd-text);
  margin-bottom: 6px;
}

.unlock-desc {
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text-2);
  margin-bottom: 22px;
}

.unlock-promo {
  margin: -12px 0 18px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--awd-gold-text);
}

.unlock-form {
  display: flex;
  flex-direction: column;
}

.unlock-field-box {
  height: 44px;
  box-sizing: border-box;
  margin-bottom: 12px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  background: var(--awd-surface);
  transition: border-color 0.15s, box-shadow 0.15s;

  &:focus-within {
    border-color: var(--awd-accent);
    box-shadow: 0 0 0 3px var(--awd-accent-soft);
  }
}

.unlock-field-prefix {
  flex-shrink: 0;
  padding-right: 10px;
  border-right: 1px solid var(--awd-border);
  font-size: 15px;
  font-weight: 500;
  color: var(--awd-text-2);
}

.unlock-field {
  flex: 1;
  min-width: 0;
  height: 42px;
  padding: 0;
  border: 0;
  outline: none;
  font-size: 15px;
  font-family: inherit;
  color: var(--awd-text);
  background: transparent;

  &::placeholder {
    color: var(--awd-text-3);
  }
}

.unlock-code-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.unlock-field-inline {
  flex: 1;
  min-width: 0;
}

.unlock-code-btn {
  flex-shrink: 0;
  height: 44px;
  line-height: 42px;
  margin: 0;
  padding: 0 14px;
  background: var(--awd-surface);
  color: var(--awd-accent-text);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;

  &:hover:not([disabled]) {
    border-color: var(--awd-accent);
  }

  &[disabled] {
    color: var(--awd-text-3);
    background: var(--awd-surface);
    cursor: default;
  }
}

.unlock-input {
  width: 100%;
  height: 88px;
  box-sizing: border-box;
  padding: 12px 14px;
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  outline: none;
  resize: none;
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text);
  background: var(--awd-surface);
  font-family: 'SF Mono', Menlo, Consolas, monospace;

  &:focus {
    border-color: var(--awd-accent);
    box-shadow: 0 0 0 3px var(--awd-accent-soft);
  }

  &::placeholder {
    color: var(--awd-text-3);
  }
}

.unlock-code-links {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.unlock-error {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--awd-danger-text);
}

.unlock-btn {
  margin: 8px 0 0;
  width: 100%;
  height: 46px;
  line-height: 46px;
  padding: 0;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border: none;
  border-radius: 11px;
  font-size: 15px;
  font-weight: 600;
  font-family: inherit;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: var(--awd-accent-hover);
  }

  &.is-busy {
    opacity: 0.7;
  }
}

.unlock-consent {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.consent-row {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  cursor: pointer;
}

.consent-mark {
  flex-shrink: 0;
  width: 15px;
  height: 15px;
  margin-top: 2px;
  box-sizing: border-box;
  border: 1.5px solid var(--awd-border-strong);
  border-radius: 4px;
  background: var(--awd-surface);
  transition: background 0.15s, border-color 0.15s;

  &.checked {
    border-color: var(--awd-accent);
    background: var(--awd-accent) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath fill='none' stroke='%23fff' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round' d='M3.5 8.5l3 3 6-7'/%3E%3C/svg%3E") center / 11px no-repeat;
  }
}

.consent-text {
  font-size: 12px;
  line-height: 1.55;
  color: var(--awd-text-2);
}

.unlock-link {
  font-size: 12px;
  color: var(--awd-accent-text);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.unlock-card-foot {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--awd-border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  line-height: 1;
  color: var(--awd-text-3);
}

.unlock-lang {
  display: flex;
  align-items: center;
  gap: 6px;
}

.unlock-lang-item {
  cursor: pointer;

  &.is-active {
    color: var(--awd-text);
    font-weight: 500;
    cursor: default;
  }
}

.unlock-foot-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.unlock-foot-link {
  color: var(--awd-text-2);
  cursor: pointer;

  &:hover {
    color: var(--awd-accent-text);
  }
}
</style>

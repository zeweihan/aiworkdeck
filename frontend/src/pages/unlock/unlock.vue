<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 解锁门：桌面首启的唯一关卡（设计 2026-09-23 §2）。整页一块底，左栏是品牌展示
       （BrandShowcase，与云端浏览器登录页共用），右侧一张悬浮的登录卡。
       窄窗口（< 1080px）左栏整块不渲染，卡片居中。 -->
  <view class="unlock-page">
    <view class="unlock-split">
      <view class="unlock-showcase">
        <BrandShowcase :site="siteStatus.current" />
      </view>

      <view class="unlock-panel">
        <view class="unlock-card">
          <!-- Logo 图片自带 AI WorkDeck 字标，下面不再重复写一行文字标题 -->
          <image class="unlock-logo awd-brand-logo" src="/static/logo_full_v2.png" mode="heightFix" />

          <!-- 站点分段控件（§2.2）。桌面账户是官网账户，两站账户库独立、币种与支付通道不同，
               所以这里切的是站点而不是「手机号 / 邮箱」。单站形态（multiSite=false）整个不渲染；
               被配置钉定时渲染但不可点。 -->
          <view
            v-if="siteStatus.multiSite"
            class="unlock-site-seg"
            :class="{ 'is-disabled': siteStatus.pinned, 'is-busy': siteBusy || rescueBusy }"
          >
            <text
              v-for="s in siteStatus.sites"
              :key="s.id"
              class="unlock-site-seg-item"
              :class="{ 'is-active': s.id === siteStatus.current }"
              @tap="onSiteSegTap(s)"
            >{{ siteSegLabel(s) }}</text>
          </view>

          <!-- 登录与注册是同一条链路（官网验证码端点「不存在即注册」，回包带 isNewUser），
               页面上只有一个入口。用 v-show 而不是 v-if：人机验证控件挂在这块里，
               切到 Key 模式再切回来时 DOM 不能被销毁重建，否则控件就挂丢了。 -->
          <view v-show="mode !== 'code'" class="unlock-main">
            <text class="unlock-title">{{ $t('onboarding.unlock.title') }}</text>
            <text class="unlock-desc">{{ isPhoneSite ? $t('onboarding.unlock.descPhone') : $t('onboarding.unlock.descEmail') }}</text>
            <!-- 共创开发者计划。窗口一过（北京时间 2026-10-01 起）这条就会失真，
                 所以按本机时间直接不渲染，不留一句过期的承诺在登录页上。 -->
            <text v-if="promoActive" class="unlock-promo">{{ $t('onboarding.unlock.promoLine', { amount: promoAmount }) }}</text>

            <view class="unlock-form">
              <!-- 标识符按站点取：cn 是手机号，intl 是邮箱。两个字段各自保留输入，
                   来回切站不会把已填的内容互相覆盖。 -->
              <view v-if="isPhoneSite" class="unlock-field-box">
                <text class="unlock-field-prefix">+86</text>
                <input
                  class="unlock-field"
                  v-model="phone"
                  type="number"
                  :placeholder="$t('onboarding.unlock.phonePlaceholder')"
                  placeholder-class="unlock-placeholder"
                />
              </view>
              <view v-else class="unlock-field-box">
                <input
                  class="unlock-field"
                  v-model="email"
                  :placeholder="$t('onboarding.unlock.emailPlaceholder')"
                  placeholder-class="unlock-placeholder"
                />
              </view>
              <view class="unlock-code-row">
                <view class="unlock-field-box unlock-field-inline">
                  <input
                    class="unlock-field"
                    v-model="smsCode"
                    type="number"
                    :placeholder="isPhoneSite ? $t('onboarding.unlock.smsPlaceholder') : $t('onboarding.unlock.emailCodePlaceholder')"
                    placeholder-class="unlock-placeholder"
                  />
                </view>
                <button
                  class="unlock-code-btn"
                  :disabled="sendingCode || cooldown > 0 || !codeIdentifier"
                  @tap="handleSendCode"
                >
                  {{ codeBtnLabel }}
                </button>
              </view>
              <!-- 人机验证控件挂点。阿里云（大陆站）是点了才弹拼图，平时不占版面；
                   Turnstile（国际站）走官网托管页 iframe（file:// 下没法直接 render），
                   managed 模式平时就是一个 300x65 的小框，is-embed 给它留位置。
                   未启用时整块不渲染。 -->
              <view
                v-show="captcha"
                class="unlock-captcha-holder"
                :class="{ 'is-embed': captcha && captcha.provider === 'turnstile' }"
              >
                <view id="unlock-captcha"></view>
                <!-- 阿里云 SDK 要一个它能挂点击事件的元素；Turnstile 用不到但留着无害 -->
                <button id="unlock-captcha-trigger" class="unlock-captcha-trigger" type="button"></button>
              </view>
            </view>
          </view>

          <!-- 试用码 / 手工粘 Key：**trialCodeEnabled 为真时才有**，由卡片底部链接进入——
               那是商业版 / 私有部署 / 自行构建的入口（application-desktop.yml 刻意留的开关）；
               官方发布版关着它，这一块与底部入口都不渲染。 -->
          <view v-if="mode === 'code'" class="unlock-main">
            <text class="unlock-title">{{ $t('onboarding.unlock.codeTitle') }}</text>
            <view class="unlock-form">
              <textarea
                class="unlock-input"
                v-model="code"
                :placeholder="$t('onboarding.unlock.codePlaceholder')"
                placeholder-class="unlock-placeholder"
                :maxlength="-1"
              />
              <!-- 注意：不要在 textarea 上挂 @input 清 errorMsg——uni-textarea 在错误文案渲染
                   引发布局变化时会补发一次 input 事件，错误提示会被立刻清掉（联调实测）。
                   errorMsg 在每次点击解锁时重置，足够。 -->
              <view class="unlock-code-links">
                <!-- 站点错配救济：国际站账户的 Key 粘到国内站会被判「Key 无效」，
                     而 Key 本身是好的。这里给一条一键切站重试的出路，省得用户跑去
                     官网重新生成 Key 再撞一次同样的墙。 -->
                <text v-if="canRescue" class="unlock-link" @tap="handleRescue">
                  {{ rescueBusy ? $t('onboarding.unlock.rescueSwitching') : rescueLabel }}
                </text>
                <text class="unlock-link" @tap="openTrialCodePage">{{ $t('onboarding.unlock.getTrialCode') }}</text>
              </view>
            </view>
          </view>

          <!-- 共用提交区：错误提示、主按钮与两项同意对两种模式一视同仁。
               同意分两枚勾选框且都不预勾选：《服务条款》《隐私政策》是合同同意；
               跨境传输是个保法第三十九条的「单独同意」，绝不能并进协议一揽子打包
               （打包的不叫单独同意，还留下刻意规避的书面证据）。 -->
          <text v-if="errorMsg" class="unlock-error">{{ errorMsg }}</text>
          <button
            class="unlock-btn"
            :class="{ 'is-busy': submitBusy }"
            :disabled="submitBusy"
            @tap="handlePrimary"
          >
            {{ footerLabel }}
          </button>
          <view class="unlock-consent">
            <view class="consent-row" @tap="agreementChecked = !agreementChecked">
              <view class="consent-mark" :class="{ checked: agreementChecked }"></view>
              <text class="consent-text">
                {{ $t('onboarding.unlock.agreePrefix') }}
                <text class="unlock-link" @tap.stop="openLegalDoc('terms')">{{ $t('onboarding.unlock.termsName') }}</text>
                {{ $t('onboarding.unlock.agreeAnd') }}
                <text class="unlock-link" @tap.stop="openLegalDoc('privacy')">{{ $t('onboarding.unlock.privacyName') }}</text>
              </text>
            </view>
            <view class="consent-row" @tap="crossBorderChecked = !crossBorderChecked">
              <view class="consent-mark" :class="{ checked: crossBorderChecked }"></view>
              <text class="consent-text">
                {{ $t('onboarding.unlock.crossBorderLabel') }}
                <text class="unlock-link" @tap.stop="showCrossBorderNotice">{{ $t('onboarding.unlock.crossBorderView') }}</text>
              </text>
            </view>
          </view>

          <!-- 底部一行：左边语言切换（§2.4，语言名用各自的语言写，不翻译），
               右边是 Key 入口（仅 trialCodeEnabled）或从 Key 模式返回。 -->
          <view class="unlock-card-foot">
            <view class="unlock-lang">
              <text class="unlock-lang-item" :class="{ 'is-active': !isEn }" @tap="pickLanguage('zh-CN')">中文</text>
              <text class="unlock-lang-sep">·</text>
              <text class="unlock-lang-item" :class="{ 'is-active': isEn }" @tap="pickLanguage('en-US')">English</text>
            </view>
            <text v-if="mode === 'code'" class="unlock-foot-link" @tap="switchMode('login')">{{ $t('onboarding.unlock.back') }}</text>
            <text v-else-if="trialCodeEnabled" class="unlock-foot-link" @tap="switchMode('code')">{{ $t('onboarding.unlock.useKeyLink') }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { activateLicense, getLicenseStatus, getSiteStatus, selectSite, sendAccountLoginCode, loginAccount, getAccountCaptchaConfig, getWizardStatus, submitWizard, acceptLegalAgreement } from '@/services/api.js'
import { setupCaptcha, teardownCaptcha } from '@/utils/captcha.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { loadSiteLinks, siteBaseUrl, resetSiteLinks } from '@/utils/siteLinks.js'
import { getAppLanguage, setAppLanguage, isEnglish, isLanguageManuallyChosen } from '@/utils/appLanguage.js'
import BrandShowcase from '@/components/BrandShowcase.vue'

// 与站点无关（GitHub README），不走 siteBaseUrl()
const TRIAL_CODE_URL = 'https://github.com/zeweihan/aiworkdeck#readme'

// 登录页展示的《服务条款》《隐私政策》组合版本。协议实质内容变更时 +1 日期，
// 后端只记录「哪个版本在何时被同意过」（legal.userAgreement.*），不据此设闸。
const AGREEMENT_VERSION = '2026-08-27'

// 共创开发者计划注册赠金的窗口末端：北京时间 2026-10-01 00:00（= 2026-09-30 16:00 UTC）。
// 到点之后推广位整块不渲染——服务端那边的窗口也在同一时刻关。
const PROMO_END_TS = Date.parse('2026-09-30T16:00:00Z')

// 首装按界面语言预选站点（§2.2）只做一次：做过就落这个标记，之后永远以用户在分段控件上的选择为准。
// 用本机标记而不是「site.json 在不在」：GET /api/site 的回包不带这个信息（§6 回包不变），
// 而国际站开放之前没有任何安装能写出 site.json，两种判据在存量机器上等价。
const SITE_PRESELECT_KEY = 'awd_site_preselected'

export default {
  name: 'UnlockPage',
  components: { BrandShowcase },
  data() {
    return {
      code: '',
      errorMsg: '',
      unlocking: false,
      // 官方发布版关掉了试用码这条解锁路（后端 security.license.trial-code.enabled）。
      // 判据只有后端一处，前端不自己猜；查不到时按 true 渲染——老后端与查询失败
      // 都不该把「试用码 / Key」这一整页藏掉，那会让手工粘 Key 的人无路可走。
      trialCodeEnabled: true,
      // 站点第一次真正生效就是解锁请求，所以站点选择必须落在这一页：
      // 启动分流页不承载业务 UI，首启向导与设置页都在解锁之后
      siteStatus: { current: '', pinned: false, multiSite: false, sites: [] },
      siteBusy: false,
      rescueBusy: false,
      // 本机授权与账户连接状态，只用来判断切站要不要二次确认（§2.2）。
      // 读不到（licenseKnown=false）时按「可能有东西会被清掉」处理，照旧确认。
      licenseKnown: false,
      licenseMode: '',
      licenseAccountConnected: false,
      // 'login' 是验证码登录（登录与注册同一条链路），'code' 是试用码 / 手工粘 Key 那条路
      mode: 'login',
      phone: '',
      email: '',
      smsCode: '',
      sendingCode: false,
      loggingIn: false,
      cooldown: 0,
      cooldownTimer: null,
      // 人机验证控件。null = 本站未启用或装配失败，此时照常发码（官网那边也不会校验）
      captcha: null,
      // 装配代次：切站后重新装配时，先发出的那次若后返回，不许覆盖新站的控件
      captchaGen: 0,
      // 两项同意都绝不预勾选：预勾选的同意无效（跨境那枚还是个保法 39 条的单独同意）
      agreementChecked: false,
      crossBorderChecked: false,
    }
  },
  beforeUnmount() {
    // 不清的话切走这一页还留着一个每秒跑的定时器
    if (this.cooldownTimer) clearInterval(this.cooldownTimer)
    // 托管页 iframe 的 message 监听挂在 window 上，页面走了也要摘
    teardownCaptcha()
  },
  computed: {
    /**
     * 大陆站用手机号+验证码，国际站用邮箱+验证码。
     * 站点未知时按手机号渲染：内置站点就是 cn。
     */
    isPhoneSite() {
      return this.siteStatus.current !== 'intl'
    },
    /** 本站验证码登录用的标识符：cn 是手机号，intl 是邮箱。 */
    codeIdentifier() {
      return this.isPhoneSite ? (this.phone || '').trim() : (this.email || '').trim()
    },
    /**
     * 注册赠金推广位还在不在窗口内。两站都有赠金（2026-08-19 维护者拍板），
     * 金额按站点分流：cn ¥99.99 / intl $9.90——都是各自官网侧真配了的数，
     * 改金额要连服务器 data/gateway-config.json 的 signupGrantCents 一起改。
     */
    promoActive() {
      return Date.now() < PROMO_END_TS
    },
    promoAmount() {
      return this.isPhoneSite ? '¥99.99' : '$9.90'
    },
    isEn() {
      return isEnglish()
    },
    codeBtnLabel() {
      if (this.cooldown > 0) return this.$t('onboarding.unlock.resendIn', { n: this.cooldown })
      return this.sendingCode ? this.$t('onboarding.unlock.sendingCode') : this.$t('onboarding.unlock.sendCode')
    },
    otherSites() {
      return (this.siteStatus.sites || []).filter((s) => s && s.id !== this.siteStatus.current)
    },
    /**
     * 切站要不要二次确认：只有本机确实有东西会被清掉时才问——已连账户、或用账户 Key 解锁。
     * 解锁页上的常态是两样都没有，此时切站只是换一个登录目标，弹确认只会吓到人。
     */
    needsSwitchConfirm() {
      return !this.licenseKnown || this.licenseAccountConnected || this.licenseMode === 'account'
    },
    canRescue() {
      // 有码可重试、且确实有别的站可切时才给出路
      return !!this.errorMsg && this.siteStatus.multiSite === true
        && this.otherSites.length > 0 && !!this.normalizedCode
    },
    rescueLabel() {
      return this.otherSites.length === 1
        ? this.$t('onboarding.unlock.rescueToOne', { name: this.otherSites[0].displayName })
        : this.$t('onboarding.unlock.rescueGeneric')
    },
    // 自动去掉粘贴带进来的空白与换行
    normalizedCode() {
      return (this.code || '').replace(/\s+/g, '')
    },
    /** 共用主按钮：code 模式是解锁口径，登录模式是「继续」。 */
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
  onLoad() {
    // 两个请求都不能阻塞解锁：失败一律按单站处理
    loadSiteLinks()
    this.bootstrapSite()
    this.setupCaptchaWidget()
  },
  methods: {
    /** 站点与授权状态都到手之后，才轮得到「首装按语言预选站点」。 */
    async bootstrapSite() {
      await Promise.all([this.refreshSiteStatus(), this.refreshTrialGate()])
      await this.maybePreselectSite()
    },
    /**
     * 首装按界面语言预选站点（§2.2）：非中文 → 国际站，否则大陆站。只做一次，
     * 且只在切站不会清掉任何东西时做——已连账户的机器上替用户切站是破坏性动作。
     */
    async maybePreselectSite() {
      const st = this.siteStatus
      if (!st.multiSite || st.pinned || !st.current) return
      try {
        if (uni.getStorageSync(SITE_PRESELECT_KEY)) return
      } catch (e) { /* 读不到按没做过处理 */ }
      // 先落标记再切：切失败也不再反复重试，用户手里还有分段控件
      try { uni.setStorageSync(SITE_PRESELECT_KEY, '1') } catch (e) { /* ignore */ }
      if (this.needsSwitchConfirm) return
      const want = getAppLanguage() === 'zh-CN' ? 'cn' : 'intl'
      if (want === st.current) return
      const target = (st.sites || []).find((s) => s && s.id === want)
      if (target) await this.switchSite(target)
    },
    /**
     * 装配人机验证控件。**任何一步失败都只是不装**，不拦路——
     * 官网没启用时本来就不校验，而配置读不到时为此把人挡在门外不划算
     * （发码本身还有官网的 IP 限流与全局熔断兜着）。
     */
    async setupCaptchaWidget() {
      const gen = ++this.captchaGen
      this.captcha = null
      // #ifdef H5
      // 重新装配（切站后）先拆掉托管页控件（连同 message 监听）再清空挂点，
      // 免得新旧两套控件叠在同一个元素里
      teardownCaptcha()
      try {
        const holder = document.getElementById('unlock-captcha')
        if (holder) holder.innerHTML = ''
      } catch (e) { /* ignore */ }
      // #endif
      try {
        const config = await getAccountCaptchaConfig()
        if (gen !== this.captchaGen) return
        const widget = await setupCaptcha(config, 'unlock-captcha')
        if (gen === this.captchaGen) this.captcha = widget
      } catch (e) {
        console.warn('人机验证控件装配失败（按未启用处理）:', e && e.message)
        if (gen === this.captchaGen) this.captcha = null
      }
    },
    /** 试用码这条路还开不开。失败一律按「开着」处理，不拦路（见 data 里的注释）。 */
    async refreshTrialGate() {
      try {
        const s = await getLicenseStatus()
        this.trialCodeEnabled = !(s && s.trialCodeEnabled === false)
        this.licenseMode = (s && s.mode) || ''
        this.licenseAccountConnected = !!(s && s.accountConnected)
        this.licenseKnown = true
        // Key 入口被撤时 mode 必须回到 login——否则残留状态会把人卡在一个
        // 已经没有入口可切回来的表单上
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
        let captchaToken = ''
        if (this.captcha) {
          captchaToken = await this.captcha.getToken()
          if (!captchaToken) {
            this.errorMsg = this.$t('onboarding.unlock.captchaFailed')
            this.sendingCode = false
            return
          }
        }
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
    /**
     * 登录与注册是同一个动作：官网的验证码校验端点对没见过的手机号/邮箱是
     * 「不存在即注册」（返回体带 isNewUser），所以页面上只有一个入口、一条链路。
     */
    handlePrimary() {
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
    /** 《服务条款》/《隐私政策》按当前站点与界面语言打开官网对应页。 */
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
    /**
     * 登录/解锁成功后的一次性收尾：记录协议同意版本；向导已整体下线（2026-08-27），
     * 全新安装改在这里完成首启初始化——写入官方通道与跨境同意
     * （后端 POST /api/admin/wizard 的两道闸原样在用，只是没有向导页了）。
     * 失败不拦路：AI 设置页仍能补救，用户先进产品。
     */
    async completeSetup() {
      try {
        await acceptLegalAgreement(AGREEMENT_VERSION)
      } catch (e) {
        console.warn('记录协议同意失败（忽略）:', e && e.message)
      }
      try {
        const wiz = await getWizardStatus()
        if (wiz && wiz.initialized === false) {
          await submitWizard({ ai: { activeProvider: 'AWD_CLOUD', crossBorderConsent: true } })
        }
      } catch (e) {
        console.warn('首启初始化失败（可在 AI 设置中补救）:', e && e.message)
      }
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
      // 字段名按站点分：cn 是 phone，intl 是 email
      const payload = this.isPhoneSite
        ? { phone: identifier, code: smsCode }
        : { email: identifier, code: smsCode }
      this.errorMsg = ''
      this.loggingIn = true
      try {
        const res = await loginAccount(payload)
        this.applyLoginResult(res)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.loginFailed')
      } finally {
        this.loggingIn = false
      }
    },
    async applyLoginResult(res) {
      uni.showToast({
        // 是不是新账户由服务端说了算（isNewUser）：页面上本来就只有一个入口
        title: res && res.isNewUser
          ? this.$t('onboarding.unlock.registered')
          : this.$t('onboarding.unlock.loggedIn'),
        icon: 'success',
        duration: 1600,
      })
      // 协议记录与首启初始化先做完再走分流：reLaunch 之后这个页面就没了
      await this.completeSetup()
      // 存量账号还没绑手机号：提示去官网绑定。**不阻断进入产品**——补绑硬期限之前
      // 他们照常能用，到期后官网那侧会直接拒发 Key，那时才是真的进不来。
      if (res && res.mustBindPhone) {
        setTimeout(() => {
          uni.showModal({
            title: this.$t('onboarding.unlock.mustBindTitle'),
            content: this.$t('onboarding.unlock.mustBindBody'),
            confirmText: this.$t('onboarding.unlock.openWebsite'),
            cancelText: this.$t('onboarding.unlock.gotIt'),
            success: (r) => {
              if (r.confirm) this.openOfficialSite()
            },
            complete: () => uni.reLaunch({ url: '/pages/launch/launch' }),
          })
        }, 900)
        return
      }
      setTimeout(() => {
        uni.reLaunch({ url: '/pages/launch/launch' })
      }, 800)
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
        const res = await activateLicense(code)
        this.applyUnlockResult(res)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.unlockFailed')
      } finally {
        this.unlocking = false
      }
    },
    async applyUnlockResult(res) {
      const mode = res && res.mode
      // 粘 awdk_ Key 时解锁与账户连接是两件事，后者失败过去被完全吞掉：
      // 用户看到「已连接账户」进了产品，账户却是未连接状态而毫无感知
      const accountNotice = (res && res.accountNotice) || ''
      uni.showToast({
        // 账户连接未完成时不能说「已连接账户」（随后弹窗会说明未完成）
        title: mode === 'trial' ? this.$t('onboarding.unlock.trialUnlocked')
          : accountNotice ? this.$t('onboarding.unlock.fullUnlocked') : this.$t('onboarding.unlock.accountAndUnlocked'),
        icon: 'success',
        duration: 1600,
      })
      if (accountNotice) {
        setTimeout(() => {
          uni.showModal({
            title: this.$t('onboarding.unlock.accountNoticeTitle'),
            content: accountNotice,
            showCancel: false,
            confirmText: this.$t('onboarding.unlock.gotIt'),
            // 提示不阻断进入产品：无论怎么关掉都继续走启动分流
            complete: () => uni.reLaunch({ url: '/pages/launch/launch' }),
          })
        }, 900)
        return
      }
      setTimeout(() => {
        uni.reLaunch({ url: '/pages/launch/launch' })
      }, 800)
    },
    /** 分段控件的名字按站点 id 取；未知 id 回落后端的 displayName。 */
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
    /** 本机有账户连接或账户票据时切站是破坏性动作，必须二次确认并列清代价 */
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
      if (this.followSiteLanguage(target.id)) return
      // 两站的人机验证配置各自独立（官网各配各的），切站后按新站重新装配。
      // 不 await：控件脚本走外网加载，等它会让分段控件在这段时间里点不动
      this.setupCaptchaWidget()
    },
    /**
     * 选国际站时，用户从没亲手选过界面语言就顺带切到英文（§2.4）；选过就尊重用户。
     * 切语言必须整页 reload（i18n 单例与各处静态 label 都要重建）。返回 true 表示即将 reload。
     */
    followSiteLanguage(siteId) {
      if (siteId !== 'intl' || isEnglish() || isLanguageManuallyChosen()) return false
      setAppLanguage('en-US', { auto: true })
      setTimeout(() => {
        try { window.location.reload() } catch (e) { /* 非浏览器环境忽略 */ }
      }, 600)
      return true
    },
    /** 底部语言切换：用户亲手选的，之后选站不再替他改语言。 */
    pickLanguage(lang) {
      if (lang === getAppLanguage()) return
      setAppLanguage(lang)
      // 延迟给 App.vue 的镜像同步（主进程 IPC + 后端 POST）留出发出的窗口，再整页 reload
      setTimeout(() => {
        try { window.location.reload() } catch (e) { /* 非浏览器环境忽略 */ }
      }, 600)
    },
    /** 失败救济：这条路不再二次确认，错误文案本身就是上下文 */
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
        // 成功之后才清错误：留着旧错误，救济入口在整个过程里都不会闪没
        this.errorMsg = ''
        this.applyUnlockResult(res)
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.rescueFailed')
      } finally {
        this.rescueBusy = false
      }
    },
    openTrialCodePage() {
      openExternalUrl(TRIAL_CODE_URL)
    },
    /** 仍留着：未绑手机号的弹窗要把人送到官网账户页 */
    openOfficialSite() {
      openExternalUrl(siteBaseUrl())
    },
  },
}
</script>

<style lang="scss" scoped>
/* 触发元素必须存在且可被 click()，所以用 0 尺寸而不是 display:none——
   display:none 的元素 SDK 挂不上事件，控件永远弹不出来。 */
.unlock-captcha-trigger {
  width: 0;
  height: 0;
  padding: 0;
  border: 0;
  opacity: 0;
  position: absolute;
}

/* 国际站托管页 iframe（300x65，随 size 消息长高）：宽度不超过表单列，
   窄卡片下 iframe 自身 max-width:100% 收窄而不是把卡片撑出横向滚动；
   大陆站（阿里云弹窗）不带 is-embed，挂点保持零占位。 */
.unlock-captcha-holder.is-embed {
  margin-top: 12px;
  min-height: 65px;
  max-width: 100%;
  overflow: hidden;
}

/* 整页一块底（§2.1）：暖底渐变 + 左下一团极淡竹月青光晕，左右两栏之间没有可见分界 */
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

/* ---------- 右侧登录卡 ---------- */

.unlock-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  box-sizing: border-box;
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
}

.unlock-logo {
  height: 30px;
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

/* 单站形态没有分段控件，标题与 Logo 之间补回同样的间距 */
.unlock-logo + .unlock-main {
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

/* 共创赠金：标题下一行浅色提示，不抢说明文字的位置 */
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

/* 输入框外壳：边框与焦点态挂在外壳上，+86 前缀和输入框同在一个框里 */
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
  font-size: 15px;
  color: var(--awd-text);
  background: transparent;
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
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;

  &::after {
    border: none;
  }

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
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text);
  background: var(--awd-surface);
  font-family: 'SF Mono', Menlo, Consolas, monospace;

  &:focus {
    border-color: var(--awd-accent);
    box-shadow: 0 0 0 3px var(--awd-accent-soft);
  }
}

.unlock-code-links {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.unlock-placeholder {
  color: var(--awd-text-3);
  font-size: 15px;
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
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border: none;
  border-radius: 11px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: background 0.2s;

  &::after {
    border: none;
  }

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

.unlock-foot-link {
  color: var(--awd-text-2);
  cursor: pointer;

  &:hover {
    color: var(--awd-accent-text);
  }
}

/* 窄窗口降级：左栏整块不渲染，卡片居中。1080px 以下两栏放不下还留得出呼吸感 */
@media (max-width: 1080px) {
  .unlock-split {
    grid-template-columns: 1fr;
  }

  .unlock-showcase {
    display: none;
  }
}
</style>

<template>
  <!-- 解锁门：桌面首启的唯一关卡。浅色双栏——左侧品牌视觉区（跟随鼠标的产品 mockup），
       右侧账户卡。窄窗口收成单栏、视觉区整块不渲染。 -->
  <view class="unlock-page" @mousemove="handleMouseMove">
    <view class="unlock-stage">
      <!-- 品牌视觉区。纯装饰：里面一个字都没有，只有线框与水印——
           登录页不该出现任何需要单独维护、且可能与产品实际能力对不上的宣传语。 -->
      <view class="unlock-showcase">
        <view class="showcase-glow showcase-glow-a"></view>
        <view class="showcase-glow showcase-glow-b"></view>
        <view class="showcase-stage" :style="{ transform: showcaseTransform }">
          <view class="mock-window">
            <view class="mock-titlebar">
              <view class="mock-dot"></view>
              <view class="mock-dot"></view>
              <view class="mock-dot"></view>
            </view>
            <view class="mock-body">
              <view class="mock-rail">
                <view class="mock-rail-item is-active"></view>
                <view class="mock-rail-item"></view>
                <view class="mock-rail-item"></view>
                <view class="mock-rail-item"></view>
              </view>
              <view class="mock-sidebar">
                <view class="mock-line" style="width: 78%"></view>
                <view class="mock-line mock-line-indent" style="width: 62%"></view>
                <view class="mock-line mock-line-indent is-active" style="width: 70%"></view>
                <view class="mock-line mock-line-indent" style="width: 54%"></view>
                <view class="mock-line" style="width: 66%"></view>
                <view class="mock-line mock-line-indent" style="width: 58%"></view>
              </view>
              <view class="mock-editor">
                <view class="mock-tabs">
                  <view class="mock-tab is-active"></view>
                  <view class="mock-tab"></view>
                </view>
                <view class="mock-doc">
                  <view class="mock-doc-title"></view>
                  <view class="mock-doc-line" style="width: 94%"></view>
                  <view class="mock-doc-line" style="width: 88%"></view>
                  <view class="mock-doc-line mock-doc-line-mark" style="width: 72%"></view>
                  <view class="mock-doc-line" style="width: 91%"></view>
                  <view class="mock-doc-line" style="width: 64%"></view>
                </view>
                <image class="mock-watermark" src="/static/monochrome.png" mode="aspectFit" />
              </view>
              <view class="mock-ai">
                <view class="mock-bubble"></view>
                <view class="mock-bubble is-user"></view>
                <view class="mock-bubble"></view>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view class="unlock-panel">
        <view class="unlock-card">
          <!-- Logo 图片自带 AI WorkDeck 字标，下面不再重复写一行文字标题 -->
          <image class="unlock-logo" src="/static/logo_full_v2.png" mode="heightFix" />
          <text class="unlock-subtitle">{{ $t('onboarding.unlock.subtitle') }}</text>

          <!-- 登录与注册走的是同一条链路（官网验证码端点「不存在即注册」），
               这里切的只是文案与强调，接口一行不换。
               第三个页签是试用码 / 手工粘 Key：**trialCodeEnabled 为真时才有**——
               那是商业版 / 私有部署 / 自行构建的入口（application-desktop.yml 刻意留的开关），
               砍掉等于把那条路堵死；官方发布版关着它，页签就只剩登录与注册两个。 -->
          <view class="unlock-tabs">
            <text class="unlock-tab" :class="{ 'is-active': mode === 'login' }" @tap="switchMode('login')">
              {{ $t('onboarding.unlock.loginTab') }}
            </text>
            <text class="unlock-tab" :class="{ 'is-active': mode === 'register' }" @tap="switchMode('register')">
              {{ $t('onboarding.unlock.registerTab') }}
            </text>
            <text
              v-if="trialCodeEnabled"
              class="unlock-tab"
              :class="{ 'is-active': mode === 'code' }"
              @tap="switchMode('code')"
            >
              {{ codeTabLabel }}
            </text>
          </view>

          <!-- 共创开发者计划。窗口一过（北京时间 2026-10-01 起）这条就会失真，
               所以按本机时间直接不渲染，不留一句过期的承诺在登录页上。
               只在大陆站展示：赠金是官网 cn 侧配的，金额也是人民币。 -->
          <view v-if="promoActive && mode !== 'code'" class="unlock-promo" :class="{ 'is-strong': mode === 'register' }">
            <text class="unlock-promo-title">{{ $t('onboarding.unlock.promoTitle') }}</text>
            <text class="unlock-promo-body">
              {{ mode === 'register' ? $t('onboarding.unlock.promoBodyRegister', { amount: promoAmount }) : $t('onboarding.unlock.promoBody', { amount: promoAmount }) }}
            </text>
          </view>

          <view v-if="mode !== 'code'" class="unlock-form">
            <!-- 标识符按站点取：cn 是手机号，intl 是邮箱。两站都是「验证码即登录」，
                 只是通道不同——与官网 AuthForms 的 channel 分叉是同一套口径。 -->
            <input
              v-if="isPhoneSite"
              class="unlock-field"
              v-model="phone"
              type="number"
              :placeholder="$t('onboarding.unlock.phonePlaceholder')"
              placeholder-class="unlock-placeholder"
            />
            <input
              v-else
              class="unlock-field"
              v-model="email"
              :placeholder="$t('onboarding.unlock.emailPlaceholder')"
              placeholder-class="unlock-placeholder"
            />
            <view class="unlock-code-row">
              <input
                class="unlock-field unlock-field-inline"
                v-model="smsCode"
                type="number"
                :placeholder="$t('onboarding.unlock.smsPlaceholder')"
                placeholder-class="unlock-placeholder"
              />
              <button
                class="unlock-code-btn"
                :disabled="sendingCode || cooldown > 0 || !codeIdentifier"
                @tap="handleSendCode"
              >
                {{ codeBtnLabel }}
              </button>
            </view>
            <!-- 人机验证控件挂点。Turnstile 是隐形的、阿里云是点了才弹拼图，
                 所以平时这里不占版面；未启用时整块不渲染。 -->
            <view v-show="captcha" class="unlock-captcha-holder">
              <view id="unlock-captcha"></view>
              <!-- 阿里云 SDK 要一个它能挂点击事件的元素；Turnstile 用不到但留着无害 -->
              <button id="unlock-captcha-trigger" class="unlock-captcha-trigger" type="button"></button>
            </view>

            <text v-if="errorMsg" class="unlock-error">{{ errorMsg }}</text>
            <button
              class="unlock-btn"
              :class="{ 'is-busy': loggingIn }"
              :disabled="loggingIn"
              @tap="handleLogin"
            >
              {{ primaryLabel }}
            </button>
            <text v-if="mode === 'register'" class="unlock-hint unlock-register-hint">
              {{ isPhoneSite ? $t('onboarding.unlock.registerHintPhone') : $t('onboarding.unlock.registerHintEmail') }}
            </text>
          </view>

          <view v-else class="unlock-form">
            <textarea
              class="unlock-input"
              v-model="code"
              :placeholder="codePlaceholder"
              placeholder-class="unlock-placeholder"
              :maxlength="-1"
            />
            <!-- 注意：不要在 textarea 上挂 @input 清 errorMsg——uni-textarea 在错误文案渲染
                 引发布局变化时会补发一次 input 事件，错误提示会被立刻清掉（联调实测）。
                 errorMsg 在每次点击解锁时重置，足够。 -->
            <text v-if="errorMsg" class="unlock-error">{{ errorMsg }}</text>
            <!-- 站点错配救济：国际站账户的 Key 粘到国内站会被判「Key 无效」，
                 而 Key 本身是好的。这里给一条一键切站重试的出路，省得用户跑去
                 官网重新生成 Key 再撞一次同样的墙。 -->
            <text
              v-if="canRescue"
              class="unlock-link unlock-rescue"
              @tap="handleRescue"
            >
              {{ rescueBusy ? $t('onboarding.unlock.rescueSwitching') : rescueLabel }}
            </text>
            <button
              class="unlock-btn"
              :class="{ 'is-busy': unlocking }"
              :disabled="unlocking"
              @tap="handleUnlock"
            >
              {{ unlocking ? $t('onboarding.unlock.unlocking') : $t('onboarding.unlock.unlock') }}
            </button>
          </view>

          <!-- 两条外链都已撤：「获取正式版」在「注册即正式版」之后是错的指路，
               「获取试用码」只跟着试用码这条路一起存在。整行可能一项都不剩，所以整体条件渲染。 -->
          <view v-if="trialCodeEnabled || showSiteRow" class="unlock-links">
            <text v-if="trialCodeEnabled" class="unlock-link" @tap="openTrialCodePage">
              {{ $t('onboarding.unlock.getTrialCode') }}
            </text>
            <text v-if="trialCodeEnabled && showSiteRow" class="unlock-link-sep">|</text>
            <!-- 单站形态（multiSite=false）下整段不渲染，用户看不到任何变化 -->
            <template v-if="showSiteRow">
              <text v-if="siteStatus.pinned" class="unlock-site-fixed">{{ $t('onboarding.unlock.siteLabel', { name: currentSiteName }) }}</text>
              <text v-else class="unlock-link" @tap="openSitePicker">{{ siteLinkLabel }}</text>
            </template>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { activateLicense, getLicenseStatus, getSiteStatus, selectSite, sendAccountLoginCode, loginAccount, getAccountCaptchaConfig } from '@/services/api.js'
import { setupCaptcha } from '@/utils/captcha.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { loadSiteLinks, siteBaseUrl, resetSiteLinks } from '@/utils/siteLinks.js'

// 与站点无关（GitHub README），不走 siteBaseUrl()
const TRIAL_CODE_URL = 'https://github.com/zeweihan/aiworkdeck#readme'

// 共创开发者计划注册赠金的窗口末端：北京时间 2026-10-01 00:00（= 2026-09-30 16:00 UTC）。
// 到点之后推广位整块不渲染——服务端那边的窗口也在同一时刻关。
const PROMO_END_TS = Date.parse('2026-09-30T16:00:00Z')

export default {
  name: 'UnlockPage',
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
      // 'login' / 'register' 是同一条链路的两副文案（见 handleLogin 的注释），
      // 'code' 是试用码 / 手工粘 Key 那条路
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
      // 视觉区的鼠标视差。0..1 的归一化位置；motionOn=false 时整块不动
      // （系统「减少动态效果」开着，或非 H5 环境拿不到鼠标）
      pointerX: 0,
      pointerY: 0.5,
      motionOn: true,
    }
  },
  beforeUnmount() {
    // 不清的话切走这一页还留着一个每秒跑的定时器
    if (this.cooldownTimer) clearInterval(this.cooldownTimer)
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
    /** 主按钮文案：注册态换口径，赠金窗口内再点名赠金。 */
    primaryLabel() {
      if (this.mode === 'register') {
        if (this.loggingIn) return this.$t('onboarding.unlock.registering')
        return this.promoActive
          ? this.$t('onboarding.unlock.registerWithGrant')
          : this.$t('onboarding.unlock.register')
      }
      return this.loggingIn ? this.$t('onboarding.unlock.loggingIn') : this.$t('onboarding.unlock.login')
    },
    codeBtnLabel() {
      if (this.cooldown > 0) return this.$t('onboarding.unlock.resendIn', { n: this.cooldown })
      return this.sendingCode ? this.$t('onboarding.unlock.sendingCode') : this.$t('onboarding.unlock.sendCode')
    },
    /**
     * 视觉区跟随鼠标的等比例旋转，与 pages/login/login.vue 同一套口径：
     * 鼠标在左边缘时侧转，移到登录卡（约 60% 宽）时正面朝前。
     * 竖直方向另给一点轻微俯仰，幅度刻意小——大了会晃眼。
     */
    showcaseTransform() {
      if (!this.motionOn) return 'none'
      const threshold = 0.6
      const p = Math.min(Math.max(this.pointerX / threshold, 0), 1)
      const rotY = 22 * (1 - p)
      const rotX = 8 * (1 - p) + (0.5 - this.pointerY) * 5
      const scale = 0.95 + 0.05 * p
      const translateX = -40 * (1 - p)
      return `perspective(1800px) rotateY(${rotY}deg) rotateX(${rotX}deg) scale(${scale}) translateX(${translateX}px)`
    },
    currentSite() {
      const sites = this.siteStatus.sites || []
      return sites.find((s) => s && s.id === this.siteStatus.current) || null
    },
    currentSiteName() {
      return (this.currentSite && this.currentSite.displayName) || ''
    },
    otherSites() {
      return (this.siteStatus.sites || []).filter((s) => s && s.id !== this.siteStatus.current)
    },
    showSiteRow() {
      return this.siteStatus.multiSite === true && !!this.currentSiteName
    },
    siteLinkLabel() {
      return this.siteBusy
        ? this.$t('onboarding.unlock.siteSwitching')
        : this.$t('onboarding.unlock.siteLabel', { name: this.currentSiteName })
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
    /** 关掉试用码之后这个标签事实上只收账户 Key，名字与提示都要跟着改口。 */
    codeTabLabel() {
      return this.trialCodeEnabled
        ? this.$t('onboarding.unlock.codeTab')
        : this.$t('onboarding.unlock.keyTab')
    },
    codePlaceholder() {
      return this.trialCodeEnabled
        ? this.$t('onboarding.unlock.codePlaceholder')
        : this.$t('onboarding.unlock.keyPlaceholder')
    },
    emptyInputHint() {
      return this.trialCodeEnabled
        ? this.$t('onboarding.unlock.pasteFirst')
        : this.$t('onboarding.unlock.keyPasteFirst')
    },
  },
  onLoad() {
    // 两个请求都不能阻塞解锁：失败一律按单站处理
    loadSiteLinks()
    this.refreshSiteStatus()
    this.refreshTrialGate()
    this.setupCaptchaWidget()
    this.detectMotionPreference()
  },
  methods: {
    /** 系统「减少动态效果」开着就不做视差——这类偏好设置一律尊重，不给开关。 */
    detectMotionPreference() {
      // #ifdef H5
      try {
        this.motionOn = !window.matchMedia('(prefers-reduced-motion: reduce)').matches
      } catch (e) {
        this.motionOn = true
      }
      // #endif
    },
    handleMouseMove(e) {
      // #ifdef H5
      if (!this.motionOn) return
      const w = window.innerWidth || 1
      const h = window.innerHeight || 1
      this.pointerX = Math.min(Math.max((e.clientX || 0) / w, 0), 1)
      this.pointerY = Math.min(Math.max((e.clientY || 0) / h, 0), 1)
      // #endif
    },
    /**
     * 装配人机验证控件。**任何一步失败都只是不装**，不拦路——
     * 官网没启用时本来就不校验，而配置读不到时为此把人挡在门外不划算
     * （发码本身还有官网的 IP 限流与全局熔断兜着）。
     */
    async setupCaptchaWidget() {
      try {
        const config = await getAccountCaptchaConfig()
        this.captcha = await setupCaptcha(config, 'unlock-captcha')
      } catch (e) {
        console.warn('人机验证控件装配失败（按未启用处理）:', e && e.message)
        this.captcha = null
      }
    },
    /** 试用码这条路还开不开。失败一律按「开着」处理，不拦路（见 data 里的注释）。 */
    async refreshTrialGate() {
      try {
        const s = await getLicenseStatus()
        this.trialCodeEnabled = !(s && s.trialCodeEnabled === false)
        // 页签被撤时 mode 必须回到 login——否则残留状态会把人卡在一个
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
     * 「不存在即注册」（返回体带 isNewUser），所以这里**不按 mode 分链路**，
     * 只按 mode 换文案。分成两条链路等于凭空造一条服务端没有的路。
     */
    async handleLogin() {
      const identifier = this.codeIdentifier
      const smsCode = (this.smsCode || '').trim()
      if (!identifier) {
        this.errorMsg = this.isPhoneSite
          ? this.$t('onboarding.unlock.phoneFirst')
          : this.$t('onboarding.unlock.emailFirst')
        return
      }
      if (!smsCode) {
        this.errorMsg = this.$t('onboarding.unlock.smsCodeFirst')
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
    applyLoginResult(res) {
      uni.showToast({
        // 是不是新账户由服务端说了算（isNewUser），不看用户点的是哪个页签——
        // 在「登录」页签下第一次用一个新号码进来的人，看到的也该是注册成功的口径
        title: res && res.isNewUser
          ? this.$t('onboarding.unlock.registered')
          : this.$t('onboarding.unlock.loggedIn'),
        icon: 'success',
        duration: 1600,
      })
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
      const code = this.normalizedCode
      if (!code) {
        this.errorMsg = this.emptyInputHint
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
    applyUnlockResult(res) {
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
    /** 主动切站：先让用户从全部站点里挑一个 */
    openSitePicker() {
      if (this.siteBusy || this.rescueBusy || this.siteStatus.pinned) return
      const sites = this.siteStatus.sites || []
      uni.showActionSheet({
        itemList: sites.map((s) => s.displayName),
        success: (res) => {
          const target = sites[res.tapIndex]
          if (!target || target.id === this.siteStatus.current) return
          this.confirmSwitchSite(target)
        },
        fail: () => {},
      })
    },
    /** 主动切站是破坏性动作，必须二次确认并列清代价 */
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
      try {
        await selectSite(target.id)
        resetSiteLinks()
        await this.refreshSiteStatus()
        uni.showToast({ title: this.$t('onboarding.unlock.switchedTo', { name: target.displayName }), icon: 'none', duration: 1600 })
      } catch (e) {
        this.errorMsg = (e && e.message) || this.$t('onboarding.unlock.switchFailed')
      } finally {
        this.siteBusy = false
      }
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

.unlock-page {
  width: 100vw;
  min-height: 100vh;
  box-sizing: border-box;
  padding: 40px 32px;
  background:
    radial-gradient(900px 520px at 12% 18%, rgba(91, 209, 151, 0.16), transparent 62%),
    radial-gradient(720px 480px at 88% 84%, rgba(26, 83, 54, 0.09), transparent 66%),
    #f8f9fa;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.unlock-stage {
  width: 100%;
  max-width: 1160px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 64px;
}

/* ---------- 左侧品牌视觉区 ---------- */

.unlock-showcase {
  position: relative;
  flex: 1 1 0;
  min-width: 0;
  height: 460px;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 透视容器与被转的元素必须分开：perspective 挂在这里，transform 挂在 .showcase-stage */
  perspective: 1800px;
}

.showcase-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  pointer-events: none;
}

.showcase-glow-a {
  width: 340px;
  height: 340px;
  top: -40px;
  left: 4%;
  background: rgba(91, 209, 151, 0.32);
}

.showcase-glow-b {
  width: 300px;
  height: 300px;
  bottom: -30px;
  right: 6%;
  background: rgba(26, 83, 54, 0.16);
}

.showcase-stage {
  position: relative;
  width: 100%;
  max-width: 600px;
  transform-style: preserve-3d;
  transition: transform 0.12s linear;
}

.mock-window {
  width: 100%;
  height: 380px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 30px 60px rgba(15, 23, 42, 0.14);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.mock-titlebar {
  height: 30px;
  flex-shrink: 0;
  background: #f1f5f9;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
}

.mock-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
}

.mock-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.mock-rail {
  width: 40px;
  flex-shrink: 0;
  background: #1a5336;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.mock-rail-item {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.18);

  &.is-active {
    background: #5bd197;
  }
}

.mock-sidebar {
  width: 130px;
  flex-shrink: 0;
  background: #f8fafc;
  border-right: 1px solid #e2e8f0;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mock-line {
  height: 7px;
  border-radius: 4px;
  background: #dbe3ec;

  &.mock-line-indent {
    margin-left: 12px;
  }

  &.is-active {
    background: #5bd197;
  }
}

.mock-editor {
  flex: 1;
  min-width: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  background: #ffffff;
}

.mock-tabs {
  height: 28px;
  flex-shrink: 0;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
}

.mock-tab {
  width: 62px;
  height: 8px;
  border-radius: 4px;
  background: #e2e8f0;

  &.is-active {
    background: #1a5336;
    opacity: 0.55;
  }
}

.mock-doc {
  flex: 1;
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.mock-doc-title {
  width: 46%;
  height: 12px;
  border-radius: 4px;
  background: #0f172a;
  opacity: 0.72;
  margin-bottom: 6px;
}

.mock-doc-line {
  height: 7px;
  border-radius: 4px;
  background: #e6ecf2;

  /* 一条被 AI 改过的行：品牌 mint，暗示修订 */
  &.mock-doc-line-mark {
    background: rgba(91, 209, 151, 0.55);
  }
}

.mock-watermark {
  position: absolute;
  right: 14px;
  bottom: 12px;
  width: 54px;
  height: 54px;
  opacity: 0.06;
}

.mock-ai {
  width: 108px;
  flex-shrink: 0;
  border-left: 1px solid #e2e8f0;
  background: #fbfdfc;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mock-bubble {
  height: 26px;
  border-radius: 8px;
  background: #eef3f0;

  &.is-user {
    background: rgba(26, 83, 54, 0.14);
    margin-left: 16px;
    height: 18px;
  }
}

/* ---------- 右侧账户卡 ---------- */

.unlock-panel {
  flex: 0 0 auto;
  display: flex;
  justify-content: center;
}

.unlock-card {
  width: 420px;
  max-width: calc(100vw - 48px);
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
  padding: 40px 36px 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.unlock-logo {
  height: 40px;
  margin-bottom: 14px;
}

.unlock-subtitle {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 24px;
  text-align: center;
}

.unlock-form {
  width: 100%;
  margin-top: 18px;
  display: flex;
  flex-direction: column;
}

.unlock-tabs {
  /* .unlock-card 是 align-items:center，子元素默认收缩到内容宽度——不写这行，
     页签会被挤窄到「账户登录」四个字都放不下而换行（.unlock-form 早就写了同一行补偿）。 */
  width: 100%;
  display: flex;
  gap: 4px;
  padding: 4px;
  background: #f1f5f9;
  border-radius: 8px;
}

.unlock-tab {
  flex: 1;
  text-align: center;
  white-space: nowrap;
  padding: 8px 0;
  font-size: 13px;
  color: #64748b;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;

  &.is-active {
    background: #ffffff;
    color: #1a5336;
    font-weight: 500;
    box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  }
}

.unlock-promo {
  width: 100%;
  box-sizing: border-box;
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid rgba(91, 209, 151, 0.5);
  border-radius: 8px;
  background: rgba(91, 209, 151, 0.09);
  display: flex;
  flex-direction: column;
  gap: 4px;

  /* 注册页签下这条是主角，给足对比度 */
  &.is-strong {
    border-color: #5bd197;
    background: rgba(91, 209, 151, 0.16);
  }
}

.unlock-promo-title {
  font-size: 13px;
  font-weight: 600;
  color: #1a5336;
  font-family: 'Songti SC', 'Source Han Serif SC', 'Noto Serif SC', Georgia, serif;
  letter-spacing: 0.3px;
}

.unlock-promo-body {
  font-size: 12px;
  line-height: 1.6;
  color: #33694c;
}

/* 登录字段：与 .unlock-input 同一套边框语言，但单行且用正文字体
   （手机号与验证码不是代码，等宽字体在这里只会显得生硬） */
.unlock-field {
  width: 100%;
  height: 40px;
  box-sizing: border-box;
  padding: 0 14px;
  margin-bottom: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 14px;
  color: #0f172a;
  background: #ffffff;

  &:focus {
    border-color: #1a5336;
    box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
  }
}

.unlock-code-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.unlock-field-inline {
  flex: 1;
}

.unlock-code-btn {
  flex-shrink: 0;
  height: 40px;
  line-height: 40px;
  padding: 0 14px;
  background: #ffffff;
  color: #1a5336;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;

  &:hover:not([disabled]) {
    border-color: #1a5336;
  }

  &[disabled] {
    color: #94a3b8;
    cursor: default;
  }
}

.unlock-input {
  width: 100%;
  height: 88px;
  box-sizing: border-box;
  padding: 12px 14px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #0f172a;
  background: #ffffff;
  font-family: 'SF Mono', Menlo, Consolas, monospace;

  &:focus {
    border-color: #1a5336;
    box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
  }
}

.unlock-placeholder {
  color: #94a3b8;
  font-size: 13px;
}

.unlock-error {
  margin-top: 10px;
  font-size: 12px;
  color: #dc2626;
}

.unlock-hint {
  font-size: 12px;
  line-height: 1.6;
  color: #64748b;
}

.unlock-register-hint {
  margin-top: 12px;
  text-align: center;
}

.unlock-btn {
  margin-top: 18px;
  width: 100%;
  height: 44px;
  line-height: 44px;
  background: #1a5336;
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: #14422b;
  }

  &.is-busy {
    opacity: 0.7;
  }
}

.unlock-links {
  margin-top: 22px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.unlock-link {
  font-size: 12px;
  color: #1a5336;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.unlock-link-sep {
  font-size: 12px;
  color: #cbd5e1;
}

.unlock-site-fixed {
  font-size: 12px;
  color: #64748b;
}

.unlock-rescue {
  align-self: flex-start;
  margin-top: 8px;
}

/* 窄窗口降级：视觉区整块不渲染，收成原来的单卡居中。
   1080px 是「600 的视觉区 + 420 的卡 + 间距」放不下的临界点。 */
@media (max-width: 1080px) {
  .unlock-showcase {
    display: none;
  }

  .unlock-stage {
    gap: 0;
  }
}

/* 系统「减少动态效果」：JS 那边已经把 transform 停在 none，这里连过渡也一并去掉 */
@media (prefers-reduced-motion: reduce) {
  .showcase-stage {
    transition: none;
  }
}
</style>

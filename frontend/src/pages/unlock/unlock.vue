<template>
  <!-- 解锁门：桌面首启的唯一关卡。浅色单卡片居中，试用码（离线）或账户 Key（在线）二选一 -->
  <view class="unlock-page">
    <view class="unlock-card">
      <image class="unlock-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      <text class="unlock-title">AI WorkDeck</text>
      <text class="unlock-subtitle">{{ $t('onboarding.unlock.subtitle') }}</text>

      <!-- 账户登录是新的主路径；试用码 / 手工粘 Key 保留给离线试用、团队服务器与私有部署 -->
      <!-- 官方版只有账户登录这一条路：账户 Key 已经由登录自动签发，用户看不到也不需要粘。
           **但 trialCodeEnabled 为真时整块要留着**——那是商业版 / 私有部署 / 自行构建的
           试用码入口（application-desktop.yml 刻意留的开关），砍掉等于把那条路堵死。
           只剩一个页签时不渲染整条 tab 栏：一个孤零零的页签不是选择，是噪音。 -->
      <view v-if="trialCodeEnabled" class="unlock-tabs">
        <text class="unlock-tab" :class="{ 'is-active': mode === 'login' }" @tap="switchMode('login')">
          {{ $t('onboarding.unlock.loginTab') }}
        </text>
        <text class="unlock-tab" :class="{ 'is-active': mode === 'code' }" @tap="switchMode('code')">
          {{ codeTabLabel }}
        </text>
      </view>

      <view v-if="mode === 'login'" class="unlock-form">
        <template v-if="loginKind === 'code'">
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
        </template>
        <template v-else>
          <text class="unlock-hint">{{ $t('onboarding.unlock.passwordOnlyLegacy') }}</text>
          <input
            class="unlock-field"
            v-model="account"
            :placeholder="$t('onboarding.unlock.accountPlaceholder')"
            placeholder-class="unlock-placeholder"
          />
          <input
            class="unlock-field"
            v-model="password"
            password
            :placeholder="$t('onboarding.unlock.passwordPlaceholder')"
            placeholder-class="unlock-placeholder"
          />
        </template>

        <text v-if="errorMsg" class="unlock-error">{{ errorMsg }}</text>
        <button
          class="unlock-btn"
          :class="{ 'is-busy': loggingIn }"
          :disabled="loggingIn"
          @tap="handleLogin"
        >
          {{ loggingIn ? $t('onboarding.unlock.loggingIn') : $t('onboarding.unlock.login') }}
        </button>
        <text class="unlock-link unlock-login-switch" @tap="toggleLoginKind">
          {{ loginKind === 'code' ? $t('onboarding.unlock.usePassword') : $t('onboarding.unlock.useCode') }}
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

      <view class="unlock-links">
        <template v-if="trialCodeEnabled">
          <text class="unlock-link" @tap="openTrialCodePage">{{ $t('onboarding.unlock.getTrialCode') }}</text>
          <text class="unlock-link-sep">|</text>
        </template>
        <text class="unlock-link" @tap="openOfficialSite">{{ $t('onboarding.unlock.getFullVersion') }}</text>
        <!-- 单站形态（multiSite=false）下整段不渲染，用户看不到任何变化 -->
        <template v-if="showSiteRow">
          <text class="unlock-link-sep">|</text>
          <text v-if="siteStatus.pinned" class="unlock-site-fixed">{{ $t('onboarding.unlock.siteLabel', { name: currentSiteName }) }}</text>
          <text v-else class="unlock-link" @tap="openSitePicker">{{ siteLinkLabel }}</text>
        </template>
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
      // 账户登录（新的主路径）
      mode: 'login',
      // 'code' = 验证码登录（cn 手机号 / intl 邮箱，两站的主路径）；
      // 'password' = 存量口令账号。**intl 必须有 code 这条**：那边验证码注册出来的
      // 账号没有口令，只留口令路等于新用户永远连不上桌面端。
      loginKind: 'code',
      phone: '',
      email: '',
      smsCode: '',
      account: '',
      password: '',
      sendingCode: false,
      loggingIn: false,
      cooldown: 0,
      cooldownTimer: null,
      // 人机验证控件。null = 本站未启用或装配失败，此时照常发码（官网那边也不会校验）
      captcha: null,
    }
  },
  beforeUnmount() {
    // 不清的话切走这一页还留着一个每秒跑的定时器
    if (this.cooldownTimer) clearInterval(this.cooldownTimer)
  },
  computed: {
    /**
     * 大陆站用手机号+验证码，国际站用邮箱+口令。
     * 站点未知时按手机号渲染：内置站点就是 cn，且万一判错用户还能切到「试用码 / Key」页自救。
     */
    isPhoneSite() {
      return this.siteStatus.current !== 'intl'
    },
    /** 本站验证码登录用的标识符：cn 是手机号，intl 是邮箱。 */
    codeIdentifier() {
      return this.isPhoneSite ? (this.phone || '').trim() : (this.email || '').trim()
    },
    codeBtnLabel() {
      if (this.cooldown > 0) return this.$t('onboarding.unlock.resendIn', { n: this.cooldown })
      return this.sendingCode ? this.$t('onboarding.unlock.sendingCode') : this.$t('onboarding.unlock.sendCode')
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
  },
  methods: {
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
        // 页签整条被隐藏时，mode 必须回到 login——否则残留状态会把人卡在一个
        // 已经没有入口可切回来的表单上
        if (!this.trialCodeEnabled) this.mode = 'login'
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
    toggleLoginKind() {
      this.loginKind = this.loginKind === 'code' ? 'password' : 'code'
      this.errorMsg = ''
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
    async handleLogin() {
      let payload
      if (this.loginKind === 'code') {
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
        payload = this.isPhoneSite
          ? { phone: identifier, code: smsCode }
          : { email: identifier, code: smsCode }
      } else {
        const account = (this.account || '').trim()
        if (!account || !this.password) {
          this.errorMsg = this.$t('onboarding.unlock.credentialsFirst')
          return
        }
        payload = { account, password: this.password }
      }
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
        title: this.$t('onboarding.unlock.loggedIn'),
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
    openOfficialSite() {
      openExternalUrl(siteBaseUrl())
    },
  },
}
</script>

<style lang="scss" scoped>
/* 触发元素必须存在且可被 click()，所以用 0 尺寸而不是 display:none——
   display:none 的元素 SDK 挂不上事件，控件永远弹不出来。 */
.unlock-login-switch {
  margin-top: 12px;
  text-align: center;
  font-size: 12px;
}

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
  height: 100vh;
  background: #f8f9fa;
  display: flex;
  align-items: center;
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
  margin-bottom: 20px;
}

.unlock-title {
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.5px;
}

.unlock-subtitle {
  margin-top: 8px;
  font-size: 13px;
  color: #64748b;
}

.unlock-form {
  width: 100%;
  margin-top: 28px;
  display: flex;
  flex-direction: column;
}

.unlock-tabs {
  /* .unlock-card 是 align-items:center，子元素默认收缩到内容宽度——不写这行，
     页签会被挤窄到「账户登录」四个字都放不下而换行（.unlock-form 早就写了同一行补偿）。 */
  width: 100%;
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
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
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  「账户与安全」栏目。2026-08-20 从 components/userprofile/UserProfilePane.vue 的
  「设置」tab 整块搬出来（个人中心并进统一「设置」页的「个人」组）。

  内容一行没改，只有两件事跟着宿主换了：
   · 用户信息由本组件自己拉（原来是 UserProfilePane 的 loadUserInfo）；
   · beforeUnmount 仍然要清那两个绑定验证码的倒计时定时器——统一设置页常驻工作台
     标签里，不清会跨标签泄漏（这是修过的坑，别回退）。
-->
<template>
  <view class="panel-settings">
    <view class="settings-form">
      <view class="form-group">
        <text class="group-title">{{ $t('account.basicInfoGroupTitle') }}</text>
        <!-- 姓名引导（Spec §6）：手机号注册的默认展示名是打码手机号，
             而同事在案卷参与人列表、时间线、批注作者里看到的就是这个字段 -->
        <view v-if="accountProfile.displayNameIsDefault" class="name-nudge" @tap="focusDisplayName">
          <text class="name-nudge-text">{{ $t('account.nameNudgeText') }}</text>
        </view>
        <view class="form-row">
          <text class="form-label">{{ $t('account.avatarLabel') }}</text>
          <view class="avatar-preview tappable" @tap="onAvatarTap">
            <image v-if="userInfo.avatarUrl" class="avatar-image" :src="userInfo.avatarUrl" mode="aspectFill" />
            <text v-else class="avatar-char">{{ getInitial(userInfo.displayName) || 'U' }}</text>
          </view>
          <!-- 删除只有官网那条路有对应端点（DELETE /api/account/avatar）；
               自建服务器只有上传，没有删除，就不画这个入口 -->
          <text v-if="canEditProfile && userInfo.avatarUrl" class="bind-link" @tap="onRemoveAvatar">
            {{ $t('account.avatarRemoveAction') }}
          </text>
        </view>
        <view class="form-row">
          <text class="form-label">{{ $t('account.nicknameLabel') }}</text>
          <!-- 自建服务器（非 local-mode / 未连接账户）服务端没有改名接口，保持只读 -->
          <input
            v-if="canEditProfile"
            class="bind-input"
            v-model="displayNameInput"
            maxlength="24"
            :focus="displayNameFocus"
            :disabled="displayNameSaving"
            :placeholder="$t('account.nicknamePlaceholder')"
            @blur="saveDisplayName"
            @confirm="saveDisplayName"
          />
          <text v-else class="form-value">{{ userInfo.displayName }}</text>
          <text v-if="displayNameSaving" class="bind-link">{{ $t('account.nicknameSaving') }}</text>
        </view>
        <text v-if="displayNameError" class="form-error">{{ displayNameError }}</text>
      </view>

      <!-- 账号安全（server 模式；认证器恒可用，短信取决于通道配置） -->
      <view v-if="!isDesktop" class="form-group">
        <text class="group-title">{{ $t('account.accountSecurityGroupTitle') }}</text>

        <!-- 认证器（TOTP）：零成本、无国界，登录二次验证优先走它 -->
        <view class="form-row">
          <text class="form-label">{{ $t('account.authenticatorLabel') }}</text>
          <text class="form-value">{{ userInfo.totpEnabled ? $t('account.bound') : $t('account.unbound') }}</text>
          <text class="bind-link" @tap="toggleTotpPanel">{{ userInfo.totpEnabled ? $t('account.unbindAction') : $t('account.bindAction') }}</text>
        </view>
        <view v-if="showTotpPanel" class="bind-phone-form">
          <template v-if="!userInfo.totpEnabled">
            <text class="bind-tip">{{ $t('account.totpSetupTip') }}</text>
            <image v-if="totpQrDataUrl" class="totp-qr" :src="totpQrDataUrl" mode="widthFix" />
            <view class="form-row">
              <text class="form-label">{{ $t('account.secretKeyLabel') }}</text>
              <text class="totp-secret">{{ totpSecret }}</text>
            </view>
            <view class="form-row">
              <text class="form-label">{{ $t('account.verificationCodeLabel') }}</text>
              <input class="bind-input code" type="number" maxlength="6" v-model="totpCodeInput" :placeholder="$t('account.appCodePlaceholder')" />
            </view>
            <view class="bind-actions">
              <button class="btn-bind-confirm" :disabled="totpSubmitting" @tap="confirmTotpBind">{{ $t('account.finishBindBtn') }}</button>
              <text class="bind-link" @tap="cancelTotpPanel">{{ $t('common.cancel') }}</text>
            </view>
          </template>
          <template v-else>
            <text class="bind-tip">{{ $t('account.unbindTotpTip') }}</text>
            <view class="form-row">
              <text class="form-label">{{ $t('account.verificationCodeLabel') }}</text>
              <input class="bind-input code" type="number" maxlength="6" v-model="totpCodeInput" :placeholder="$t('account.appCodePlaceholder')" />
            </view>
            <view class="bind-actions">
              <button class="btn-bind-confirm" :disabled="totpSubmitting" @tap="confirmTotpDisable">{{ $t('account.confirmUnbindBtn') }}</button>
              <text class="bind-link" @tap="cancelTotpPanel">{{ $t('common.cancel') }}</text>
            </view>
          </template>
        </view>

        <view v-if="userInfo.smsAuthEnabled" class="form-row">
          <text class="form-label">{{ $t('account.phoneLabel') }}</text>
          <text class="form-value">{{ userInfo.phoneMasked || $t('account.unbound') }}</text>
          <text class="bind-link" @tap="showBindPhone = !showBindPhone">{{ userInfo.phoneMasked ? $t('account.changeAction') : $t('account.bindAction') }}</text>
        </view>
        <view v-if="showBindPhone" class="bind-phone-form">
          <view class="form-row">
            <text class="form-label">{{ $t('account.newPhoneLabel') }}</text>
            <input class="bind-input" type="number" maxlength="11" v-model="bindPhoneInput" :placeholder="$t('account.phoneInputPlaceholder')" />
          </view>
          <view class="form-row">
            <text class="form-label">{{ $t('account.verificationCodeLabel') }}</text>
            <input class="bind-input code" type="number" maxlength="6" v-model="bindCodeInput" :placeholder="$t('account.sixDigitCodePlaceholder')" />
            <button class="btn-send-code" :disabled="bindCountdown > 0" @tap="sendBindPhoneCode">
              {{ bindCountdown > 0 ? bindCountdown + 's' : $t('account.getCodeBtn') }}
            </button>
          </view>
          <view class="bind-actions">
            <button class="btn-bind-confirm" :disabled="bindSubmitting" @tap="confirmBindPhone">{{ $t('account.confirmBindBtn') }}</button>
            <text class="bind-link" @tap="cancelBindPhone">{{ $t('common.cancel') }}</text>
          </view>
          <text class="bind-tip">{{ $t('account.bindPhoneTip') }}</text>
        </view>

        <view v-if="userInfo.mailAuthEnabled" class="form-row">
          <text class="form-label">{{ $t('account.emailLabel') }}</text>
          <text class="form-value">{{ userInfo.emailMasked || $t('account.unbound') }}</text>
          <text class="bind-link" @tap="showBindEmail = !showBindEmail">{{ userInfo.emailMasked ? $t('account.changeAction') : $t('account.bindAction') }}</text>
        </view>
        <view v-if="showBindEmail" class="bind-phone-form">
          <view class="form-row">
            <text class="form-label">{{ $t('account.newEmailLabel') }}</text>
            <input class="bind-input" v-model="bindEmailInput" :placeholder="$t('account.emailInputPlaceholder')" />
          </view>
          <view class="form-row">
            <text class="form-label">{{ $t('account.verificationCodeLabel') }}</text>
            <input class="bind-input code" type="number" maxlength="6" v-model="bindEmailCodeInput" :placeholder="$t('account.sixDigitCodePlaceholder')" />
            <button class="btn-send-code" :disabled="bindEmailCountdown > 0" @tap="sendBindEmailCode">
              {{ bindEmailCountdown > 0 ? bindEmailCountdown + 's' : $t('account.getCodeBtn') }}
            </button>
          </view>
          <view class="bind-actions">
            <button class="btn-bind-confirm" :disabled="bindEmailSubmitting" @tap="confirmBindEmail">{{ $t('account.confirmBindBtn') }}</button>
            <text class="bind-link" @tap="cancelBindEmail">{{ $t('common.cancel') }}</text>
          </view>
          <text class="bind-tip">{{ $t('account.bindEmailTip') }}</text>
        </view>
      </view>

      <!-- 授权（桌面端）：当前模式 / 激活时间 / 解除授权 -->
      <view v-if="isDesktop && licenseInfo.unlocked" class="form-group">
        <text class="group-title">{{ $t('account.licenseGroupTitle') }}</text>
        <view class="form-row">
          <text class="form-label">{{ $t('account.currentModeLabel') }}</text>
          <!-- 读 edition 不读 mode：mode 只是授权票据，先用试用码解锁、
               后连账户的用户 mode 永远停在 trial（后端已把两条状态组合成 edition） -->
          <text class="form-value">{{ licenseInfo.edition === 'paid' ? $t('account.paidEdition') : $t('account.trialEdition') }}</text>
        </view>
        <view class="form-row">
          <text class="form-label">{{ $t('account.activatedAtLabel') }}</text>
          <text class="form-value">{{ licenseInfo.activatedAt ? formatTime(licenseInfo.activatedAt) : '—' }}</text>
        </view>
        <!-- 与「退出登录」分工（dev-board#205）：退出登录管账户连接（换账号用它），
             这里只清本机的解锁票据、回到启动解锁页——给文案说清楚，别让用户猜 -->
        <text class="bind-tip">{{ $t('account.deactivateHint') }}</text>
        <button class="btn-logout-settings" @tap="handleDeactivate">{{ $t('account.deactivateBtn') }}</button>
      </view>

      <!-- 插件访问令牌（桌面端）：Office 插件等外部客户端连接本机后端的凭据 -->
      <view v-if="isDesktop" class="form-group">
        <text class="group-title">{{ $t('account.deviceTokenGroupTitle') }}</text>
        <text class="bind-tip">{{ $t('account.deviceTokenTip') }}</text>
        <view class="form-row">
          <text class="form-label">{{ $t('account.tokenNameLabel') }}</text>
          <input class="bind-input" v-model="tokenNameInput" maxlength="30" :placeholder="$t('account.tokenNamePlaceholder')" />
          <button class="btn-send-code" :disabled="tokenIssuing" @tap="handleIssueToken">{{ $t('account.issueTokenBtn') }}</button>
        </view>
        <view v-for="t in deviceTokens" :key="t.id" class="form-row">
          <view class="token-info">
            <text class="token-name">{{ t.name || $t('account.unnamedToken') }}</text>
            <text class="token-meta">
              {{ $t('account.tokenMeta', { createdAt: formatTime(t.createdAt) || '—', lastUsed: t.lastUsedAt ? formatTime(t.lastUsedAt) : $t('account.never') }) }}
            </text>
          </view>
          <text class="bind-link" @tap="handleRevokeToken(t)">{{ $t('account.revokeAction') }}</text>
        </view>
        <text v-if="!deviceTokens.length" class="bind-tip">{{ $t('account.noTokensYet') }}</text>
      </view>

      <!-- 文档属性里的产品标识（可溯源性设计规范附录 B4）。默认开：Word / WPS /
           LibreOffice 保存文档时都会写这个标准字段，我们此前是唯一不写的那个。
           只写产品名与版本，不写作者/单位/机器名——所以它不是隐私项，是「交付前
           要不要清元数据」的开关。 -->
      <view class="form-group">
        <text class="group-title">{{ $t('account.docGeneratorGroupTitle') }}</text>
        <view class="form-row doc-generator-row">
          <text class="form-label doc-generator-label">{{ $t('account.docGeneratorLabel') }}</text>
          <AwdSwitch
            :checked="docGeneratorEnabled"
            :disabled="docGeneratorBusy"
            @change="onToggleDocGenerator"
          />
        </view>
        <text class="bind-tip">{{ $t('account.docGeneratorHint') }}</text>
        <text v-if="docGeneratorEnabled && docGeneratorApplication" class="bind-tip">
          {{ $t('account.docGeneratorCurrent', { application: docGeneratorApplication }) }}
        </text>
      </view>

      <!-- 界面语言。2026-08-18 从设置页「系统配置」搬来：语言是每个人自己的
           偏好（storage 权威源、人人可改、不要 admin 权限）。
           独立保存链（setAppLanguage 直写），与本栏其它字段无关。 -->
      <view class="form-group">
        <text class="group-title">{{ appLanguage === 'en-US' ? 'Language' : '语言 / Language' }}</text>
        <text class="bind-tip">
          {{ appLanguage === 'en-US'
            ? 'Applies to the interface, document editor, and AI replies. Newly opened editors use the new language; restart the app for full effect.'
            : '作用于界面、文档编辑器与 AI 回复。新打开的编辑器使用新语言，重启应用后完全生效。' }}
        </text>
        <view class="lang-row">
          <view
            v-for="opt in appLanguageOptions"
            :key="opt.value"
            class="lang-item"
            :class="{ checked: appLanguage === opt.value }"
            @tap="onAppLanguagePick(opt.value)"
          >
            <view class="lang-dot"></view>
            <text class="lang-label">{{ opt.label }}</text>
          </view>
        </view>
      </view>

      <!-- 退出登录。**桌面端也要有**：此前这一块写着 v-if="!isDesktop"，
           于是桌面端全应用没有一个登出入口，想换账号只能去设置页把
           「断开连接」和「解除授权」各点一遍。两件事已收进 utils/signOut.js。 -->
      <view class="form-group">
        <text class="group-title">{{ $t('account.logoutGroupTitle') }}</text>
        <text class="bind-tip">{{ $t('account.logoutGroupHint') }}</text>
        <button class="btn-logout-settings" @tap="handleLogout">{{ $t('account.logoutBtn') }}</button>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getCurrentUser as getCurrentUserApi, getLicenseStatus, deactivateLicense,
  sendSmsCode, bindPhone, sendMailCode, bindEmail,
  totpSetup, totpActivate, totpDisable,
  issueLocalDeviceToken, listDeviceTokens, revokeDeviceToken,
  uploadAvatar, updateAccountProfile, uploadAccountAvatar, deleteAccountAvatar,
} from '@/services/api.js'
import { loadIdentityProfile, PROFILE_SOURCE } from '@/services/accountProfile.js'
import { getDocumentGeneratorSettings, updateDocumentGeneratorSettings } from '@/services/api.js'
import { resetDocumentStampCache } from '@/utils/documentGeneratorSetting.js'
import AwdSwitch from '@/components/AwdSwitch.vue'
import { isDesktopHost } from '@/services/host.js'
import { getCurrentUser, setSessionUser } from '@/utils/auth.js'
import { signOut } from '@/utils/signOut.js'
import { getAppLanguage, setAppLanguage } from '@/utils/appLanguage.js'
import { getInitial } from '@/utils/textInitial.js'
import { shouldAcceptResponse } from '@/utils/requestGeneration.js'

export default {
  name: 'PersonalSettingsPanel',
  components: { AwdSwitch },
  computed: {
    isDesktop() {
      return isDesktopHost()
    },
    /**
     * 昵称能不能改、头像能不能删（Spec §6）：只有「local-mode 且已连接账户」这条路
     * 有官网那三个写端点。自建服务器本期不做改名，头像仍可传（走本机 /api/users/avatar）。
     */
    canEditProfile() {
      return this.accountProfile.source === PROFILE_SOURCE.ACCOUNT
    },
  },
  data() {
    return {
      userInfo: {
        id: null,
        username: '',
        displayName: this.$t('account.defaultUserName'),
        avatarUrl: null,
      },

      // 名字与头像归谁管（services/accountProfile.js）。拉不到一律按 LOCAL 画，
      // 也就是今天这套只读形态——绝不先画出一个必然失败的输入框。
      accountProfile: { source: PROFILE_SOURCE.LOCAL, accountId: '', displayNameIsDefault: false },
      displayNameInput: '',
      displayNameSaving: false,
      displayNameError: '',
      displayNameFocus: false,
      avatarBusy: false,

      // 授权状态（桌面端）：{ unlocked, mode, plan, activatedAt?, accountConnected, edition }
      licenseInfo: {},

      // 插件访问令牌（桌面端）：明文只在生成时返回一次，这里只留列表元信息
      deviceTokens: [],
      tokenNameInput: '',
      tokenIssuing: false,

      // 界面语言：读写走 utils/appLanguage.js（storage 权威源 + App.vue 镜像同步）。
      // 选项标签用各自母语，刻意不随语言翻译。
      appLanguage: getAppLanguage(),
      appLanguageOptions: [
        { value: 'zh-CN', label: '简体中文' },
        { value: 'en-US', label: 'English' },
      ],

      // 文档属性里的产品标识（B4）：默认按「开」渲染，拉到后端值再纠正——
      // 缺省就是开，先画成关会在加载瞬间闪一下。
      docGeneratorEnabled: true,
      docGeneratorApplication: '',
      docGeneratorBusy: false,

      // 认证器（TOTP）绑定
      showTotpPanel: false,
      totpSecret: '',
      totpQrDataUrl: '',
      totpCodeInput: '',
      totpBusy: false, // startSetup 在飞期间为 true，防止重复点击触发并发请求
      _totpRequestSeq: 0, // 请求代次：只接受"此刻最新一次"发出的响应
      // 上面两个管的是「开始设置」那一步；下面这个管「确认」那一步——
      // 验证码是一次性的：连点两次会并发发两次请求，第二次必然失败，
      // 于是成功 toast 后面又叠一个失败 toast。三个闸各管一个面板。
      totpSubmitting: false,

      // 手机号绑定（登录短信验证，仅 server 模式且启用时显示）
      showBindPhone: false,
      bindPhoneInput: '',
      bindCodeInput: '',
      bindCountdown: 0,
      bindCountdownTimer: null,
      bindSubmitting: false,

      // 邮箱绑定（与手机号并列的二次验证方式；绑了之后优先走邮件，省短信费）
      showBindEmail: false,
      bindEmailInput: '',
      bindEmailCodeInput: '',
      bindEmailCountdown: 0,
      bindEmailCountdownTimer: null,
      bindEmailSubmitting: false,
    }
  },
  mounted() {
    this.loadUserInfo()
    this.loadAccountProfile()
    this.loadDocGeneratorSetting()
    // 设置页别处（账户分区的姓名引导、连接成功后的弹窗）点「去填写」时把光标送到这里。
    // 那两处切栏之后本组件才挂上来，所以只能靠事件，不能靠 prop。
    this._onFocusDisplayName = () => this.focusDisplayName()
    uni.$on('awd:focus-display-name', this._onFocusDisplayName)
    if (this.isDesktop) {
      this.loadLicenseInfo()
      this.loadDeviceTokens()
    }
  },
  beforeUnmount() {
    // 统一设置页是常驻工作台的标签，不会像整页那样随导航销毁重建，
    // 定时器不清会跨标签泄漏。
    if (this.bindCountdownTimer) {
      clearInterval(this.bindCountdownTimer)
      this.bindCountdownTimer = null
    }
    if (this.bindEmailCountdownTimer) {
      clearInterval(this.bindEmailCountdownTimer)
      this.bindEmailCountdownTimer = null
    }
    if (this._onFocusDisplayName) {
      uni.$off('awd:focus-display-name', this._onFocusDisplayName)
      this._onFocusDisplayName = null
    }
  },
  methods: {
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 getInitial(...) 调用
    getInitial,
    async loadDocGeneratorSetting() {
      try {
        const res = await getDocumentGeneratorSettings()
        if (res && res.code === 0) {
          this.docGeneratorEnabled = res.enabled !== false
          this.docGeneratorApplication = res.application || ''
        }
      } catch (e) {
        // 读不到就保持默认显示，不打扰用户：这个开关不影响任何正在进行的工作
      }
    },
    async onToggleDocGenerator(next) {
      if (this.docGeneratorBusy) return
      const prev = this.docGeneratorEnabled
      this.docGeneratorBusy = true
      this.docGeneratorEnabled = next
      try {
        const res = await updateDocumentGeneratorSettings({ enabled: next })
        if (!res || res.code !== 0) throw new Error('save failed')
        this.docGeneratorEnabled = res.enabled !== false
        this.docGeneratorApplication = res.application || this.docGeneratorApplication
        // 编辑器的保存路径按会话缓存这个开关，改完必须让它重取，否则要等下次启动才生效
        resetDocumentStampCache()
      } catch (e) {
        this.docGeneratorEnabled = prev
        uni.showToast({ title: this.$t('account.docGeneratorSaveFailed'), icon: 'none' })
      } finally {
        this.docGeneratorBusy = false
      }
    },
    async loadUserInfo() {
      const user = getCurrentUser()
      if (user) {
        this.userInfo = user
      }
      // 短信绑定状态（smsAuthEnabled/phoneMasked）只在 /api/auth/me 下发，
      // 缓存的登录响应里没有——有缓存也拉一次合并
      try {
        const res = await getCurrentUserApi()
        if (res.code === 0 && res.data) {
          this.userInfo = { ...this.userInfo, ...res.data }
        }
      } catch (error) {
        console.error('获取用户信息失败:', error)
      }
      // 输入框只在这里跟随权威源；用户正在编辑时本方法不会被调用（只在挂载与写入成功后跑）
      this.displayNameInput = this.userInfo.displayName || ''
    },
    async loadAccountProfile() {
      const { source, profile } = await loadIdentityProfile()
      this.accountProfile = {
        source,
        accountId: (profile && profile.accountId) || '',
        displayNameIsDefault: !!(profile && profile.displayNameIsDefault),
      }
    },
    /** 姓名引导点下去：把光标送进昵称输入框（uni 的 focus 是 prop，要先落回 false 才能再次触发） */
    focusDisplayName() {
      if (!this.canEditProfile) return
      this.displayNameFocus = false
      this.$nextTick(() => { this.displayNameFocus = true })
    },
    /**
     * 存昵称。@blur 与 @confirm 都会调到这里（回车之后紧接着失焦），
     * 所以「没变就什么都不做」这条不是优化，是防止一次编辑发两次写请求。
     */
    async saveDisplayName() {
      if (!this.canEditProfile || this.displayNameSaving) return
      const current = String(this.userInfo.displayName || '')
      const next = String(this.displayNameInput || '').trim()
      if (!next || next === current) {
        // 清空不算「改成空名」：空展示名同事那边照样看不出是谁，回落到原值
        this.displayNameInput = current
        this.displayNameError = ''
        return
      }
      if (next.length > 24) {
        this.displayNameError = this.$t('account.nicknameTooLong')
        return
      }
      this.displayNameSaving = true
      this.displayNameError = ''
      try {
        const data = await updateAccountProfile(next)
        const saved = (data && data.displayName) || next
        this.userInfo = { ...this.userInfo, displayName: saved }
        this.displayNameInput = saved
        setSessionUser(this.userInfo)
        // displayNameIsDefault 跟着变（引导条随之消失），所以要重拉而不是本地推断
        await this.loadAccountProfile()
        uni.$emit('awd:identity-updated')
      } catch (e) {
        this.displayNameError = this.$t('account.nicknameSaveFailed', { message: (e && e.message) || '' })
        this.displayNameInput = current
      } finally {
        this.displayNameSaving = false
      }
    },
    /**
     * 头像：路由规则与 AdminPane 侧栏那处同源（services/accountProfile.js）。
     * 写官网之后本机 User 行由后端刷新，所以重拉一次 /api/auth/me 就能拿到新头像。
     */
    onAvatarTap() {
      if (this.avatarBusy) return
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: async (res) => {
          const filePath = res.tempFilePaths[0]
          this.avatarBusy = true
          try {
            uni.showLoading({ title: this.$t('account.uploadingTitle') })
            if (this.canEditProfile) {
              await uploadAccountAvatar(filePath)
              await this.loadUserInfo()
            } else {
              const result = await uploadAvatar(filePath)
              const url = result && result.data && result.data.avatarUrl
              if (url) {
                this.userInfo = { ...this.userInfo, avatarUrl: url }
                setSessionUser(this.userInfo)
              }
            }
            uni.$emit('awd:identity-updated')
            uni.showToast({ title: this.$t('account.avatarUpdateSuccess'), icon: 'success' })
          } catch (e) {
            uni.showToast({ title: this.$t('account.avatarUploadFailed', { message: (e && e.message) || '' }), icon: 'none' })
          } finally {
            uni.hideLoading()
            this.avatarBusy = false
          }
        },
      })
    },
    async onRemoveAvatar() {
      if (this.avatarBusy || !this.canEditProfile) return
      this.avatarBusy = true
      try {
        await deleteAccountAvatar()
        await this.loadUserInfo()
        uni.$emit('awd:identity-updated')
        uni.showToast({ title: this.$t('account.avatarRemoveSuccess'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: this.$t('account.avatarRemoveFailed', { message: (e && e.message) || '' }), icon: 'none' })
      } finally {
        this.avatarBusy = false
      }
    },
    async loadLicenseInfo() {
      try {
        const status = await getLicenseStatus()
        this.licenseInfo = status || {}
      } catch (e) {
        // 旧后端没有该端点：静默忽略
        this.licenseInfo = {}
      }
    },
    async loadDeviceTokens() {
      try {
        const res = await listDeviceTokens()
        this.deviceTokens = (res && res.data && res.data.tokens) || []
      } catch (e) {
        // 旧后端没有该端点：当作没有令牌，不打扰用户
        this.deviceTokens = []
      }
    },
    async handleIssueToken() {
      if (this.tokenIssuing) return
      this.tokenIssuing = true
      try {
        const res = await issueLocalDeviceToken(this.tokenNameInput.trim())
        const token = res && res.data && res.data.token
        if (!token) throw new Error(this.$t('account.tokenIssueFailed'))
        this.tokenNameInput = ''
        await this.loadDeviceTokens()
        // 明文只在这一次拿得到，弹窗里直接给复制
        uni.showModal({
          title: this.$t('account.tokenGeneratedTitle'),
          content: token + '\n\n' + this.$t('account.tokenGeneratedTip'),
          cancelText: this.$t('common.close'),
          confirmText: this.$t('account.copyAction'),
          success: (r) => {
            if (!r.confirm) return
            uni.setClipboardData({
              data: token,
              success: () => uni.showToast({ title: this.$t('common.copied'), icon: 'none' }),
            })
          },
        })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('account.tokenIssueFailed'), icon: 'none' })
      } finally {
        this.tokenIssuing = false
      }
    },
    handleRevokeToken(token) {
      uni.showModal({
        title: this.$t('account.revokeTokenTitle'),
        content: this.$t('account.revokeTokenContent'),
        cancelText: this.$t('common.cancel'),
        confirmText: this.$t('account.confirmRevokeBtn'),
        success: async (r) => {
          if (!r.confirm) return
          try {
            await revokeDeviceToken(token.id)
            await this.loadDeviceTokens()
            uni.showToast({ title: this.$t('account.revokedToast'), icon: 'none' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('account.revokeFailed'), icon: 'none' })
          }
        },
      })
    },
    handleDeactivate() {
      uni.showModal({
        title: this.$t('account.deactivateBtn'),
        content: this.$t('account.deactivateContent'),
        cancelText: this.$t('common.cancel'),
        confirmText: this.$t('account.confirmDeactivateBtn'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deactivateLicense()
            uni.reLaunch({ url: '/pages/launch/launch' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('account.deactivateFailed'), icon: 'none' })
          }
        },
      })
    },
    // 桌面端要多退一层（账户连接 + 授权票据），流程收在 utils/signOut.js，
    // 与应用菜单「退出登录…」共用一份，别在这里另写一条会漂的。
    handleLogout() {
      signOut()
    },
    onAppLanguagePick(value) {
      if (value === this.appLanguage) return
      this.appLanguage = setAppLanguage(value)
      uni.showToast({
        title: this.appLanguage === 'en-US' ? 'Switching to English…' : '正在切换为简体中文…',
        icon: 'none',
      })
      // 整页 reload：i18n 单例的 locale、config 模块顶层取值的静态 label、
      // LOWA 编辑器 uilang 全部随之重建。延迟给 App.vue 的镜像同步
      //（主进程 IPC + 后端 POST）留出发出的窗口。
      setTimeout(() => {
        try { window.location.reload() } catch (e) { /* 非浏览器环境忽略 */ }
      }, 600)
    },
    async toggleTotpPanel() {
      if (this.totpBusy) return // startSetup 在飞期间禁用，防止二次点击触发并发请求
      if (this.showTotpPanel) {
        this.cancelTotpPanel()
        return
      }
      this.totpCodeInput = ''
      this.showTotpPanel = true
      if (this.userInfo.totpEnabled) return
      // 请求代次：后端 startSetup 每次都新生成一把密钥并落库（后来者覆盖）。反复点
      // 「绑定」/取消/「绑定」会连续发出多个 startSetup，必须只认最后一次发出的那份
      // 响应，否则界面可能显示 A 请求的密钥而数据库存的是 B 请求的，用户扫到一把
      // 服务端已经不认的密钥，验证码永远校验不过。
      const seq = ++this._totpRequestSeq
      this.totpBusy = true
      try {
        const res = await totpSetup()
        if (!shouldAcceptResponse(seq, this._totpRequestSeq)) return
        this.totpSecret = (res.data && res.data.secret) || ''
        const uri = (res.data && res.data.provisioningUri) || ''
        // 二维码在前端渲染：otpauth URI 含密钥，不该经由图片服务多走一手
        const QRCode = (await import('qrcode')).default
        const dataUrl = uri ? await QRCode.toDataURL(uri, { margin: 1, width: 180 }) : ''
        if (!shouldAcceptResponse(seq, this._totpRequestSeq)) return
        this.totpQrDataUrl = dataUrl
      } catch (e) {
        if (!shouldAcceptResponse(seq, this._totpRequestSeq)) return
        this.showTotpPanel = false
        uni.showToast({ title: e.message || this.$t('account.getTotpBindInfoFailed'), icon: 'none' })
      } finally {
        if (shouldAcceptResponse(seq, this._totpRequestSeq)) this.totpBusy = false
      }
    },
    async confirmTotpBind() {
      if (!this.totpCodeInput || this.totpCodeInput.length < 6) {
        uni.showToast({ title: this.$t('account.enterSixDigitCode'), icon: 'none' })
        return
      }
      if (this.totpSubmitting) return
      this.totpSubmitting = true
      try {
        await totpActivate(this.totpCodeInput)
        this.userInfo = { ...this.userInfo, totpEnabled: true }
        setSessionUser(this.userInfo)
        uni.showToast({ title: this.$t('account.totpBoundSuccess'), icon: 'success' })
        this.cancelTotpPanel()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.bindFailed'), icon: 'none' })
      } finally {
        this.totpSubmitting = false
      }
    },
    async confirmTotpDisable() {
      if (!this.totpCodeInput || this.totpCodeInput.length < 6) {
        uni.showToast({ title: this.$t('account.enterSixDigitCode'), icon: 'none' })
        return
      }
      if (this.totpSubmitting) return
      this.totpSubmitting = true
      try {
        await totpDisable(this.totpCodeInput)
        this.userInfo = { ...this.userInfo, totpEnabled: false }
        setSessionUser(this.userInfo)
        uni.showToast({ title: this.$t('account.totpUnboundSuccess'), icon: 'success' })
        this.cancelTotpPanel()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.unbindFailed'), icon: 'none' })
      } finally {
        this.totpSubmitting = false
      }
    },
    cancelTotpPanel() {
      // 让任何还在飞的 startSetup 响应作废（代次一旦不匹配，它的 finally 也不会
      // 再去解 totpBusy），所以这里要显式解锁，否则 in-flight 期间取消一次
      // 就会把「绑定」按钮永久锁死。
      this._totpRequestSeq++
      this.totpBusy = false
      this.showTotpPanel = false
      this.totpSecret = ''
      this.totpQrDataUrl = ''
      this.totpCodeInput = ''
    },
    async sendBindPhoneCode() {
      if (this.bindCountdown > 0) return
      if (!/^1[3-9]\d{9}$/.test(this.bindPhoneInput)) {
        uni.showToast({ title: this.$t('account.invalidPhone'), icon: 'none' })
        return
      }
      try {
        await sendSmsCode({ scene: 'bind', phone: this.bindPhoneInput })
        uni.showToast({ title: this.$t('account.codeSentToast'), icon: 'none' })
        this.bindCountdown = 60
        if (this.bindCountdownTimer) clearInterval(this.bindCountdownTimer)
        this.bindCountdownTimer = setInterval(() => {
          if (this.bindCountdown > 0) {
            this.bindCountdown--
          } else {
            clearInterval(this.bindCountdownTimer)
            this.bindCountdownTimer = null
          }
        }, 1000)
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.sendFailed'), icon: 'none' })
      }
    },
    async confirmBindPhone() {
      if (!this.bindCodeInput || this.bindCodeInput.length < 6) {
        uni.showToast({ title: this.$t('account.enterSixDigitCode'), icon: 'none' })
        return
      }
      if (this.bindSubmitting) return
      this.bindSubmitting = true
      try {
        const res = await bindPhone(this.bindPhoneInput, this.bindCodeInput)
        uni.showToast({ title: this.$t('account.bindSuccessToast'), icon: 'success' })
        const phoneMasked = (res.data && res.data.phoneMasked) || ''
        this.userInfo = { ...this.userInfo, phoneMasked }
        setSessionUser(this.userInfo)
        this.cancelBindPhone()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.bindFailed'), icon: 'none' })
      } finally {
        this.bindSubmitting = false
      }
    },
    cancelBindPhone() {
      this.showBindPhone = false
      this.bindPhoneInput = ''
      this.bindCodeInput = ''
      this.bindCountdown = 0
      if (this.bindCountdownTimer) {
        clearInterval(this.bindCountdownTimer)
        this.bindCountdownTimer = null
      }
    },
    async sendBindEmailCode() {
      if (this.bindEmailCountdown > 0) return
      // 只挡明显不是邮箱的输入；真正的规范化与判定在后端，前端不复刻一套正则
      if (!/^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test((this.bindEmailInput || '').trim())) {
        uni.showToast({ title: this.$t('account.invalidEmail'), icon: 'none' })
        return
      }
      try {
        await sendMailCode({ scene: 'bind', email: this.bindEmailInput.trim() })
        uni.showToast({ title: this.$t('account.codeSentToast'), icon: 'none' })
        this.bindEmailCountdown = 60
        if (this.bindEmailCountdownTimer) clearInterval(this.bindEmailCountdownTimer)
        this.bindEmailCountdownTimer = setInterval(() => {
          if (this.bindEmailCountdown > 0) {
            this.bindEmailCountdown--
          } else {
            clearInterval(this.bindEmailCountdownTimer)
            this.bindEmailCountdownTimer = null
          }
        }, 1000)
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.sendFailed'), icon: 'none' })
      }
    },
    async confirmBindEmail() {
      if (!this.bindEmailCodeInput || this.bindEmailCodeInput.length < 6) {
        uni.showToast({ title: this.$t('account.enterSixDigitCode'), icon: 'none' })
        return
      }
      if (this.bindEmailSubmitting) return
      this.bindEmailSubmitting = true
      try {
        const res = await bindEmail(this.bindEmailInput.trim(), this.bindEmailCodeInput)
        uni.showToast({ title: this.$t('account.bindSuccessToast'), icon: 'success' })
        const emailMasked = (res.data && res.data.emailMasked) || ''
        this.userInfo = { ...this.userInfo, emailMasked }
        setSessionUser(this.userInfo)
        this.cancelBindEmail()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('account.bindFailed'), icon: 'none' })
      } finally {
        this.bindEmailSubmitting = false
      }
    },
    cancelBindEmail() {
      this.showBindEmail = false
      this.bindEmailInput = ''
      this.bindEmailCodeInput = ''
      this.bindEmailCountdown = 0
      if (this.bindEmailCountdownTimer) {
        clearInterval(this.bindEmailCountdownTimer)
        this.bindEmailCountdownTimer = null
      }
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },
  },
}
</script>

<style lang="scss" scoped>
$brand-dark: #212629;
$text-secondary: #6C757D;

.panel-settings {
  background: var(--awd-surface);
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 2px 12px rgba(18, 52, 77, 0.04);
  box-sizing: border-box;
}

.group-title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: var(--awd-text);
  margin-bottom: 24px;
  padding-left: 12px;
  border-left: 4px solid var(--awd-accent);
}

.form-group {
  margin-bottom: 40px;

  &:last-child {
    margin-bottom: 0;
  }
}

.form-row {
  display: flex;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid var(--awd-border-subtle);

  &:last-child {
    border-bottom: none;
  }
}

.form-label {
  width: 100px;
  font-size: 14px;
  color: var(--awd-text-2);
}

.form-value {
  font-size: 14px;
  color: var(--awd-text);
  font-weight: 500;
}

.avatar-preview {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: $brand-dark;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 20px;
  overflow: hidden;
  flex-shrink: 0;

  &.tappable { cursor: pointer; }
}

.avatar-image {
  width: 48px;
  height: 48px;
  border-radius: 50%;
}

.form-error {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--awd-danger-text);
}

/* 姓名引导条：弱强调，不抢「基本信息」本身 */
.name-nudge {
  margin-bottom: 12px;
  padding: 8px 12px;
  border: 1px solid var(--awd-accent-soft);
  border-radius: 6px;
  background: var(--awd-accent-wash);
  cursor: pointer;
}

.name-nudge-text {
  font-size: 12px;
  line-height: 18px;
  color: var(--awd-text);
}

/* 界面语言（从设置页「系统配置」搬来）。单选样式独立写一份：admin 那套
   .radio-item 是那一页 scoped 的，跨组件用不了。 */
.lang-row {
    display: flex;
    flex-direction: row;
    gap: 10px;
    margin-top: 12px;
    flex-wrap: wrap;
}

.lang-item {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 8px;
    padding: 8px 16px;
    border: 1px solid var(--awd-border);
    border-radius: 999px;
    background: var(--awd-surface);
    cursor: pointer;
    transition: all 0.2s;

    &:hover { border-color: var(--awd-accent); }

    &.checked {
        border-color: var(--awd-accent);
        background: var(--awd-accent-wash);
    }
}

.lang-dot {
    width: 14px;
    height: 14px;
    border-radius: 50%;
    border: 1px solid var(--awd-border-strong);
    box-sizing: border-box;
    flex-shrink: 0;
}

.lang-item.checked .lang-dot {
    border: 4px solid var(--awd-accent);
    background: var(--awd-surface);
}

.lang-label {
    font-size: 14px;
    color: var(--awd-text);
}

.btn-logout-settings {
    background: var(--awd-surface);
    border: 1px solid var(--awd-border);
    color: var(--awd-text-2);
    height: 44px;
    line-height: 42px; /* Adjust for border */
    border-radius: 8px;
    font-size: 14px;
    width: 100%;
    margin-top: 12px;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
        border-color: $text-secondary;
        color: var(--awd-text);
        background: var(--awd-bg);
    }
}

/* 手机号 / 邮箱 / 认证器绑定 */
.bind-link {
    color: var(--awd-accent-text);
    font-size: 13px;
    margin-left: 12px;
    cursor: pointer;
}
.bind-phone-form {
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px dashed var(--awd-border);
}
.bind-input {
    flex: 1;
    height: 36px;
    border: 1px solid var(--awd-border);
    border-radius: 6px;
    padding: 0 10px;
    font-size: 13px;
    background: var(--awd-surface);
}
.btn-send-code {
    height: 36px;
    line-height: 34px;
    margin-left: 8px;
    padding: 0 12px;
    border: 1px solid var(--awd-border);
    border-radius: 6px;
    background: var(--awd-surface);
    color: var(--awd-text);
    font-size: 13px;
    cursor: pointer;

    &[disabled] {
        opacity: 0.5;
        cursor: default;
    }
}
.bind-actions {
    display: flex;
    align-items: center;
    margin-top: 10px;
}
.btn-bind-confirm {
    height: 36px;
    line-height: 36px;
    padding: 0 18px;
    border-radius: 6px;
    background: var(--awd-accent);
    color: var(--awd-text-on-accent);
    font-size: 13px;
    cursor: pointer;
}
.doc-generator-row {
    justify-content: space-between;
}
.doc-generator-label {
    width: auto;
    flex: 1;
    color: var(--awd-text);
}
.bind-tip {
    display: block;
    margin-top: 8px;
    font-size: 12px;
    color: var(--awd-text-2);
}
.token-info {
    flex: 1;
    display: flex;
    flex-direction: column;
}
.token-name {
    font-size: 14px;
    color: var(--awd-text);
    font-weight: 500;
}
.token-meta {
    margin-top: 2px;
    font-size: 12px;
    color: var(--awd-text-2);
}
.totp-qr {
    width: 180px;
    margin: 10px 0;
    background: var(--awd-surface);
    border: 1px solid var(--awd-border);
    border-radius: 6px;
}
.totp-secret {
    flex: 1;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    letter-spacing: 1px;
    color: var(--awd-text);
    word-break: break-all;
    user-select: text;
}
</style>

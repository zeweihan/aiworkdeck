<template>
  <view class="wizard-page">
    <view class="bg-gradient"></view>

    <view class="wizard-card">
      <!-- Header -->
      <view class="card-header">
        <image class="card-logo" src="/static/iconmark_v2.png" mode="heightFix" />
        <view class="header-texts">
          <text class="wizard-title">{{ $t('onboarding.wizard.title') }}</text>
          <text class="wizard-subtitle">{{ $t('onboarding.wizard.subtitle') }}</text>
        </view>
      </view>

      <!-- Step 1: 连接账户（必做）。
           2026-08-21 起产品只有官方版（dev-board#98）：AI 只走平台通道 AWD_CLOUD，
           这里不再让用户在 OLLAMA / OPENROUTER / AWD_CLOUD 三者里选——只剩一项的单选
           是装饰，直接改成「连接账户」的直述。连接块本身沿用此前 AWD_CLOUD 选中后展开的那一块，
           向导里每条「下一步」都必须能在向导里做完（地雷 15）。 -->
      <view class="section">
        <view class="section-title">
          <text class="step-badge">1</text>
          <text class="title-text">{{ $t('onboarding.wizard.step1Title') }}</text>
          <text class="required-tag">{{ $t('onboarding.wizard.required') }}</text>
        </view>
        <text class="section-hint">{{ $t('onboarding.wizard.step1Intro') }}</text>
        <view class="provider-account">
          <template v-if="!platformAiAvailable">
            <text class="account-line">{{ $t('onboarding.wizard.accountIntro') }}</text>
            <view class="account-actions">
              <text class="account-link" @tap="openAccountSite">{{ $t('onboarding.wizard.goGetKey') }}</text>
            </view>
            <input
              class="text-input"
              v-model="accountKey"
              :placeholder="$t('onboarding.wizard.accountKeyPlaceholder')"
            />
            <button
              class="account-btn"
              :disabled="connectingAccount"
              @tap="handleConnectAccount"
            >
              {{ connectingAccount ? $t('onboarding.wizard.connecting') : $t('onboarding.wizard.connectAccount') }}
            </button>
          </template>
          <template v-else-if="platformNeedsAllocation">
            <text class="account-line">{{ $t('onboarding.wizard.accountNoCredits', { label: accountLabel }) }}</text>
            <view class="account-actions">
              <text class="account-link" @tap="openAccountSite">{{ $t('onboarding.wizard.goTopUp') }}</text>
              <text class="account-link" @tap="handleRecheckAccount">
                {{ recheckingAccount ? $t('onboarding.wizard.rechecking') : $t('onboarding.wizard.recheck') }}
              </text>
            </view>
          </template>
          <template v-else>
            <text class="account-line account-ok">{{ $t('onboarding.wizard.accountReady', { label: accountLabel }) }}</text>
          </template>
          <text v-if="accountError" class="account-error">{{ accountError }}</text>

          <!-- 跨境传输的单独同意（个保法第三十九条）。与管理后台是同一道闸
               （AdminConfigController.crossBorderBlockReason），向导这边曾经完全没有，
               而向导恰恰是选平台通道的主入口。绝不预勾选——预勾选的同意无效。 -->
          <view class="consent-box">
            <text class="consent-title">{{ $t('onboarding.wizard.consentTitle') }}</text>
            <text class="consent-body">{{ $t('onboarding.wizard.consentBody') }}</text>
            <view class="consent-check" @tap="crossBorderConsent = !crossBorderConsent">
              <view class="consent-box-mark" :class="{ checked: crossBorderConsent }"></view>
              <text class="consent-check-label">{{ $t('onboarding.wizard.consentCheckLabel') }}</text>
            </view>
          </view>
        </view>
        <!-- 合规口径：法律行业产品对数据流向的表述不能含糊。改造前这里写「AI PPT 在本机完成、
             数据不出本机」，而 AI PPT 的大纲、页面文案与配图全部交给云端模型生成，
             对律师是错误承诺。 -->
        <view class="compliance-note">
          <text>{{ $t('onboarding.wizard.complianceNote') }}</text>
        </view>
      </view>

      <!-- Step 2: 平台服务总览。
           改造前这里是 OCR / 语音 / 企业数据三组共 9 个输入框，全部撤走——首次开机就被要求
           去 8 家供应商开账号填 23 个字段，没有律师会去做，结果是绝大多数功能对绝大多数人
           根本不存在。**不是净删除**：这些服务一项没少，只是由我们统一代采、按用量折算
           Credits 从同一个账户余额扣。官方版不露 BYOK（dev-board#98），这里也不再指路
           「使用自己的 Key」。 -->
      <view class="section">
        <view class="section-title collapsible" @tap="showServices = !showServices">
          <text class="step-badge">2</text>
          <text class="title-text">{{ $t('onboarding.wizard.step2Title') }}</text>
          <text class="optional-tag">{{ $t('onboarding.wizard.optional') }}</text>
          <text class="collapse-arrow">{{ showServices ? $t('onboarding.wizard.collapse') : $t('onboarding.wizard.expand') }}</text>
        </view>
        <view v-if="showServices" class="advanced-body">
          <text class="section-hint">{{ $t('onboarding.wizard.step2Intro') }}</text>

          <text v-if="servicesError" class="svc-banner svc-banner-warn">{{ servicesError }}</text>
          <text
            v-else-if="!isDesktop || (servicesLoaded && !platformState.platformAvailable)"
            class="svc-banner"
          >{{ $t('platform.serverModeBody') }}</text>

          <view v-if="serviceRows.length" class="svc-list">
            <view v-for="row in serviceRows" :key="row.key" class="svc-row">
              <view class="svc-row-main">
                <text class="svc-name">{{ row.name }}</text>
                <text class="svc-desc">{{ row.desc }}</text>
              </view>
              <text class="svc-tier" :class="row.tierClass">{{ row.tierLabel }}</text>
            </view>
          </view>

          <!-- 未连账户时步骤 1 已经有连接块（现在恒常渲染），这里只指路，
               不再渲染第二个绑同一个 v-model 的输入框。 -->
          <text v-if="servicesNeedAccount" class="account-line">
            {{ $t('onboarding.wizard.servicesConnectAbove') }}
          </text>
          <text
            v-else-if="servicesLoaded && platformState.accountConnected"
            class="account-line account-ok"
          >{{ $t('onboarding.wizard.servicesReady') }}</text>
        </view>
      </view>

      <!-- Footer -->
      <view class="footer">
        <text class="footer-hint">{{ $t('onboarding.wizard.footerHint') }}</text>
        <button class="submit-btn" :disabled="submitting" :loading="submitting" @tap="handleSubmit">
          {{ submitting ? $t('onboarding.wizard.initializing') : $t('onboarding.wizard.finishSetup') }}
        </button>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getWizardStatus,
  submitWizard,
  getAccountStatus,
  getAccountUsage,
  connectAccount,
  getPlatformServices,
} from '@/services/api.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { isDesktopHost } from '@/services/host.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { platformServiceMeta, sortPlatformServices } from '@/config/platformServices.js'

// 官网账户页：生成账户 Key、充值都在这里（与 admin 页同一地址）。Credits 重构后没有「分配额度」这一步了
const ACCOUNT_SITE_URL = 'https://www.aiworkdeck.com/zh/account'

// 官方版唯一的 AI 供应商。后端 toSettingsUpdates 仍校验三档枚举，这里固定写平台通道。
const OFFICIAL_PROVIDER = 'AWD_CLOUD'

export default {
  name: 'FirstRunWizard',
  data() {
    return {
      submitting: false,
      // 默认展开：这一段的全部意义就是让用户**看见**「其余七项服务不用你配」。
      // 收起来就等于没做——他仍然以为要自己去开一堆账号。
      showServices: true,
      isDesktop: isDesktopHost(),
      // 七项外部服务的档位快照（GET /api/platform-services）。
      // provider 一律读接口给的生效值，不自己按凭证是否为空去猜。
      platformState: { services: [], platformAvailable: false, accountConnected: false },
      servicesLoaded: false,
      servicesError: '',
      // 平台通道「AI WorkDeck 云端」的两个前置条件，与 admin 页同一判据：
      // 已连接账户（本地读盘）+ 账户有 Credits（用量接口的 creditsCents）。
      platformAiAvailable: false,
      platformNeedsAllocation: false,
      accountName: '',
      accountKey: '',
      accountError: '',
      connectingAccount: false,
      recheckingAccount: false,
      // 跨境传输的单独同意（个保法第三十九条）。初值必须是 false——预勾选的同意无效。
      crossBorderConsent: false,
    }
  },
  computed: {
    accountLabel() {
      return this.accountName ? this.$t('onboarding.wizard.accountLabel', { name: this.accountName }) : ''
    },
    // 步骤 2 的服务状态行。档位标签**只由接口的 provider 决定**，
    // 「platform 档但还没连账户」单独成一态——那不是故障，是新用户的必经状态（设计 §7.1）。
    serviceRows() {
      const connected = this.platformState.accountConnected
      return sortPlatformServices(this.platformState.services).map((s) => {
        const meta = platformServiceMeta(s.service)
        let tierLabel = this.$t('platform.tierPlatform')
        let tierClass = 'tier-platform'
        if (s.provider === 'local') {
          tierLabel = this.$t('platform.tierLocal')
          tierClass = 'tier-local'
        } else if (s.provider === 'byok') {
          tierLabel = this.$t('platform.tierByok')
          tierClass = 'tier-byok'
        } else if (!connected) {
          tierLabel = this.$t('platform.tierNeedsAccount')
          tierClass = 'tier-need'
        }
        return {
          key: s.service,
          name: meta.nameKey ? this.$t(meta.nameKey) : s.service,
          desc: meta.descKey ? this.$t(meta.descKey) : '',
          tierLabel,
          tierClass,
        }
      })
    },
    // 有服务停在 platform 档却还没连账户 → 步骤 2 里指一句「在上方连接」
    servicesNeedAccount() {
      if (!this.servicesLoaded || !this.platformState.platformAvailable) return false
      if (this.platformState.accountConnected) return false
      return (this.platformState.services || []).some((s) => s.provider === 'platform')
    },
  },
  onLoad() {
    this.checkStatus()
    if (this.isDesktop) {
      this.loadPlatformAi()
      this.loadPlatformServices()
    }
  },
  methods: {
    // 平台通道可用性：status 是后端本地读盘（不打官网），可用时再补一次用量接口判额度。
    async loadPlatformAi() {
      try {
        const s = await getAccountStatus()
        this.platformAiAvailable = !!(s && s.platformAiAvailable)
        this.accountName = (s && (s.displayName || s.username)) || ''
      } catch (e) {
        this.platformAiAvailable = false
        return
      }
      if (!this.platformAiAvailable) return
      try {
        const usage = await getAccountUsage()
        const platform = (usage && usage.platform) || null
        // quotaAvailable=false 表示实时口径拿不到，此时不当作「没额度」拦人
        // 判据是 Credits 余额。用旧的 hasKey 口径会卡住刚充完值、还没调用过 AI 的新用户——
        // 「向导里每一条下一步都必须能在向导里做完」，这条路当年就是这么走死的。
        this.platformNeedsAllocation = !!(platform && platform.quotaAvailable && !platform.hasAiQuota)
        if (platform && typeof platform.creditsCents === 'number') {
          this.platformNeedsAllocation = platform.creditsCents <= 0
        }
      } catch (e) {
        this.platformNeedsAllocation = false
      }
    },
    // 七项外部服务的档位快照。读失败时说清是「没读到状态」而不是「服务不可用」，
    // 并且**不拦提交**——这一段是告知，不是必答题。
    async loadPlatformServices() {
      try {
        const s = (await getPlatformServices()) || {}
        this.platformState = {
          services: Array.isArray(s.services) ? s.services : [],
          platformAvailable: !!s.platformAvailable,
          accountConnected: !!s.accountConnected,
        }
        this.servicesError = ''
      } catch (e) {
        this.servicesError = (e && e.message) || this.$t('platform.loadFailed')
      } finally {
        this.servicesLoaded = true
      }
    },
    openAccountSite() {
      openExternalUrl(ACCOUNT_SITE_URL)
    },
    // 就地连接账户：与 admin 页 onConnectAccount 同一条链路（连接 → 重取状态 → 权益缓存失效）
    async handleConnectAccount() {
      const key = (this.accountKey || '').replace(/\s+/g, '')
      if (!key) {
        this.accountError = this.$t('onboarding.wizard.pasteKeyFirst')
        return
      }
      this.accountError = ''
      this.connectingAccount = true
      try {
        await connectAccount(key)
        this.accountKey = ''
        await this.loadPlatformAi()
        // 平台服务的可用性同样随账户走：连上之后步骤 2 那七行要立刻从
        // 「需要连接账户」翻成「平台代采」，否则用户不知道自己刚才做的事生效了没有
        await this.loadPlatformServices()
        // 已购功能解锁随账户走，连接后必须让权益缓存失效重取
        await refreshEntitlements(true)
        uni.showToast({ title: this.$t('onboarding.wizard.accountConnectedToast'), icon: 'none' })
      } catch (e) {
        this.accountError = (e && e.message) || this.$t('onboarding.wizard.connectFailed')
      } finally {
        this.connectingAccount = false
      }
    },
    // 用户到官网充完值回到向导：不必重启应用，重查一次即可
    async handleRecheckAccount() {
      if (this.recheckingAccount) return
      this.accountError = ''
      this.recheckingAccount = true
      try {
        await this.loadPlatformAi()
        await this.loadPlatformServices()
        if (this.platformNeedsAllocation) {
          this.accountError = this.$t('onboarding.wizard.creditsNotFoundYet')
        }
      } finally {
        this.recheckingAccount = false
      }
    },
    async checkStatus() {
      try {
        const res = await getWizardStatus()
        if (res && res.initialized) {
          uni.reLaunch({ url: '/pages/launch/launch' })
        }
      } catch (e) {
        // 后端暂不可达时留在向导页，提交时会再次校验
        console.warn('查询向导状态失败:', e)
      }
    },
    // 只带 ai.activeProvider 与同意；不带 external / ollama* 字段——
    // 后端 toSettingsUpdates 对 null 字段一律跳过，存量 key 不会被清空。
    buildPayload() {
      return {
        ai: {
          activeProvider: OFFICIAL_PROVIDER,
          crossBorderConsent: this.crossBorderConsent,
        },
      }
    },
    async handleSubmit() {
      // 平台通道两个前置条件缺一都会在发第一条消息时才报错，拦在这里并指出下一步
      if (!this.platformAiAvailable) {
        uni.showToast({ title: this.$t('onboarding.wizard.connectAccountFirst'), icon: 'none' })
        return
      }
      if (this.platformNeedsAllocation) {
        // 文案红线：不能含「请先」——api.js 用它判掉线并清会话
        uni.showToast({ title: this.$t('onboarding.wizard.creditsEmpty'), icon: 'none' })
        return
      }
      // 跨境同意：后端 crossBorderBlockReason 也会拦（两道都在才算数），
      // 这里拦一次是为了把提示给在勾选框旁边而不是提交失败之后
      if (!this.crossBorderConsent) {
        uni.showToast({ title: this.$t('onboarding.wizard.consentRequired'), icon: 'none' })
        return
      }

      this.submitting = true
      try {
        await submitWizard(this.buildPayload())
        uni.showToast({ title: this.$t('onboarding.wizard.initDone'), icon: 'success' })
        setTimeout(() => {
          uni.reLaunch({ url: '/pages/launch/launch' })
        }, 600)
      } catch (e) {
        const msg = e && e.message ? e.message : this.$t('onboarding.wizard.initFailed')
        // 409（已初始化）等场景：提示后回到登录页（indexOf 的两个匹配串是后端消息判据，不抽）
        if (msg.indexOf('已初始化') !== -1 || msg.indexOf('Already initialized') !== -1) {
          uni.showToast({ title: this.$t('onboarding.wizard.alreadyInitialized'), icon: 'none' })
          setTimeout(() => {
            uni.reLaunch({ url: '/pages/launch/launch' })
          }, 800)
        } else {
          uni.showToast({ title: msg, icon: 'none' })
        }
      } finally {
        this.submitting = false
      }
    },
  },
}
</script>


<style scoped>
.wizard-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 48px 16px 64px;
  box-sizing: border-box;
}

.bg-gradient {
  position: fixed;
  inset: 0;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 45%, #e0f2fe 100%);
  z-index: -1;
}

.wizard-card {
  width: 100%;
  max-width: 720px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgba(15, 23, 42, 0.08);
  padding: 32px 36px;
  box-sizing: border-box;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
}

.card-logo {
  height: 44px;
  width: 44px;
}

.header-texts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.wizard-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.wizard-subtitle {
  font-size: 13px;
  color: #64748b;
}

.section {
  margin-bottom: 22px;
  padding-top: 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.18);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.section-title.collapsible {
  cursor: pointer;
}

.step-badge {
  width: 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  background: #1e293b;
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.title-text {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.required-tag,
.optional-tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
}

.required-tag {
  color: #b91c1c;
  background: #fee2e2;
}

.optional-tag {
  color: #475569;
  background: #f1f5f9;
}

.collapse-arrow {
  margin-left: auto;
  font-size: 12px;
  color: #64748b;
}

.section-hint {
  display: block;
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 10px;
}

.provider-account {
  margin-top: 4px;
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px dashed rgba(148, 163, 184, 0.4);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.account-line {
  font-size: 12px;
  color: #475569;
  line-height: 1.7;
}

.account-ok {
  color: #166534;
}

.account-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.account-link {
  font-size: 12px;
  color: #1d4ed8;
  cursor: pointer;
}

/* 跨境同意块。单位与配色跟随本页（px + slate 系），不要照抄 admin 页那份（rpx + #1a5336）。 */
.consent-box {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-left: 3px solid #166534;
  border-radius: 6px;
  background: #f8fafc;
}

.consent-title {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 6px;
}

.consent-body {
  display: block;
  font-size: 12px;
  line-height: 1.7;
  color: #475569;
}

.consent-check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  cursor: pointer;
}

.consent-box-mark {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  margin-top: 2px;
  border: 1px solid #94a3b8;
  border-radius: 3px;
  background: #fff;
}

.consent-box-mark.checked {
  background: #166534;
  border-color: #166534;
}

.consent-check-label {
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
}

.account-link:hover {
  text-decoration: underline;
}

.account-btn {
  height: 36px;
  line-height: 36px;
  border-radius: 8px;
  background: #1e293b;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}

.account-btn[disabled] {
  opacity: 0.6;
}

.account-error {
  font-size: 12px;
  color: #dc2626;
  line-height: 1.6;
}

.compliance-note {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px dashed rgba(148, 163, 184, 0.4);
}

.compliance-note text {
  font-size: 12px;
  color: #64748b;
  line-height: 1.7;
}

.text-input {
  height: 36px;
  padding: 0 12px;
  font-size: 13px;
  border: 1px solid rgba(148, 163, 184, 0.45);
  border-radius: 8px;
  background: #fff;
  box-sizing: border-box;
}

/* 步骤 2：平台服务状态列表。单位与配色跟随本页（px + slate 系）。 */
.svc-banner {
  display: block;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px dashed rgba(148, 163, 184, 0.4);
  font-size: 12px;
  color: #64748b;
  line-height: 1.7;
}

.svc-banner-warn {
  color: #b45309;
  border-color: rgba(180, 83, 9, 0.35);
}

.svc-list {
  display: flex;
  flex-direction: column;
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 10px;
  overflow: hidden;
}

.svc-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.svc-row:last-child {
  border-bottom: none;
}

.svc-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.svc-name {
  font-size: 13px;
  color: #0f172a;
}

.svc-desc {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}

.svc-tier {
  flex: none;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
}

.tier-platform {
  color: #166534;
  background: #dcfce7;
}

.tier-byok {
  color: #475569;
  background: #f1f5f9;
}

.tier-local {
  color: #1d4ed8;
  background: #dbeafe;
}

.tier-need {
  color: #9a3412;
  background: #ffedd5;
}

.svc-foot {
  margin-top: 10px;
  margin-bottom: 0;
}

.footer {
  margin-top: 8px;
  padding-top: 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.18);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.footer-hint {
  font-size: 12px;
  color: #94a3b8;
}

.submit-btn {
  height: 44px;
  line-height: 44px;
  border-radius: 10px;
  background: #1e293b;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.submit-btn[disabled] {
  opacity: 0.6;
}

@media (max-width: 560px) {
  .wizard-card {
    padding: 24px 18px;
  }
}
</style>

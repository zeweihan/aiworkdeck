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

      <!-- Step 1: AI provider (required) -->
      <view class="section">
        <view class="section-title">
          <text class="step-badge">1</text>
          <text class="title-text">{{ $t('onboarding.wizard.step1Title') }}</text>
          <text class="required-tag">{{ $t('onboarding.wizard.required') }}</text>
        </view>
        <view class="provider-list">
          <view
            v-for="opt in providerOptions"
            :key="opt.value"
            class="provider-card"
            :class="{ selected: form.ai.activeProvider === opt.value }"
            @tap="pickProvider(opt)"
          >
            <view class="provider-head">
              <view class="radio-dot" :class="{ checked: form.ai.activeProvider === opt.value }"></view>
              <text class="provider-name">{{ opt.label }}</text>
              <text class="data-flow-tag" :class="opt.local ? 'tag-local' : 'tag-cloud'">
                {{ opt.local ? $t('onboarding.wizard.dataLocal') : $t('onboarding.wizard.dataCloud') }}
              </text>
            </view>
            <text class="provider-desc">{{ opt.desc }}</text>
            <view v-if="form.ai.activeProvider === opt.value && opt.setupHint" class="provider-setup">
              <text class="setup-line">{{ opt.setupHint }}</text>
              <text class="setup-cmd" selectable>{{ opt.setupCmd }}</text>
            </view>
            <!-- 本地 Ollama 的连通性探测就地完成：装没装、跑没跑、模型拉没拉，
                 三件事都要在向导里能看清并能重试，不能让用户点完「完成设置」
                 到发第一条消息才收到 Connection refused（地雷 15）。 -->
            <view v-if="form.ai.activeProvider === opt.value && opt.value === 'OLLAMA'" class="provider-setup ollama-probe">
              <text class="setup-line">{{ $t('onboarding.wizard.ollamaAskOnly') }}</text>
              <text v-if="ollamaProbe.checking" class="setup-line">{{ $t('onboarding.wizard.ollamaProbing') }}</text>
              <template v-else-if="ollamaProbe.error">
                <text class="setup-line probe-bad">{{ ollamaProbe.error }}</text>
              </template>
              <!-- message / nextStep / command 全部来自后端：三态各自该说什么、该给哪条命令，
                   由 OllamaProbeService 一处决定，前端只负责排版与配色。 -->
              <template v-else-if="ollamaProbe.done">
                <text
                  class="setup-line"
                  :class="ollamaProbe.status === 'READY' ? 'probe-ok' : 'probe-bad'"
                >{{ ollamaProbe.message }}</text>
                <text v-if="ollamaProbe.nextStep" class="setup-line">{{ ollamaProbe.nextStep }}</text>
                <text v-if="ollamaProbe.command" class="setup-cmd" selectable>{{ ollamaProbe.command }}</text>
                <text v-if="ollamaProbe.status === 'SERVICE_DOWN'" class="setup-cmd" selectable>https://ollama.com/download</text>
              </template>
              <view class="account-actions">
                <text class="account-link" @tap="runOllamaProbe">
                  {{ ollamaProbe.checking ? $t('onboarding.wizard.probing') : $t('onboarding.wizard.reprobe') }}
                </text>
              </view>
              <text class="setup-line">{{ $t('onboarding.wizard.ollamaChangeHint') }}</text>
            </view>
            <view v-if="form.ai.activeProvider === opt.value && opt.keyField" class="provider-key">
              <input
                class="text-input"
                :password="true"
                v-model="apiKeys[opt.value]"
                :placeholder="opt.keyPlaceholder"
              />
            </view>
            <!-- 平台通道的连接就地完成：把「进入产品后再去系统管理粘贴 Key」的死路收回向导内 -->
            <view v-if="form.ai.activeProvider === opt.value && opt.accountField" class="provider-account">
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
          </view>
        </view>
        <!-- 合规口径：法律行业产品对数据流向的表述不能含糊。改造前这里写「AI PPT 在本机完成、
             数据不出本机」，而 AI PPT 的大纲、页面文案与配图全部交给云端模型生成，
             对律师是错误承诺。 -->
        <view class="compliance-note">
          <text>{{ $t('onboarding.wizard.complianceNote') }}</text>
        </view>
      </view>

      <!-- Step 2: advanced (collapsed) -->
      <view class="section">
        <view class="section-title collapsible" @tap="showAdvanced = !showAdvanced">
          <text class="step-badge">2</text>
          <text class="title-text">{{ $t('onboarding.wizard.step2Title') }}</text>
          <text class="optional-tag">{{ $t('onboarding.wizard.optional') }}</text>
          <text class="collapse-arrow">{{ showAdvanced ? $t('onboarding.wizard.collapse') : $t('onboarding.wizard.expand') }}</text>
        </view>
        <view v-if="showAdvanced" class="advanced-body">
          <view class="adv-group">
            <text class="adv-group-title">{{ $t('onboarding.wizard.ocrGroup') }}</text>
            <view class="form-grid">
              <view class="form-item">
                <text class="form-label">AccessKey ID</text>
                <input class="text-input" v-model="form.external.aliyunOcr.accessKeyId" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
              <view class="form-item">
                <text class="form-label">AccessKey Secret</text>
                <input class="text-input" :password="true" v-model="form.external.aliyunOcr.accessKeySecret" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
            </view>
          </view>
          <view class="adv-group">
            <text class="adv-group-title">{{ $t('onboarding.wizard.dataGroup') }}</text>
            <view class="form-grid">
              <view class="form-item">
                <text class="form-label">{{ $t('onboarding.wizard.qichachaKey') }}</text>
                <input class="text-input" v-model="form.external.qichacha.key" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
              <view class="form-item">
                <text class="form-label">{{ $t('onboarding.wizard.qichachaSecret') }}</text>
                <input class="text-input" :password="true" v-model="form.external.qichacha.secret" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
              <view class="form-item">
                <text class="form-label">Tushare Token</text>
                <input class="text-input" :password="true" v-model="form.external.tushare.token" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
              <view class="form-item">
                <text class="form-label">{{ $t('onboarding.wizard.pkulawToken') }}</text>
                <input class="text-input" :password="true" v-model="form.external.pkulaw.token" :placeholder="$t('onboarding.wizard.optionalPlaceholder')" />
              </view>
            </view>
          </view>
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
  probeOllama,
} from '@/services/api.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { isDesktopHost } from '@/services/host.js'
import { openExternalUrl } from '@/utils/externalLink.js'

// 官网账户页：生成账户 Key、充值都在这里（与 admin 页同一地址）。Credits 重构后没有「分配额度」这一步了
const ACCOUNT_SITE_URL = 'https://www.aiworkdeck.com/zh/account'

export default {
  name: 'FirstRunWizard',
  data() {
    return {
      submitting: false,
      showAdvanced: false,
      isDesktop: isDesktopHost(),
      // 平台通道「AI Workdeck 云端」的两个前置条件，与 admin 页同一判据：
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
      // 各云端提供商的 key 暂存：切换选项不丢已填内容，提交时只带选中者
      apiKeys: {
        OPENROUTER: '',
      },
      // 本地 Ollama 探测结果（GET /api/ai/ollama/probe）。done=false 表示还没探过，
      // 此时不给「已就绪」也不给报错，避免刚点上选项就红一片。
      // 字段名与后端 OllamaProbeService.ProbeResult 逐字对齐：status 三态
      // （READY / MODEL_MISSING / SERVICE_DOWN）＋后端给的 message / nextStep / command。
      // message 与 command 一律原样展示，前端不自己拼 `ollama pull`——目标模型是后端
      // 按「入参 > DB > yml」解析的，前端拼出来的会和它不一致。
      ollamaProbe: {
        checking: false,
        done: false,
        status: '',
        baseUrl: '',
        targetModel: '',
        message: '',
        nextStep: '',
        command: '',
        error: '',
      },
      // 供应商三档（GEMINI 已下线）：Gemini 系列模型仍可通过 OpenRouter 的 google/* 使用。
      byokOptions: [
        {
          value: 'OLLAMA',
          label: this.$t('onboarding.wizard.ollamaLabel'),
          local: true,
          desc: this.$t('onboarding.wizard.ollamaDesc'),
          keyField: null,
        },
        {
          value: 'OPENROUTER',
          label: 'OpenRouter',
          local: false,
          desc: this.$t('onboarding.wizard.openRouterDesc'),
          keyField: 'apiKey',
          keyPlaceholder: this.$t('onboarding.wizard.openRouterKeyPlaceholder'),
          setupHint: this.$t('onboarding.wizard.openRouterSetupHint'),
          setupCmd: 'https://openrouter.ai/settings/keys',
        },
      ],
      form: {
        ai: {
          // 刻意不预选：预选 OLLAMA 时，没装 Ollama 的用户一路点「完成设置」，
          // 会到发第一条消息才收到 Connection refused。必须让用户显式选一个。
          activeProvider: '',
        },
        external: {
          aliyunOcr: { accessKeyId: '', accessKeySecret: '' },
          qichacha: { key: '', secret: '' },
          tushare: { token: '' },
          pkulaw: { token: '' },
        },
      },
    }
  },
  computed: {
    // 「AI Workdeck 云端」置顶：用账户 Key 解锁的用户买的就是这条通道，
    // 不列出来他会被引导去再配一家别的 Key。选中即就地展开连接块——
    // 前置条件不满足时**不能只给一句「进入产品后再去设置里连」**，那是死路：
    // 用户在向导里没有任何办法把它变成可用，只能先选一家别的凑合。
    providerOptions() {
      if (!this.isDesktop) return this.byokOptions
      return [
        {
          value: 'AWD_CLOUD',
          label: this.$t('onboarding.wizard.awdCloudLabel'),
          local: false,
          desc: this.$t('onboarding.wizard.awdCloudDesc'),
          keyField: null,
          accountField: true,
        },
        ...this.byokOptions,
      ]
    },
    accountLabel() {
      return this.accountName ? this.$t('onboarding.wizard.accountLabel', { name: this.accountName }) : ''
    },
  },
  onLoad() {
    this.checkStatus()
    if (this.isDesktop) {
      this.loadPlatformAi()
    }
  },
  methods: {
    // 平台通道可用性：status 是后端本地读盘（不打官网），可用时再补一次用量接口判额度。
    // 两个条件都满足才替用户预选它——解锁时粘的就是账户 Key 的人不该再被问一遍。
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
      if (!this.platformNeedsAllocation && !this.form.ai.activeProvider) {
        this.form.ai.activeProvider = 'AWD_CLOUD'
      }
    },
    pickProvider(opt) {
      this.form.ai.activeProvider = opt.value
      // 选中本地档就立刻探一次：把「装了没 / 跑着没 / 模型拉了没」摆在眼前，
      // 用户不需要先提交再回来看结果
      if (opt.value === 'OLLAMA' && !this.ollamaProbe.checking) {
        this.runOllamaProbe()
      }
    },
    // 本机 Ollama 探测。后端读自己的配置去打 /api/tags（不接受前端传地址，
    // 免得这个端点变成一个可以拿后端当跳板的探测器）。
    async runOllamaProbe() {
      if (this.ollamaProbe.checking) return
      this.ollamaProbe.checking = true
      this.ollamaProbe.error = ''
      try {
        const r = (await probeOllama()) || {}
        this.ollamaProbe.status = r.status || ''
        this.ollamaProbe.baseUrl = r.baseUrl || ''
        this.ollamaProbe.targetModel = r.targetModel || ''
        this.ollamaProbe.message = r.message || ''
        this.ollamaProbe.nextStep = r.nextStep || ''
        this.ollamaProbe.command = r.command || ''
        this.ollamaProbe.done = true
      } catch (e) {
        // 探测端点本身不可达（后端还没起 / 旧后端没有这个端点）：
        // 说清楚是「没探到」而不是「Ollama 坏了」，并且照样拦住提交
        this.ollamaProbe.status = ''
        this.ollamaProbe.done = true
        this.ollamaProbe.error = (e && e.message)
          ? this.$t('onboarding.wizard.probeFailedWithReason', { reason: e.message })
          : this.$t('onboarding.wizard.probeFailedRetry')
      } finally {
        this.ollamaProbe.checking = false
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
    buildPayload() {
      const trim = (v) => (v || '').trim()
      const provider = this.form.ai.activeProvider
      const payload = { ai: { activeProvider: provider } }
      // 只在选平台通道时带同意：其余档位不涉及跨境，带 false 会把已有同意误撤回
      if (provider === 'AWD_CLOUD') {
        payload.ai.crossBorderConsent = this.crossBorderConsent
      }
      const external = {}

      if (provider === 'OPENROUTER' && trim(this.apiKeys.OPENROUTER)) {
        external.openRouter = { apiKey: trim(this.apiKeys.OPENROUTER) }
      }

      const ocr = this.form.external.aliyunOcr
      if (trim(ocr.accessKeyId) || trim(ocr.accessKeySecret)) {
        external.aliyunOcr = { accessKeyId: trim(ocr.accessKeyId), accessKeySecret: trim(ocr.accessKeySecret) }
      }
      const qcc = this.form.external.qichacha
      if (trim(qcc.key) || trim(qcc.secret)) {
        external.qichacha = { key: trim(qcc.key), secret: trim(qcc.secret) }
      }
      if (trim(this.form.external.tushare.token)) {
        external.tushare = { token: trim(this.form.external.tushare.token) }
      }
      if (trim(this.form.external.pkulaw.token)) {
        external.pkulaw = { token: trim(this.form.external.pkulaw.token) }
      }

      if (Object.keys(external).length > 0) {
        payload.external = external
      }
      return payload
    },
    async handleSubmit() {
      const provider = this.form.ai.activeProvider
      if (!provider) {
        uni.showToast({ title: this.$t('onboarding.wizard.pickProviderFirst'), icon: 'none' })
        return
      }
      if (provider === 'OPENROUTER' && !this.apiKeys.OPENROUTER.trim()) {
        uni.showToast({ title: this.$t('onboarding.wizard.fillOpenRouterKey'), icon: 'none' })
        return
      }
      // 本地档：服务没起或目标模型没 pull 都拦住，并指出下一步（同上方探测块）。
      // 还没探过就先探一次再判——不能因为用户没点「重新检测」就放行一个跑不起来的配置。
      if (provider === 'OLLAMA') {
        if (!this.ollamaProbe.done && !this.ollamaProbe.checking) {
          await this.runOllamaProbe()
        }
        if (this.ollamaProbe.error) {
          uni.showToast({ title: this.$t('onboarding.wizard.probeNotDone'), icon: 'none' })
          return
        }
        if (this.ollamaProbe.status === 'SERVICE_DOWN') {
          uni.showToast({ title: this.$t('onboarding.wizard.ollamaServiceDown'), icon: 'none' })
          return
        }
        if (this.ollamaProbe.status !== 'READY') {
          // MODEL_MISSING，以及后端返回了认不出的 status（宁可拦住也不放行一个跑不起来的配置）
          uni.showToast({ title: this.$t('onboarding.wizard.ollamaModelMissing'), icon: 'none' })
          return
        }
      }
      // 平台通道两个前置条件缺一都会在发第一条消息时才报错，拦在这里并指出下一步
      if (provider === 'AWD_CLOUD' && !this.platformAiAvailable) {
        uni.showToast({ title: this.$t('onboarding.wizard.connectAccountFirst'), icon: 'none' })
        return
      }
      if (provider === 'AWD_CLOUD' && this.platformNeedsAllocation) {
        // 文案红线：不能含「请先」——api.js 用它判掉线并清会话
        uni.showToast({ title: this.$t('onboarding.wizard.creditsEmpty'), icon: 'none' })
        return
      }
      // 跨境同意：后端 crossBorderBlockReason 也会拦（两道都在才算数），
      // 这里拦一次是为了把提示给在勾选框旁边而不是提交失败之后
      if (provider === 'AWD_CLOUD' && !this.crossBorderConsent) {
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

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.provider-card {
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.provider-card.selected {
  border-color: #2563eb;
  background: rgba(37, 99, 235, 0.04);
}

.provider-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.radio-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #94a3b8;
  box-sizing: border-box;
  flex-shrink: 0;
}

.radio-dot.checked {
  border-color: #2563eb;
  background: radial-gradient(circle, #2563eb 0 4px, transparent 5px);
}

.provider-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.data-flow-tag {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
}

.tag-local {
  color: #166534;
  background: #dcfce7;
}

.tag-cloud {
  color: #9a3412;
  background: #ffedd5;
}

.provider-desc {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 6px;
  line-height: 1.6;
}

.provider-key {
  margin-top: 10px;
}

.provider-account {
  margin-top: 10px;
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

.provider-setup {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f0fdf4;
  border: 1px dashed rgba(22, 101, 52, 0.35);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.setup-line {
  font-size: 12px;
  color: #166534;
  line-height: 1.6;
}

/* 探测块用中性底色：它承载的不只是「怎么装」，还有失败原因与重试动作 */
.ollama-probe {
  background: #f8fafc;
  border-color: rgba(148, 163, 184, 0.4);
}

.ollama-probe .setup-line {
  color: #475569;
}

/* 选择器带上父类：.ollama-probe .setup-line 的权重比裸类名高，否则这两档颜色不生效 */
.ollama-probe .probe-bad {
  color: #b45309;
}

.ollama-probe .probe-ok {
  color: #166534;
}

.setup-cmd {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  color: #0f172a;
  background: #ffffff;
  border: 1px solid rgba(148, 163, 184, 0.45);
  border-radius: 6px;
  padding: 6px 10px;
  user-select: all;
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

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.form-label {
  font-size: 12px;
  color: #475569;
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

.adv-group {
  margin-bottom: 14px;
}

.adv-group-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
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

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

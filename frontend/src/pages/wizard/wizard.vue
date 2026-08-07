<template>
  <view class="wizard-page">
    <view class="bg-gradient"></view>

    <view class="wizard-card">
      <!-- Header -->
      <view class="card-header">
        <image class="card-logo" src="/static/iconmark_v2.png" mode="heightFix" />
        <view class="header-texts">
          <text class="wizard-title">欢迎使用 AI Workdeck</text>
          <text class="wizard-subtitle">首次运行设置 · 只需一步，选择您的 AI 提供商即可开始</text>
        </view>
      </view>

      <!-- Step 1: AI provider (required) -->
      <view class="section">
        <view class="section-title">
          <text class="step-badge">1</text>
          <text class="title-text">AI 提供商</text>
          <text class="required-tag">必选</text>
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
                {{ opt.local ? '数据不出本机' : '数据发往云端' }}
              </text>
            </view>
            <text class="provider-desc">{{ opt.desc }}</text>
            <view v-if="form.ai.activeProvider === opt.value && opt.setupHint" class="provider-setup">
              <text class="setup-line">{{ opt.setupHint }}</text>
              <text class="setup-cmd" selectable>{{ opt.setupCmd }}</text>
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
                <text class="account-line">
                  桌面端无需注册登录：在官网账户页生成一枚账户 Key（awdk_ 开头），粘贴到下面直接连接。Key 只保存在本机，随时可以断开。
                </text>
                <view class="account-actions">
                  <text class="account-link" @tap="openAccountSite">前往官网获取 Key</text>
                </view>
                <input
                  class="text-input"
                  v-model="accountKey"
                  placeholder="粘贴 awdk_ 开头的账户 Key"
                />
                <button
                  class="account-btn"
                  :disabled="connectingAccount"
                  @tap="handleConnectAccount"
                >
                  {{ connectingAccount ? '正在连接…' : '连接账户' }}
                </button>
              </template>
              <template v-else-if="platformNeedsAllocation">
                <text class="account-line">
                  账户已连接{{ accountLabel }}。还差一步：到官网账户页从余额分配 AI 额度，分配完点「重新检查」。
                </text>
                <view class="account-actions">
                  <text class="account-link" @tap="openAccountSite">前往官网分配额度</text>
                  <text class="account-link" @tap="handleRecheckAccount">
                    {{ recheckingAccount ? '检查中…' : '重新检查' }}
                  </text>
                </view>
              </template>
              <template v-else>
                <text class="account-line account-ok">账户已连接{{ accountLabel }}，可以直接开始使用。</text>
              </template>
              <text v-if="accountError" class="account-error">{{ accountError }}</text>
            </view>
          </view>
        </view>
        <view class="compliance-note">
          <text>
            数据流向说明：选择本地 Ollama 时，对话内容仅在本机处理；选择云端提供商时，对话内容将发送至该第三方服务，
            请律师朋友注意执业保密义务。当前使用的模型与提供商在产品内可见，且可随时在「系统管理」中更换或停用。
            文档解析、AI PPT 与语音合成均在本机完成、数据不出本机；本地组件（解析/语音模型）首次使用需在「系统管理 → 组件管理」一次性下载，之后离线可用。
          </text>
        </view>
      </view>

      <!-- Step 2: advanced (collapsed) -->
      <view class="section">
        <view class="section-title collapsible" @tap="showAdvanced = !showAdvanced">
          <text class="step-badge">2</text>
          <text class="title-text">高级选项（OCR / 语音 / 企业数据）</text>
          <text class="optional-tag">可选</text>
          <text class="collapse-arrow">{{ showAdvanced ? '▲ 收起' : '▼ 展开' }}</text>
        </view>
        <view v-if="showAdvanced" class="advanced-body">
          <view class="adv-group">
            <text class="adv-group-title">OCR 识别（阿里云）</text>
            <view class="form-grid">
              <view class="form-item">
                <text class="form-label">AccessKey ID</text>
                <input class="text-input" v-model="form.external.aliyunOcr.accessKeyId" placeholder="选填" />
              </view>
              <view class="form-item">
                <text class="form-label">AccessKey Secret</text>
                <input class="text-input" :password="true" v-model="form.external.aliyunOcr.accessKeySecret" placeholder="选填" />
              </view>
            </view>
          </view>
          <view class="adv-group">
            <text class="adv-group-title">语音合成（可选云端 ElevenLabs——默认使用本地引擎，无需配置）</text>
            <view class="form-grid">
              <view class="form-item">
                <text class="form-label">API Key</text>
                <input class="text-input" :password="true" v-model="form.external.elevenLabs.apiKey" placeholder="选填" />
              </view>
            </view>
          </view>
          <view class="adv-group">
            <text class="adv-group-title">企业与法律数据</text>
            <view class="form-grid">
              <view class="form-item">
                <text class="form-label">企查查 Key</text>
                <input class="text-input" v-model="form.external.qichacha.key" placeholder="选填" />
              </view>
              <view class="form-item">
                <text class="form-label">企查查 SecretKey</text>
                <input class="text-input" :password="true" v-model="form.external.qichacha.secret" placeholder="选填" />
              </view>
              <view class="form-item">
                <text class="form-label">Tushare Token</text>
                <input class="text-input" :password="true" v-model="form.external.tushare.token" placeholder="选填" />
              </view>
              <view class="form-item">
                <text class="form-label">北大法宝 Token</text>
                <input class="text-input" :password="true" v-model="form.external.pkulaw.token" placeholder="选填" />
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- Footer -->
      <view class="footer">
        <text class="footer-hint">所有配置可随时在「系统管理」中修改。</text>
        <button class="submit-btn" :disabled="submitting" :loading="submitting" @tap="handleSubmit">
          {{ submitting ? '正在初始化…' : '完成设置' }}
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
} from '@/services/api.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { isDesktopHost } from '@/services/host.js'
import { openExternalUrl } from '@/utils/externalLink.js'

// 官网账户页：生成账户 Key、充值、分配 AI 额度都在这里（与 admin 页同一地址）
const ACCOUNT_SITE_URL = 'https://www.aiworkdeck.com/zh/account'

export default {
  name: 'FirstRunWizard',
  data() {
    return {
      submitting: false,
      showAdvanced: false,
      isDesktop: isDesktopHost(),
      // 平台通道「AI Workdeck 云端」的两个前置条件，与 admin 页同一判据：
      // 已连接账户（本地读盘）+ 已在官网从余额分配 AI 额度（用量接口）。
      platformAiAvailable: false,
      platformNeedsAllocation: false,
      accountName: '',
      accountKey: '',
      accountError: '',
      connectingAccount: false,
      recheckingAccount: false,
      // 各云端提供商的 key 暂存：切换选项不丢已填内容，提交时只带选中者
      apiKeys: {
        OPENROUTER: '',
        GEMINI: '',
      },
      byokOptions: [
        {
          value: 'OLLAMA',
          label: '本地 Ollama',
          local: true,
          desc: '零密钥、零费用，对话数据全程不离开本机。需先在本机安装并启动 Ollama（ollama.com）。',
          keyField: null,
          setupHint: '安装并启动 Ollama 后，请在终端拉取本产品使用的模型（约 6GB，首次对话前必须完成）：',
          setupCmd: 'ollama pull qwen3-vl:8b',
        },
        {
          value: 'OPENROUTER',
          label: 'OpenRouter',
          local: false,
          desc: '一个 Key 接入多家主流模型（OpenAI / Anthropic / Google 等），按用量计费。',
          keyField: 'apiKey',
          keyPlaceholder: '请输入 OpenRouter API Key（必填）',
          setupHint: '如何获取：打开下方网址 → 注册/登录 → 在 Keys 页面点「Create Key」→ 复制生成的 Key 粘贴到下方输入框（首次使用需在 Credits 页面充值少量金额）：',
          setupCmd: 'https://openrouter.ai/settings/keys',
        },
        {
          value: 'GEMINI',
          label: 'Google Gemini',
          local: false,
          desc: '使用 Google Gemini 云端模型，需要 Gemini API Key。同一 Key 也用于 AI PPT 的图像生成。',
          keyField: 'apiKey',
          keyPlaceholder: '请输入 Gemini API Key（必填）',
          setupHint: '如何获取：用 Google 账号登录下方网址（Google AI Studio）→ 点「Create API key」→ 复制生成的 Key 粘贴到下方输入框（有免费额度，无需绑卡即可开始）：',
          setupCmd: 'https://aistudio.google.com/apikey',
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
          elevenLabs: { apiKey: '' },
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
          label: 'AI Workdeck 云端',
          local: false,
          desc: '用账户余额直接调用平台模型，无需自备 Key。按实际扣费结算，用量在「系统管理 → 账户与用量」可查。',
          keyField: null,
          accountField: true,
        },
        ...this.byokOptions,
      ]
    },
    accountLabel() {
      return this.accountName ? `（${this.accountName}）` : ''
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
        this.platformNeedsAllocation = !!(platform && platform.quotaAvailable && !platform.hasAiQuota)
      } catch (e) {
        this.platformNeedsAllocation = false
      }
      if (!this.platformNeedsAllocation && !this.form.ai.activeProvider) {
        this.form.ai.activeProvider = 'AWD_CLOUD'
      }
    },
    pickProvider(opt) {
      this.form.ai.activeProvider = opt.value
    },
    openAccountSite() {
      openExternalUrl(ACCOUNT_SITE_URL)
    },
    // 就地连接账户：与 admin 页 onConnectAccount 同一条链路（连接 → 重取状态 → 权益缓存失效）
    async handleConnectAccount() {
      const key = (this.accountKey || '').replace(/\s+/g, '')
      if (!key) {
        this.accountError = '请先粘贴账户 Key'
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
        uni.showToast({ title: '已连接账户', icon: 'none' })
      } catch (e) {
        this.accountError = (e && e.message) || '连接失败，请检查 Key 后重试'
      } finally {
        this.connectingAccount = false
      }
    },
    // 用户到官网分配完额度回到向导：不必重启应用，重查一次即可
    async handleRecheckAccount() {
      if (this.recheckingAccount) return
      this.accountError = ''
      this.recheckingAccount = true
      try {
        await this.loadPlatformAi()
        if (this.platformNeedsAllocation) {
          this.accountError = '还没查到已分配的 AI 额度，稍等片刻再试'
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
      const external = {}

      if (provider === 'OPENROUTER' && trim(this.apiKeys.OPENROUTER)) {
        external.openRouter = { apiKey: trim(this.apiKeys.OPENROUTER) }
      }
      if (provider === 'GEMINI' && trim(this.apiKeys.GEMINI)) {
        external.google = { apiKey: trim(this.apiKeys.GEMINI) }
      }

      const ocr = this.form.external.aliyunOcr
      if (trim(ocr.accessKeyId) || trim(ocr.accessKeySecret)) {
        external.aliyunOcr = { accessKeyId: trim(ocr.accessKeyId), accessKeySecret: trim(ocr.accessKeySecret) }
      }
      if (trim(this.form.external.elevenLabs.apiKey)) {
        external.elevenLabs = { apiKey: trim(this.form.external.elevenLabs.apiKey) }
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
        uni.showToast({ title: '请选择 AI 提供商', icon: 'none' })
        return
      }
      if (provider === 'OPENROUTER' && !this.apiKeys.OPENROUTER.trim()) {
        uni.showToast({ title: '请填写 OpenRouter API Key', icon: 'none' })
        return
      }
      if (provider === 'GEMINI' && !this.apiKeys.GEMINI.trim()) {
        uni.showToast({ title: '请填写 Gemini API Key', icon: 'none' })
        return
      }
      // 平台通道两个前置条件缺一都会在发第一条消息时才报错，拦在这里并指出下一步
      if (provider === 'AWD_CLOUD' && !this.platformAiAvailable) {
        uni.showToast({ title: '请先在上方连接账户', icon: 'none' })
        return
      }
      if (provider === 'AWD_CLOUD' && this.platformNeedsAllocation) {
        uni.showToast({ title: '请先在官网账户页分配 AI 额度', icon: 'none' })
        return
      }

      this.submitting = true
      try {
        await submitWizard(this.buildPayload())
        uni.showToast({ title: '初始化完成', icon: 'success' })
        setTimeout(() => {
          uni.reLaunch({ url: '/pages/launch/launch' })
        }, 600)
      } catch (e) {
        const msg = e && e.message ? e.message : '初始化失败，请重试'
        // 409（已初始化）等场景：提示后回到登录页
        if (msg.indexOf('已初始化') !== -1 || msg.indexOf('Already initialized') !== -1) {
          uni.showToast({ title: '系统已初始化', icon: 'none' })
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

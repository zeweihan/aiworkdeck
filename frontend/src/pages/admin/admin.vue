<template>
  <view class="page-admin">
    <view class="admin-container">
      <!-- Sidebar -->
      <view class="admin-sidebar">
        <view class="sidebar-logo-area">
            <image src="/static/logo_full_v2.png" class="sidebar-logo" mode="heightFix" />
        </view>

        <view class="nav-card">
            <view class="nav-card-header">
                <text class="nav-card-title">系统管理</text>
            </view>
            <view class="nav-list">
                <view
                  v-for="nav in visibleNavItems"
                  :key="nav.key"
                  class="nav-item"
                  :class="{ active: activeNav === nav.key }"
                  @tap="onNavTap(nav)"
                >
                  <text class="nav-text">{{ nav.label }}</text>
                </view>
            </view>
            
            <view class="nav-footer">
                <view class="action-item" @tap="handleRerunWizard">
                  <text class="action-text">重新运行首次向导</text>
                  <text class="action-arrow">›</text>
                </view>
                <view class="action-item" @tap="goToUserProfile">
                  <text class="action-text">返回个人中心</text>
                  <text class="action-arrow">›</text>
                </view>
            </view>
        </view>
      </view>

      <!-- 右侧内容 -->
      <view class="admin-main">
        <!-- 配置管理 -->
        <scroll-view
          v-if="activeNav === 'config'"
          scroll-y
          class="config-scroll"
        >
          <!-- 外部服务 -->
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">外部服务供应商</text>
              <text class="section-subtitle">
                配置 Google（Gemini）、企查查、Tushare 的接入参数
              </text>
            </view>
            <view class="section-body">
              <!-- Google / Gemini -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">Google（Gemini）</text>
                </view>
                <view class="form-row">
                  <text class="form-label">API Key</text>
                  <input
                    v-model="form.external.google.apiKey"
                    class="form-input"
                    placeholder="请输入 Google Gemini API Key"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">模型名称</text>
                  <input
                    v-model="form.external.google.modelName"
                    class="form-input"
                    placeholder="例如：gemini-2.5-pro"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">API 地址</text>
                  <input
                    v-model="form.external.google.apiBaseUrl"
                    class="form-input"
                    placeholder="https://generativelanguage.googleapis.com/v1beta"
                  />
                </view>
              </view>

              <!-- OpenRouter -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">OpenRouter</text>
                </view>
                <view class="form-row">
                  <text class="form-label">API Key</text>
                  <input
                    v-model="form.external.openRouter.apiKey"
                    class="form-input"
                    placeholder="请输入 OpenRouter API Key"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">API 地址</text>
                  <input
                    v-model="form.external.openRouter.baseUrl"
                    class="form-input"
                    placeholder="https://openrouter.ai/api/v1"
                  />
                </view>
              </view>

              <!-- 企查查 -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">企查查</text>
                </view>
                <view class="form-row">
                  <text class="form-label">Base URL</text>
                  <input
                    v-model="form.external.qichacha.baseUrl"
                    class="form-input"
                    placeholder="https://api.qichacha.com"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">Key</text>
                  <input
                    v-model="form.external.qichacha.key"
                    class="form-input"
                    placeholder="请输入 key"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">Secret</text>
                  <input
                    v-model="form.external.qichacha.secret"
                    class="form-input"
                    placeholder="请输入 secret"
                  />
                </view>
              </view>

              <!-- Tushare -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">Tushare</text>
                </view>
                <view class="form-row">
                  <text class="form-label">Base URL</text>
                  <input
                    v-model="form.external.tushare.baseUrl"
                    class="form-input"
                    placeholder="http://api.tushare.pro"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">Token</text>
                  <input
                    v-model="form.external.tushare.token"
                    class="form-input"
                    placeholder="请输入 Token"
                  />
                </view>
              </view>

              <!-- 阿里云 OCR -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">阿里云 OCR</text>
                </view>
                <view class="form-row">
                  <text class="form-label">AccessKey ID</text>
                  <input
                    v-model="form.external.aliyunOcr.accessKeyId"
                    class="form-input"
                    placeholder="请输入 AccessKey ID"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">AccessKey Secret</text>
                  <input
                    v-model="form.external.aliyunOcr.accessKeySecret"
                    class="form-input"
                    placeholder="请输入 AccessKey Secret（将保存到系统配置）"
                    password
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">Endpoint</text>
                  <input
                    v-model="form.external.aliyunOcr.endpoint"
                    class="form-input"
                    placeholder="例如：ocr-api.cn-hangzhou.aliyuncs.com"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">RegionId</text>
                  <input
                    v-model="form.external.aliyunOcr.regionId"
                    class="form-input"
                    placeholder="例如：cn-hangzhou"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">公网 Base URL</text>
                  <input
                    v-model="form.external.aliyunOcr.publicBaseUrl"
                    class="form-input"
                    placeholder="例如：https://你的域名（用于 /api/ocr/temp 供阿里云拉图）"
                  />
                </view>
              </view>


              <!-- PKULaw -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">北大法宝 (PKULam)</text>
                </view>
                <view class="form-row">
                  <text class="form-label">Token</text>
                  <input
                    v-model="form.external.pkulaw.token"
                    class="form-input"
                    placeholder="请输入 PKULaw Access Token"
                  />
                </view>
              </view>

              <!-- 博查搜索（AI 网络搜索工具） -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">博查搜索 (Bocha AI)</text>
                </view>
                <view class="form-row">
                  <text class="form-label">API Key</text>
                  <input
                    v-model="form.external.bocha.apiKey"
                    class="form-input"
                    placeholder="AI 助手「网络搜索」所需，bochaai.com 申请"
                  />
                </view>
              </view>

              <!-- ElevenLabs -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">ElevenLabs (TTS)</text>
                </view>
                <view class="form-row">
                  <text class="form-label">API Key</text>
                  <input
                    v-model="form.external.elevenLabs.apiKey"
                    class="form-input"
                    placeholder="请输入 ElevenLabs API Key"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">API 地址</text>
                  <input
                    v-model="form.external.elevenLabs.baseUrl"
                    class="form-input"
                    placeholder="https://api.elevenlabs.io/v1"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">模型 ID</text>
                  <input
                    v-model="form.external.elevenLabs.modelId"
                    class="form-input"
                    placeholder="eleven_multilingual_v2"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">默认 Voice ID</text>
                  <input
                    v-model="form.external.elevenLabs.defaultVoiceId"
                    class="form-input"
                    placeholder="例如: JBFqnCBsd6RMkjVDRZzb"
                  />
                </view>
              </view>
            </view>
          </view>



          <!-- 保存按钮 -->
          <view class="fixed-footer">
            <button
              class="btn-save"
              type="primary"
              :loading="saving"
              @tap="handleSave"
            >
              保存配置
            </button>
          </view>
        </scroll-view>

        <!-- AI 配置 -->
        <scroll-view
          v-else-if="activeNav === 'ai'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">AI 服务配置</text>
              <text class="section-subtitle">
                配置系统提示词与当前使用的大模型供应商
              </text>
            </view>
            <view class="section-body">
              <view class="form-row">
                <text class="form-label">默认AI供应商</text>
                <view class="provider-radio-group">
                  <view
                    v-for="opt in aiProviderOptions"
                    :key="opt.value"
                    class="radio-item"
                    :class="{ checked: form.ai.activeProvider === opt.value }"
                    @tap="form.ai.activeProvider = opt.value"
                  >
                    <view class="radio-dot"></view>
                    <text class="radio-label">{{ opt.label }}</text>
                  </view>
                </view>
              </view>
              
              <!-- Tab for Prompt Config -->
              <view class="prompt-tabs">
                  <view 
                    class="prompt-tab" 
                    :class="{ active: activePromptTab === 'OLLAMA' }"
                    @tap="activePromptTab = 'OLLAMA'"
                  >本地 Ollama</view>
                  <view 
                    class="prompt-tab" 
                    :class="{ active: activePromptTab === 'GEMINI' }"
                    @tap="activePromptTab = 'GEMINI'"
                  >Google Gemini</view>
              </view>
              
              <view class="form-row vertical" v-if="activePromptTab === 'OLLAMA'">
                <text class="form-label">Ollama 系统提示词</text>
                <textarea
                  class="prompt-textarea"
                  v-model="form.ai.systemPromptOllama"
                  placeholder="针对 Ollama 模型的系统提示词 (留空则无系统指令)"
                  :maxlength="-1"
                  auto-height
                />
              </view>

              <view class="form-row vertical" v-if="activePromptTab === 'GEMINI'">
                <text class="form-label">Gemini 系统提示词</text>
                <textarea
                  class="prompt-textarea"
                  v-model="form.ai.systemPromptGemini"
                  placeholder="针对 Gemini 模型的系统提示词 (留空则无系统指令)"
                  :maxlength="-1"
                  auto-height
                />
              </view>
              
              <!-- Assistant Management Section -->
              <view class="section-divider"></view>
              <view class="section-header-inline">
                  <text class="section-title-sm">AI 助手管理</text>
                  <view class="admin-ai-add-btn" @tap="handleAddAssistant">+ 新增助手</view>
              </view>
              
              <view class="assistant-list">
                  <view v-for="(ast, index) in form.ai.assistants" :key="ast.id" class="assistant-card">
                      <view class="ast-header">
                          <text class="ast-name">{{ ast.name }} <text class="ast-id">({{ ast.id }})</text></text>
                          <view class="ast-actions">
                              <text class="action-btn" @tap="handleEditAssistant(index)">编辑</text>
                              <text class="action-btn delete" @tap="handleDeleteAssistant(index)">删除</text>
                          </view>
                      </view>
                      <text class="ast-desc">{{ ast.description || '暂无描述' }}</text>
                  </view>
              </view>

            </view>
          </view>

          <!-- 保存按钮 -->
          <view class="fixed-footer">
            <button
              class="btn-save"
              type="primary"
              :loading="saving"
              @tap="handleSave"
            >
              保存配置
            </button>
          </view>
        </scroll-view>

        <!-- 组件管理（仅桌面端：本地模型下载与服务启用） -->
        <scroll-view
          v-else-if="activeNav === 'components'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">组件管理</text>
              <text class="section-subtitle">
                本地 AI 组件按需下载，数据不出本机；下载为一次性，之后离线可用
              </text>
            </view>
            <view class="section-body">
              <view v-if="components.length === 0" class="empty">
                <text class="empty-text">加载中...</text>
              </view>
              <view
                v-for="comp in components"
                :key="comp.id"
                class="comp-row"
              >
                <view class="comp-main">
                  <text class="comp-name">{{ comp.name }}</text>
                  <text class="comp-sub">
                    {{ comp.sizeHint }}
                    <text v-if="comp.state === 'installed' && comp.serviceRunning"> · 服务运行中</text>
                    <text v-else-if="comp.state === 'installed'"> · 已就绪</text>
                    <text v-else-if="comp.state === 'downloading'"> · 下载中 {{ comp.percent != null ? comp.percent + '%' : '' }}</text>
                    <text v-else-if="comp.state === 'error'" class="comp-error"> · 出错：{{ comp.message }}</text>
                    <text v-else> · 未下载</text>
                  </text>
                  <view v-if="comp.state === 'downloading'" class="comp-progress">
                    <view
                      class="comp-progress-fill"
                      :style="{ width: (comp.percent || 0) + '%' }"
                    />
                  </view>
                </view>
                <view class="comp-actions">
                  <button
                    v-if="comp.state === 'absent' || comp.state === 'error'"
                    class="comp-btn primary"
                    @tap="handleComponentDownload(comp)"
                  >
                    下载
                  </button>
                  <button
                    v-if="comp.state === 'downloading'"
                    class="comp-btn"
                    @tap="handleComponentCancel(comp)"
                  >
                    取消
                  </button>
                  <button
                    v-if="comp.state === 'installed' && !comp.serviceRunning"
                    class="comp-btn primary"
                    @tap="handleComponentEnable(comp)"
                  >
                    启用
                  </button>
                  <button
                    v-if="comp.state === 'installed'"
                    class="comp-btn danger"
                    @tap="handleComponentRemove(comp)"
                  >
                    删除
                  </button>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>

        <!-- 用户管理 -->
        <scroll-view
          v-else
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">用户管理</text>
              <text class="section-subtitle">
                当前系统中的注册用户（暂为只读列表）
              </text>
            </view>
            <view class="section-body">
              <view v-if="usersLoading" class="loading">
                <text class="loading-text">加载中...</text>
              </view>
              <view v-else-if="users.length === 0" class="empty">
                <text class="empty-text">暂无用户</text>
              </view>
              <view v-else class="user-list">
                <view
                  v-for="u in users"
                  :key="u.id"
                  class="user-row"
                >
                  <view class="user-main">
                    <view class="avatar-mini">
                      <text class="avatar-char">
                        {{ (u.displayName || u.username || 'U').charAt(0) }}
                      </text>
                    </view>
                    <view class="user-meta">
                      <text class="user-name">
                        {{ u.displayName || u.username }}
                        <text
                          v-if="u.username === 'admin'"
                          class="admin-tag"
                        >
                          管理员
                        </text>
                      </text>
                      <text class="user-sub">
                        @{{ u.username }} · ID: {{ u.id }}
                      </text>
                    </view>
                  </view>
                  <view class="user-extra">
                    <text class="user-email">{{ u.email || '—' }}</text>
                  </view>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
    </view>
    
    <!-- Assistant Edit Modal (Custom Overlay) -->
    <view v-if="showAssistantModal" class="modal-overlay" @tap.stop>
        <view class="modal-content">
            <view class="modal-header">
                <text class="modal-title">{{ isEditing ? '编辑助手' : '新增助手' }}</text>
                <text class="modal-close" @tap="closeAssistantModal">×</text>
            </view>
            <scroll-view scroll-y class="modal-body">
                <view class="modal-body-inner">
                    <view class="modal-field">
                        <text class="form-label">ID (唯一标识)</text>
                        <input class="modal-input" v-model="editingAssistant.id" :disabled="isEditing" placeholder="例如: code-reviewer"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">助手名称</text>
                        <input class="modal-input" v-model="editingAssistant.name" placeholder="例如: 代码评审专家"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">描述</text>
                        <input class="modal-input" v-model="editingAssistant.description" placeholder="简短描述功能"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">系统提示词</text>
                        <textarea class="modal-textarea" v-model="editingAssistant.systemPrompt" placeholder="设定助手的角色和行为..." maxlength="-1" auto-height/>
                    </view>
                </view>
            </scroll-view>
            <view class="modal-footer">
                <button class="btn-cancel" @tap="closeAssistantModal">取消</button>
                <button class="btn-primary" @tap="saveAssistantModal">确定</button>
            </view>
        </view>
    </view>
  </view>
</template>

<script>
import { getAdminConfig, saveAdminConfig, getAdminUsers, resetWizard } from '@/services/api.js'
import { getCurrentUser } from '@/utils/auth.js'

export default {
  name: 'AdminPage',
  data() {
    return {
      userDisplayName: '用户',
      activeNav: 'config',
      activePromptTab: 'OLLAMA', // 'OLLAMA' | 'GEMINI'
      navItems: [
        { key: 'config', label: '系统配置' },
        { key: 'ai', label: 'AI 功能设置' },
        { key: 'components', label: '组件管理', desktopOnly: true },
        { key: 'plugins', label: '插件广场', route: '/pages/plugin-market/plugin-market' },
        { key: 'users', label: '用户管理' },
      ],
      components: [],
      form: {
        external: {
          google: { apiKey: '', modelName: '', apiBaseUrl: '' },
          openRouter: { apiKey: '', baseUrl: '' },
          qichacha: { baseUrl: '', key: '', secret: '' },
          tushare: { baseUrl: '', token: '' },
          aliyunOcr: { accessKeyId: '', accessKeySecret: '', endpoint: '', regionId: '', publicBaseUrl: '' },
          pkulaw: { token: '' },
          bocha: { apiKey: '' },
          elevenLabs: { apiKey: '', baseUrl: '', modelId: '', defaultVoiceId: '' },
        },
        ai: {
          systemPromptOllama: '',
          systemPromptGemini: '',
          activeProvider: 'OLLAMA',
          assistants: [],
        },
      },
      aiProviderOptions: [
        { value: 'OLLAMA', label: '本地 Ollama' },
        { value: 'GEMINI', label: 'Google Gemini' },
        { value: 'OPENROUTER', label: 'OpenRouter' },
      ],
      // Helpers
      defaultAssistants: [
        { id: 'default', name: '默认助手', tools: [], systemPrompt: '你是一个专业的助手。', description: 'Generic Assistant' },
        { id: 'rename', name: '重命名助手', tools: ['renameFile', 'listFiles'], systemPrompt: '你是一个由Google Deepmind开发的文件管理专家。用户会提供文件目录信息或重命名请求，你需要使用工具对文件进行批量重命名。注意：在执行重命名前，最好列出计划，但如果用户非常明确，可以直接调用工具。', description: 'Rename Assistant' },
        { id: 'info-extract', name: '信息抽取助手', tools: [], systemPrompt: '你负责从文档中提取关键信息。请以JSON格式输出提取结果。', description: 'Info Extractor' },
        { id: 'desensitization', name: '脱敏助手', tools: [], systemPrompt: '你负责识别并脱敏文档中的敏感信息。将敏感信息替换为[脱敏]。', description: 'De-identification' },
      ],
      // Modal State
      showAssistantModal: false,
      editingAssistant: {
          id: '',
          name: '',
          systemPrompt: '',
          description: '',
          tools: []
      },
      isEditing: false, // true if editing existing, false if adding new
      saving: false,
      usersLoading: false,
      users: [],
    }
  },
  computed: {
    isDesktop() {
      return typeof window !== 'undefined' && !!(window.checkbaDesktop && window.checkbaDesktop.model)
    },
    visibleNavItems() {
      return this.navItems.filter((n) => !n.desktopOnly || this.isDesktop)
    },
  },
  onLoad() {
    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username || '用户'
    }
    this.loadConfig()
    this.loadUsers()
    if (this.isDesktop) {
      this.loadComponents()
      // 订阅主进程模型下载进度；onUnload 退订
      this._modelProgressUnsub = window.checkbaDesktop.model.onProgress((evt) => {
        const comp = this.components.find((c) => c.id === evt.id)
        if (!comp) return
        if (evt.phase === 'progress') {
          comp.state = 'downloading'
          if (typeof evt.percent === 'number') comp.percent = evt.percent
        } else {
          // done / error：以主进程状态为准，整体刷新
          this.loadComponents()
        }
      })
    }
  },
  onUnload() {
    if (this._modelProgressUnsub) {
      this._modelProgressUnsub()
      this._modelProgressUnsub = null
    }
  },
  methods: {
    async loadComponents() {
      if (!this.isDesktop) return
      try {
        const res = await window.checkbaDesktop.model.status()
        this.components = (res && res.components ? res.components : []).map((c) => ({ percent: null, ...c }))
      } catch (e) {
        console.error('loadComponents failed', e)
      }
    },
    handleComponentDownload(comp) {
      uni.showModal({
        title: '下载组件',
        content: `「${comp.name}」需一次性下载${comp.sizeHint}到本机，之后离线可用。是否开始？`,
        success: async (r) => {
          if (!r.confirm) return
          try {
            await window.checkbaDesktop.model.download(comp.id)
            comp.state = 'downloading'
            comp.percent = 0
          } catch (e) {
            uni.showToast({ title: '启动下载失败', icon: 'none' })
          }
        },
      })
    },
    async handleComponentCancel(comp) {
      try {
        await window.checkbaDesktop.model.cancel(comp.id)
      } finally {
        this.loadComponents()
      }
    },
    handleComponentRemove(comp) {
      uni.showModal({
        title: '删除组件',
        content: `将删除「${comp.name}」的本地文件（${comp.sizeHint}），再次使用需重新下载。确认删除？`,
        success: async (r) => {
          if (!r.confirm) return
          try {
            await window.checkbaDesktop.model.remove(comp.id)
          } finally {
            this.loadComponents()
          }
        },
      })
    },
    async handleComponentEnable(comp) {
      // serviceName 由主进程 model-status 按组件→服务映射带回
      if (!comp.serviceName) return
      uni.showLoading({ title: '启动中...' })
      try {
        const res = await window.checkbaDesktop.services.ensure(comp.serviceName)
        if (!res || !res.ok) {
          uni.showToast({ title: '启动失败：' + ((res && res.message) || '未知错误'), icon: 'none' })
        }
      } finally {
        uni.hideLoading()
        this.loadComponents()
      }
    },
    goBack() {
      // 有历史就返回，否则回到个人中心
      try {
        uni.navigateBack()
      } catch (e) {
        uni.navigateTo({ url: '/pages/userprofile/userprofile' })
      }
    },
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    // 重跑首次向导：重置 completed 标记后跳向导页。已有配置不清空，
    // 向导提交时按新填内容覆盖对应 key。
    handleRerunWizard() {
      uni.showModal({
        title: '重新运行首次向导',
        content: '将重新打开首次运行向导（重新选择 AI 提供商并填写 Key）。现有配置不会被清空，向导提交后按新填内容覆盖。是否继续？',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await resetWizard()
            uni.reLaunch({ url: '/pages/wizard/wizard' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '重置失败', icon: 'none' })
          }
        },
      })
    },
    onNavTap(nav) {
      // 带 route 的导航项跳转独立页面（如插件广场），其余切换本页内容区
      if (nav.route) {
        uni.navigateTo({ url: nav.route })
        return
      }
      this.activeNav = nav.key
    },
    async loadConfig() {
      try {
        const data = await getAdminConfig()
        if (data && data.external) {
          this.form.external = {
            google: {
              apiKey: data.external.google?.apiKey || '',
              modelName: data.external.google?.modelName || '',
              apiBaseUrl: data.external.google?.apiBaseUrl || '',
            },
            qichacha: {
              baseUrl: data.external.qichacha?.baseUrl || '',
              key: data.external.qichacha?.key || '',
              secret: data.external.qichacha?.secret || '',
            },
            tushare: {
              baseUrl: data.external.tushare?.baseUrl || '',
              token: data.external.tushare?.token || '',
            },
            aliyunOcr: {
              accessKeyId: data.external.aliyunOcr?.accessKeyId || '',
              accessKeySecret: data.external.aliyunOcr?.accessKeySecret || '',
              endpoint: data.external.aliyunOcr?.endpoint || 'ocr-api.cn-hangzhou.aliyuncs.com',
              regionId: data.external.aliyunOcr?.regionId || 'cn-hangzhou',
              publicBaseUrl: data.external.aliyunOcr?.publicBaseUrl || '',
            },
            openRouter: {
              apiKey: data.external.openRouter?.apiKey || '',
              baseUrl: data.external.openRouter?.baseUrl || '',
            },
            pkulaw: {
              token: data.external.pkulaw?.token || '',
            },
            bocha: {
              apiKey: data.external.bocha?.apiKey || '',
            },
            elevenLabs: {
              apiKey: data.external.elevenLabs?.apiKey || '',
              baseUrl: data.external.elevenLabs?.baseUrl || '',
              modelId: data.external.elevenLabs?.modelId || '',
              defaultVoiceId: data.external.elevenLabs?.defaultVoiceId || '',
            },
          }
        }
        if (data && data.ai) {
          this.form.ai.systemPromptOllama = data.ai.systemPromptOllama || ''
          this.form.ai.systemPromptGemini = data.ai.systemPromptGemini || ''
          this.form.ai.activeProvider = data.ai.activeProvider || 'OLLAMA'
          
          if (data.ai.assistants && data.ai.assistants.length > 0) {
              this.form.ai.assistants = data.ai.assistants;
          } else {
              // Initialize with defaults if empty (first time migration)
              this.form.ai.assistants = JSON.parse(JSON.stringify(this.defaultAssistants));
          }
        }
      } catch (e) {
        console.error('加载后台配置失败', e)
        // 403（非 admin 账号）时把后端原因带给用户：请用 admin 账号登录后配置
        uni.showToast({ title: (e && e.message) || '加载配置失败', icon: 'none' })
      }
    },
    async loadUsers() {
      this.usersLoading = true
      try {
        const list = await getAdminUsers()
        this.users = Array.isArray(list) ? list : []
      } catch (e) {
        console.error('加载用户列表失败', e)
      } finally {
        this.usersLoading = false
      }
    },
    async handleSave() {
      this.saving = true
      try {
        await saveAdminConfig(this.form)
        uni.showToast({ title: '保存成功', icon: 'success' })
      } catch (e) {
        console.error('保存后台配置失败', e)
        uni.showToast({
          title: e.message || '保存失败',
          icon: 'none',
        })
      } finally {
        this.saving = false
      }
    },
    // Assistant Methods
    handleAddAssistant() {
        this.isEditing = false;
        this.editingAssistant = {
            id: '',
            name: '',
            systemPrompt: '',
            description: '',
            tools: [] // Future: select tools
        };
        this.showAssistantModal = true;
    },
    handleEditAssistant(index) {
        this.isEditing = true;
        // Deep copy to disconnect reference
        this.editingAssistant = JSON.parse(JSON.stringify(this.form.ai.assistants[index]));
        this.editingIndex = index;
        this.showAssistantModal = true;
    },
    handleDeleteAssistant(index) {
        uni.showModal({
            title: '确认删除',
            content: '确定要删除这个助手吗？',
            confirmText: '删除',
            confirmColor: '#ff4d4f',
            success: (res) => {
                if (res.confirm) {
                    this.form.ai.assistants.splice(index, 1);
                }
            }
        });
    },
    closeAssistantModal() {
        this.showAssistantModal = false;
    },
    saveAssistantModal() {
        if (!this.editingAssistant.id || !this.editingAssistant.name) {
            uni.showToast({ title: 'ID和名称不能为空', icon: 'none' });
            return;
        }
        
        // Check ID uniqueness if adding
        if (!this.isEditing) {
            const exists = this.form.ai.assistants.find(a => a.id === this.editingAssistant.id);
            if (exists) {
                uni.showToast({ title: 'ID 已存在', icon: 'none' });
                return;
            }
            this.form.ai.assistants.push(this.editingAssistant);
        } else {
            // Update existing
            // If ID changed, check unique? Usually ID shouldn't change, but let's allow it for now if needed, or check validity.
            // Simplified: direct overwrite
            this.form.ai.assistants.splice(this.editingIndex, 1, this.editingAssistant);
        }
        
        this.showAssistantModal = false;
    }
  },
}
</script>

<style lang="scss" scoped>
/* AI Workdeck Color System */
$brand-forest: #1A5336;
$brand-mint: #5BD197;
$brand-mint-light: #E6F9F0;
$brand-forest-dark: #123A26;

$brand-primary: $brand-forest;
$brand-accent: $brand-mint;
$brand-bg: #F8F9FA; // Gray-Pale
$brand-white: #FFFFFF;
$text-main: #2C3338; // Gray-Dark
$text-secondary: #6C757D; // Gray-Medium
$border-color: #E9ECEF; // Gray-Light

.prompt-tabs {
    display: flex;
    border-bottom: 1px solid $border-color;
    margin-bottom: 16px;
    gap: 24px;
}
.prompt-tab {
    padding: 8px 0;
    font-size: 13px;
    color: $text-secondary;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    transition: all 0.2s;
    font-weight: 500;
}
.prompt-tab.active {
    color: $brand-primary;
    border-bottom-color: $brand-primary;
}
.page-admin {
  min-height: 100vh;
  /* AI Workdeck Palette Background */
  background: linear-gradient(135deg, #F8F9FA 0%, #E8F3ED 100%);
  display: flex;
  flex-direction: column;
  padding: 40px 24px;
  box-sizing: border-box;
}

.admin-container {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 24px;
}

.admin-sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.sidebar-logo-area {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 24px;
    padding-left: 8px;
}

.sidebar-logo {
    height: 32px;
    width: auto;
}

.nav-card {
  background: $brand-white;
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.05);
  border: 1px solid rgba(0,0,0,0.02);
  overflow: hidden;
  padding: 24px 0 16px;
  display: flex;
  flex-direction: column;
}

.nav-card-header {
    padding: 0 24px 16px;
    border-bottom: 1px solid $border-color;
    margin-bottom: 12px;
}

.nav-card-title {
   font-size: 13px;
   font-weight: 600;
   color: $text-secondary;
   text-transform: uppercase;
   letter-spacing: 0.5px;
}

.nav-list {
    padding: 0 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.nav-item {
  padding: 12px 16px;
  border-radius: 8px;
  transition: all 0.2s ease;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav-item:hover {
    background-color: rgba(0,0,0, 0.02);
}

.nav-item.active {
  background: $brand-mint-light;
}

.nav-text {
  font-size: 14px;
  color: $text-secondary;
  font-weight: 500;
}

.nav-item.active .nav-text {
  color: $brand-primary;
  font-weight: 600;
}

.nav-footer {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid #f9f9f9;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 24px;
  cursor: pointer;
  transition: background 0.2s;
  
  &:hover {
    background-color: #F8F9FA;
  }
}

.action-text {
  font-size: 14px;
  color: $text-secondary;
}

.action-arrow {
  font-size: 18px;
  color: #ADB5BD;
  font-family: monospace;
}

.admin-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.config-scroll {
  height: calc(100vh - 140px);
}

.section-card {
  background: $brand-white;
  border-radius: 12px;
  border: 1px solid $border-color;
  margin-bottom: 24px;
  overflow: hidden;
}

.section-header {
  padding: 24px 24px 16px;
  border-bottom: 1px solid $border-color;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: $text-main;
}

.section-subtitle {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  color: $text-secondary;
}

.section-body {
  padding: 24px;
}

.provider-card {
  border: 1px solid $border-color;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
  background-color: #FAFAFA;
}

.provider-header {
  margin-bottom: 16px;
}

.provider-name {
  font-size: 14px;
  font-weight: 600;
  color: $text-main;
}

.form-row {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
  &:last-child {
      margin-bottom: 0;
  }
}

.form-row.vertical {
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%; /* Fix modal input width */
}

.form-label {
  width: 100px;
  font-size: 13px;
  color: $text-main;
  font-weight: 500;
}

.form-input {
  flex: 1;
  height: 38px;
  padding: 0 12px;
  border-radius: 6px;
  border: 1px solid $border-color;
  font-size: 13px;
  background-color: #fff;
  transition: border-color 0.2s;
  
  &:focus {
      border-color: $brand-primary;
      outline: none;
  }
}

.prompt-textarea {
  width: 100%;
  min-height: 120px;
  padding: 12px;
  border-radius: 6px;
  border: 1px solid $border-color;
  font-size: 13px;
  background-color: #fff;
  box-sizing: border-box;
  line-height: 1.5;
  
  &:focus {
      border-color: $brand-primary;
      outline: none;
  }
}

.provider-radio-group {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.radio-item {
  display: flex;
  align-items: center;
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid $border-color;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s;
}

.radio-item:hover {
    border-color: $text-secondary;
}

.radio-item.checked {
  border-color: $brand-primary;
  background: $brand-mint-light;
}

.radio-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 4px solid #fff;
  box-shadow: 0 0 0 1px $text-secondary;
  margin-right: 8px;
}

// Assistant & Modal Styles
.section-header-inline {
    display: flex;
    flex-direction: row; /* Ensure row layout */
    justify-content: flex-start;
    align-items: center;
    gap: 16px; /* Explicit gap */
    margin-bottom: 16px;
}
.section-title-sm {
    font-size: 14px;
    font-weight: 600;
    color: $text-main;
}

/* Explicit new class for the button to avoid native button styles */
.admin-ai-add-btn {
    font-size: 12px;
    background-color: #fff;
    color: $text-secondary;
    padding: 4px 12px;
    border-radius: 4px;
    border: 1px solid $border-color;
    line-height: 1.5;
    cursor: pointer;
    transition: all 0.2s;
    display: inline-flex; /* Use inline-flex */
    align-items: center;
    justify-content: center;
    
    &:hover {
        color: $brand-primary;
        border-color: $brand-primary;
        background: $brand-mint-light;
    }
}

/* Clean Modal Styles - No reuse of .form-row */
.modal-field {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    width: 100%;
    margin-bottom: 16px;
    box-sizing: border-box;
}

.modal-input {
    width: 100%;
    flex: none; /* Disable flex scaling */
    height: 38px;
    padding: 0 12px;
    border-radius: 6px;
    border: 1px solid $border-color;
    font-size: 13px;
    background-color: #fff;
    box-sizing: border-box; /* Strict box model */
    transition: border-color 0.2s;
}

.modal-input:focus {
    border-color: $brand-primary;
    outline: none;
}

.modal-textarea {
    width: 100%;
    flex: none;
    min-height: 120px;
    padding: 12px;
    border-radius: 6px;
    border: 1px solid $border-color;
    font-size: 13px;
    background-color: #fff;
    box-sizing: border-box;
    line-height: 1.5;
}

.modal-textarea:focus {
    border-color: $brand-primary;
    outline: none;
}

.section-divider {
    height: 1px;
    background: $border-color;
    margin: 24px 0;
}
.assistant-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.assistant-card {
    background: #FAFAFA;
    border: 1px solid $border-color;
    border-radius: 8px;
    padding: 16px;
    transition: all 0.2s;
    &:hover {
        border-color: $brand-primary;
    }
}
.ast-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
}
.ast-name {
    font-size: 14px;
    font-weight: 600;
    color: $text-main;
}
.ast-id {
    font-size: 12px;
    color: $text-secondary;
    font-weight: 400;
    margin-left: 6px;
}
.ast-actions {
    display: flex;
    gap: 12px;
}
.action-btn {
    font-size: 12px;
    color: $brand-primary;
    cursor: pointer;
    &:hover { opacity: 0.8; }
}
.action-btn.delete {
    color: #ff4d4f;
}
.ast-desc {
    font-size: 12px;
    color: $text-secondary;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
}

// Modal
.modal-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.5);
    z-index: 1000;
    display: flex;
    align-items: center;
    justify-content: center;
}
.modal-content {
    width: 500px;
    max-width: 90vw;
    background: #fff;
    border-radius: 12px;
    display: flex;
    flex-direction: column;
    max-height: 85vh;
    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}
.modal-header {
    padding: 16px 24px;
    border-bottom: 1px solid $border-color;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.modal-title {
    font-size: 16px;
    font-weight: 600;
    color: $text-main;
}
.modal-close {
    font-size: 24px;
    color: $text-secondary;
    cursor: pointer;
    line-height: 1;
    &:hover { color: $text-main; }
}
.modal-body {
    flex: 1;
    overflow-y: auto;
    min-height: 0; /* Important for flex child scroll */
}
.modal-body-inner {
    padding: 24px;
    box-sizing: border-box;
    width: 100%;
}
.modal-footer {
    padding: 16px 24px;
    border-top: 1px solid $border-color;
    display: flex;
    justify-content: flex-end;
    gap: 12px;
}
.btn-cancel {
    font-size: 14px;
    background: #fff;
    border: 1px solid $border-color;
    color: $text-main;
    padding: 6px 16px;
    border-radius: 6px;
    line-height: 1.5;
    &:after { border: none; }
}
.btn-primary {
    font-size: 14px;
    background: $brand-primary;
    color: #fff;
    border: none;
    padding: 6px 16px;
    border-radius: 6px;
    line-height: 1.5;
     &:after { border: none; }
}

.radio-item.checked .radio-dot {
  background: $brand-primary;
  box-shadow: 0 0 0 1px $brand-primary;
}

.radio-label {
  font-size: 13px;
  color: $text-main;
}

.fixed-footer {
  padding: 24px 0;
  display: flex;
  justify-content: flex-end;
}

.btn-save {
  min-width: 140px;
  height: 40px;
  line-height: 40px;
  background: $brand-primary;
  color: #fff;
  border-radius: 6px; // Slightly rounded
  font-size: 14px;
  font-weight: 500;
  border: none;
  cursor: pointer;
  box-shadow: 0 2px 4px rgba(26, 83, 54, 0.2);
  transition: background 0.2s;
  
  &:active {
      background: $brand-forest-dark;
  }
  
  &[loading] {
      opacity: 0.8;
  }
}

.user-list {
  display: flex;
  flex-direction: column;
}

.user-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid $border-color;
  &:last-child {
      border-bottom: none;
  }
}

.user-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar-mini {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: $border-color;
  color: $text-secondary;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-char {
  font-size: 16px;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: $text-main;
}

.user-sub {
  font-size: 12px;
  color: $text-secondary;
}

.admin-tag {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  background: $brand-mint-light;
  color: $brand-primary;
  font-weight: 500;
  vertical-align: middle;
}

.user-extra {
  font-size: 13px;
  color: $text-secondary;
}

.loading,
.empty {
  padding: 40px 0;
  text-align: center;
}

.loading-text,
.empty-text {
  font-size: 14px;
  color: $text-secondary;
}

/* 组件管理（桌面端） */
.comp-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.comp-main {
  flex: 1;
  min-width: 0;
}

.comp-name {
  display: block;
  font-size: 14px;
  font-weight: 600;
}

.comp-sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: $text-secondary;
}

.comp-error {
  color: #d03050;
}

.comp-progress {
  margin-top: 8px;
  height: 6px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.comp-progress-fill {
  height: 100%;
  border-radius: 3px;
  background: #18a058;
  transition: width 0.3s ease;
}

.comp-actions {
  display: flex;
  gap: 8px;
  margin-left: 16px;
}

.comp-btn {
  font-size: 12px;
  line-height: 1;
  padding: 8px 14px;
  border-radius: 6px;
  background: #f2f3f5;
  color: #333;
}

.comp-btn.primary {
  background: #18a058;
  color: #fff;
}

.comp-btn.danger {
  background: #fef0f0;
  color: #d03050;
}
</style>

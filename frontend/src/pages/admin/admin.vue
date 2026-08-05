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
                    :class="{ checked: form.ai.activeProvider === opt.value, unavailable: opt.unavailable }"
                    @tap="onPickProvider(opt)"
                  >
                    <view class="radio-dot"></view>
                    <text class="radio-label">{{ opt.label }}</text>
                    <text v-if="opt.hint" class="radio-hint">{{ opt.hint }}</text>
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

        <!-- 账户与用量（仅桌面端：连接 AI Workdeck 账户、余额与 AI 额度） -->
        <scroll-view
          v-else-if="activeNav === 'account'"
          scroll-y
          class="config-scroll"
        >
          <!-- 未连接：引导去官网取 Key -->
          <view v-if="!account.connected" class="section-card">
            <view class="section-header">
              <text class="section-title">账户与用量</text>
              <text class="section-subtitle">
                连接 AI Workdeck 账户后，可以使用平台 AI 通道、查看余额与用量，并同步已购插件与功能解锁
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <text class="account-intro">
                  桌面端不需要注册登录。在官网账户页生成一枚账户 Key（awdk_ 开头），粘贴到下面即可完成连接；Key 保存在本机，随时可以断开。
                </text>
                <view class="account-link-row">
                  <button class="comp-btn" @tap="openAccountSite">前往官网获取 Key</button>
                </view>
                <view class="form-row">
                  <text class="form-label">账户 Key</text>
                  <input
                    v-model="accountKeyInput"
                    class="form-input"
                    placeholder="粘贴 awdk_ 开头的账户 Key"
                  />
                </view>
                <view class="account-connect-actions">
                  <button class="btn-primary" :disabled="accountBusy" @tap="onConnectAccount">
                    {{ accountBusy ? '连接中...' : '连接账户' }}
                  </button>
                </view>
              </view>
            </view>
          </view>

          <!-- 已连接：账户信息 + AI 额度 + 本地用量明细 -->
          <template v-else>
            <view class="section-card">
              <view class="section-header">
                <text class="section-title">账户</text>
                <text class="section-subtitle">当前本机已连接的 AI Workdeck 账户</text>
              </view>
              <view class="section-body">
                <view class="provider-card">
                  <view class="provider-header account-header">
                    <view class="account-identity">
                      <text class="provider-name">{{ account.displayName || account.username || '账户' }}</text>
                      <text class="account-sub">{{ account.username }}<text v-if="accountPlanLabel"> · {{ accountPlanLabel }}</text></text>
                    </view>
                    <button class="comp-btn danger" @tap="onDisconnectAccount">断开连接</button>
                  </view>
                  <!-- 官网不可达：只降级平台数字，本地统计照常 -->
                  <text v-if="!accountPlatformReachable" class="account-note">
                    {{ (accountPlatform && accountPlatform.message) || '暂时无法连接 AI Workdeck 服务器，余额与额度稍后再试。本机连接未受影响。' }}
                  </text>
                  <template v-else>
                    <view class="account-metrics">
                      <view class="account-metric">
                        <text class="account-metric-label">钱包余额</text>
                        <text class="account-metric-value">{{ accountBalanceYuan }} 元</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">AI 额度已用</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.usageUsd) }}</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">AI 额度剩余</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.remainingUsd) }}</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">AI 额度上限</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.limitUsd) }}</text>
                      </view>
                    </view>
                    <!-- 已连账户但没分配过额度：平台 AI 通道此时不可用，给明确的下一步 -->
                    <text v-if="accountNeedsAllocation" class="account-note">
                      尚未分配 AI 额度，暂时不能使用「AI Workdeck 云端」通道。请到官网账户页从余额分配额度后再回来。
                    </text>
                    <text v-else-if="!accountQuotaAvailable" class="account-note">
                      AI 额度信息暂时取不到，稍后重试。
                    </text>
                  </template>
                  <text class="account-note">
                    余额用于充值与购买；AI 额度是从余额分配到平台 AI 通道的部分，在官网账户页分配。
                  </text>
                  <!-- 购买在官网完成，桌面端拉一次即可看到新解锁的功能 -->
                  <view class="account-refresh-row">
                    <button class="comp-btn" :disabled="entitlementBusy" @tap="onRefreshEntitlements">
                      {{ entitlementBusy ? '刷新中...' : '我已购买，刷新权益' }}
                    </button>
                  </view>
                </view>
              </view>
            </view>

            <view class="section-card">
              <view class="section-header">
                <text class="section-title">最近用量</text>
                <text class="section-subtitle">
                  本机记录的调用明细。自带 Key 的通道费用为本地估算，平台通道以实际扣费为准
                </text>
              </view>
              <view class="section-body">
                <view v-if="!accountUsageRows.length" class="empty">
                  <text class="empty-text">暂无用量记录</text>
                </view>
                <view
                  v-for="(row, idx) in accountUsageRows"
                  :key="'usage-' + idx"
                  class="usage-row"
                >
                  <view class="usage-main">
                    <text class="usage-model">{{ row.model || '未知模型' }}</text>
                    <text class="usage-time">
                      {{ formatUsageTime(row.createdAt) }}
                      <text v-if="usageSourceLabel(row)"> · {{ usageSourceLabel(row) }}</text>
                    </text>
                  </view>
                  <view class="usage-numbers">
                    <text class="usage-tokens">{{ row.totalTokens || 0 }} tokens</text>
                    <text class="usage-cost">{{ usageCostText(row) }}</text>
                  </view>
                </view>
              </view>
            </view>
          </template>

          <!-- 文件缓存区存储位置。
               「当前位置」永远显示，不按权益隐藏：权益可能在自选位置生效之后失效
               （Key 被吊销、断开账户、离线超宽限），数据仍在自选路径上照常读写。
               此时若把整块藏起来，用户就看不到自己的文件在哪，也看不到下面那句
               「该目录当前不可访问」——而那是文件突然打不开时唯一的指路牌。
               未解锁时藏的是「更改位置」这个付费动作，不是信息本身。 -->
          <view v-if="storageLocation.path" class="section-card">
            <view class="section-header">
              <text class="section-title">文件缓存区存储位置</text>
              <text class="section-subtitle">
                文件缓存区与项目文件在本机的存放目录。解锁无限版后不再有容量限制，建议放到空间充裕的磁盘
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <view class="form-row">
                  <text class="form-label">当前位置</text>
                  <text class="storage-path">{{ storageLocation.path }}</text>
                </view>
                <text v-if="!storageLocation.available" class="storage-warn">
                  该目录当前不可访问（磁盘未连接或已被移动）。文件操作会失败，请接回磁盘，或恢复默认位置。
                </text>
                <text v-else-if="!storageLocation.custom" class="account-note">
                  当前使用默认位置。
                </text>
                <UnlockHint
                  v-if="!storageCanMove"
                  text="更改存储位置属于「文件缓存区无限版」。已有文件不受影响，仍在上面这个目录里。"
                />
                <view class="account-connect-actions">
                  <button
                    v-if="storageCanMove"
                    class="comp-btn"
                    :disabled="storageBusy"
                    @tap="onChangeStorageLocation"
                  >
                    {{ storageBusy ? '迁移中...' : '更改位置' }}
                  </button>
                  <!-- 恢复默认不设权益闸：这是退回免费版的默认状态，不发放任何付费能力。
                       锁在付费墙后面会让「权益失效 + 外置盘拔掉」的用户彻底出不来。 -->
                  <button
                    v-if="storageLocation.custom"
                    class="comp-btn"
                    :disabled="storageBusy"
                    @tap="onResetStorageLocation"
                  >
                    恢复默认位置
                  </button>
                </view>
                <text v-if="storageCanMove" class="account-note">
                  迁移会把现有文件<text class="storage-emph">复制</text>到新目录并逐一校验，成功后才切换。
                  原目录会完整保留为备份，确认无误后可自行删除。迁移期间请不要编辑文档，
                  若期间有文档自动保存，本次迁移会整体放弃并保持原位置。
                </text>
              </view>
            </view>
          </view>

          <!-- 本机工作区（免登身份）。只在本机确实有一个以上账号时出现——
               绝大多数安装只有一个，摆一张永远只有一行的卡片是噪音。
               这里是选错工作区之后的补救入口：老安装的库里常有多个历史账号，
               启动时的选择页只出现一次。 -->
          <view v-if="identityCandidates.length > 1" class="section-card">
            <view class="section-header">
              <text class="section-title">本机工作区</text>
              <text class="section-subtitle">
                本机检测到多个历史账号，当前使用的是其中一个。切换后项目与文件会换成另一个账号名下的内容，数据不会移动
              </text>
            </view>
            <view class="section-body">
              <view
                v-for="item in identityCandidates"
                :key="item.userId"
                class="comp-row"
              >
                <view class="comp-main">
                  <text class="comp-name">{{ item.displayName || item.username }}</text>
                  <text class="comp-sub">
                    {{ item.username }} · {{ item.projectCount }} 个项目 · {{ item.fileCount }} 个文件
                  </text>
                </view>
                <view class="comp-actions">
                  <text v-if="item.userId === identityCurrentId" class="account-note">当前使用</text>
                  <button
                    v-else
                    class="comp-btn"
                    :disabled="identityBusy"
                    @tap="onSwitchIdentity(item)"
                  >
                    切换
                  </button>
                </view>
              </view>
            </view>
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

        <!-- 团队案件库（仅桌面端：连接案件库、管理已连的库）。项目里的协作抽屉是同一批动作的
             主入口，这里保留给「一台机器连多个库」与浏览器端的管理场景。 -->
        <scroll-view
          v-else-if="activeNav === 'cloud'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">团队案件库</text>
              <text class="section-subtitle">
                团队案件库是律所自己的一台服务器。连上之后，案卷可以放进去，所里同事各自取一份到本机办，
                各自改各自的，交稿时再合到一起
              </text>
            </view>
            <view class="section-body">
              <view
                v-for="conn in cloudConnections"
                :key="conn.id"
                class="provider-card"
              >
                <view class="provider-header cloud-conn-header">
                  <view class="cloud-conn-info">
                    <text class="provider-name">{{ conn.serverUrl }}</text>
                    <text class="cloud-conn-user">{{ conn.displayName || conn.username }}</text>
                  </view>
                  <button class="comp-btn danger" @tap="onDisconnectCloud(conn)">退出这个案件库</button>
                </view>
              </view>

              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">连接团队案件库</text>
                </view>
                <view class="form-row">
                  <text class="form-label">案件库地址</text>
                  <input
                    v-model="cloudForm.serverUrl"
                    class="form-input"
                    placeholder="例如 https://team.example.com"
                  />
                </view>
                <text v-if="cloudServerUrlIsHttp" class="cloud-http-warn">
                  未加密地址仅建议在律所内网使用
                </text>
                <view class="form-row">
                  <text class="form-label">账号</text>
                  <input
                    v-model="cloudForm.username"
                    class="form-input"
                    placeholder="你在案件库里的账号"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">密码</text>
                  <input
                    v-model="cloudForm.password"
                    class="form-input"
                    placeholder="密码"
                    password
                  />
                </view>
                <view class="cloud-connect-actions">
                  <button class="btn-primary" :disabled="cloudBusy" @tap="onConnectCloud">
                    {{ cloudBusy ? '连接中…' : '连接' }}
                  </button>
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
import {
  getAdminConfig, saveAdminConfig, resetWizard,
  cloudConnect, listCloudConnections, disconnectCloudConnection,
  getAccountStatus, connectAccount, disconnectAccount, getAccountUsage,
  getStorageLocation, moveStorageLocation, resetStorageLocation,
  getLocalIdentityCandidates, selectLocalIdentity,
} from '@/services/api.js'
import { getCurrentUser } from '@/utils/auth.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { refreshEntitlements, isEnabled, FEATURES } from '@/composables/useEntitlement.js'
import UnlockHint from '@/components/UnlockHint.vue'

// 官网账户页：生成账户 Key、充值、分配 AI 额度都在这里
const ACCOUNT_SITE_URL = 'https://www.aiworkdeck.com/zh/account'

export default {
  name: 'AdminPage',
  components: { UnlockHint },
  data() {
    return {
      userDisplayName: '用户',
      activeNav: 'config',
      activePromptTab: 'OLLAMA', // 'OLLAMA' | 'GEMINI'
      navItems: [
        { key: 'config', label: '系统配置' },
        { key: 'ai', label: 'AI 功能设置' },
        { key: 'account', label: '账户与用量', desktopOnly: true },
        { key: 'components', label: '组件管理', desktopOnly: true },
        { key: 'cloud', label: '团队案件库', desktopOnly: true },
        { key: 'plugins', label: '插件广场', route: '/pages/plugin-market/plugin-market' },
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
      // 平台 AI 通道是否可选（= 是否已连接账户），来自 /api/account/status
      platformAiAvailable: false,
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
      cloudConnections: [],
      cloudForm: { serverUrl: '', username: '', password: '' },
      cloudBusy: false,
      // 账户与用量（商业化 PR-B）
      // status 是纯本地读盘（不含余额），余额与额度都在 usage 的 platform 段
      account: { connected: false, username: '', displayName: '', keyMasked: '' },
      accountUsage: null, // { local: {...}, platform: {...} }，形状见 api.js getAccountUsage
      accountKeyInput: '',
      accountBusy: false,
      entitlementBusy: false,
      // 文件缓存区存储位置（PR-C）
      // { path, defaultPath, custom, available, movedAt, entitled }
      // path 为空 = 后端没给（非单机模式/旧后端），整块不显示
      storageLocation: { path: '', defaultPath: '', custom: false, available: true, entitled: false },
      storageBusy: false,
      // 本机工作区（免登身份）候选。长度 <= 1 时整块卡片不渲染
      identityCandidates: [],
      identityCurrentId: null,
      identityBusy: false,
    }
  },
  computed: {
    isDesktop() {
      return typeof window !== 'undefined' && !!(window.checkbaDesktop && window.checkbaDesktop.model)
    },
    stageUnlimited() {
      return isEnabled(FEATURES.STAGE_UNLIMITED)
    },
    // 能不能改到自选位置。以后端返回的 entitled 为准（它才是执行者）；
    // 老后端不返回这个字段时退回本地权益缓存判断。
    // 注意这只管「更改位置」这个付费动作——查看当前位置与恢复默认位置都不受它约束。
    storageCanMove() {
      const entitled = this.storageLocation.entitled
      return entitled === undefined || entitled === null ? this.stageUnlimited : !!entitled
    },
    visibleNavItems() {
      return this.navItems.filter((n) => !n.desktopOnly || this.isDesktop)
    },
    // 未加密地址提醒：仅按前缀判断，不做完整 URL 校验（连接失败自会有报错）。
    cloudServerUrlIsHttp() {
      return /^http:\/\//i.test((this.cloudForm.serverUrl || '').trim())
    },
    // 供应商单选项。「AI Workdeck 云端」是平台计费通道，条件不满足时展示但不可选——
    // 隐藏它会让用户根本发现不了这个选项，直接可选又会在发消息时才报错。
    // 两个前置条件都要单独判：连接账户 → 在官网从余额分配 AI 额度。
    // 缺后者时官网 /api/account/ai-key 返回 409 no_allocation，只在发消息那一刻才炸。
    aiProviderOptions() {
      const options = [
        { value: 'OLLAMA', label: '本地 Ollama' },
        { value: 'GEMINI', label: 'Google Gemini' },
        { value: 'OPENROUTER', label: 'OpenRouter' },
      ]
      if (this.isDesktop) {
        let hint = ''
        if (!this.platformAiAvailable) hint = '需先连接账户'
        else if (this.accountNeedsAllocation) hint = '需先在官网分配额度'
        options.push({
          value: 'AWD_CLOUD',
          label: 'AI Workdeck 云端',
          hint,
          unavailable: !!hint,
        })
      }
      return options
    },
    // 平台结算段：官网不可达时 available=false，其余字段不可信
    accountPlatform() {
      return (this.accountUsage && this.accountUsage.platform) || null
    },
    accountPlatformReachable() {
      return !!(this.accountPlatform && this.accountPlatform.available)
    },
    // 余额后端以整数分下发，展示统一转元
    accountBalanceYuan() {
      const cents = this.accountPlatform && this.accountPlatform.balanceCents
      return ((Number(cents) || 0) / 100).toFixed(2)
    },
    accountPlanLabel() {
      const plan = this.accountPlatform && this.accountPlatform.plan
      if (plan === 'paid') return '付费账户'
      if (plan === 'free') return '免费账户'
      return ''
    },
    // 额度三个数只在实时口径拿得到时才展示——拿不到时显示「暂不可用」，
    // 而不是把 0 当成真实剩余额度让用户以为额度用光了
    accountQuotaAvailable() {
      return !!(this.accountPlatform && this.accountPlatform.quotaAvailable)
    },
    // 已连账户但从未分配过 AI 额度：面板要给出「去官网分配」的引导
    accountNeedsAllocation() {
      return this.accountQuotaAvailable && !this.accountPlatform.hasAiQuota
    },
    accountUsageRows() {
      const rows = this.accountUsage && this.accountUsage.local && this.accountUsage.local.recent
      return Array.isArray(rows) ? rows : []
    },
  },
  onLoad(query) {
    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username || '用户'
    }
    // 深链定位面板（顶栏「已连接账户」chip → ?nav=account）；
    // 只认当前可见的本页面板，route 型导航项不在此列
    const nav = query && query.nav
    if (nav && this.visibleNavItems.some((n) => n.key === nav && !n.route)) {
      this.onNavTap({ key: nav })
    }
    this.loadConfig()
    if (this.isDesktop) {
      // AI 面板的「AI Workdeck 云端」选项是否可选，取决于是否已连接账户。
      // status 是后端纯本地读盘，不打官网，可以随页面加载
      this.loadPlatformAiAvailability()
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
      if (nav.key === 'cloud') {
        this.loadCloudConnections()
      }
      if (nav.key === 'account') {
        this.loadAccount()
        // 权益决定「更改位置」按钮出不出现；当前位置本身无论有没有权益都要显示
        this.loadStorageLocation()
        this.loadIdentityCandidates()
        refreshEntitlements()
      }
    },
    async loadIdentityCandidates() {
      try {
        const res = await getLocalIdentityCandidates()
        this.identityCandidates = (res && res.candidates) || []
        this.identityCurrentId = (res && res.currentUserId) || null
      } catch (e) {
        // 团队服务器部署没有本机工作区概念，读不到就整块不显示
        this.identityCandidates = []
        this.identityCurrentId = null
      }
    },
    async onSwitchIdentity(item) {
      const ok = await new Promise((r) => uni.showModal({
        title: '切换本机工作区',
        content: `切换到「${item.displayName || item.username}」后，项目与文件会换成该账号名下的内容。`
          + '当前账号的数据不会被删除或移动，随时可以切回来。切换后应用会重新启动。',
        confirmText: '切换',
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      this.identityBusy = true
      try {
        await selectLocalIdentity(item.userId)
        // 全站几乎每个页面都缓存着上一个身份的数据，就地刷新不干净——回启动链重走一遍
        uni.removeStorageSync('checkba_last_project_id')
        uni.reLaunch({ url: '/pages/launch/launch' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '切换失败', icon: 'none' })
      } finally {
        this.identityBusy = false
      }
    },
    async loadPlatformAiAvailability() {
      try {
        const s = await getAccountStatus()
        this.platformAiAvailable = !!(s && s.platformAiAvailable)
      } catch (e) {
        this.platformAiAvailable = false
      }
      // 「已连接但没分配额度」也会让平台通道打不通，判据在用量接口里（accountNeedsAllocation）。
      // 设置页不是热路径，多这一次请求换来单选项如实标注，好过发消息时才报错。
      if (this.platformAiAvailable) {
        await this.loadAccountUsage()
      }
    },
    // 供应商单选：不可选项给出下一步，而不是静默不响应
    onPickProvider(opt) {
      if (opt.value === this.form.ai.activeProvider) return
      if (opt.unavailable) {
        uni.showToast({ title: opt.hint || '当前不可用', icon: 'none' })
        return
      }
      this.form.ai.activeProvider = opt.value
    },
    // ---------- 账户与用量 ----------
    // 拉状态；已连接才继续拉用量（未连接时后端没有可查的账户）
    async loadAccount() {
      try {
        const s = await getAccountStatus()
        this.platformAiAvailable = !!(s && s.platformAiAvailable)
        this.account = {
          connected: !!(s && s.connected),
          username: (s && s.username) || '',
          displayName: (s && s.displayName) || '',
          keyMasked: (s && s.keyMasked) || '',
        }
      } catch (e) {
        // 旧后端没有该端点 / 请求失败：按未连接展示引导，不弹错打断
        this.account = { connected: false, username: '', displayName: '', keyMasked: '' }
        this.platformAiAvailable = false
      }
      if (this.account.connected) {
        await this.loadAccountUsage()
      } else {
        this.accountUsage = null
      }
    },
    async loadAccountUsage() {
      try {
        this.accountUsage = await getAccountUsage()
      } catch (e) {
        // 账户已连但尚未分配 AI 额度时后端会报错，此处按「无额度」展示
        this.accountUsage = null
      }
    },
    openAccountSite() {
      openExternalUrl(ACCOUNT_SITE_URL)
    },
    // 购买在官网完成。这里强制重取一次权益（refresh=true 会让后端先同步官网），
    // 让刚买完回到桌面的用户不用重启就看到解锁结果。
    async onRefreshEntitlements() {
      this.entitlementBusy = true
      try {
        await refreshEntitlements(true)
        await this.loadStorageLocation()
        uni.showToast({
          title: this.stageUnlimited ? '权益已更新' : '已刷新，未发现新的解锁',
          icon: 'none',
        })
      } catch (e) {
        uni.showToast({ title: '刷新失败，请稍后重试', icon: 'none' })
      } finally {
        this.entitlementBusy = false
      }
    },
    // ---------- 文件缓存区存储位置 ----------
    async loadStorageLocation() {
      try {
        const loc = await getStorageLocation()
        if (loc && typeof loc === 'object') this.storageLocation = loc
      } catch (e) {
        // 旧后端 / 非单机模式：拿不到 path，整块不显示，静默即可
      }
    },
    // 恢复默认位置：只换指针，不搬也不删文件。自选目录里的东西原样留在那里，
    // 所以弹窗必须把原路径念给用户听——否则会以为「数据没了」。
    async onResetStorageLocation() {
      const previous = this.storageLocation.path
      const ok = await new Promise((r) => uni.showModal({
        title: '恢复默认位置',
        content: '应用将改用默认目录：\n' + (this.storageLocation.defaultPath || '')
          + '\n\n当前目录中的文件不会被删除，仍完整保留在：\n' + previous
          + '\n\n恢复后这些文件在应用里将不再出现（数据仍在磁盘上），可稍后自行拷回或重新迁移。',
        confirmText: '恢复默认',
        success: (res) => r(res.confirm),
      }))
      if (!ok) return

      this.storageBusy = true
      try {
        await resetStorageLocation()
        await this.loadStorageLocation()
        uni.showModal({
          title: '已恢复默认位置',
          content: '原目录中的文件一个都没有删除，仍在：\n' + previous,
          showCancel: false,
        })
      } catch (e) {
        uni.showModal({
          title: '恢复未完成',
          content: (e && e.message) || '恢复失败，存储位置维持不变。',
          showCancel: false,
        })
      } finally {
        this.storageBusy = false
      }
    },
    async onChangeStorageLocation() {
      const desktop = typeof window !== 'undefined' ? window.checkbaDesktop : null
      if (!desktop || !desktop.fs || typeof desktop.fs.showOpenDialog !== 'function') {
        uni.showToast({ title: '仅桌面版支持选择目录', icon: 'none' })
        return
      }
      let picked
      try {
        const res = await desktop.fs.showOpenDialog({
          title: '选择文件缓存区存储位置',
          properties: ['openDirectory', 'createDirectory'],
        })
        if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return
        picked = res.filePaths[0]
      } catch (e) {
        uni.showToast({ title: '打开目录选择器失败', icon: 'none' })
        return
      }

      const ok = await new Promise((r) => uni.showModal({
        title: '迁移到新位置',
        content: '将把现有文件复制到：\n' + picked
          + '\n\n复制并校验通过后才会切换，原目录会完整保留为备份。迁移期间请不要编辑文档。',
        confirmText: '开始迁移',
        success: (res) => r(res.confirm),
      }))
      if (!ok) return

      this.storageBusy = true
      try {
        const res = await moveStorageLocation(picked)
        await this.loadStorageLocation()
        const data = (res && res.data) || {}
        uni.showModal({
          title: '迁移完成',
          content: '已迁移 ' + (data.movedFiles || 0) + ' 个文件到新位置。\n\n原目录仍保留在：\n'
            + (data.previousPath || '') + '\n\n确认一切正常后，可自行删除原目录。',
          showCancel: false,
        })
      } catch (e) {
        // 失败即回滚：存储位置维持原样，用户数据一个字节没动
        uni.showModal({
          title: '迁移未完成',
          content: (e && e.message) || '迁移失败。存储位置维持不变，文件未受影响。',
          showCancel: false,
        })
      } finally {
        this.storageBusy = false
      }
    },
    async onConnectAccount() {
      const key = (this.accountKeyInput || '').trim()
      if (!key) {
        uni.showToast({ title: '请先粘贴账户 Key', icon: 'none' })
        return
      }
      this.accountBusy = true
      try {
        await connectAccount(key)
        this.accountKeyInput = ''
        await this.loadAccount()
        // 已购功能解锁随账户走，连接后必须让权益缓存失效重取
        await refreshEntitlements(true)
        this.notifyMarketAccountChanged()
        uni.showToast({ title: '已连接账户', icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '连接失败', icon: 'none' })
      } finally {
        this.accountBusy = false
      }
    },
    async onDisconnectAccount() {
      const ok = await new Promise((r) => uni.showModal({
        title: '断开账户连接',
        content: '断开后本机将清除账户 Key：不再能使用平台 AI 通道，已购插件与功能解锁也会同步失效。本机数据不受影响，随时可以重新连接。',
        confirmText: '断开',
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      try {
        const res = await disconnectAccount()
        await this.loadAccount()
        await refreshEntitlements(true)
        this.notifyMarketAccountChanged()
        // 后端会把 activeProvider 从平台通道摘下来（否则每条消息都报未连接账户，
        // 而设置页仍显示平台通道正常选中）。这里同步表单并如实告知切到了哪一个。
        const fallback = res && res.aiProviderFallback
        if (fallback) {
          this.form.ai.activeProvider = fallback
          const label = (this.aiProviderOptions.find((o) => o.value === fallback) || {}).label || fallback
          uni.showModal({
            title: '已断开连接',
            content: 'AI 供应商原本是「AI Workdeck 云端」，断开后已切换为「' + label + '」。可在「AI 服务配置」重新选择。',
            showCancel: false,
          })
        } else {
          uni.showToast({ title: '已断开连接', icon: 'none' })
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '操作失败', icon: 'none' })
      }
    },
    /**
     * 账户连接状态变了 → 广场的付费项按钮形态跟着变（「需连接账户」↔「购买」/「安装」）。
     *
     * 设置页是 navigateTo 打开的，上一页并不销毁：不广播的话用户从「需连接账户」点进来、
     * 连完账户返回，广场还是旧数据，再点又回到这里，转不出去。
     * 两个事件名分属两个订阅方（左栏 MarketSidebarPanel / 中栏 MarketDetailPane），都要发。
     */
    notifyMarketAccountChanged() {
      uni.$emit('awd:market-changed')
      uni.$emit('awd:market-changed-from-sidebar')
    },
    // 金额一律两位小数，缺值显示 $0.00 而不是 NaN
    formatUsd(v) {
      const n = Number(v)
      return '$' + (Number.isFinite(n) ? n : 0).toFixed(2)
    },
    // 额度数字：实时口径拿不到时显示「—」。
    // 这里不能沿用 formatUsd 的 0 兜底——$0.00 会被读成「额度已用光」
    quotaText(v) {
      if (!this.accountQuotaAvailable) return '—'
      const n = Number(v)
      return Number.isFinite(n) ? this.formatUsd(n) : '—'
    },
    // 费用口径标注（Spec §3：本地估算与平台结算两套数字必须分得开）
    usageSourceLabel(row) {
      const src = row && row.costSource
      if (src === 'platform') return '平台结算'
      if (src === 'estimate') return '本地估算'
      return ''
    },
    // 平台通道在对账完成前 cost 为 null——显示「待结算」，
    // 绝不能拿 $0.00 顶替，那会让用户以为这次调用不花钱
    usageCostText(row) {
      const cost = row && row.cost
      if (cost === null || cost === undefined || cost === '') {
        return row && row.costSource === 'platform' ? '待结算' : '—'
      }
      // 估算值加约等号，避免和真实账单混淆
      const prefix = row && row.costSource === 'estimate' ? '≈' : ''
      return prefix + this.formatUsd(cost)
    },
    // 后端 LocalDateTime 序列化成 "2026-08-05T12:34:56"，只取到分钟。
    // 刻意不过 Date 解析：无时区后缀的串在不同实现下会被当本地/UTC，反而错位。
    formatUsageTime(ts) {
      if (!ts) return ''
      return String(ts).replace('T', ' ').slice(0, 16)
    },
    async loadCloudConnections() {
      try {
        const res = await listCloudConnections()
        this.cloudConnections = (res.data && res.data.connections) || []
      } catch (e) {
        this.cloudConnections = []
      }
    },
    async onConnectCloud() {
      if (!this.cloudForm.serverUrl || !this.cloudForm.username) return
      this.cloudBusy = true
      try {
        await cloudConnect(
          this.cloudForm.serverUrl.trim(), this.cloudForm.username.trim(),
          this.cloudForm.password, '桌面端'
        )
        this.cloudForm = { serverUrl: '', username: '', password: '' }
        await this.loadCloudConnections()
        uni.showToast({ title: '已连上团队案件库', icon: 'none' })
      } catch (e) {
        uni.showToast({ title: e.message || '连接失败', icon: 'none' })
      } finally {
        this.cloudBusy = false
      }
    },
    async onDisconnectCloud(conn) {
      const ok = await new Promise((r) => uni.showModal({
        title: '退出这个案件库',
        content: '退出后本机不再和这个案件库同步；已经放进去的案卷要重新连上才能继续交稿。案件库里的内容不受影响。',
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      try {
        await disconnectCloudConnection(conn.id)
        await this.loadCloudConnections()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
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

// 前置条件未满足（未连接账户 / 未分配额度）的「AI Workdeck 云端」：
// 可见但压低，点击给出下一步而不是静默失败
.radio-item.unavailable {
  opacity: 0.55;
}

.radio-hint {
  margin-left: 8px;
  font-size: 12px;
  color: $text-secondary;
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

/* 团队案件库 */
.cloud-conn-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0;
}

.cloud-conn-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cloud-conn-user {
  font-size: 12px;
  color: $text-secondary;
}

.cloud-http-warn {
  display: block;
  margin: -8px 0 16px;
  font-size: 12px;
  color: #b45309;
}

.cloud-connect-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 账户与用量 */
.account-intro {
  display: block;
  margin-bottom: 14px;
  font-size: 13px;
  line-height: 21px;
  color: $text-secondary;
}

.account-link-row {
  display: flex;
  margin-bottom: 16px;
}

.account-connect-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.account-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.account-identity {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.account-sub {
  font-size: 12px;
  color: $text-secondary;
}

.account-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
}

.account-metric {
  flex: 1 1 130px;
  min-width: 130px;
  padding: 12px 14px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 6px;
  background: $brand-bg;
}

.account-metric-label {
  display: block;
  font-size: 12px;
  color: $text-secondary;
}

.account-metric-value {
  display: block;
  margin-top: 6px;
  font-size: 18px;
  font-weight: 600;
  color: $brand-primary;
}

.account-note {
  display: block;
  margin-top: 14px;
  font-size: 12px;
  line-height: 18px;
  color: $text-secondary;
}

.account-refresh-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

/* 存储位置：路径要能整段看清，故等宽字体 + 允许换行 */
.storage-path {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 18px;
  color: $text-main;
  word-break: break-all;
}

.storage-warn {
  display: block;
  margin-top: 10px;
  padding: 6px 10px;
  border-radius: 4px;
  background: #fdf7ec;
  border: 1px solid #ecdfc3;
  font-size: 12px;
  line-height: 18px;
  color: #8a6d2f;
}

.storage-emph {
  font-weight: 600;
  color: $text-main;
}

.usage-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.usage-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.usage-model {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-time {
  font-size: 12px;
  color: $text-secondary;
}

.usage-numbers {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-left: 16px;
  flex-shrink: 0;
}

.usage-tokens {
  font-size: 12px;
  color: $text-secondary;
}

.usage-cost {
  font-size: 13px;
  font-weight: 600;
}
</style>

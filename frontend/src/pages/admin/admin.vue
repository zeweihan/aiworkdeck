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
                <text class="nav-card-title">{{ $t('admin.navCardTitle') }}</text>
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
                  <text class="action-text">{{ $t('admin.rerunWizard') }}</text>
                  <text class="action-arrow">›</text>
                </view>
                <view class="action-item" @tap="goToUserProfile">
                  <text class="action-text">{{ $t('admin.backToProfile') }}</text>
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
          <!-- 应用语言：立即生效、独立保存链（setAppLanguage 直写，不走 handleSave
               的 /api/admin/config——那条要求 admin 权限，语言是人人可改的设置）。 -->
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ appLanguage === 'en-US' ? 'Language' : '语言 / Language' }}</text>
              <text class="section-subtitle">
                {{ appLanguage === 'en-US'
                  ? 'Applies to the interface, document editor, and AI replies. Newly opened editors use the new language; restart the app for full effect.'
                  : '作用于界面、文档编辑器与 AI 回复。新打开的编辑器使用新语言，重启应用后完全生效。' }}
              </text>
            </view>
            <view class="section-body">
              <view class="form-row">
                <text class="form-label">{{ appLanguage === 'en-US' ? 'Language' : '界面语言' }}</text>
                <view class="provider-radio-group">
                  <view
                    v-for="opt in appLanguageOptions"
                    :key="opt.value"
                    class="radio-item"
                    :class="{ checked: appLanguage === opt.value }"
                    @tap="onAppLanguagePick(opt.value)"
                  >
                    <view class="radio-dot"></view>
                    <text class="radio-label">{{ opt.label }}</text>
                  </view>
                </view>
              </view>
            </view>
          </view>

          <!-- 外部服务。
               这里只剩 OpenRouter：其余七家的凭证已随「平台服务」分区搬走，收进各自的
               「使用自己的 Key（高级）」折叠区（默认由我们代采，用户不必再填 23 个字段）。
               OpenRouter 留在这里是因为它属于 AI 那条通路——凭证下发 + 本机直连，不经网关。 -->
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.externalTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.externalSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <!-- OpenRouter。Google（Gemini）那三个字段随 GEMINI 供应商下线一起删除：
                   Gemini 系列模型仍可用，走 OpenRouter 的 google/* 即可。 -->
              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">OpenRouter</text>
                </view>
                <view class="form-row">
                  <text class="form-label">API Key</text>
                  <input
                    v-model="form.external.openRouter.apiKey"
                    class="form-input"
                    :placeholder="$t('admin.openRouterKeyPlaceholder')"
                    password
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.apiBaseUrlLabel') }}</text>
                  <input
                    v-model="form.external.openRouter.baseUrl"
                    class="form-input"
                    placeholder="https://openrouter.ai/api/v1"
                  />
                </view>
              </view>
              <text class="field-note">{{ $t('admin.externalMovedNote') }}</text>
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
              {{ $t('admin.saveConfigButton') }}
            </button>
          </view>
        </scroll-view>

        <!-- 平台服务（P5：配置面收敛）。
             七项外部服务各自走哪一档，以及自备 Key 时的全部凭证字段。
             当前档位一律读 GET /api/platform-services 的 provider（后端解析后的生效值），
             绝不自己按凭证是否为空去猜——存量机器上这两件事经常对不上。 -->
        <scroll-view
          v-else-if="activeNav === 'platform'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.navPlatform') }}</text>
              <text class="section-subtitle">{{ $t('admin.platformSubtitle') }}</text>
            </view>
            <view class="section-body">
              <!-- 三种全局状态，互斥。none 之外的两种都要给出下一步。 -->
              <!--
                未结算的预扣。设计 §4.6 要求这笔钱「必须可解释」：一场两小时录音的预扣
                会把余额压低、进而让余额闸拦住 AI 对话——用户会同时发现转写和对话都停了，
                没有这一行他无从知道是转写占住的。
              -->
              <view v-if="pendingHoldNotice" class="platform-banner">
                <text class="platform-banner-body">{{ pendingHoldNotice }}</text>
              </view>
              <!-- 余额低于用户设定的阈值。只在**确知**余额时出现（读不到给 null，
                   不拿「不知道」编一个数出来），阈值为 0 表示用户没启用这条提醒。 -->
              <view v-if="lowBalanceNotice" class="platform-banner platform-banner-warn">
                <text class="platform-banner-body">{{ lowBalanceNotice }}</text>
              </view>
              <view v-if="platformServicesError" class="platform-banner platform-banner-warn">
                <text class="platform-banner-body">{{ platformServicesError }}</text>
              </view>
              <view v-else-if="platformLoaded && !platformState.platformAvailable" class="platform-banner">
                <text class="platform-banner-title">{{ $t('platform.serverModeTitle') }}</text>
                <text class="platform-banner-body">{{ $t('platform.serverModeBody') }}</text>
              </view>
              <view v-else-if="platformLoaded && !platformState.accountConnected" class="platform-banner">
                <text class="platform-banner-title">{{ $t('platform.notConnectedTitle') }}</text>
                <text class="platform-banner-body">{{ $t('platform.notConnectedBody') }}</text>
                <text class="platform-link" @tap="onNavTap({ key: 'account' })">{{ $t('platform.goConnect') }}</text>
              </view>

              <view v-for="svc in platformServiceRows" :key="svc.key" class="provider-card">
                <view class="provider-header platform-row-head">
                  <view class="platform-row-info">
                    <text class="provider-name">{{ svc.name }}</text>
                    <text class="platform-row-desc">{{ svc.desc }}</text>
                  </view>
                  <view class="platform-row-side">
                    <text class="platform-tier" :class="svc.tierClass">{{ svc.tierLabel }}</text>
                    <!-- 本月消耗。取不到时是破折号而不是 0——0 在陈述一个我们并不知道的事实。 -->
                    <text class="platform-usage">{{ $t('platform.usageMonthLabel') }} {{ svc.usageText }}</text>
                  </view>
                </view>

                <view class="form-row">
                  <text class="form-label">{{ $t('platform.tierLabel') }}</text>
                  <AwdSelect
                    class="mode-picker"
                    :range="svc.optionLabels"
                    :value="svc.optionIndex"
                    :disabled="platformBusy"
                    @change="onPlatformTierPick(svc, $event)"
                  />
                </view>
                <text v-for="(note, i) in svc.notes" :key="'n' + i" class="field-note">{{ note }}</text>

                <!-- 「改用自己的 Key」的逃生门。网关的每一类失败（未开放 / 上游挂 / 我们挂）
                     都要能指到这里，所以它是一个可以被深链就地展开的固定入口
                     （?nav=platform&service=ocr），不是藏在别处的高级设置。 -->
                <view class="platform-fold-head" @tap="togglePlatformByok(svc.key)">
                  <text class="platform-fold-title">{{ $t('platform.useOwnKey') }}</text>
                  <text class="platform-fold-arrow">
                    {{ platformByokOpen[svc.key] ? $t('platform.collapse') : $t('platform.expand') }}
                  </text>
                </view>

                <view v-if="platformByokOpen[svc.key]" class="platform-byok-body">
                  <text class="field-note">
                    {{ svc.hasByokCredentials ? $t('platform.byokPresentNote') : $t('platform.byokMissingNote') }}
                  </text>

                  <!-- 会议录音转写（通义听悟 + OSS 中转） -->
                  <template v-if="svc.key === 'asr'">
                    <view class="form-row">
                      <text class="form-label">AccessKey ID</text>
                      <input v-model="form.external.tingwu.accessKeyId" class="form-input" :placeholder="$t('admin.tingwuKeyIdPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">AccessKey Secret</text>
                      <input v-model="form.external.tingwu.accessKeySecret" class="form-input" password :placeholder="$t('admin.tingwuKeySecretPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">{{ $t('admin.tingwuAppKeyLabel') }}</text>
                      <input v-model="form.external.tingwu.appKey" class="form-input" :placeholder="$t('admin.tingwuAppKeyPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">OSS Bucket</text>
                      <input v-model="form.external.tingwu.ossBucket" class="form-input" :placeholder="$t('admin.tingwuBucketPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">OSS Endpoint</text>
                      <input v-model="form.external.tingwu.ossEndpoint" class="form-input" :placeholder="$t('admin.tingwuEndpointPlaceholder')" />
                    </view>
                  </template>

                  <!-- 图片文字识别（阿里云 OCR） -->
                  <template v-else-if="svc.key === 'ocr'">
                    <view class="form-row">
                      <text class="form-label">AccessKey ID</text>
                      <input v-model="form.external.aliyunOcr.accessKeyId" class="form-input" :placeholder="$t('admin.ocrKeyIdPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">AccessKey Secret</text>
                      <input v-model="form.external.aliyunOcr.accessKeySecret" class="form-input" password :placeholder="$t('admin.ocrKeySecretPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">Endpoint</text>
                      <input v-model="form.external.aliyunOcr.endpoint" class="form-input" :placeholder="$t('admin.ocrEndpointPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">RegionId</text>
                      <input v-model="form.external.aliyunOcr.regionId" class="form-input" :placeholder="$t('admin.ocrRegionPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">{{ $t('admin.publicBaseUrlLabel') }}</text>
                      <input v-model="form.external.aliyunOcr.publicBaseUrl" class="form-input" :placeholder="$t('admin.ocrPublicBaseUrlPlaceholder')" />
                    </view>
                  </template>

                  <!-- 联网搜索（博查） -->
                  <template v-else-if="svc.key === 'search'">
                    <view class="form-row">
                      <text class="form-label">API Key</text>
                      <input v-model="form.external.bocha.apiKey" class="form-input" :placeholder="$t('admin.bochaKeyPlaceholder')" />
                    </view>
                  </template>

                  <!-- 企业工商信息（企查查） -->
                  <template v-else-if="svc.key === 'qichacha'">
                    <view class="form-row">
                      <text class="form-label">Base URL</text>
                      <input v-model="form.external.qichacha.baseUrl" class="form-input" placeholder="https://api.qichacha.com" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">Key</text>
                      <input v-model="form.external.qichacha.key" class="form-input" :placeholder="$t('admin.enterKeyPlaceholder')" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">Secret</text>
                      <input v-model="form.external.qichacha.secret" class="form-input" :placeholder="$t('admin.enterSecretPlaceholder')" />
                    </view>
                  </template>

                  <!-- 证券与财务数据（Tushare） -->
                  <template v-else-if="svc.key === 'tushare'">
                    <view class="form-row">
                      <text class="form-label">Base URL</text>
                      <input v-model="form.external.tushare.baseUrl" class="form-input" placeholder="http://api.tushare.pro" />
                    </view>
                    <view class="form-row">
                      <text class="form-label">Token</text>
                      <input v-model="form.external.tushare.token" class="form-input" :placeholder="$t('admin.enterTokenPlaceholder')" />
                    </view>
                  </template>

                  <!-- 法律法规与案例（北大法宝） -->
                  <template v-else-if="svc.key === 'pkulaw'">
                    <view class="form-row">
                      <text class="form-label">Token</text>
                      <input v-model="form.external.pkulaw.token" class="form-input" :placeholder="$t('admin.pkulawTokenPlaceholder')" />
                    </view>
                  </template>

                  <text class="field-note">{{ $t('platform.saveHint') }}</text>
                </view>
              </view>

              <!-- 花费闸门。设计 §4.9 的用户闸：超过上限时问一句「是否继续」，
                   **是可恢复的确认而不是失败**。刻意不做「每次调用前弹确认」——
                   与「零配置、少打扰」的产品目标冲突，设计里明确否了。
                   两个阈值与档位同属机器级设置，所以写同一个控制器、就地生效。 -->
              <view class="provider-card">
                <view class="provider-header platform-row-head">
                  <view class="platform-row-info">
                    <text class="provider-name">{{ $t('platform.budgetTitle') }}</text>
                    <text class="platform-row-desc">{{ $t('platform.budgetSubtitle') }}</text>
                  </view>
                  <text v-if="usageTotalText" class="platform-usage">{{ usageTotalText }}</text>
                </view>

                <view class="form-row">
                  <text class="form-label">{{ $t('platform.budgetTaskLimitLabel') }}</text>
                  <input
                    v-model="budgetForm.taskLimit"
                    class="form-input"
                    type="digit"
                    :placeholder="$t('platform.budgetUnit')"
                  />
                </view>
                <text class="field-note">{{ $t('platform.budgetTaskLimitNote') }}</text>

                <view class="form-row">
                  <text class="form-label">{{ $t('platform.budgetLowBalanceLabel') }}</text>
                  <input
                    v-model="budgetForm.lowBalance"
                    class="form-input"
                    type="digit"
                    :placeholder="$t('platform.budgetUnit')"
                  />
                </view>
                <text class="field-note">{{ $t('platform.budgetLowBalanceNote') }}</text>

                <text v-if="!platformRemote.pricingAvailable" class="field-note">
                  {{ $t('platform.usageUnavailable') }}
                </text>

                <view class="platform-budget-actions">
                  <button class="comp-btn primary" :disabled="budgetBusy" @tap="onSaveBudget">
                    {{ $t('platform.budgetSave') }}
                  </button>
                </view>
              </view>

              <!-- AI 单列说明：它不是这七项里的一项，通路完全不同，
                   摆在这里是为了回答「那 AI 的钱走哪条路」这个必然会被问到的问题。 -->
              <view class="provider-card">
                <view class="provider-header platform-row-head">
                  <view class="platform-row-info">
                    <text class="provider-name">{{ $t('platform.aiRowName') }}</text>
                    <text class="platform-row-desc">{{ $t('platform.aiRowDesc') }}</text>
                  </view>
                </view>
                <text class="platform-link" @tap="onNavTap({ key: 'ai' })">{{ $t('platform.goAiSettings') }}</text>
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
              {{ $t('admin.saveConfigButton') }}
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
              <text class="section-title">{{ $t('admin.aiSectionTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.aiSectionSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="form-row">
                <text class="form-label">{{ $t('admin.defaultProviderLabel') }}</text>
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

              <!-- 跨境传输的单独同意（个保法第三十九条）。只在选中云端通道时出现：
                   那正是内容开始出境的决定点。绝不预勾选——预勾选的同意是无效的。 -->
              <view v-if="form.ai.activeProvider === 'AWD_CLOUD'" class="form-row consent-row">
                <view class="consent-box">
                  <text class="consent-title">{{ $t('admin.consentTitle') }}</text>
                  <text class="consent-body">{{ $t('admin.consentBodyPrefix') }}<text class="consent-em">{{ $t('admin.consentEntity') }}</text>{{ $t('admin.consentBodySuffix') }}</text>
                  <view class="consent-check" @tap="toggleCrossBorderConsent">
                    <view class="consent-box-mark" :class="{ checked: crossBorderConsented }"></view>
                    <text class="consent-check-label">{{ $t('admin.consentCheckLabel') }}</text>
                  </view>
                  <text v-if="crossBorderConsentAt" class="consent-meta">{{ $t('admin.consentAt', { time: formatConsentAt }) }}</text>
                  <text class="consent-link" @tap="openPrivacyCrossBorder">{{ $t('admin.consentPrivacyLink') }}</text>
                </view>
              </view>

              <!-- 模型选择。清单唯一来源是后端模型目录（GET /api/ai/models）——
                   历史上前端硬编码过两份互不同步的清单，结果是「后端加模型用户看不到、
                   前端加模型被工厂静默回落默认模型」。
                   系统提示词的 OLLAMA / GEMINI 两个 tab 已随 v1 对话通道移除：
                   唯一读者是已删的 AiChatService，对四条通道本来就全部失效。 -->
              <view class="section-divider"></view>
              <view class="section-header-inline">
                  <text class="section-title-sm">{{ $t('admin.modelSelectionTitle') }}</text>
              </view>
              <text class="field-note">
                {{ $t('admin.modelSelectionNote') }}
              </text>
              <text v-if="modelCatalogError" class="field-note field-note-warn">{{ modelCatalogError }}</text>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.defaultModelLabel') }}</text>
                <AwdSelect
                  class="mode-picker"
                  :range="modelLabels('defaultModel')"
                  :value="modelIndex('defaultModel')"
                  @change="onModelPick('defaultModel', $event)"
                />
              </view>
              <text v-if="catalogDefaultModel" class="field-note">
                {{ $t('admin.effectiveDefaultModel', { model: catalogDefaultModel }) }}
              </text>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.auxModelLabel') }}</text>
                <AwdSelect
                  class="mode-picker"
                  :range="modelLabels('auxModel')"
                  :value="modelIndex('auxModel')"
                  @change="onModelPick('auxModel', $event)"
                />
              </view>
              <text class="field-note">
                {{ $t('admin.auxModelNote') }}
              </text>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.subagentModelLabel') }}</text>
                <AwdSelect
                  class="mode-picker"
                  :range="modelLabels('subagentModel')"
                  :value="modelIndex('subagentModel')"
                  @change="onModelPick('subagentModel', $event)"
                />
              </view>
              <text class="field-note">{{ $t('admin.subagentModelNote') }}</text>

              <!-- 网络区域。手动覆盖是一等设置不是隐藏兜底：本地判定（系统国家 + 时区）
                   对出差、挂代理、公司专线出境的用户必然判错，手动指定是唯一出路。 -->
              <view class="section-divider"></view>
              <view class="section-header-inline">
                  <text class="section-title-sm">{{ $t('admin.networkRegionTitle') }}</text>
              </view>
              <text class="field-note">
                {{ $t('admin.networkRegionNote') }}
              </text>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.regionModeLabel') }}</text>
                <view class="provider-radio-group">
                  <view
                    v-for="opt in networkRegionOptions"
                    :key="opt.value"
                    class="radio-item"
                    :class="{ checked: form.ai.networkRegion === opt.value }"
                    @tap="form.ai.networkRegion = opt.value"
                  >
                    <view class="radio-dot"></view>
                    <text class="radio-label">{{ opt.label }}</text>
                  </view>
                </view>
              </view>
              <text class="field-note">{{ networkRegionSummary }}</text>

              <!-- 本地 Ollama。改造前全产品无处可改：yml 里是硬编码字面量，
                   只能靠 AI_MODEL_OLLAMA_MODEL_NAME 环境变量覆盖，终端用户等于改不了。 -->
              <view class="section-divider"></view>
              <view class="section-header-inline">
                  <text class="section-title-sm">{{ $t('admin.ollamaSectionTitle') }}</text>
              </view>
              <text class="field-note">
                {{ $t('admin.ollamaNote') }}
              </text>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.serverAddressLabel') }}</text>
                <input
                  v-model="form.ai.ollamaBaseUrl"
                  class="form-input"
                  placeholder="http://localhost:11434"
                />
              </view>
              <view class="form-row">
                <text class="form-label">{{ $t('admin.modelNameLabel') }}</text>
                <input
                  v-model="form.ai.ollamaModelName"
                  class="form-input"
                  :placeholder="$t('admin.ollamaModelPlaceholder')"
                />
              </view>

              <!-- Assistant Management Section -->
              <view class="section-divider"></view>
              <view class="section-header-inline">
                  <text class="section-title-sm">{{ $t('admin.assistantMgmtTitle') }}</text>
                  <view class="admin-ai-add-btn" @tap="handleAddAssistant">{{ $t('admin.addAssistantButton') }}</view>
              </view>

              <view class="assistant-list">
                  <view v-for="(ast, index) in form.ai.assistants" :key="ast.id" class="assistant-card">
                      <view class="ast-header">
                          <text class="ast-name">{{ ast.name }} <text class="ast-id">({{ ast.id }})</text></text>
                          <view class="ast-actions">
                              <text class="action-btn" @tap="handleEditAssistant(index)">{{ $t('admin.edit') }}</text>
                              <text class="action-btn delete" @tap="handleDeleteAssistant(index)">{{ $t('common.delete') }}</text>
                          </view>
                      </view>
                      <text class="ast-desc">{{ ast.description || $t('admin.noDescription') }}</text>
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
              {{ $t('admin.saveConfigButton') }}
            </button>
          </view>
        </scroll-view>

        <!-- 账户与用量（仅桌面端：连接 AI WorkDeck 账户、余额与 AI 额度） -->
        <scroll-view
          v-else-if="activeNav === 'account'"
          scroll-y
          class="config-scroll"
        >
          <!-- 当前站点。摆在账户连接之前：账户 Key 是站点签发的，
               连接之前先知道自己在哪个站，才不会拿着另一个站的 Key 连不上。 -->
          <view v-if="site.displayName" class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.siteSectionTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.siteSectionSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.siteLabel') }}</text>
                  <text class="site-name">{{ site.displayName }}</text>
                </view>
                <text v-if="site.pinned" class="account-note">
                  {{ $t('admin.sitePinnedNote') }}
                </text>
                <template v-else-if="siteSwitchTargets.length">
                  <view class="account-connect-actions">
                    <button
                      v-for="target in siteSwitchTargets"
                      :key="target.id"
                      class="comp-btn"
                      :disabled="siteBusy"
                      @tap="onSwitchSite(target)"
                    >
                      {{ siteBusy ? $t('admin.siteSwitching') : $t('admin.switchToSite', { name: target.displayName }) }}
                    </button>
                  </view>
                  <text class="account-note">
                    {{ $t('admin.siteSwitchNote') }}
                  </text>
                </template>
              </view>
            </view>
          </view>

          <!-- 未连接：引导去官网取 Key -->
          <view v-if="!account.connected" class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.navAccount') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.accountSectionSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <text class="account-intro">
                  {{ $t('admin.accountIntro') }}
                </text>
                <view class="account-link-row">
                  <button class="comp-btn" @tap="openAccountSite">{{ $t('admin.getKeyButton') }}</button>
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.accountKeyLabel') }}</text>
                  <input
                    v-model="accountKeyInput"
                    class="form-input"
                    :placeholder="$t('admin.accountKeyPlaceholder')"
                  />
                </view>
                <view class="account-connect-actions">
                  <button class="btn-primary" :disabled="accountBusy" @tap="onConnectAccount">
                    {{ accountBusy ? $t('admin.accountConnecting') : $t('admin.connectAccountButton') }}
                  </button>
                </view>
              </view>
            </view>
          </view>

          <!-- 已连接：账户信息 + AI 额度 + 本地用量明细 -->
          <template v-else>
            <view class="section-card">
              <view class="section-header">
                <text class="section-title">{{ $t('admin.accountTitle') }}</text>
                <text class="section-subtitle">{{ $t('admin.accountConnectedSubtitle') }}</text>
              </view>
              <view class="section-body">
                <view class="provider-card">
                  <view class="provider-header account-header">
                    <view class="account-identity">
                      <text class="provider-name">{{ account.displayName || account.username || $t('admin.accountTitle') }}</text>
                      <text class="account-sub">{{ account.username }}<text v-if="accountPlanLabel"> · {{ accountPlanLabel }}</text></text>
                    </view>
                    <button class="comp-btn danger" @tap="onDisconnectAccount">{{ $t('admin.disconnectButton') }}</button>
                  </view>
                  <!-- 官网不可达：只降级平台数字，本地统计照常 -->
                  <text v-if="!accountPlatformReachable" class="account-note">
                    {{ (accountPlatform && accountPlatform.message) || $t('admin.platformUnreachable') }}
                  </text>
                  <template v-else>
                    <view class="account-metrics">
                      <view class="account-metric">
                        <text class="account-metric-label">{{ $t('admin.walletBalanceLabel') }}</text>
                        <text class="account-metric-value">{{ $t('admin.balanceYuan', { amount: accountBalanceYuan }) }}</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">{{ $t('admin.quotaUsedLabel') }}</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.usageUsd) }}</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">{{ $t('admin.quotaRemainingLabel') }}</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.remainingUsd) }}</text>
                      </view>
                      <view class="account-metric">
                        <text class="account-metric-label">{{ $t('admin.quotaLimitLabel') }}</text>
                        <text class="account-metric-value">{{ quotaText(accountPlatform && accountPlatform.limitUsd) }}</text>
                      </view>
                    </view>
                    <!-- 已连账户但没分配过额度：平台 AI 通道此时不可用，给明确的下一步 -->
                    <text v-if="accountNeedsAllocation" class="account-note">
                      {{ $t('admin.needsAllocationNote') }}
                    </text>
                    <text v-else-if="!accountQuotaAvailable" class="account-note">
                      {{ $t('admin.quotaUnavailableNote') }}
                    </text>
                  </template>
                  <text class="account-note">
                    {{ $t('admin.balanceExplainNote') }}
                  </text>
                  <!-- 购买在官网完成，桌面端拉一次即可看到新解锁的功能 -->
                  <view class="account-refresh-row">
                    <button class="comp-btn" :disabled="entitlementBusy" @tap="onRefreshEntitlements">
                      {{ entitlementBusy ? $t('admin.refreshing') : $t('admin.refreshEntitlementsButton') }}
                    </button>
                  </view>
                </view>
              </view>
            </view>

            <view class="section-card">
              <view class="section-header">
                <text class="section-title">{{ $t('admin.recentUsageTitle') }}</text>
                <text class="section-subtitle">
                  {{ $t('admin.recentUsageSubtitle') }}
                </text>
              </view>
              <view class="section-body">
                <view v-if="!accountUsageRows.length" class="empty">
                  <text class="empty-text">{{ $t('admin.noUsage') }}</text>
                </view>
                <view
                  v-for="(row, idx) in accountUsageRows"
                  :key="'usage-' + idx"
                  class="usage-row"
                >
                  <view class="usage-main">
                    <text class="usage-model">{{ row.model || $t('admin.unknownModel') }}</text>
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
              <text class="section-title">{{ $t('admin.storageSectionTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.storageSectionSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.storageCurrentLabel') }}</text>
                  <text class="storage-path">{{ storageLocation.path }}</text>
                </view>
                <text v-if="!storageLocation.available" class="storage-warn">
                  {{ $t('admin.storageUnavailableWarn') }}
                </text>
                <text v-else-if="!storageLocation.custom" class="account-note">
                  {{ $t('admin.storageDefaultNote') }}
                </text>
                <UnlockHint
                  v-if="!storageCanMove"
                  :text="$t('admin.unlockHintStorage')"
                />
                <view class="account-connect-actions">
                  <button
                    v-if="storageCanMove"
                    class="comp-btn"
                    :disabled="storageBusy"
                    @tap="onChangeStorageLocation"
                  >
                    {{ storageBusy ? $t('admin.migrating') : $t('admin.changeLocationButton') }}
                  </button>
                  <!-- 恢复默认不设权益闸：这是退回免费版的默认状态，不发放任何付费能力。
                       锁在付费墙后面会让「权益失效 + 外置盘拔掉」的用户彻底出不来。 -->
                  <button
                    v-if="storageLocation.custom"
                    class="comp-btn"
                    :disabled="storageBusy"
                    @tap="onResetStorageLocation"
                  >
                    {{ $t('admin.resetLocationButton') }}
                  </button>
                </view>
                <text v-if="storageCanMove" class="account-note">
                  {{ $t('admin.storageMoveNotePrefix') }}<text class="storage-emph">{{ $t('admin.storageMoveNoteEm') }}</text>{{ $t('admin.storageMoveNoteSuffix') }}
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
              <text class="section-title">{{ $t('admin.identitySectionTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.identitySectionSubtitle') }}
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
                    {{ $t('admin.identityMeta', { username: item.username, projects: item.projectCount, files: item.fileCount }) }}
                  </text>
                </view>
                <view class="comp-actions">
                  <text v-if="item.userId === identityCurrentId" class="account-note">{{ $t('admin.identityCurrent') }}</text>
                  <button
                    v-else
                    class="comp-btn"
                    :disabled="identityBusy"
                    @tap="onSwitchIdentity(item)"
                  >
                    {{ $t('admin.switchButton') }}
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
              <text class="section-title">{{ $t('admin.navComponents') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.componentsSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view v-if="components.length === 0" class="empty">
                <text class="empty-text">{{ $t('admin.loadingDots') }}</text>
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
                    <text v-if="comp.state === 'installed' && comp.serviceRunning"> · {{ $t('admin.compServiceRunning') }}</text>
                    <text v-else-if="comp.state === 'installed'"> · {{ $t('admin.compReady') }}</text>
                    <text v-else-if="comp.state === 'downloading'"> · {{ $t('admin.compDownloading') }} {{ comp.percent != null ? comp.percent + '%' : '' }}</text>
                    <text v-else-if="comp.state === 'error'" class="comp-error"> · {{ $t('admin.compError', { msg: comp.message }) }}</text>
                    <text v-else> · {{ $t('admin.compNotDownloaded') }}</text>
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
                    {{ $t('admin.downloadButton') }}
                  </button>
                  <button
                    v-if="comp.state === 'downloading'"
                    class="comp-btn"
                    @tap="handleComponentCancel(comp)"
                  >
                    {{ $t('common.cancel') }}
                  </button>
                  <button
                    v-if="comp.state === 'installed' && !comp.serviceRunning"
                    class="comp-btn primary"
                    @tap="handleComponentEnable(comp)"
                  >
                    {{ $t('admin.enableButton') }}
                  </button>
                  <button
                    v-if="comp.state === 'installed'"
                    class="comp-btn danger"
                    @tap="handleComponentRemove(comp)"
                  >
                    {{ $t('common.delete') }}
                  </button>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>

        <!-- 软件更新（仅桌面端）：小版本补丁应用内更新，大版本引导官网下载全量包
             （docs/INCREMENTAL_UPDATE_DESIGN.md §6/§7） -->
        <scroll-view
          v-else-if="activeNav === 'updates'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.navUpdates') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.updatesSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="comp-row">
                <view class="comp-main">
                  <text class="comp-name">{{ $t('admin.currentVersion', { version: update.effectiveVersion || '-' }) }}</text>
                  <text class="comp-sub">
                    <text v-if="update.effectiveVersion !== update.appVersion">{{ $t('admin.updateBaseAndPatch', { app: update.appVersion, effective: update.effectiveVersion }) }}</text>
                    <text v-else>{{ $t('admin.fullInstallerVersion') }}</text>
                    <text v-if="update.checkedAt"> · {{ $t('admin.lastChecked', { time: formatUpdateTime(update.checkedAt) }) }}</text>
                  </text>
                  <text v-if="update.phase === 'checking'" class="comp-sub">{{ $t('admin.checkingUpdate') }}</text>
                  <text v-else-if="update.phase === 'downloading'" class="comp-sub">
                    {{ update.progress && update.progress.component ? $t('admin.downloadingPatchComp', { name: update.progress.component }) : $t('admin.downloadingPatch') }}
                  </text>
                  <text v-else-if="update.phase === 'ready'" class="comp-sub">
                    {{ $t('admin.updateReady', { version: update.available && update.available.version }) }}
                  </text>
                  <text v-else-if="update.phase === 'error'" class="comp-error">{{ $t('admin.updateCheckFailedMsg', { error: update.error }) }}</text>
                  <text v-else-if="update.checkedAt && !update.majorAvailable" class="comp-sub">{{ $t('admin.upToDate') }}</text>
                  <view v-if="update.phase === 'downloading' && update.progress && update.progress.total" class="comp-progress">
                    <view
                      class="comp-progress-fill"
                      :style="{ width: Math.min(100, Math.round(update.progress.received / update.progress.total * 100)) + '%' }"
                    />
                  </view>
                </view>
                <view class="comp-actions">
                  <button
                    v-if="update.phase === 'ready'"
                    class="comp-btn primary"
                    @tap="handleUpdateRestart"
                  >
                    {{ $t('admin.restartNowButton') }}
                  </button>
                  <button
                    v-else
                    class="comp-btn"
                    :disabled="update.phase === 'checking' || update.phase === 'downloading'"
                    @tap="handleUpdateCheck"
                  >
                    {{ $t('admin.checkUpdateButton') }}
                  </button>
                </view>
              </view>
              <view v-if="update.majorAvailable" class="comp-row">
                <view class="comp-main">
                  <text class="comp-name">{{ $t('admin.majorReleased', { version: update.majorAvailable.major }) }}</text>
                  <text class="comp-sub">{{ $t('admin.majorNote') }}</text>
                </view>
                <view class="comp-actions">
                  <button class="comp-btn primary" @tap="handleUpdateOpenDownload">{{ $t('admin.goDownloadButton') }}</button>
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
              <text class="section-title">{{ $t('admin.navCloud') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.cloudSubtitle') }}
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
                  <button class="comp-btn danger" @tap="onDisconnectCloud(conn)">{{ $t('admin.cloudDisconnectButton') }}</button>
                </view>
              </view>

              <view class="provider-card">
                <view class="provider-header">
                  <text class="provider-name">{{ $t('admin.cloudConnectTitle') }}</text>
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.cloudServerLabel') }}</text>
                  <input
                    v-model="cloudForm.serverUrl"
                    class="form-input"
                    :placeholder="$t('admin.cloudServerPlaceholder')"
                  />
                </view>
                <text v-if="cloudServerUrlIsHttp" class="cloud-http-warn">
                  {{ $t('admin.cloudHttpWarn') }}
                </text>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.usernameLabel') }}</text>
                  <input
                    v-model="cloudForm.username"
                    class="form-input"
                    :placeholder="$t('admin.cloudUsernamePlaceholder')"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.passwordLabel') }}</text>
                  <input
                    v-model="cloudForm.password"
                    class="form-input"
                    :placeholder="$t('admin.passwordLabel')"
                    password
                  />
                </view>
                <view class="cloud-connect-actions">
                  <button class="btn-primary" :disabled="cloudBusy" @tap="onConnectCloud">
                    {{ cloudBusy ? $t('admin.cloudConnecting') : $t('admin.connectButton') }}
                  </button>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>

        <!-- 记忆同步（仅桌面端）：AI 记忆经独立 Git 仓库跨机器同步。
             与案卷的版本记录互不相干——记忆仓库绝不进项目文档仓库主线（领域红线）。 -->
        <scroll-view
          v-else-if="activeNav === 'memory'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.navMemory') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.memorySubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view v-if="memoryLoading && !memoryRepos.length" class="empty">
                <text class="empty-text">{{ $t('admin.loadingDots') }}</text>
              </view>
              <view
                v-for="repo in memoryRepos"
                :key="repo.repoKey"
                class="provider-card"
              >
                <view class="provider-header memory-repo-header">
                  <view class="memory-repo-info">
                    <text class="provider-name">{{ repo.title }}</text>
                    <text class="memory-repo-sub">{{ repo.subtitle }}</text>
                  </view>
                  <text class="memory-status" :class="memoryStatusClass(repo)">
                    {{ memoryStatusLabel(repo) }}
                  </text>
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.memoryUrlLabel') }}</text>
                  <input
                    v-model="repo.form.url"
                    class="form-input"
                    :placeholder="$t('admin.memoryUrlPlaceholder', { repoKey: repo.repoKey })"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.usernameLabel') }}</text>
                  <input
                    v-model="repo.form.username"
                    class="form-input"
                    :placeholder="$t('admin.memoryUsernamePlaceholder')"
                  />
                </view>
                <view class="form-row">
                  <text class="form-label">{{ $t('admin.memorySecretLabel') }}</text>
                  <input
                    v-model="repo.form.secret"
                    class="form-input"
                    password
                    :placeholder="memorySecretPlaceholder(repo)"
                  />
                </view>
                <view class="account-connect-actions">
                  <button
                    v-if="repo.status && repo.status.configured"
                    class="comp-btn danger"
                    :disabled="repo.busy"
                    @tap="onDisconnectMemory(repo)"
                  >
                    {{ $t('admin.disconnect') }}
                  </button>
                  <button
                    v-if="repo.status && repo.status.configured"
                    class="comp-btn"
                    :disabled="repo.busy"
                    @tap="onSyncMemoryNow(repo)"
                  >
                    {{ $t('admin.syncNowButton') }}
                  </button>
                  <button class="btn-primary" :disabled="repo.busy" @tap="onSaveMemoryRemote(repo)">
                    {{ repo.busy ? $t('admin.processing') : $t('admin.saveAndSyncButton') }}
                  </button>
                </view>
                <text
                  v-if="repo.feedback"
                  class="memory-feedback"
                  :class="{ 'memory-feedback-warn': repo.feedbackError }"
                >{{ repo.feedback }}</text>
              </view>
              <view v-if="!memoryLoading && !memoryRepos.length" class="empty">
                <text class="empty-text">{{ $t('admin.noMemoryRepos') }}</text>
              </view>
            </view>
          </view>
        </scroll-view>

        <!-- 数据统计（匿名使用统计开关 + 本地使用统计） -->
        <scroll-view
          v-else-if="activeNav === 'telemetry'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.navTelemetry') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.telemetrySubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="provider-card">
                <view class="provider-header telemetry-switch-row">
                  <view class="telemetry-switch-info">
                    <text class="provider-name">{{ $t('admin.telemetryRollupTitle') }}</text>
                    <text class="telemetry-switch-desc">
                      {{ $t('admin.telemetryRollupDesc') }}
                    </text>
                  </view>
                  <AwdSwitch
                    :checked="telemetrySettings.rollupEnabled"
                    :disabled="telemetryBusy"
                    @change="onToggleTelemetry('rollupEnabled', $event)"
                  />
                </view>
              </view>
              <view class="provider-card">
                <view class="provider-header telemetry-switch-row">
                  <view class="telemetry-switch-info">
                    <text class="provider-name">{{ $t('admin.telemetryEventsTitle') }}</text>
                    <text class="telemetry-switch-desc">
                      {{ $t('admin.telemetryEventsDesc') }}
                    </text>
                  </view>
                  <AwdSwitch
                    :checked="telemetrySettings.eventsEnabled"
                    :disabled="telemetryBusy"
                    @change="onToggleTelemetry('eventsEnabled', $event)"
                  />
                </view>
              </view>
              <view class="telemetry-privacy-note">
                <text class="telemetry-privacy-title">{{ $t('admin.telemetryPrivacyTitle') }}</text>
                <text class="telemetry-privacy-line">{{ $t('admin.telemetryPrivacyLine') }}</text>
              </view>
            </view>
          </view>

          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.localStatsTitle') }}</text>
              <text class="section-subtitle">{{ $t('admin.localStatsSubtitle') }}</text>
              <view class="telemetry-days-row">
                <text
                  v-for="d in [7, 30, 90]"
                  :key="d"
                  class="telemetry-days-btn"
                  :class="{ active: telemetryDays === d }"
                  @tap="setTelemetryDays(d)"
                >{{ $t('admin.lastNDays', { n: d }) }}</text>
              </view>
            </view>
            <view class="section-body" v-if="telemetrySummary">
              <view class="telemetry-kpi-row">
                <view class="telemetry-kpi">
                  <text class="telemetry-kpi-num">{{ telemetrySummary.counters['ai.turn'] || 0 }}</text>
                  <text class="telemetry-kpi-label">{{ $t('admin.kpiTurns') }}</text>
                </view>
                <view class="telemetry-kpi">
                  <text class="telemetry-kpi-num">{{ telemetrySummary.counters['ai.tool'] || 0 }}</text>
                  <text class="telemetry-kpi-label">{{ $t('admin.kpiTools') }}</text>
                </view>
                <view class="telemetry-kpi">
                  <text class="telemetry-kpi-num">{{ telemetrySummary.editorActions.agent || 0 }}</text>
                  <text class="telemetry-kpi-label">{{ $t('admin.kpiAgentEdits') }}</text>
                </view>
                <view class="telemetry-kpi">
                  <text class="telemetry-kpi-num">{{ telemetrySummary.editorActions.human || 0 }}</text>
                  <text class="telemetry-kpi-label">{{ $t('admin.kpiHumanEdits') }}</text>
                </view>
              </view>
              <view v-if="telemetrySummary.byMatterCategory.length" class="telemetry-list">
                <text class="telemetry-list-title">{{ $t('admin.matterCategoryTitle') }}</text>
                <view v-for="item in telemetrySummary.byMatterCategory" :key="'m-' + item.name" class="telemetry-list-row">
                  <text class="telemetry-list-name">{{ item.name }}</text>
                  <text class="telemetry-list-count">{{ item.count }}</text>
                </view>
              </view>
              <view v-if="telemetrySummary.byTool.length" class="telemetry-list">
                <text class="telemetry-list-title">{{ $t('admin.topToolsTitle') }}</text>
                <view v-for="item in telemetrySummary.byTool.slice(0, 8)" :key="'t-' + item.name" class="telemetry-list-row">
                  <text class="telemetry-list-name">{{ item.name }}</text>
                  <text class="telemetry-list-count">{{ item.count }}</text>
                </view>
              </view>
            </view>
            <view class="section-body" v-else>
              <text class="telemetry-empty">{{ $t('admin.telemetryEmpty') }}</text>
            </view>
          </view>
        </scroll-view>

        <!-- 用户反馈：谁在什么时候提了什么，以及优化者把它办到哪一步了 -->
        <scroll-view
          v-else-if="activeNav === 'feedback'"
          scroll-y
          class="config-scroll"
        >
          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.optimizerTitle') }}</text>
              <text class="section-subtitle">
                {{ $t('admin.optimizerSubtitle') }}
              </text>
            </view>
            <view class="section-body">
              <view class="fb-status-row">
                <view class="fb-status-cell">
                  <text class="fb-status-label">{{ $t('admin.statusLabelText') }}</text>
                  <text class="fb-status-value">
                    {{ optimizer.enabled ? (optimizer.running ? $t('admin.optimizerRunningNow') : $t('admin.enabledText')) : $t('admin.notEnabledText') }}
                  </text>
                </view>
                <view class="fb-status-cell">
                  <text class="fb-status-label">{{ $t('admin.scheduleLabel') }}</text>
                  <text class="fb-status-value mono">{{ optimizer.cron || '—' }}</text>
                </view>
                <view class="fb-status-cell">
                  <text class="fb-status-label">{{ $t('admin.pendingLabel') }}</text>
                  <text class="fb-status-value">{{ optimizer.pending }}</text>
                </view>
                <view class="fb-status-cell">
                  <text class="fb-status-label">{{ $t('admin.notifyChannelLabel') }}</text>
                  <text class="fb-status-value">
                    {{ optimizer.notifyReady ? optimizer.notifyChannel : (optimizer.notifyIssue || $t('admin.notConfigured')) }}
                  </text>
                </view>
                <view class="fb-status-cell">
                  <text class="fb-status-label">{{ $t('admin.lastRunLabel') }}</text>
                  <text class="fb-status-value">{{ optimizer.lastRunAt || $t('admin.never') }}</text>
                </view>
              </view>
              <view class="fb-actions">
                <text class="fb-btn" @tap="reloadFeedbackPanel">{{ $t('admin.refreshButton') }}</text>
                <text
                  class="fb-btn primary"
                  :class="{ disabled: !optimizer.enabled || optimizer.running }"
                  @tap="triggerOptimizer"
                >{{ $t('admin.runNowButton') }}</text>
              </view>
              <text v-if="optimizer.lastReportText" class="fb-report">{{ optimizer.lastReportText }}</text>
            </view>
          </view>

          <view class="section-card">
            <view class="section-header">
              <text class="section-title">{{ $t('admin.feedbackRecordsTitle') }}</text>
              <text class="section-subtitle">{{ $t('admin.feedbackRecordsSubtitle') }}</text>
              <view class="telemetry-days-row">
                <text
                  v-for="f in feedbackFilters"
                  :key="'ff-' + f.key"
                  class="telemetry-days-btn"
                  :class="{ active: feedbackFilter === f.key }"
                  @tap="setFeedbackFilter(f.key)"
                >{{ f.label }}</text>
              </view>
            </view>
            <view class="section-body">
              <view v-if="!feedbackList.length" class="telemetry-empty">
                <text>{{ feedbackLoading ? $t('common.loading') : $t('admin.noFeedbackYet') }}</text>
              </view>
              <view
                v-for="fb in feedbackList"
                :key="'fb-' + fb.id"
                class="fb-item"
                @tap="toggleFeedbackDetail(fb.id)"
              >
                <view class="fb-item-head">
                  <text class="fb-chip" :class="'fb-chip-' + fb.status.toLowerCase()">{{ statusLabel(fb.status) }}</text>
                  <text class="fb-item-title">#{{ fb.id }} · {{ fb.kind === 'IDEA' ? $t('admin.kindIdea') : $t('admin.kindBug') }}</text>
                  <text class="fb-item-meta">{{ fb.username || $t('admin.unknownUser') }} · {{ shortTime(fb.createdAt) }}</text>
                </view>
                <text class="fb-item-text">{{ fb.text || fb.voiceTranscript || $t('admin.onlyAttachment') }}</text>
                <view class="fb-item-sub">
                  <text class="fb-item-tag">{{ fb.page || $t('admin.unknownPage') }}</text>
                  <text class="fb-item-tag">{{ fb.appVersion || '—' }}</text>
                  <text v-if="fb.triageVerdict" class="fb-item-tag">{{ $t('admin.triageLabel', { verdict: verdictLabel(fb.triageVerdict) }) }}</text>
                  <text v-if="fb.prUrl" class="fb-item-tag link" @tap.stop="openPr(fb.prUrl)">
                    {{ fb.status === 'PR_OPENED' ? $t('admin.viewPr') : $t('admin.viewIssue') }}
                  </text>
                </view>

                <view v-if="feedbackDetail && feedbackDetail.id === fb.id" class="fb-detail" @tap.stop>
                  <view v-if="feedbackDetail.attachments && feedbackDetail.attachments.length" class="fb-atts">
                    <template v-for="a in feedbackDetail.attachments" :key="'a-' + a.id">
                      <image
                        v-if="a.type === 'IMAGE'"
                        class="fb-att-img"
                        mode="aspectFill"
                        :src="attachmentUrl(fb.id, a.id)"
                        @tap.stop="previewAttachment(fb.id, a.id)"
                      />
                      <text v-else class="fb-item-tag link" @tap.stop="previewAttachment(fb.id, a.id)">
                        {{ $t('admin.voiceSize', { size: Math.round((a.sizeBytes || 0) / 1024) }) }}
                      </text>
                    </template>
                  </view>
                  <text v-if="fb.voiceTranscript" class="fb-detail-line">{{ $t('admin.voiceTranscript', { text: fb.voiceTranscript }) }}</text>
                  <text v-if="feedbackDetail.triageText" class="fb-detail-line">{{ feedbackDetail.triageText }}</text>
                  <text v-if="fb.lastError" class="fb-detail-line err">{{ $t('admin.lastError', { error: fb.lastError }) }}</text>
                  <text class="fb-detail-label">{{ $t('admin.contextLabel') }}</text>
                  <text class="fb-detail-pre">{{ feedbackDetail.contextText }}</text>
                </view>
              </view>
            </view>
          </view>
        </scroll-view>

        <!-- 插件广场：与其余设置项一致地在页内切换（独立页 /pages/plugin-market 保留给直链） -->
        <view
          v-else-if="activeNav === 'plugins'"
          class="market-embed"
        >
          <MarketPane :standalone="false" />
        </view>
      </view>
    </view>

    <!-- Assistant Edit Modal (Custom Overlay) -->
    <view v-if="showAssistantModal" class="modal-overlay" @tap.stop>
        <view class="modal-content">
            <view class="modal-header">
                <text class="modal-title">{{ isEditing ? $t('admin.editAssistantTitle') : $t('admin.newAssistantTitle') }}</text>
                <text class="modal-close" @tap="closeAssistantModal">×</text>
            </view>
            <scroll-view scroll-y class="modal-body">
                <view class="modal-body-inner">
                    <view class="modal-field">
                        <text class="form-label">{{ $t('admin.assistantIdLabel') }}</text>
                        <input class="modal-input" v-model="editingAssistant.id" :disabled="isEditing" :placeholder="$t('admin.assistantIdPlaceholder')"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">{{ $t('admin.assistantNameLabel') }}</text>
                        <input class="modal-input" v-model="editingAssistant.name" :placeholder="$t('admin.assistantNamePlaceholder')"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">{{ $t('admin.descriptionLabel') }}</text>
                        <input class="modal-input" v-model="editingAssistant.description" :placeholder="$t('admin.assistantDescPlaceholder')"/>
                    </view>
                     <view class="modal-field">
                        <text class="form-label">{{ $t('admin.systemPromptLabel') }}</text>
                        <textarea class="modal-textarea" v-model="editingAssistant.systemPrompt" :placeholder="$t('admin.systemPromptPlaceholder')" maxlength="-1" auto-height/>
                    </view>
                </view>
            </scroll-view>
            <view class="modal-footer">
                <button class="btn-cancel" @tap="closeAssistantModal">{{ $t('common.cancel') }}</button>
                <button class="btn-primary" @tap="saveAssistantModal">{{ $t('common.confirm') }}</button>
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
  getMemorySyncStatus, setMemorySyncRemote, removeMemorySyncRemote, syncMemoryNow,
  getCurrentUser as fetchCurrentUser, getMyProjects,
  getTelemetrySettings, updateTelemetrySettings, getTelemetrySummary,
  fetchAiModels,
  getFeedbackList, getFeedbackDetail, getOptimizerStatus, runOptimizer, getApiBaseUrl,
  getSiteStatus, selectSite,
  getPlatformServices, getPlatformServiceRemote, setPlatformServiceProvider, savePlatformBudget,
} from '@/services/api.js'
import {
  platformServiceMeta, sortPlatformServices, localTierReady, refreshLocalAsrReadiness,
} from '@/config/platformServices.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'
import { getLastProjectId } from '@/utils/recentProjects.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { accountPageUrl, siteBaseUrl, loadSiteLinks, resetSiteLinks } from '@/utils/siteLinks.js'
import { host } from '@/services/host.js'
import { refreshEntitlements, isEnabled, FEATURES } from '@/composables/useEntitlement.js'
import { getAppLanguage, setAppLanguage } from '@/utils/appLanguage.js'
import UnlockHint from '@/components/UnlockHint.vue'
import MarketPane from '@/components/MarketPane.vue'
import AwdSelect from '@/components/AwdSelect.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'

export default {
  name: 'AdminPage',
  components: { UnlockHint, MarketPane, AwdSelect, AwdSwitch },
  data() {
    return {
      userDisplayName: this.$t('admin.userFallbackName'),
      activeNav: 'config',
      // 应用语言：读写走 utils/appLanguage.js（storage 权威源 + App.vue 镜像同步），
      // 与 form/handleSave 无关。选项标签用各自母语，刻意不随语言翻译。
      appLanguage: getAppLanguage(),
      appLanguageOptions: [
        { value: 'zh-CN', label: '简体中文' },
        { value: 'en-US', label: 'English' },
      ],
      /** 服务端记录的同意时间戳；空 = 未同意或告知文本已改版需重新征求 */
      crossBorderConsentAt: '',
      navItems: [
        { key: 'config', label: this.$t('admin.navConfig') },
        { key: 'ai', label: this.$t('admin.navAi') },
        // 平台服务对团队服务器同样要可见：平台档在那里不可选，但那 21 个 BYOK 凭证字段
        // 已经搬进这个面板，标成 desktopOnly 会让自建服务器的管理员没地方填。
        { key: 'platform', label: this.$t('admin.navPlatform') },
        { key: 'account', label: this.$t('admin.navAccount'), desktopOnly: true },
        { key: 'components', label: this.$t('admin.navComponents'), desktopOnly: true },
        { key: 'updates', label: this.$t('admin.navUpdates'), desktopOnly: true },
        { key: 'cloud', label: this.$t('admin.navCloud'), desktopOnly: true },
        { key: 'memory', label: this.$t('admin.navMemory'), desktopOnly: true },
        { key: 'telemetry', label: this.$t('admin.navTelemetry') },
        { key: 'feedback', label: this.$t('admin.navFeedback') },
        { key: 'plugins', label: this.$t('admin.navPlugins') },
      ],
      // 用户反馈与优化者（右下角浮窗提交 → 优化者分诊 → 开 PR / 发邮件）
      feedbackList: [],
      feedbackDetail: null,
      feedbackLoading: false,
      feedbackFilter: '',
      feedbackFilters: [
        { key: '', label: this.$t('admin.filterAll') },
        { key: 'NEW', label: this.$t('admin.pendingLabel') },
        { key: 'PR_OPENED', label: this.$t('admin.prOpened') },
        { key: 'EMAILED', label: this.$t('admin.emailed') },
        { key: 'FAILED', label: this.$t('admin.filterFailed') },
      ],
      optimizer: {
        enabled: false,
        running: false,
        cron: '',
        pending: 0,
        notifyChannel: '',
        notifyReady: false,
        notifyIssue: '',
        lastRunAt: '',
        lastReportText: '',
      },
      components: [],
      // 软件更新状态（主进程 update-service 快照；事件推送增量刷新）
      update: {
        phase: 'idle',
        appVersion: '',
        effectiveVersion: '',
        checkedAt: null,
        available: null,
        majorAvailable: null,
        progress: null,
        error: null,
      },
      form: {
        external: {
          openRouter: { apiKey: '', baseUrl: '' },
          qichacha: { baseUrl: '', key: '', secret: '' },
          tushare: { baseUrl: '', token: '' },
          aliyunOcr: { accessKeyId: '', accessKeySecret: '', endpoint: '', regionId: '', publicBaseUrl: '' },
          pkulaw: { token: '' },
          bocha: { apiKey: '' },
          tingwu: { accessKeyId: '', accessKeySecret: '', appKey: '', ossBucket: '', ossEndpoint: '' },
        },
        ai: {
          activeProvider: 'OLLAMA',
          // 三个模型键：空串 = 跟随内置默认（子 Agent 是继承辅助模型）
          defaultModel: '',
          auxModel: '',
          subagentModel: '',
          networkRegion: 'auto',
          ollamaBaseUrl: '',
          ollamaModelName: '',
          // 跨境单独同意：null = 本次未动，true/false = 本次勾选/撤回。
          // 绝不初始化为 true——预勾选的同意在个保法下无效。
          crossBorderConsent: null,
          assistants: [],
        },
      },
      // 模型目录（GET /api/ai/models）：模型清单 + 区域判定结果与依据。
      // 前端不许再自己硬编码任何模型清单。
      modelCatalog: null,
      modelCatalogError: '',
      networkRegionOptions: [
        { value: 'auto', label: this.$t('admin.regionAuto') },
        { value: 'domestic', label: this.$t('admin.regionDomestic') },
        { value: 'international', label: this.$t('admin.regionInternational') },
      ],
      // 平台 AI 通道是否可选（= 是否已连接账户），来自 /api/account/status
      platformAiAvailable: false,
      // 「平台服务」分区（GET /api/platform-services）。
      // services 里的 provider 是后端解析后的**生效值**（非 local-mode 下即使库里写着
      // platform 也回 byok），界面展示与选中项一律以它为准。
      // pricingAvailable=false 时 enabled/balanceCents/pendingHoldCents 全是「不知道」，
      // 不许当成 false/0 渲染（同 ai-usage「查不到用量显示破折号不显示 0」那条）。
      // 本地那一半：GET /api/platform-services 秒回，页面靠它整页渲染完（档位与切档
      // 控件立刻可用）。远端那一半单独放 platformRemote，异步填，填不上就是「—」。
      platformState: {
        services: [], platformAvailable: false, accountConnected: false,
        budget: { taskLimitCents: 0, lowBalanceCents: 0 },
      },
      // 远端那一半（GET /api/platform-services/remote）。三个 null 与空 enabled 表
      // 都是「不知道」的初值，**不是** 0 / 未开放——在真值到达之前一律显示破折号。
      platformRemote: {
        pricingAvailable: false, enabled: {}, balanceCents: null,
        pendingHoldCents: null, usage: null,
      },
      platformLoaded: false,
      platformServicesError: '',
      platformBusy: false,
      // 花费闸门的两个阈值，界面上以 Credits（元）为单位编辑，存库是分。
      // 与档位不同，它们是输入框而不是开关：跟着每个字符写库既没必要也会打断输入，
      // 所以留一个明确的保存动作。
      budgetForm: { taskLimit: '0', lowBalance: '0' },
      budgetBusy: false,
      // 每项的「使用自己的 Key（高级）」是否展开。默认全收起；
      // ?nav=platform&service=ocr 这样的深链会就地展开对应那一项（错误提示的逃生门）。
      platformByokOpen: {},
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
      // 当前站点（双主站）。displayName 为空 = 还没取到 / 旧后端没有该端点，整块不渲染
      site: { current: '', displayName: '', pinned: false, multiSite: false, sites: [] },
      siteBusy: false,
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
      // 数据统计（匿名使用统计开关 + 本地统计）
      telemetrySettings: { rollupEnabled: true, eventsEnabled: false },
      telemetryBusy: false,
      telemetrySummary: null,
      telemetryDays: 30,
      // 本机工作区（免登身份）候选。长度 <= 1 时整块卡片不渲染
      identityCandidates: [],
      identityCurrentId: null,
      identityBusy: false,
      // 记忆同步（Phase A 桌面配置 UI）：两张卡——用户记忆仓 + 当前案卷记忆仓。
      // 每项 { repoKey, title, subtitle, status, form:{url,username,secret}, busy, feedback, feedbackError }
      memoryRepos: [],
      memoryLoading: false,
    }
  },
  computed: {
    isDesktop() {
      return !!(host.model)
    },
    stageUnlimited() {
      return isEnabled(FEATURES.STAGE_UNLIMITED)
    },
    // 可切换的目标站点（不含当前站点）。钉定或单站时为空数组，界面上就没有切换入口
    siteSwitchTargets() {
      if (this.site.pinned || !this.site.multiSite) return []
      return (this.site.sites || []).filter((s) => s.id !== this.site.current)
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
    // 「平台服务」的每一行：当前档位标签 + 可切的档位清单 + 说明。
    //
    // 可切清单是**动态**拼的，不是一个固定三选项再置灰：
    //  - platformAvailable=false（团队服务器 / 云端实例）时 platform 档整个不出现（D5）；
    //  - hasLocal 为真但本地引擎在这台机器上还没就绪（asr 的模型没下载）时 local 档也不
    //    出现，并说明去哪儿下。摆一个能选中、真用起来才炸的档位，正是设计 §6.2.1
    //    点名要避免的事。判据读 localTierReady，与会议面板的开关同一个出口。
    /**
     * 「有 N Credits 正被转写占用」的提示。
     *
     * 只在**确知**有未结算预扣时出现：`pendingHoldCents` 为 null 表示单价表没取到
     * （网关不可达/未连账户），那是「不知道」不是「零」，不能拿它编一个数出来。
     */
    pendingHoldNotice() {
      const cents = this.platformRemote.pendingHoldCents
      if (typeof cents !== 'number' || cents <= 0) return ''
      return this.$t('platform.holdNotice', { credits: (cents / 100).toFixed(2) })
    },
    /**
     * 「余额已经低于你设的线」。
     *
     * 三个条件缺一不可：用户启用了这条提醒（阈值 > 0）、余额是**确知**的、并且确实低了。
     * 余额读不到时给 null，那是「不知道」不是「零」——拿它去比大小会在每次网关抖动时
     * 弹一句「余额不足」，而用户账上可能还有好几百。
     */
    lowBalanceNotice() {
      const threshold = this.platformState.budget.lowBalanceCents
      const balance = this.platformRemote.balanceCents
      if (!threshold || threshold <= 0) return ''
      if (typeof balance !== 'number' || balance >= threshold) return ''
      return this.$t('platform.lowBalanceNotice', {
        credits: (balance / 100).toFixed(2),
        threshold: (threshold / 100).toFixed(2),
      })
    },
    // 本月合计。读不到时整句不显示——摆一个「合计 —」只是在占位置。
    usageTotalText() {
      const usage = this.platformRemote.usage
      if (!usage) return ''
      return this.$t('platform.usageTotal', {
        credits: ((usage.totalCents || 0) / 100).toFixed(2),
        month: usage.month || '',
      })
    },
    platformServiceRows() {
      const available = this.platformState.platformAvailable
      const connected = this.platformState.accountConnected
      return sortPlatformServices(this.platformState.services).map((s) => {
        const meta = platformServiceMeta(s.service)
        const localUsable = s.hasLocal && localTierReady(s.service)
        // 开放状态来自异步那一半。**undefined 是「还不知道」**（还没到 / 取不到），
        // 与 false「未开放」是两回事——只有 false 才配显示成「未开放」。
        const enabled = this.platformRemote.enabled[s.service]

        const values = []
        if (available) values.push('platform')
        values.push('byok')
        if (localUsable) values.push('local')
        const labelOf = {
          platform: this.$t('platform.tierPlatform'),
          byok: this.$t('platform.tierByok'),
          local: this.$t('platform.tierLocal'),
        }

        let tierLabel = labelOf[s.provider] || s.provider
        let tierClass = 'tier-' + s.provider
        if (s.provider === 'platform' && !connected) {
          tierLabel = this.$t('platform.tierNeedsAccount')
          tierClass = 'tier-need'
        } else if (s.provider === 'platform' && enabled === false) {
          // 平台侧还没开放这一项（合同/账号未就绪）。**必须与「出错了」分开**：
          // 未开放是产品尚未提供，用户能做的是改用自己的 Key；显示成故障会让他
          // 反复重试一件永远不会成功的事。enabled 为 undefined 是「查不到」不是「未开放」，
          // 那种情况照常显示平台档，真调用时网关自己会给准确的信封。
          tierLabel = this.$t('platform.tierDisabled')
          tierClass = 'tier-need'
        }

        const notes = []
        if (s.provider === 'platform' && enabled === false) {
          notes.push(this.$t('platform.tierDisabledNote'))
        } else if (s.provider === 'platform') {
          notes.push(this.$t('platform.tierPlatformNote'))
        }
        if (s.provider === 'local') notes.push(this.$t('platform.tierLocalNote'))
        if (s.hasLocal && !localUsable) notes.push(this.$t('platform.localAsrPending'))

        // 生效值不在可切清单里（例如非 local-mode 下后端已把 platform 解析成 byok）
        // 时选中项落在 byok 上——下拉显示的必须是**正在生效**的那一档。
        const idx = values.indexOf(s.provider)
        return {
          key: s.service,
          usageText: this.serviceUsageText(s.service),
          name: meta.nameKey ? this.$t(meta.nameKey) : s.service,
          desc: meta.descKey ? this.$t(meta.descKey) : '',
          hasByokCredentials: !!s.hasByokCredentials,
          provider: s.provider,
          tierLabel,
          tierClass,
          notes,
          optionValues: values,
          optionLabels: values.map((v) => labelOf[v]),
          optionIndex: idx < 0 ? values.indexOf('byok') : idx,
        }
      })
    },
    // 未加密地址提醒：仅按前缀判断，不做完整 URL 校验（连接失败自会有报错）。
    cloudServerUrlIsHttp() {
      return /^http:\/\//i.test((this.cloudForm.serverUrl || '').trim())
    },
    // 供应商单选项。「AI WorkDeck 云端」是平台计费通道，条件不满足时展示但不可选——
    // 隐藏它会让用户根本发现不了这个选项，直接可选又会在发消息时才报错。
    // 两个前置条件都要单独判：连接账户 → 账户里有 Credits。
    // 缺后者时官网 /api/account/ai-key 返回 409 no_allocation，只在发消息那一刻才炸。
    aiProviderOptions() {
      // 三档（GEMINI 已下线：主对话在架构上跑不通，其模型经 OpenRouter 的 google/* 仍可用）
      const options = [
        { value: 'OLLAMA', label: this.$t('admin.providerOllama') },
        { value: 'OPENROUTER', label: this.$t('admin.providerOpenRouter') },
      ]
      if (this.isDesktop) {
        let hint = ''
        if (!this.platformAiAvailable) hint = this.$t('admin.hintConnectFirst')
        else if (this.accountNeedsAllocation) hint = this.$t('admin.hintTopUp')
        options.push({
          value: 'AWD_CLOUD',
          label: this.$t('admin.providerAwdCloud'),
          hint,
          unavailable: !!hint,
        })
      }
      return options
    },
    // 已同意 = 本次刚勾选，或服务端有记录且本次没撤回
    crossBorderConsented() {
      if (this.form.ai.crossBorderConsent === true) return true
      if (this.form.ai.crossBorderConsent === false) return false
      return !!this.crossBorderConsentAt
    },
    formatConsentAt() {
      if (!this.crossBorderConsentAt) return ''
      const d = new Date(this.crossBorderConsentAt)
      return Number.isNaN(d.getTime()) ? this.crossBorderConsentAt : d.toLocaleString()
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
      if (plan === 'paid') return this.$t('admin.paidPlan')
      if (plan === 'free') return this.$t('admin.freePlan')
      return ''
    },
    // 额度三个数只在实时口径拿得到时才展示——拿不到时显示「暂不可用」，
    // 而不是把 0 当成真实剩余额度让用户以为额度用光了
    accountQuotaAvailable() {
      return !!(this.accountPlatform && this.accountPlatform.quotaAvailable)
    },
    // 已连账户但从未分配过 AI 额度：面板要给出「去官网分配」的引导
    // Credits 重构后判据是余额，不是「官网库里有没有 key 行」。
    // 后端 hasAiQuota 已由 creditsCents 算出；这里沿用它，语义变成「有没有 Credits」。
    // 注意 quotaAvailable=false 只表示用量查不到，不代表没 Credits，所以不能据此判缺额度。
    accountNeedsAllocation() {
      if (!this.accountPlatform) return false
      const credits = this.accountPlatform.creditsCents
      if (typeof credits === 'number') return credits <= 0
      return this.accountQuotaAvailable && !this.accountPlatform.hasAiQuota
    },
    accountUsageRows() {
      const rows = this.accountUsage && this.accountUsage.local && this.accountUsage.local.recent
      return Array.isArray(rows) ? rows : []
    },
    catalogModels() {
      const models = this.modelCatalog && this.modelCatalog.models
      return Array.isArray(models) ? models : []
    },
    // 后端解析出的「当前真正会用的默认模型」（DB 的 ai.defaultModel 优先于 yml）。
    // 展示它是为了让「跟随内置默认」这个选项有可核对的落点。
    catalogDefaultModel() {
      return (this.modelCatalog && this.modelCatalog.defaultModel) || ''
    },
    // 区域判定的结果与依据都要摆出来：用户要能看懂「国际模型为什么不见了」
    networkRegionSummary() {
      const c = this.modelCatalog
      if (!c) return this.$t('admin.catalogNotLoaded')
      const region = c.networkRegion === 'INTERNATIONAL'
        ? this.$t('admin.regionIntlDesc')
        : this.$t('admin.regionDomesticDesc')
      const mode = c.networkRegionMode === 'auto' ? this.$t('admin.regionAuto') : this.$t('admin.regionManualDesc')
      return this.$t('admin.regionSummary', {
        region,
        mode,
        basis: c.networkRegionBasis || '—',
        count: this.catalogModels.length,
      })
    },
  },
  onLoad(query) {
    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username || this.$t('admin.userFallbackName')
    }
    // 深链定位面板（顶栏「已连接账户」chip → ?nav=account）；只认当前可见的本页面板
    const nav = query && query.nav
    if (nav && this.visibleNavItems.some((n) => n.key === nav)) {
      this.onNavTap({ key: nav })
    }
    // ?nav=platform&service=ocr：就地展开那一项的「使用自己的 Key（高级）」。
    // 网关的失败提示要能一步指到逃生门，中间不能再让用户自己去找哪一项。
    const svc = query && query.service
    if (nav === 'platform' && svc) {
      this.platformByokOpen = { ...this.platformByokOpen, [svc]: true }
    }
    this.loadConfig()
    this.loadModelCatalog()
    this.loadTelemetry()
    // 官网链接预热：本页多处「跳官网」（取 Key、解锁提示、广场购买）都是同步取地址，
    // 而 siteLinks 的模块缓存不会被面板自己的 getSiteStatus 顺带填上。
    // 不预热时第一次点击只能拿到兜底站点，国际站用户会被送到没有他账户的站
    loadSiteLinks()
    if (this.isDesktop) {
      // AI 面板的「AI WorkDeck 云端」选项是否可选，取决于是否已连接账户。
      // status 是后端纯本地读盘，不打官网，可以随页面加载
      this.loadPlatformAiAvailability()
      this.loadComponents()
      // 订阅主进程模型下载进度；onUnload 退订
      this._modelProgressUnsub = host.model.onProgress((evt) => {
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
      // 软件更新：拉初始状态 + 订阅主进程推送（快照全量携带，直接覆盖本地态）
      this.loadUpdateStatus()
      if (host.update) {
        this._updateEventUnsub = host.update.onEvent((evt) => {
          if (evt && evt.state) this.update = { ...this.update, ...evt.state }
        })
      }
    }
  },
  onUnload() {
    if (this._modelProgressUnsub) {
      this._modelProgressUnsub()
      this._modelProgressUnsub = null
    }
    if (this._updateEventUnsub) {
      this._updateEventUnsub()
      this._updateEventUnsub = null
    }
  },
  methods: {
    // ---------- 数据统计 ----------
    async loadTelemetry() {
      try {
        const s = await getTelemetrySettings()
        if (s) {
          this.telemetrySettings.rollupEnabled = !!s.rollupEnabled
          this.telemetrySettings.eventsEnabled = !!s.eventsEnabled
        }
      } catch (e) {
        console.warn('loadTelemetry settings failed', e)
      }
      await this.loadTelemetrySummary()
    },
    async loadTelemetrySummary() {
      try {
        const r = await getTelemetrySummary(this.telemetryDays)
        if (r && r.code === 0) {
          const hasData = r.counters && Object.keys(r.counters).length > 0
          this.telemetrySummary = hasData ? {
            counters: r.counters || {},
            byTool: r.byTool || [],
            bySkill: r.bySkill || [],
            byMatterCategory: r.byMatterCategory || [],
            editorActions: r.editorActions || { agent: 0, human: 0 },
            tokens: r.tokens || {},
          } : null
        }
      } catch (e) {
        console.warn('loadTelemetrySummary failed', e)
      }
    },
    setTelemetryDays(d) {
      this.telemetryDays = d
      this.loadTelemetrySummary()
    },
    // AwdSwitch 直接抛布尔值
    async onToggleTelemetry(key, value) {
      this.telemetryBusy = true
      try {
        const r = await updateTelemetrySettings({ [key]: value })
        if (r) {
          this.telemetrySettings.rollupEnabled = !!r.rollupEnabled
          this.telemetrySettings.eventsEnabled = !!r.eventsEnabled
        }
      } catch (e) {
        uni.showToast({ title: this.$t('admin.saveFailedPleaseRetry'), icon: 'none' })
        // 回读真实状态，避免开关显示与后端不一致
        this.loadTelemetry()
      } finally {
        this.telemetryBusy = false
      }
    },
    async loadUpdateStatus() {
      if (!this.isDesktop || !host.update) return
      try {
        const s = await host.update.status()
        if (s) this.update = { ...this.update, ...s }
      } catch (e) {
        console.error('loadUpdateStatus failed', e)
      }
    },
    async handleUpdateCheck() {
      try {
        const s = await host.update.check()
        if (s) this.update = { ...this.update, ...s }
      } catch (e) {
        uni.showToast({ title: this.$t('admin.updateCheckToastFailed'), icon: 'none' })
      }
    },
    handleUpdateRestart() {
      uni.showModal({
        title: this.$t('admin.restartAppTitle'),
        content: this.$t('admin.restartAppContent', {
          version: this.update.available ? this.update.available.version : this.$t('admin.newVersionFallback'),
        }),
        success: (r) => {
          if (r.confirm) host.update.restart()
        },
      })
    },
    handleUpdateOpenDownload() {
      const page = (this.update.majorAvailable && this.update.majorAvailable.page) || 'https://www.aiworkdeck.com'
      host.shell.openExternal(page)
    },
    formatUpdateTime(iso) {
      try {
        const d = new Date(iso)
        const p = (n) => (n < 10 ? '0' + n : '' + n)
        return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
      } catch (e) {
        return ''
      }
    },
    async loadComponents() {
      if (!this.isDesktop) return
      try {
        const res = await host.model.status()
        this.components = (res && res.components ? res.components : []).map((c) => ({ percent: null, ...c }))
      } catch (e) {
        console.error('loadComponents failed', e)
      }
    },
    handleComponentDownload(comp) {
      uni.showModal({
        title: this.$t('admin.downloadComponentTitle'),
        content: this.$t('admin.downloadComponentContent', { name: comp.name, size: comp.sizeHint }),
        success: async (r) => {
          if (!r.confirm) return
          try {
            await host.model.download(comp.id)
            comp.state = 'downloading'
            comp.percent = 0
          } catch (e) {
            uni.showToast({ title: this.$t('admin.startDownloadFailed'), icon: 'none' })
          }
        },
      })
    },
    async handleComponentCancel(comp) {
      try {
        await host.model.cancel(comp.id)
      } finally {
        this.loadComponents()
      }
    },
    handleComponentRemove(comp) {
      uni.showModal({
        title: this.$t('admin.removeComponentTitle'),
        content: this.$t('admin.removeComponentContent', { name: comp.name, size: comp.sizeHint }),
        success: async (r) => {
          if (!r.confirm) return
          try {
            await host.model.remove(comp.id)
          } finally {
            this.loadComponents()
          }
        },
      })
    },
    async handleComponentEnable(comp) {
      // serviceName 由主进程 model-status 按组件→服务映射带回
      if (!comp.serviceName) return
      uni.showLoading({ title: this.$t('admin.startingService') })
      try {
        const res = await host.services.ensure(comp.serviceName)
        if (!res || !res.ok) {
          uni.showToast({ title: this.$t('admin.serviceStartFailed', { msg: (res && res.message) || this.$t('admin.unknownError') }), icon: 'none' })
        }
      } finally {
        uni.hideLoading()
        this.loadComponents()
      }
    },
    // 设置页与个人中心是**同级**的两个页面，互跳一律 redirectTo（替换本页）而不是
    // navigateTo（往栈里再压一页）。压栈的旧写法配上个人中心那边的 navigateBack，
    // 形成了 admin ⇄ 个人中心互相弹的死循环——从工作台点进设置的人再也回不到项目。
    // 替换之后栈始终是 [来处, 当前设置页]，全局返回键一步就能回到来处。
    goToUserProfile() {
      uni.redirectTo({ url: '/pages/userprofile/userprofile' })
    },
    // 重跑首次向导：重置 completed 标记后跳向导页。已有配置不清空，
    // 向导提交时按新填内容覆盖对应 key。
    handleRerunWizard() {
      uni.showModal({
        title: this.$t('admin.rerunWizard'),
        content: this.$t('admin.rerunWizardContent'),
        success: async (r) => {
          if (!r.confirm) return
          try {
            await resetWizard()
            uni.reLaunch({ url: '/pages/wizard/wizard' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('admin.resetFailed'), icon: 'none' })
          }
        },
      })
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
    onNavTap(nav) {
      this.activeNav = nav.key
      if (nav.key === 'cloud') {
        this.loadCloudConnections()
      }
      if (nav.key === 'platform') {
        this.loadPlatformServices()
      }
      if (nav.key === 'account') {
        this.loadSite()
        this.loadAccount()
        // 权益决定「更改位置」按钮出不出现；当前位置本身无论有没有权益都要显示
        this.loadStorageLocation()
        this.loadIdentityCandidates()
        refreshEntitlements()
      }
      if (nav.key === 'memory') {
        this.loadMemoryRepos()
      }
      if (nav.key === 'feedback') {
        this.reloadFeedbackPanel()
      }
    },

    // ---- 用户反馈 / 优化者 ----
    async reloadFeedbackPanel() {
      await Promise.all([this.loadFeedbackList(), this.loadOptimizerStatus()])
    },
    async loadFeedbackList() {
      this.feedbackLoading = true
      try {
        const res = await getFeedbackList(this.feedbackFilter, 100)
        this.feedbackList = ((res && res.data && res.data.items) || [])
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('admin.loadFeedbackFailed'), icon: 'none' })
      } finally {
        this.feedbackLoading = false
      }
    },
    async loadOptimizerStatus() {
      try {
        const res = await getOptimizerStatus()
        const d = (res && res.data) || {}
        this.optimizer = {
          enabled: !!d.enabled,
          running: !!d.running,
          cron: d.cron || '',
          pending: d.pending || 0,
          notifyChannel: d.notifyChannel || '',
          notifyReady: !!d.notifyReady,
          notifyIssue: d.notifyIssue || '',
          lastRunAt: d.lastRunAt ? String(d.lastRunAt).replace('T', ' ').slice(0, 19) : '',
          lastReportText: d.lastReport
            ? this.$t('admin.lastReport', {
              picked: d.lastReport.picked,
              prOpened: d.lastReport.prOpened,
              emailed: d.lastReport.emailed,
              skipped: d.lastReport.skipped,
              failed: d.lastReport.failed,
            }) + (d.lastReport.note ? this.$t('admin.lastReportNote', { note: d.lastReport.note }) : '')
            : '',
        }
      } catch (e) {
        // 团队服务器上没开优化者时读不到，不拦整个面板
        this.optimizer.enabled = false
      }
    },
    setFeedbackFilter(key) {
      this.feedbackFilter = key
      this.feedbackDetail = null
      this.loadFeedbackList()
    },
    async triggerOptimizer() {
      if (!this.optimizer.enabled || this.optimizer.running) return
      try {
        await runOptimizer()
        uni.showToast({ title: this.$t('admin.optimizerStarted'), icon: 'none' })
        this.optimizer.running = true
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('admin.triggerFailed'), icon: 'none' })
      }
    },
    async toggleFeedbackDetail(id) {
      if (this.feedbackDetail && this.feedbackDetail.id === id) {
        this.feedbackDetail = null
        return
      }
      try {
        const res = await getFeedbackDetail(id)
        const d = (res && res.data) || {}
        this.feedbackDetail = {
          id,
          attachments: d.attachments || [],
          triageText: this.formatTriage(d.triageJson),
          contextText: this.formatContext(d.contextJson),
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('admin.loadDetailFailed'), icon: 'none' })
      }
    },
    formatTriage(json) {
      if (!json) return ''
      try {
        const t = JSON.parse(json)
        return this.$t('admin.triageSummary', {
          verdict: this.verdictLabel(t.verdict),
          confidence: Number(t.confidence || 0).toFixed(2),
        })
          + `${t.summary ? ' · ' + t.summary : ''}`
          + `${t.reason ? ' · ' + this.$t('admin.triageBasis', { reason: t.reason }) : ''}`
      } catch (e) {
        return ''
      }
    },
    formatContext(json) {
      if (!json) return this.$t('admin.ctxNone')
      try {
        const c = JSON.parse(json)
        const lines = []
        const rt = c.runtime || {}
        lines.push(this.$t('admin.ctxVersion', { version: rt.appVersion || '—', platform: rt.platform || '—', java: rt.java || '—' }))
        const cl = c.client || {}
        if (cl.window) lines.push(this.$t('admin.ctxWindow', { w: cl.window.w, h: cl.window.h, dpr: cl.window.dpr }))
        if (cl.localTime) lines.push(this.$t('admin.ctxLocalTime', { time: cl.localTime }))
        if (cl.recentErrors && cl.recentErrors.length) {
          lines.push(this.$t('admin.ctxRecentErrors', { count: cl.recentErrors.length }))
          cl.recentErrors.slice(-5).forEach((e) => lines.push(`  · ${e.message}`))
        }
        if (c.backendLogTail) {
          lines.push(this.$t('admin.ctxBackendLog'))
          String(c.backendLogTail).trim().split('\n').slice(-20).forEach((l) => lines.push('  ' + l))
        }
        return lines.join('\n')
      } catch (e) {
        return json.slice(0, 2000)
      }
    },
    attachmentUrl(feedbackId, attachmentId) {
      const sid = getSessionId()
      return `${getApiBaseUrl()}/api/feedback/${feedbackId}/attachment/${attachmentId}`
        + (sid ? `?token=${encodeURIComponent(sid)}` : '')
    },
    previewAttachment(feedbackId, attachmentId) {
      const url = this.attachmentUrl(feedbackId, attachmentId)
      // 图片走系统预览、语音交给系统浏览器：后台面板不自带播放器
      uni.previewImage({ urls: [url], fail: () => openExternalUrl(url) })
    },
    openPr(url) {
      openExternalUrl(url)
    },
    statusLabel(s) {
      return ({
        NEW: this.$t('admin.pendingLabel'), PR_OPENED: this.$t('admin.prOpened'), EMAILED: this.$t('admin.emailed'),
        SKIPPED: this.$t('admin.skipped'), FAILED: this.$t('admin.processFailed'),
      })[s] || s
    },
    verdictLabel(v) {
      return ({ BUG: this.$t('admin.verdictBug'), SUGGESTION: this.$t('admin.kindIdea'), UNCLEAR: this.$t('admin.verdictUnclear'), NOISE: this.$t('admin.verdictNoise') })[v] || v || '—'
    },
    shortTime(t) {
      return t ? String(t).replace('T', ' ').slice(0, 16) : ''
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
        title: this.$t('admin.switchIdentityTitle'),
        content: this.$t('admin.switchIdentityContent', { name: item.displayName || item.username }),
        confirmText: this.$t('admin.switchButton'),
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
        uni.showToast({ title: (e && e.message) || this.$t('admin.switchFailed'), icon: 'none' })
      } finally {
        this.identityBusy = false
      }
    },
    // ---------- 记忆同步 ----------
    // 两张卡都不是硬前提：用户信息拿不到就只显示案卷卡，最近没开过案卷就只显示用户卡。
    async loadMemoryRepos() {
      this.memoryLoading = true
      const repos = []
      try {
        const me = await fetchCurrentUser()
        const uid = me && me.data && me.data.id
        if (uid) {
          repos.push(this.newMemoryRepo(
            `user-${uid}-memory`, this.$t('admin.myMemoryTitle'),
            this.$t('admin.myMemorySubtitle'),
          ))
        }
      } catch (e) {
        // 用户信息读不到就不显示这张卡，不拦整个面板
      }
      const projectId = getLastProjectId()
      if (projectId) {
        let name = ''
        try {
          const res = await getMyProjects()
          const list = (res && res.data) || []
          const hit = list.find((p) => Number(p.id) === projectId)
          if (hit && hit.name) name = hit.name
        } catch (e) {
          // 名字取不到就不带名字，不拦路
        }
        repos.push(this.newMemoryRepo(
          `project-${projectId}-memory`,
          name ? this.$t('admin.caseMemoryTitleNamed', { name }) : this.$t('admin.caseMemoryTitle'),
          this.$t('admin.caseMemorySubtitle'),
        ))
      }
      this.memoryRepos = repos
      // 注意用 this.memoryRepos 里的响应式代理逐个刷新，改裸对象不触发渲染
      await Promise.all(this.memoryRepos.map((r) => this.refreshMemoryStatus(r)))
      this.memoryLoading = false
    },
    newMemoryRepo(repoKey, title, subtitle) {
      return {
        repoKey,
        title,
        subtitle,
        status: null,
        form: { url: '', username: '', secret: '' },
        busy: false,
        feedback: '',
        feedbackError: false,
      }
    },
    async refreshMemoryStatus(repo) {
      try {
        const res = await getMemorySyncStatus(repo.repoKey)
        const d = (res && res.data) || {}
        repo.status = d
        repo.form.url = d.url || ''
        repo.form.username = d.username || ''
        // 凭据只写不读：令牌永远不回填输入框，占位符提示「已保存，留空沿用」
        repo.form.secret = ''
      } catch (e) {
        repo.status = { configured: false }
        repo.feedback = (e && e.message) || this.$t('admin.statusReadFailed')
        repo.feedbackError = true
      }
    },
    memoryStatusLabel(repo) {
      const s = repo.status
      if (!s) return ''
      if (!s.configured) return this.$t('admin.memoryNotConfigured')
      if (s.pendingUpload) return this.$t('admin.memoryConfiguredPending')
      return s.lastSyncAt ? this.$t('admin.memoryLastSync', { time: this.formatUsageTime(s.lastSyncAt) }) : this.$t('admin.memoryConfigured')
    },
    memoryStatusClass(repo) {
      const s = repo.status
      return {
        'memory-status-on': !!(s && s.configured && !s.pendingUpload),
        'memory-status-warn': !!(s && s.configured && s.pendingUpload),
      }
    },
    memorySecretPlaceholder(repo) {
      const masked = repo.status && repo.status.secretMasked
      return masked
        ? this.$t('admin.memorySecretSaved', { masked })
        : this.$t('admin.memorySecretPlaceholderText')
    },
    async onSaveMemoryRemote(repo) {
      const url = (repo.form.url || '').trim()
      if (!url) {
        repo.feedback = this.$t('admin.memoryUrlRequired')
        repo.feedbackError = true
        return
      }
      repo.busy = true
      repo.feedback = ''
      try {
        const res = await setMemorySyncRemote(repo.repoKey, {
          url,
          username: (repo.form.username || '').trim(),
          secret: repo.form.secret || '',
        })
        const sync = res && res.data && res.data.sync
        this.applyMemorySyncResult(repo, sync, this.$t('admin.memorySaved'))
        await this.refreshMemoryStatus(repo)
      } catch (e) {
        repo.feedback = (e && e.message) || this.$t('admin.saveFailedRetry')
        repo.feedbackError = true
      } finally {
        repo.busy = false
      }
    },
    async onSyncMemoryNow(repo) {
      repo.busy = true
      repo.feedback = ''
      try {
        const res = await syncMemoryNow(repo.repoKey)
        this.applyMemorySyncResult(repo, res && res.data, this.$t('admin.syncDone'))
        await this.refreshMemoryStatus(repo)
      } catch (e) {
        repo.feedback = (e && e.message) || this.$t('admin.syncFailedAutoRetry')
        repo.feedbackError = true
      } finally {
        repo.busy = false
      }
    },
    // 同步结果统一转成一句话反馈：离线与推送未成不是致命错误，后台会自动重试
    applyMemorySyncResult(repo, sync, okText) {
      if (!sync) {
        repo.feedback = okText
        repo.feedbackError = false
        return
      }
      if (sync.offline) {
        repo.feedback = this.$t('admin.syncOffline', { ok: okText })
        repo.feedbackError = true
      } else if (sync.pendingUpload) {
        repo.feedback = this.$t('admin.syncPendingPush', { ok: okText })
        repo.feedbackError = true
      } else {
        repo.feedback = this.$t('admin.syncInSync', { ok: okText })
        repo.feedbackError = false
      }
    },
    async onDisconnectMemory(repo) {
      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('admin.memoryDisconnectTitle'),
        content: this.$t('admin.memoryDisconnectContent'),
        confirmText: this.$t('admin.disconnect'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      repo.busy = true
      try {
        await removeMemorySyncRemote(repo.repoKey)
        await this.refreshMemoryStatus(repo)
        repo.feedback = this.$t('admin.memoryDisconnected')
        repo.feedbackError = false
      } catch (e) {
        repo.feedback = (e && e.message) || this.$t('admin.disconnectFailedRetry')
        repo.feedbackError = true
      } finally {
        repo.busy = false
      }
    },
    async loadPlatformAiAvailability() {
      try {
        const s = await getAccountStatus()
        this.platformAiAvailable = !!(s && s.platformAiAvailable)
      } catch (e) {
        this.platformAiAvailable = false
      }
      // 「已连接但 Credits 为空」也会让平台通道打不通，判据在用量接口的 creditsCents 里。
      // 设置页不是热路径，多这一次请求换来单选项如实标注，好过发消息时才报错。
      if (this.platformAiAvailable) {
        await this.loadAccountUsage()
      }
    },
    // ---------- 模型目录与区域 ----------
    async loadModelCatalog() {
      try {
        this.modelCatalog = await fetchAiModels()
        this.modelCatalogError = ''
      } catch (e) {
        this.modelCatalog = null
        // 拿不到清单时下拉只剩「跟随内置默认」与已保存的值：宁可少给选项，
        // 也不要在这里塞一份硬编码清单当兜底（那正是三份清单互不同步的来源）
        this.modelCatalogError = (e && e.message)
          ? this.$t('admin.catalogLoadFailedWithMsg', { msg: e.message })
          : this.$t('admin.catalogLoadFailedRetry')
      }
    },
    // 某个模型字段的可选项。第一项是「跟随内置默认 / 继承辅助模型」（空串）；
    // 已保存但当前区域不可用的模型如实列出并标注——静默换成别的模型，
    // 会让设置页显示的与实际发出去的不一致。
    modelOptionsFor(field) {
      const options = [{
        value: '',
        label: field === 'subagentModel' ? this.$t('admin.inheritAux') : this.$t('admin.followBuiltIn'),
      }]
      this.catalogModels.forEach((m) => {
        const price = (m.inputPricePerM != null && m.outputPricePerM != null)
          ? this.$t('admin.modelPrice', { in: m.inputPricePerM, out: m.outputPricePerM })
          : ''
        options.push({
          value: m.id,
          label: this.$t('admin.modelOption', { name: m.name, vendor: m.vendor })
            + price + (m.tiered ? this.$t('admin.tieredSuffix') : ''),
        })
      })
      const current = this.form.ai[field] || ''
      if (current && !this.catalogModels.some((m) => m.id === current)) {
        // 清单没读到时不要说成「区域不可用」，那是两回事
        options.push({
          value: current,
          label: this.modelCatalog
            ? this.$t('admin.modelUnavailableInRegion', { model: current })
            : this.$t('admin.modelSaved', { model: current }),
        })
      }
      return options
    },
    modelLabels(field) {
      return this.modelOptionsFor(field).map((o) => o.label)
    },
    modelIndex(field) {
      const current = this.form.ai[field] || ''
      const idx = this.modelOptionsFor(field).findIndex((o) => o.value === current)
      return idx < 0 ? 0 : idx
    },
    // AwdSelect 直接抛下标（不是 uni picker 那个 event.detail.value 的形状）
    onModelPick(field, idx) {
      const opt = this.modelOptionsFor(field)[Number(idx)]
      if (opt) this.form.ai[field] = opt.value
    },
    // 供应商单选：不可选项给出下一步，而不是静默不响应
    toggleCrossBorderConsent() {
      // 明确的三态：null 跟随服务端，true/false 是本次的显式动作。
      // 撤回是个保法第十五条给的权利，必须和给予一样容易操作。
      this.form.ai.crossBorderConsent = !this.crossBorderConsented
    },
    openPrivacyCrossBorder() {
      // 隐私政策是**按站点**的：两站缔约主体、适用法、出境路径都不同。
      // 写死国内站地址会把国际站用户送去一份不适用于他的文本，
      // 而这个入口恰恰是在向他征求出境同意。cross-border 是两站共用的锚点 id。
      openExternalUrl(siteBaseUrl() + '/legal/privacy#cross-border')
    },
    onPickProvider(opt) {
      if (opt.value === this.form.ai.activeProvider) return
      if (opt.unavailable) {
        uni.showToast({ title: opt.hint || this.$t('admin.currentlyUnavailable'), icon: 'none' })
        return
      }
      this.form.ai.activeProvider = opt.value
    },
    // ---------- 当前站点（双主站） ----------
    async loadSite() {
      try {
        const s = await getSiteStatus()
        const sites = (s && s.sites) || []
        const current = sites.find((x) => x.id === (s && s.current))
        this.site = {
          current: (s && s.current) || '',
          displayName: (current && current.displayName) || '',
          pinned: !!(s && s.pinned),
          multiSite: !!(s && s.multiSite),
          sites,
        }
      } catch (e) {
        // 旧后端没有该端点：整块不渲染，不打断账户面板的其余内容
        this.site = { current: '', displayName: '', pinned: false, multiSite: false, sites: [] }
      }
    },
    // 切站是破坏性动作：旧站凭据一律清掉。用户多半只是想「换个站看看」，
    // 不会预期账户被断开，所以弹窗必须逐条念出被清的东西，也必须说清什么不受影响。
    async onSwitchSite(target) {
      if (this.siteBusy) return
      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('admin.switchToSite', { name: target.displayName }),
        content: this.$t('admin.siteSwitchConfirmContent', { name: target.displayName }),
        confirmText: this.$t('admin.siteSwitchConfirm'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return

      this.siteBusy = true
      try {
        const res = await selectSite(target.id)
        // 链接缓存里还是旧站地址，不清掉「前往官网」会把人送到一个没有他账户的站
        resetSiteLinks()
        await this.loadSite()
        await this.loadAccount()
        await refreshEntitlements(true)
        await this.loadStorageLocation()
        const lines = [this.$t('admin.siteSwitchedLine', { name: target.displayName })]
        // 平台通道此刻必然没有密钥，后端会把 AI 供应商降级，不说一声用户会以为是自己改的
        if (res && res.aiProviderFallback) {
          lines.push(this.$t('admin.siteAiFallbackLine'))
        }
        // 广场地址与统计上报地址在后端属性层固化，本次启动内不会变
        lines.push(this.$t('admin.siteNextLaunchLine'))
        uni.showModal({ title: this.$t('admin.siteSwitchedTitle'), content: lines.join('\n\n'), showCancel: false })
      } catch (e) {
        uni.showModal({
          title: this.$t('admin.siteSwitchFailedTitle'),
          content: (e && e.message) || this.$t('admin.siteSwitchFailedContent'),
          showCancel: false,
        })
      } finally {
        this.siteBusy = false
      }
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
        // 账户已连但 Credits 为空时后端会报错，此处按「无额度」展示
        this.accountUsage = null
      }
    },
    // 官网账户页：生成账户 Key、充值、分配 AI 额度都在这里。
    // 地址在点击时才取——siteLinks 首帧可能还是兜底值，固化成常量就纠正不回来了
    openAccountSite() {
      openExternalUrl(accountPageUrl())
    },
    // 购买在官网完成。这里强制重取一次权益（refresh=true 会让后端先同步官网），
    // 让刚买完回到桌面的用户不用重启就看到解锁结果。
    async onRefreshEntitlements() {
      this.entitlementBusy = true
      try {
        await refreshEntitlements(true)
        await this.loadStorageLocation()
        uni.showToast({
          title: this.stageUnlimited ? this.$t('admin.entitlementsUpdated') : this.$t('admin.entitlementsNoNew'),
          icon: 'none',
        })
      } catch (e) {
        uni.showToast({ title: this.$t('admin.refreshFailedRetry'), icon: 'none' })
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
        title: this.$t('admin.resetLocationButton'),
        content: this.$t('admin.resetLocationContent', {
          defaultPath: this.storageLocation.defaultPath || '',
          previous,
        }),
        confirmText: this.$t('admin.restoreDefaultConfirm'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return

      this.storageBusy = true
      try {
        await resetStorageLocation()
        await this.loadStorageLocation()
        uni.showModal({
          title: this.$t('admin.resetDoneTitle'),
          content: this.$t('admin.resetDoneContent', { previous }),
          showCancel: false,
        })
      } catch (e) {
        uni.showModal({
          title: this.$t('admin.resetFailedTitle'),
          content: (e && e.message) || this.$t('admin.resetFailedContent'),
          showCancel: false,
        })
      } finally {
        this.storageBusy = false
      }
    },
    async onChangeStorageLocation() {
      const desktop = host.fs && typeof host.fs.showOpenDialog === 'function' ? host : null
      if (!desktop) {
        uni.showToast({ title: this.$t('admin.desktopOnlyDirPicker'), icon: 'none' })
        return
      }
      let picked
      try {
        const res = await desktop.fs.showOpenDialog({
          title: this.$t('admin.storagePickerTitle'),
          properties: ['openDirectory', 'createDirectory'],
        })
        if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return
        picked = res.filePaths[0]
      } catch (e) {
        uni.showToast({ title: this.$t('admin.openDirPickerFailed'), icon: 'none' })
        return
      }

      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('admin.migrateTitle'),
        content: this.$t('admin.migrateContent', { path: picked }),
        confirmText: this.$t('admin.migrateConfirm'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return

      this.storageBusy = true
      try {
        const res = await moveStorageLocation(picked)
        await this.loadStorageLocation()
        const data = (res && res.data) || {}
        uni.showModal({
          title: this.$t('admin.migrateDoneTitle'),
          content: this.$t('admin.migrateDoneContent', {
            count: data.movedFiles || 0,
            previous: data.previousPath || '',
          }),
          showCancel: false,
        })
      } catch (e) {
        // 失败即回滚：存储位置维持原样，用户数据一个字节没动
        uni.showModal({
          title: this.$t('admin.migrateFailedTitle'),
          content: (e && e.message) || this.$t('admin.migrateFailedContent'),
          showCancel: false,
        })
      } finally {
        this.storageBusy = false
      }
    },
    async onConnectAccount() {
      const key = (this.accountKeyInput || '').trim()
      if (!key) {
        uni.showToast({ title: this.$t('admin.pasteKeyFirst'), icon: 'none' })
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
        uni.showToast({ title: this.$t('admin.accountConnectedToast'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('admin.connectFailed'), icon: 'none' })
      } finally {
        this.accountBusy = false
      }
    },
    async onDisconnectAccount() {
      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('admin.disconnectAccountTitle'),
        content: this.$t('admin.disconnectAccountContent'),
        confirmText: this.$t('admin.disconnect'),
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
            title: this.$t('admin.disconnectedTitle'),
            content: this.$t('admin.providerFallbackContent', { label }),
            showCancel: false,
          })
        } else {
          uni.showToast({ title: this.$t('admin.disconnectedTitle'), icon: 'none' })
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('common.failed'), icon: 'none' })
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
      if (src === 'platform') return this.$t('admin.usagePlatform')
      if (src === 'estimate') return this.$t('admin.usageEstimate')
      return ''
    },
    // 平台通道在对账完成前 cost 为 null——显示「待结算」，
    // 绝不能拿 $0.00 顶替，那会让用户以为这次调用不花钱
    usageCostText(row) {
      const cost = row && row.cost
      if (cost === null || cost === undefined || cost === '') {
        return row && row.costSource === 'platform' ? this.$t('admin.pendingSettlement') : '—'
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
        uni.showToast({ title: this.$t('admin.cloudConnectedToast'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('admin.connectFailed'), icon: 'none' })
      } finally {
        this.cloudBusy = false
      }
    },
    async onDisconnectCloud(conn) {
      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('admin.cloudDisconnectButton'),
        content: this.$t('admin.cloudDisconnectContent'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      try {
        await disconnectCloudConnection(conn.id)
        await this.loadCloudConnections()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('common.failed'), icon: 'none' })
      }
    },
    // ---------- 平台服务 ----------
    /**
     * 某一项服务本月花了多少。
     *
     * <b>「查不到」与「没花过」是两件事</b>：整段 usage 为 null 是前者（网关不可达 /
     * 未连账户 / 官网还没上这条端点），显示破折号；usage 拿到了但表里没这一项是后者，
     * 那是一个真实的 0。把前者显示成 0 会让刚跑完一场两小时转写的用户以为账没记上。
     */
    serviceUsageText(service) {
      const usage = this.platformRemote.usage
      if (!usage || !usage.services) return this.$t('platform.usageUnknown')
      const cents = usage.services[service]
      return this.$t('platform.usageCredits', {
        credits: ((typeof cents === 'number' ? cents : 0) / 100).toFixed(2),
      })
    },
    // 阈值以 Credits（元）编辑、以分存库。空串按 0（不启用）处理；
    // 负数与非数字交给后端拒绝，不在这里静默改成 0——那等于替用户做决定。
    creditsToCents(input) {
      const raw = String(input === undefined || input === null ? '' : input).trim()
      if (!raw) return 0
      const value = Number(raw)
      if (!Number.isFinite(value)) return -1
      return Math.round(value * 100)
    },
    async onSaveBudget() {
      if (this.budgetBusy) return
      const taskLimitCents = this.creditsToCents(this.budgetForm.taskLimit)
      const lowBalanceCents = this.creditsToCents(this.budgetForm.lowBalance)
      if (taskLimitCents < 0 || lowBalanceCents < 0) {
        uni.showToast({ title: this.$t('platform.budgetInvalid'), icon: 'none' })
        return
      }
      this.budgetBusy = true
      try {
        await savePlatformBudget(taskLimitCents, lowBalanceCents)
        uni.showToast({ title: this.$t('platform.budgetSaved'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('platform.budgetSaveFailed'), icon: 'none' })
      } finally {
        this.budgetBusy = false
        // 无论成败都重拉：写失败时界面必须回到库里的真相，而不是停在用户以为的值上
        await this.loadPlatformServices()
      }
    },
    async loadPlatformServices() {
      // 本地档能不能选，判据在 platformServices 那个唯一出口上，由这次探测填。
      // 与会议面板的开关同源：只在一处接探测的话，用户从另一处照样能切进一个用不了的档。
      refreshLocalAsrReadiness()
      try {
        const s = (await getPlatformServices()) || {}
        this.platformState = {
          services: Array.isArray(s.services) ? s.services : [],
          platformAvailable: !!s.platformAvailable,
          accountConnected: !!s.accountConnected,
          budget: {
            taskLimitCents: Number((s.budget && s.budget.taskLimitCents) || 0),
            lowBalanceCents: Number((s.budget && s.budget.lowBalanceCents) || 0),
          },
        }
        // 输入框跟着库里的值走。旧后端不返回 budget 时这里落成 0 = 不启用，
        // 与后端默认值一致，不会凭空冒出一个用户没设过的阈值。
        this.budgetForm = {
          taskLimit: (this.platformState.budget.taskLimitCents / 100).toFixed(2),
          lowBalance: (this.platformState.budget.lowBalanceCents / 100).toFixed(2),
        }
        this.platformServicesError = ''
      } catch (e) {
        this.platformServicesError = (e && e.message) || this.$t('platform.loadFailed')
      } finally {
        this.platformLoaded = true
      }
      // 远端那一半**故意不 await**：官网挂着的时候这一页照样要能打开，
      // 而它恰恰是用户切回自备 Key 的唯一入口。取不到就一直是「—」。
      this.loadPlatformRemote()
    },
    /**
     * 开放状态 / 余额 / 预扣 / 本月用量。慢，且随时可能取不到。
     *
     * 失败时<b>把上一次的值原样留着还是清掉</b>是个真问题：这里选择清掉（回到「不知道」），
     * 与余额闸那条「网络失败不保留上一次的 0」同口径——留着一个过期的余额去驱动
     * 「余额不足」的提醒，比不提醒更糟。
     */
    async loadPlatformRemote() {
      try {
        const r = (await getPlatformServiceRemote()) || {}
        this.platformRemote = {
          pricingAvailable: !!r.pricingAvailable,
          enabled: r.enabled && typeof r.enabled === 'object' ? r.enabled : {},
          // 这几个刻意**不**用 `!!` / `|| 0` 归一化：后端取不到单价表时给的是 null，
          // 而 null 的意思是「不知道」不是「未开放 / 余额为零」。归一化会把一次网络抖动
          // 变成「六项服务全部未开放」，比不显示这个状态更糟。
          balanceCents: typeof r.balanceCents === 'number' ? r.balanceCents : null,
          pendingHoldCents: typeof r.pendingHoldCents === 'number' ? r.pendingHoldCents : null,
          usage: r.usage && typeof r.usage === 'object' ? r.usage : null,
        }
      } catch (e) {
        this.platformRemote = {
          pricingAvailable: false, enabled: {}, balanceCents: null,
          pendingHoldCents: null, usage: null,
        }
      }
    },
    // 切档立刻写库（不等「保存配置」）：档位是一个开关而不是一张表单，
    // 让它跟着凭证字段一起等保存，用户点完下拉看不到任何变化会以为没生效。
    // 写失败**不改本地状态**，重新拉一次让界面回到真相——静默留在用户以为的那一档
    // 比报错更糟（他会以为已经切走了）。
    async onPlatformTierPick(svc, index) {
      const target = svc.optionValues[index]
      if (!target || target === svc.provider) return
      this.platformBusy = true
      try {
        await setPlatformServiceProvider(svc.key, target)
        uni.showToast({ title: this.$t('platform.switched'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('platform.switchFailed'), icon: 'none' })
      } finally {
        this.platformBusy = false
        await this.loadPlatformServices()
      }
    },
    togglePlatformByok(key) {
      this.platformByokOpen = { ...this.platformByokOpen, [key]: !this.platformByokOpen[key] }
    },
    async loadConfig() {
      try {
        const data = await getAdminConfig()
        if (data && data.external) {
          this.form.external = {
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
            tingwu: {
              accessKeyId: data.external.tingwu?.accessKeyId || '',
              accessKeySecret: data.external.tingwu?.accessKeySecret || '',
              appKey: data.external.tingwu?.appKey || '',
              ossBucket: data.external.tingwu?.ossBucket || '',
              ossEndpoint: data.external.tingwu?.ossEndpoint || '',
            },
          }
        }
        if (data && data.ai) {
          this.form.ai.activeProvider = data.ai.activeProvider || 'OLLAMA'
          // 三个模型键留空是合法值（跟随内置默认 / 继承辅助模型），不要在这里塞默认模型 id
          this.form.ai.defaultModel = data.ai.defaultModel || ''
          this.form.ai.auxModel = data.ai.auxModel || ''
          this.form.ai.subagentModel = data.ai.subagentModel || ''
          this.form.ai.networkRegion = data.ai.networkRegion || 'auto'
          this.form.ai.ollamaBaseUrl = data.ai.ollamaBaseUrl || ''
          this.form.ai.ollamaModelName = data.ai.ollamaModelName || ''
          this.crossBorderConsentAt = data.ai.crossBorderConsentAt || ''
          this.form.ai.crossBorderConsent = null


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
        uni.showToast({ title: (e && e.message) || this.$t('admin.loadConfigFailed'), icon: 'none' })
      }
    },
    async handleSave() {
      this.saving = true
      try {
        await saveAdminConfig(this.form)
        uni.showToast({ title: this.$t('admin.saveSuccess'), icon: 'success' })
        // 区域一改，可用模型清单跟着变；默认模型也可能因此落到清单外，
        // 保存后重新拉一次目录，让页面上显示的就是新口径
        this.loadModelCatalog()
      } catch (e) {
        console.error('保存后台配置失败', e)
        uni.showToast({
          title: e.message || this.$t('admin.saveFailed'),
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
            title: this.$t('admin.confirmDeleteTitle'),
            content: this.$t('admin.confirmDeleteAssistant'),
            confirmText: this.$t('common.delete'),
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
            uni.showToast({ title: this.$t('admin.idNameRequired'), icon: 'none' });
            return;
        }
        
        // Check ID uniqueness if adding
        if (!this.isEditing) {
            const exists = this.form.ai.assistants.find(a => a.id === this.editingAssistant.id);
            if (exists) {
                uni.showToast({ title: this.$t('admin.idExists'), icon: 'none' });
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
/* AI WorkDeck Color System */
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

.page-admin {
  min-height: 100vh;
  /* AI WorkDeck Palette Background */
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

/* 插件广场内嵌：与 .config-scroll 同高，MarketPane 自身按 100% 撑满并内部滚动 */
.market-embed {
  height: calc(100vh - 140px);
  border-radius: 12px;
  overflow: hidden;
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

/* 字段旁的说明行：模型/区域这几项的取舍必须写清楚，否则用户只能靠猜 */
.field-note {
  display: block;
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.7;
  margin: -4px 0 12px;
}

.field-note-warn {
  color: #B45309;
}

/* 模型下拉：形制在 AwdSelect 里，这里只管它在表单行里占多宽
   （原来那份 .mode-value/.mode-caret 是给 uni <picker> 的收起态用的，
   换成自绘下拉后没人引用了，一并删掉） */
.mode-picker {
  flex: 1;
  min-width: 0;
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

// 前置条件未满足（未连接账户 / Credits 为空）的「AI WorkDeck 云端」：
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

/* 记忆同步 */
.memory-repo-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.memory-repo-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.memory-repo-sub {
  font-size: 12px;
  color: $text-secondary;
}

.memory-status {
  font-size: 12px;
  color: $text-secondary;
  white-space: nowrap;
}

.memory-status-on {
  color: #1a5336;
}

.memory-status-warn {
  color: #b45309;
}

.memory-feedback {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  line-height: 18px;
  color: #1a5336;
}

.memory-feedback-warn {
  color: #b45309;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 当前站点 */
.site-name {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: $text-main;
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

/* 平台服务（P5 配置面收敛） */
.platform-banner {
  margin-bottom: 16px;
  padding: 12px 14px;
  background: #F4F7F5;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.platform-banner-warn {
  background: #FDF6EC;
}

.platform-banner-title {
  font-size: 13px;
  font-weight: 600;
  color: #1A5336;
}

.platform-banner-warn .platform-banner-title {
  color: #B45309;
}

.platform-banner-body {
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.7;
}

.platform-link {
  align-self: flex-start;
  font-size: 12px;
  color: #1A5336;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.platform-row-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.platform-row-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.platform-row-desc {
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.5;
}

.platform-row-side {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.platform-tier {
  flex: none;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
}

/* 本月消耗：一句附注而不是一个数字块——它是参考值，不该抢档位的位置 */
.platform-usage {
  font-size: 11px;
  color: $text-secondary;
  white-space: nowrap;
}

.platform-budget-actions {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}

.tier-platform {
  color: #1A5336;
  background: #DEF3E7;
}

.tier-byok {
  color: #4B5563;
  background: #EEF1F0;
}

.tier-local {
  color: #1D4ED8;
  background: #DBEAFE;
}

.tier-need {
  color: #B45309;
  background: #FDF0DC;
}

/* 折叠头本身就是「改用自己的 Key」的入口，做成一行可点的分隔条 */
.platform-fold-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px solid $border-color;
  cursor: pointer;
}

.platform-fold-title {
  font-size: 12px;
  color: $text-secondary;
}

.platform-fold-arrow {
  font-size: 12px;
  color: #1A5336;
}

.platform-byok-body {
  margin-top: 14px;
}

/* 数据统计 */
.telemetry-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.telemetry-switch-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.telemetry-switch-desc {
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.5;
}

.telemetry-privacy-note {
  margin-top: 12px;
  padding: 12px 14px;
  background: #F4F7F5;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.telemetry-privacy-title {
  font-size: 12px;
  font-weight: 600;
  color: #1A5336;
}

.telemetry-privacy-line {
  font-size: 12px;
  color: $text-secondary;
  line-height: 1.6;
}

.telemetry-days-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.telemetry-days-btn {
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 14px;
  border: 1px solid #D8E0DB;
  color: $text-secondary;
  cursor: pointer;
}

.telemetry-days-btn.active {
  background: #1A5336;
  border-color: #1A5336;
  color: #FFFFFF;
}

.telemetry-kpi-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.telemetry-kpi {
  flex: 1;
  min-width: 120px;
  padding: 14px 16px;
  background: #F9FAF9;
  border: 1px solid #E4EAE6;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.telemetry-kpi-num {
  font-size: 22px;
  font-weight: 700;
  color: #1A5336;
}

.telemetry-kpi-label {
  font-size: 12px;
  color: $text-secondary;
}

.telemetry-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.telemetry-list-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}

.telemetry-list-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 2px;
  border-bottom: 1px solid #F0F3F1;
}

.telemetry-list-name {
  font-size: 13px;
}

.telemetry-list-count {
  font-size: 13px;
  font-weight: 600;
  color: #1A5336;
}

.telemetry-empty {
  font-size: 13px;
  color: $text-secondary;
}

/* ---- 用户反馈 / 优化者 ---- */
.fb-status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 14px;
}

.fb-status-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 120px;
}

.fb-status-label {
  font-size: 11px;
  color: $text-secondary;
}

.fb-status-value {
  font-size: 13px;
  color: #12344D;
}

/* cron 表达式在正文衬线字体下星号会飘起来，读不出「0 0 9 * * *」的结构 */
.fb-status-value.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.fb-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.fb-btn {
  border: 1px solid #E3E8E5;
  border-radius: 7px;
  padding: 6px 14px;
  font-size: 12px;
  color: #12344D;
  cursor: pointer;
}

.fb-btn.primary {
  background: #1A5336;
  border-color: #1A5336;
  color: #FFFFFF;
}

.fb-btn.disabled {
  opacity: 0.5;
  cursor: default;
}

.fb-report {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: $text-secondary;
}

.fb-item {
  border: 1px solid #EEF1EF;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
}

.fb-item:hover {
  border-color: #CFE3D8;
}

.fb-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.fb-chip {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 9px;
  background: #F0F2F1;
  color: #6C757D;
}

.fb-chip-new { background: #FFF3E0; color: #B26A00; }
.fb-chip-pr_opened { background: #E7F6EE; color: #1A5336; }
.fb-chip-emailed { background: #E8F0FB; color: #1B4F86; }
.fb-chip-failed { background: #FBE9E7; color: #C0392B; }

.fb-item-title {
  font-size: 13px;
  font-weight: 600;
  color: #12344D;
}

.fb-item-meta {
  font-size: 11px;
  color: $text-secondary;
  margin-left: auto;
}

.fb-item-text {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: #12344D;
}

.fb-item-sub {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.fb-item-tag {
  font-size: 11px;
  color: $text-secondary;
  background: #F8F9FA;
  border-radius: 5px;
  padding: 1px 7px;
}

.fb-item-tag.link {
  color: #1A5336;
  cursor: pointer;
  text-decoration: underline;
}

.fb-detail {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #E3E8E5;
}

.fb-atts {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 8px;
}

.fb-att-img {
  width: 132px;
  height: 88px;
  border: 1px solid #E3E8E5;
  border-radius: 6px;
  background: #F8F9FA;
}

.fb-detail-line {
  display: block;
  font-size: 12px;
  line-height: 1.7;
  color: #12344D;
}

.fb-detail-line.err {
  color: #C0392B;
}

.fb-detail-label {
  display: block;
  margin-top: 8px;
  font-size: 11px;
  color: $text-secondary;
}

.fb-detail-pre {
  display: block;
  margin-top: 4px;
  padding: 8px;
  background: #F8F9FA;
  border: 1px solid #EEF1EF;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.6;
  color: $text-secondary;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}

/* 跨境单独同意（个保法第三十九条）。刻意做得可读而不刺眼：
   它不该吓退用户，但必须在做决定时看得见、看得懂。 */
.consent-row {
  margin-top: 12rpx;
}
.consent-box {
  border: 1rpx solid #e3e6e8;
  border-left: 4rpx solid #1a5336;
  border-radius: 8rpx;
  padding: 20rpx 24rpx;
  background: #fafbfb;
}
.consent-title {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #212629;
  margin-bottom: 10rpx;
}
.consent-body {
  display: block;
  font-size: 24rpx;
  line-height: 1.7;
  color: #6c757d;
}
.consent-em {
  color: #212629;
  font-weight: 600;
}
.consent-check {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  margin-top: 18rpx;
  cursor: pointer;
}
.consent-box-mark {
  width: 28rpx;
  height: 28rpx;
  flex-shrink: 0;
  margin-top: 4rpx;
  border: 2rpx solid #adb5bd;
  border-radius: 4rpx;
  background: #fff;
  transition: all 0.15s;
}
.consent-box-mark.checked {
  background: #1a5336;
  border-color: #1a5336;
}
.consent-check-label {
  font-size: 24rpx;
  line-height: 1.6;
  color: #2c3338;
}
.consent-meta {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #6c757d;
}
.consent-link {
  display: inline-block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #1a5336;
  text-decoration: underline;
}

</style>

<template>
  <scroll-view scroll-y class="mdp">
    <view class="mdp-inner">
      <!-- 头部：图标 + 名称 + 元信息 + 动作（VS Code 扩展详情页结构） -->
      <view class="mdp-head">
        <view class="mdp-glyph" :class="{ 'is-plugin': spec.kind === 'plugin' }">
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in headGlyph" :key="gi" :d="d" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </view>
        <view class="mdp-head-main">
          <view class="mdp-title-row">
            <text class="mdp-title">{{ display.name }}</text>
            <text class="mdp-kind-badge">{{ spec.kind === 'plugin' ? $t('market.pluginLabel') : (isPanel ? $t('market.panelPluginLabel') : $t('market.kindBadgeSkill')) }}</text>
            <text v-if="installedInfo" class="mdp-installed-badge">{{ $t('market.installedTag') }}</text>
            <text v-if="marketInfo" class="mdp-price-badge" :class="{ paid: isPaidItem }">{{ priceBadge }}</text>
          </view>
          <view class="mdp-byline">
            <text v-if="display.author" class="mdp-byline-item mdp-author">{{ display.author }}</text>
            <text v-if="display.version" class="mdp-byline-item">v{{ display.version }}</text>
            <text v-if="display.downloads" class="mdp-byline-item">{{ $t('market.downloadsCount', { count: display.downloads }) }}</text>
            <text v-if="display.categoryLabel" class="mdp-byline-item">{{ display.categoryLabel }}</text>
          </view>
          <!-- 性质说明：Skill 与插件是两种东西，用户不该靠猜 -->
          <text class="mdp-kind-note">{{ kindNote }}</text>
          <text v-if="display.description" class="mdp-summary">{{ display.description }}</text>

          <!-- 动作区 -->
          <view class="mdp-actions">
            <!-- 付费未购（Skill 与插件同款）：先去官网买，本机不给安装按钮——点了也只会拿到 402 -->
            <template v-if="marketInfo && !installedInfo && needsPurchase">
              <view class="mdp-btn primary" @tap="openPurchase">
                <text>{{ $t('market.buyWithPrice', { price: priceText }) }}</text>
              </view>
              <view v-if="paidStateValue === 'need-account'" class="mdp-btn" @tap="goToAccountSettings">
                <text>{{ $t('market.goConnectAccount') }}</text>
              </view>
              <view v-else class="mdp-btn" :class="{ busy }" @tap="onPurchasedRefresh">
                <text>{{ busy ? $t('market.refreshing') : $t('market.alreadyPurchasedRefresh') }}</text>
              </view>
            </template>

            <!-- Skill：安装 / 更新 / 卸载 + 生效方式三档 -->
            <template v-if="spec.kind === 'skill'">
              <view
                v-if="marketInfo && !installedInfo && !needsPurchase"
                class="mdp-btn primary"
                :class="{ busy }"
                @tap="doInstallSkill"
              >
                <text>{{ busy ? $t('market.installingEllipsis') : $t('market.install') }}</text>
              </view>
              <view
                v-else-if="marketInfo && installedInfo"
                class="mdp-btn"
                :class="{ busy }"
                @tap="doInstallSkill"
              >
                <text>{{ busy ? $t('market.processingEllipsis') : $t('market.reinstallOrUpdate') }}</text>
              </view>
              <!-- 面板型 skill（背后挂着左栏面板）按插件呈现：只有启用/停用。
                   「生效方式三档」是对话型 skill 的概念，对面板讲不通——设成 manual
                   会让面板里的 kick-off 按钮点了没反应。判据见 leftSidebarPlugins.js。
                   挂着原生资源包（packId 非空）的再加一层：包没就绪前不给开关，
                   见 docs/NATIVE_PACK_DISTRIBUTION.md §4.3/§7.1。 -->
              <template v-if="installedInfo && !installedInfo.sourcePluginId && isPanel">
                <text v-if="packRevokedState" class="mdp-pack-revoked">{{ $t('market.packRevokedNotice') }}</text>
                <view v-else-if="packId && packDownloading" class="mdp-pack-progress">
                  <text>{{ packProgressText }}</text>
                </view>
                <template v-else-if="packId && packFailed">
                  <text class="mdp-pack-error">{{ (packStatusInfo && packStatusInfo.error) || $t('market.packInstallFailedShort') }}</text>
                  <view class="mdp-btn" :class="{ busy: packBusy }" @tap="doInstallPack">
                    <text>{{ $t('common.retry') }}</text>
                  </view>
                </template>
                <view v-else-if="packId && !packReady" class="mdp-btn primary" :class="{ busy: packBusy }" @tap="doInstallPack">
                  <text>{{ packBusy ? $t('market.installingEllipsis') : packInstallLabel }}</text>
                </view>
                <view v-else class="mdp-switch-row">
                  <text class="mdp-switch-label">{{ installedInfo.enabled ? $t('market.enabledTag') : $t('market.disabledTag') }}</text>
                  <AwdSwitch :checked="!!installedInfo.enabled" @change="onPanelSkillToggle" />
                </view>
              </template>
              <AwdSelect
                v-else-if="installedInfo && !installedInfo.sourcePluginId"
                :range="ACTIVATION_LABELS"
                :value="activationIndex"
                @change="onActivationChange"
              >
                <view class="mdp-btn">
                  <text>{{ $t('market.activationModeLabel', { mode: ACTIVATION_LABELS[activationIndex] }) }}</text>
                </view>
              </AwdSelect>
              <text v-if="installedInfo && installedInfo.sourcePluginId" class="mdp-action-hint">{{ $t('market.activationHintSourcePlugin') }}</text>
              <view
                v-if="installedInfo && marketInfo && !installedInfo.sourcePluginId"
                class="mdp-btn danger"
                :class="{ busy }"
                @tap="doUninstallSkill"
              >
                <text>{{ $t('market.uninstallBtn') }}</text>
              </view>
              <view
                v-else-if="installedInfo && packId && !installedInfo.sourcePluginId"
                class="mdp-btn danger"
                :class="{ busy }"
                @tap="doUninstallPack"
              >
                <text>{{ $t('market.uninstallBtn') }}</text>
              </view>
            </template>

            <!-- 插件：安装（带权限确认）/ 启停 / 卸载 -->
            <template v-else>
              <view
                v-if="marketInfo && !installedInfo && !needsPurchase"
                class="mdp-btn primary"
                :class="{ busy }"
                @tap="doInstallPlugin"
              >
                <text>{{ busy ? $t('market.installingEllipsis') : $t('market.install') }}</text>
              </view>
              <view v-if="installedInfo" class="mdp-switch-row">
                <text class="mdp-switch-label">{{ installedInfo.enabled ? $t('market.enabledTag') : $t('market.disabledTag') }}</text>
                <AwdSwitch :checked="!!installedInfo.enabled" @change="onPluginToggle" />
              </view>
              <view
                v-if="installedInfo && marketInfo"
                class="mdp-btn danger"
                :class="{ busy }"
                @tap="doUninstallPlugin"
              >
                <text>{{ $t('market.uninstallBtn') }}</text>
              </view>
            </template>
          </view>

          <text v-if="purchaseHint" class="mdp-purchase-hint">{{ purchaseHint }}</text>
        </view>
      </view>

      <view class="mdp-divider"></view>

      <view v-if="loading" class="mdp-loading"><text>{{ $t('market.loadingEllipsis') }}</text></view>
      <view v-else-if="!marketInfo && !installedInfo" class="mdp-loading">
        <text>{{ loadError ? $t('market.loadFailedPrefix', { error: loadError }) : $t('market.itemNotFound') }}</text>
      </view>

      <template v-else>
        <!-- 适用场景（Skill）：把触发词翻译成「什么时候找它」，工具清单不在这里刷存在感 -->
        <view v-if="spec.kind === 'skill' && triggerList.length" class="mdp-section">
          <text class="mdp-sec-title">{{ $t('market.whenToUse') }}</text>
          <view class="mdp-scenario">
            <text class="mdp-scenario-lead">{{ $t('market.scenarioLead') }}</text>
            <view class="mdp-triggers">
              <text v-for="(t, i) in triggerList" :key="i" class="mdp-trigger">{{ $t('market.triggerWrap', { trigger: t }) }}</text>
            </view>
            <text class="mdp-sec-note">{{ $t('market.scenarioNote') }}</text>
          </view>
        </view>

        <!-- 声明能力（插件）：安全相关，保持显眼 -->
        <view v-if="spec.kind === 'plugin'" class="mdp-section">
          <text class="mdp-sec-title">{{ $t('market.declaredCapability') }}</text>
          <text class="mdp-sec-body">{{ pluginPermissionText }}</text>
          <text class="mdp-sec-note">{{ $t('market.pluginPermissionNote') }}</text>
        </view>

        <!-- 插件设置（规范 v2.9 P4）：manifest.settings 声明的配置项，写入只经这张表单 -->
        <view v-if="spec.kind === 'plugin' && installedInfo && pluginSettings.length" class="mdp-section">
          <text class="mdp-sec-title">{{ $t('market.pluginSettingsTitle') }}</text>
          <view v-for="s in pluginSettings" :key="s.key" class="mdp-set-row">
            <view class="mdp-set-head">
              <text class="mdp-set-label">{{ s.label || s.key }}</text>
              <AwdSwitch
                v-if="s.type === 'boolean'"
                :checked="settingsDraft[s.key] === 'true'"
                @change="v => { settingsDraft[s.key] = v ? 'true' : 'false' }"
              />
            </view>
            <view v-if="s.type === 'select'" class="mdp-set-options">
              <text
                v-for="o in (s.options || [])"
                :key="o"
                class="mdp-set-opt"
                :class="{ on: settingsDraft[s.key] === o }"
                @tap="settingsDraft[s.key] = o"
              >{{ o }}</text>
            </view>
            <input
              v-else-if="s.type !== 'boolean'"
              class="mdp-set-input"
              :password="!!s.secret"
              v-model="settingsDraft[s.key]"
              :placeholder="s.description || ''"
            />
            <text v-if="s.description && (s.type === 'boolean' || s.type === 'select')" class="mdp-sec-note">{{ s.description }}</text>
          </view>
          <view class="mdp-btn primary mdp-set-save" :class="{ busy: settingsBusy }" @tap="doSaveSettings">
            <text>{{ settingsBusy ? $t('market.savingEllipsis') : $t('market.saveSettings') }}</text>
          </view>
        </view>

        <!-- 样式画像（规范 v2.9 P4）：插件贡献的画像可设为全局默认（写端导出走它） -->
        <view v-if="spec.kind === 'plugin' && installedInfo && ownStyleProfiles.length" class="mdp-section">
          <text class="mdp-sec-title">{{ $t('market.styleProfilesTitle') }}</text>
          <view v-for="p in ownStyleProfiles" :key="p.id" class="mdp-kv-row">
            <text class="mdp-k">{{ p.name || p.id }}</text>
            <text class="mdp-v mdp-link" @tap="toggleStyleProfile(p)">
              {{ p.selected ? $t('market.profileSelected') : $t('market.profileSelect') }}
            </text>
          </view>
          <text class="mdp-sec-note">{{ $t('market.styleProfileNote') }}</text>
        </view>

        <!-- 详细信息：工具权限压缩为一行人话摘要，不再枚举内部工具名 -->
        <view class="mdp-section">
          <text class="mdp-sec-title">{{ $t('market.detailInfo') }}</text>
          <view class="mdp-kv">
            <view v-if="spec.kind === 'skill' && toolSummary" class="mdp-kv-row">
              <text class="mdp-k">{{ $t('market.kvToolPermission') }}</text><text class="mdp-v">{{ toolSummary }}</text>
            </view>
            <view v-if="marketInfo" class="mdp-kv-row">
              <text class="mdp-k">{{ $t('market.kvPrice') }}</text><text class="mdp-v">{{ priceDetailText }}</text>
            </view>
            <view class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvId') }}</text><text class="mdp-v mono">{{ spec.id }}</text></view>
            <view v-if="display.version" class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvVersion') }}</text><text class="mdp-v">v{{ display.version }}</text></view>
            <view v-if="display.author" class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvAuthor') }}</text><text class="mdp-v">{{ display.author }}</text></view>
            <view v-if="display.license" class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvLicense') }}</text><text class="mdp-v">{{ display.license }}</text></view>
            <view v-if="display.updatedAt" class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvUpdatedAt') }}</text><text class="mdp-v">{{ display.updatedAt }}</text></view>
            <view class="mdp-kv-row"><text class="mdp-k">{{ $t('market.kvSource') }}</text><text class="mdp-v">{{ sourceText }}</text></view>
            <view v-if="display.homepage" class="mdp-kv-row">
              <text class="mdp-k">{{ $t('market.kvHomepage') }}</text>
              <text class="mdp-v mdp-link" @tap="$emit('open-url', display.homepage)">{{ display.homepage }}</text>
            </view>
            <!-- 第三方内容署名（如随 skill 分发的 vendor 引擎）：MIT 等许可要求保留的版权声明 -->
            <view v-for="(c, ci) in display.credits" :key="ci" class="mdp-kv-row">
              <text class="mdp-k">{{ $t('market.kvCredits') }}</text><text class="mdp-v">{{ c }}</text>
            </view>
          </view>
        </view>
      </template>
    </view>
  </scroll-view>
</template>

<script>
// 插件广场详情 tab（VS Code 扩展详情页形态）。spec = { kind: 'skill'|'plugin', id, name }
// 由左栏 MarketSidebarPanel 点行打开。自行拉取市场与已安装两份数据合成视图，
// 装/卸/启停后通过 uni.$emit('awd:market-changed') 通知左栏刷新。
import { getPlugins, getSkills, getSkillMarket, getPluginMarket, installMarketSkill, uninstallMarketSkill, installMarketPlugin, uninstallMarketPlugin, setPluginEnabled, setSkillActivation, packStatus, packInfo, packInstall, packUninstall, getPluginSettings, savePluginSettings, getContributedStyleProfiles, selectContributedStyleProfile } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { isPanelSkill, buildVoiceGroupSkill } from '@/config/leftSidebarPlugins.js'
import { formatPrice, isPaid, paidState, priceCentsOf, priceLabel, purchaseUrl } from '@/utils/marketPricing.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { t } from '@/i18n'
import AwdSelect from '@/components/AwdSelect.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'

const CATEGORY_GLYPHS = {
  contract: ICONS.catContract,
  litigation: ICONS.catLitigation,
  compliance: ICONS.catCompliance,
  research: ICONS.catResearch,
  corporate: ICONS.catCorporate,
  office: ICONS.catOffice,
  other: ICONS.catOther,
}

const CATEGORY_LABELS = {
  contract: t('market.catContract'),
  litigation: t('market.catLitigation'),
  compliance: t('market.catCompliance'),
  research: t('market.catResearch'),
  corporate: t('market.catCorporate'),
  office: t('market.catOffice'),
  other: t('market.catOther'),
}

const PERMISSION_LABELS = {
  file_read: t('market.permFileRead'),
  file_write: t('market.permFileWrite'),
  network: t('market.permNetwork'),
  editor: t('market.permEditor'),
}

const ACTIVATION_MODES = ['auto', 'manual', 'disabled']
const ACTIVATION_LABELS = [t('market.activationAuto'), t('market.activationManual'), t('market.activationDisabled')]

export default {
  name: 'MarketDetailPane',
  components: { AwdSelect, AwdSwitch },
  props: {
    spec: {
      type: Object,
      required: true,
    },
  },
  emits: ['open-url'],
  data() {
    return {
      loading: true,
      loadError: '',
      marketInfo: null,
      installedInfo: null,
      busy: false,
      // 是否已连接官网账户；随广场列表响应下发（付费未购项据此在「购买」与「需连接账户」之间选）
      accountConnected: false,
      // 原生资源包（native pack）状态，见 docs/NATIVE_PACK_DISTRIBUTION.md §4.3
      packMeta: null,       // packInfo() 结果 {latestVersion, totalSize}，懒加载
      packStatusInfo: null, // packStatus() 结果 {state, bytesDownloaded, bytesTotal, error}
      packBusy: false,
      packTimer: null,
      // 声明式贡献点（规范 v2.9 P4）
      pluginSettings: [],   // manifest.settings 声明 + 当前值（secret 已掩码）
      settingsDraft: {},    // 表单草稿；secret 项只保存被用户改过的（掩码值原样 = 未改）
      settingsBusy: false,
      ownStyleProfiles: [], // 本插件贡献的样式画像 + 是否被选为全局默认
    }
  },
  computed: {
    ACTIVATION_LABELS() {
      return ACTIVATION_LABELS
    },
    isPaidItem() {
      return isPaid(this.marketInfo)
    },
    /** 'free' | 'purchased' | 'buy' | 'need-account'；marketInfo 为空（本机项）按免费 */
    paidStateValue() {
      return paidState(this.marketInfo, this.accountConnected)
    },
    /** 付费且未购：动作区换成购买引导，不给安装按钮 */
    needsPurchase() {
      return this.paidStateValue === 'buy' || this.paidStateValue === 'need-account'
    },
    /** 标题行徽章：免费 / ¥xx.xx / 已购买 */
    priceBadge() {
      return priceLabel(this.marketInfo)
    },
    priceText() {
      return formatPrice(priceCentsOf(this.marketInfo))
    },
    priceDetailText() {
      if (!this.isPaidItem) return this.$t('market.free')
      const base = this.priceText + this.$t('market.onetimePurchase')
      return this.marketInfo && this.marketInfo.purchased ? base + this.$t('market.purchasedSuffix') : base
    },
    purchaseHint() {
      if (!this.needsPurchase) return ''
      if (this.paidStateValue === 'need-account') {
        return this.$t('market.needAccountHint')
      }
      return this.$t('market.buyHint')
    },
    headGlyph() {
      if (this.spec.kind === 'plugin') return ICONS.blocks
      const cat = (this.marketInfo?.category || this.installedInfo?.category) || 'other'
      return CATEGORY_GLYPHS[cat] || CATEGORY_GLYPHS.other
    },
    display() {
      const m = this.marketInfo || {}
      const i = this.installedInfo || {}
      const downloads = m.downloads
      const cat = m.category || i.category
      return {
        name: m.name || i.name || this.spec.name || this.spec.id,
        description: m.description || i.description || '',
        author: m.authorDisplayName || m.author || i.author || '',
        version: m.version || i.version || '',
        license: m.license || i.license || '',
        // credits（第三方引擎署名）目前只有本机 skill 会带；官网 registry 契约暂未收录该字段
        credits: i.credits || m.credits || [],
        downloads: downloads ? (downloads >= 10000 ? (downloads / 10000).toFixed(1) + 'w' : downloads >= 1000 ? (downloads / 1000).toFixed(1) + 'k' : String(downloads)) : '',
        categoryLabel: this.spec.kind === 'skill' && cat ? (CATEGORY_LABELS[cat] || this.$t('market.catOther')) : '',
        updatedAt: m.updatedAt ? String(m.updatedAt).slice(0, 10) : '',
        homepage: m.homepage || '',
      }
    },
    triggerList() {
      return this.marketInfo?.triggers || this.installedInfo?.triggers || []
    },
    /** Skill 与插件是两种东西：一句话讲清性质，别让用户靠徽章猜 */
    kindNote() {
      if (this.spec.kind === 'plugin') {
        return this.$t('market.kindNotePlugin')
      }
      if (this.isPanel) return this.$t('market.panelPluginHint')
      return this.$t('market.kindNoteSkill')
    },
    /** 工具名是给机器看的；给用户压缩成能力域的人话摘要 */
    toolSummary() {
      const item = this.marketInfo || this.installedInfo || {}
      const tools = item.allowedTools || []
      if (!tools.length) return ''
      const domains = []
      const has = (re) => tools.some(t => re.test(t))
      if (has(/^doc_|^read_document|^write_docx|_docx$/)) domains.push(this.$t('market.domainDoc'))
      if (has(/^sheet_/)) domains.push(this.$t('market.domainSheet'))
      if (has(/^pptx_/)) domains.push(this.$t('market.domainSlides'))
      if (has(/^pdf_/)) domains.push(this.$t('market.domainPdf'))
      if (has(/^law_|^get_law_/)) domains.push(this.$t('market.domainLaw'))
      if (has(/search_web|browse_url|deep_search/)) domains.push(this.$t('market.domainWeb'))
      if (has(/^read_file$|^write_file$|^list_files$|project_files|extract_file_text/)) domains.push(this.$t('market.domainFiles'))
      if (has(/memory/)) domains.push(this.$t('market.domainMemory'))
      if (has(/evidence/)) domains.push(this.$t('market.domainEvidence'))
      const head = domains.length ? domains.join(this.$t('market.listSeparator')) : this.$t('market.domainGeneral')
      return this.$t('market.toolSummaryText', { domains: head, count: tools.length })
    },
    pluginPermissionText() {
      const item = this.marketInfo || this.installedInfo || {}
      const parts = []
      const toolCount = item.toolCount != null ? item.toolCount : (item.tools || []).length
      if (toolCount) parts.push(this.$t('market.providesTools', { count: toolCount }))
      const perms = (item.permissions || []).map(p => PERMISSION_LABELS[p] || p)
      parts.push(perms.length ? this.$t('market.requiresPerms', { perms: perms.join(this.$t('market.listSeparator')) }) : this.$t('market.noSensitiveCapability'))
      return parts.join(' · ')
    },
    activationIndex() {
      const s = this.installedInfo
      if (!s) return 0
      const mode = s.activationMode || (s.enabled ? 'auto' : 'disabled')
      const idx = ACTIVATION_MODES.indexOf(mode)
      return idx >= 0 ? idx : 0
    },
    /** 背后挂着左栏面板的 skill：详情页也按插件呈现（开关，而不是生效方式三档）。
        「语音」合并插件条目（spec.group）同理——它就是一个面板。 */
    isPanel() {
      return this.spec && this.spec.kind === 'skill' && (this.spec.group || isPanelSkill(this.spec.id))
    },
    /** 该 skill 挂着的原生资源包 id；来自 /api/skills/list 的 packId 字段，没有就是普通 skill */
    packId() {
      return (this.installedInfo && this.installedInfo.packId) || (this.marketInfo && this.marketInfo.packId) || null
    },
    packReady() {
      return !!(this.installedInfo && this.installedInfo.packReady)
    },
    packDownloading() {
      const state = this.packStatusInfo && this.packStatusInfo.state
      return state === 'downloading' || state === 'verifying' || state === 'installing'
    },
    packFailed() {
      return !!this.packStatusInfo && this.packStatusInfo.state === 'failed'
    },
    packRevokedState() {
      return !!this.packStatusInfo && this.packStatusInfo.state === 'revoked'
    },
    /** 资源包体积（MB，一位小数），来自懒加载的 packInfo 或已有的 status 快照；两边都没有就留空 */
    packSizeMB() {
      const bytes = (this.packMeta && this.packMeta.totalSize) || (this.packStatusInfo && this.packStatusInfo.bytesTotal) || 0
      if (!bytes) return ''
      return (bytes / (1024 * 1024)).toFixed(1)
    },
    packInstallLabel() {
      return this.packSizeMB
        ? this.$t('market.installNeedsPackSized', { size: this.packSizeMB })
        : this.$t('market.installNeedsPackNoSize')
    },
    packProgressText() {
      const s = this.packStatusInfo
      if (!s) return ''
      const total = s.bytesTotal || 0
      if (total > 0) {
        return this.$t('market.packDownloadingProgress', {
          downloaded: ((s.bytesDownloaded || 0) / (1024 * 1024)).toFixed(1),
          total: (total / (1024 * 1024)).toFixed(1),
        })
      }
      return this.$t('market.packDownloadingEllipsis')
    },
    uninstallPackHint() {
      return this.packSizeMB
        ? this.$t('market.uninstallPackConfirmSized', { size: this.packSizeMB })
        : this.$t('market.uninstallPackConfirmPlain')
    },
    sourceText() {
      if (this.installedInfo?.sourcePluginId) return this.$t('market.sourceBuiltinPlugin', { id: this.installedInfo.sourcePluginId })
      if (this.marketInfo) return this.$t('market.sourceOfficialMarket')
      return this.$t('market.sourceLocal')
    },
  },
  mounted() {
    this.reload()
    uni.$on('awd:market-changed-from-sidebar', this.reload)
  },
  beforeUnmount() {
    uni.$off('awd:market-changed-from-sidebar', this.reload)
    this.stopPackPoll()
  },
  methods: {
    async reload() {
      this.loading = true
      this.loadError = ''
      // 广场不可达要如实说：本页没有市场数据时按钮区是空的，若再不给原因，
      // 用户看到的就是「有标题、没价格、没按钮」的哑页面——尤其付费项本来也没有安装按钮
      let marketError = null
      const keepMarketError = (e) => { marketError = e; return null }
      try {
        if (this.spec.group) {
          // 「语音」合并插件（dev-board#66）：本机成员 skill 合成一个视图。
          // 'voice' 不是 registry 条目，不去在线广场查——marketInfo 恒空，
          // 动作区因此只剩启停开关（没有安装/卸载，内置插件本就不可卸载）。
          const iRes = await getSkills().catch(() => null)
          const installedList = Array.isArray(iRes) ? iRes : (iRes?.data || [])
          this.marketInfo = null
          this.installedInfo = buildVoiceGroupSkill(installedList)
        } else if (this.spec.kind === 'skill') {
          const [mRes, iRes] = await Promise.all([
            getSkillMarket().catch(keepMarketError),
            getSkills().catch(() => null),
          ])
          const marketList = mRes?.skills || []
          const installedList = Array.isArray(iRes) ? iRes : (iRes?.data || [])
          if (typeof mRes?.accountConnected === 'boolean') this.accountConnected = mRes.accountConnected
          this.marketInfo = marketList.find(s => s.id === this.spec.id) || null
          this.installedInfo = installedList.find(s => s.id === this.spec.id) || null
        } else {
          const [mRes, iRes] = await Promise.all([
            getPluginMarket().catch(keepMarketError),
            getPlugins().catch(() => null),
          ])
          const marketList = mRes?.plugins || []
          const installedList = Array.isArray(iRes) ? iRes : (iRes?.data || [])
          if (typeof mRes?.accountConnected === 'boolean') this.accountConnected = mRes.accountConnected
          this.marketInfo = marketList.find(p => p.id === this.spec.id) || null
          this.installedInfo = installedList.find(p => p.id === this.spec.id) || null
        }
        // 本机已装的项即使广场挂了也照常展示（信息来自本地），不必报错打扰
        if (!this.marketInfo && !this.installedInfo && marketError) {
          this.loadError = marketError.message || this.$t('market.marketUnavailableShort')
        }
      } catch (e) {
        console.error('加载详情失败:', e)
        this.loadError = e?.message || this.$t('market.networkUnavailable')
      } finally {
        this.loading = false
      }
      // 声明式贡献点（规范 v2.9）：设置表单与画像清单随详情一起拉
      await this.loadContribution()
      // 挂着资源包的面板型 skill：拉一次现状——可能是用户上次没装完，也可能是
      // 老版本升级后端自动补下载中，两种都要接着轮询而不是回到「安装」按钮
      if (this.packId) {
        await this.refreshPackStatus()
        const state = this.packStatusInfo && this.packStatusInfo.state
        if (state === 'downloading' || state === 'verifying' || state === 'installing') {
          this.packBusy = true
          this.startPackPoll()
        } else if (!this.packReady && state !== 'revoked' && !this.packMeta) {
          this.loadPackMetaLazy()
        }
      } else {
        this.stopPackPoll()
      }
    },
    notifyChanged() {
      uni.$emit('awd:market-changed')
    },

    // ---- 声明式贡献点（规范 v2.9 P4）：设置表单 + 样式画像 ----

    async loadContribution() {
      if (this.spec.kind !== 'plugin' || !this.installedInfo) {
        this.pluginSettings = []
        this.ownStyleProfiles = []
        return
      }
      try {
        const res = await getPluginSettings(this.spec.id)
        const body = res && res.settings !== undefined ? res : (res && res.data) || {}
        this.pluginSettings = Array.isArray(body.settings) ? body.settings : []
        const draft = {}
        this.pluginSettings.forEach(s => { draft[s.key] = s.value == null ? '' : String(s.value) })
        this.settingsDraft = draft
      } catch (e) {
        this.pluginSettings = []
      }
      try {
        const res = await getContributedStyleProfiles()
        const body = res && res.profiles !== undefined ? res : (res && res.data) || {}
        const all = Array.isArray(body.profiles) ? body.profiles : []
        this.ownStyleProfiles = all.filter(p => p.pluginId === this.spec.id)
      } catch (e) {
        this.ownStyleProfiles = []
      }
    },

    async doSaveSettings() {
      if (this.settingsBusy) return
      this.settingsBusy = true
      try {
        const values = {}
        this.pluginSettings.forEach(s => {
          const v = this.settingsDraft[s.key]
          // secret 项的掩码回显（****xxxx）原样未动 = 用户没改，不回写
          if (s.secret && v === s.value) return
          values[s.key] = v == null ? '' : String(v)
        })
        const res = await savePluginSettings(this.spec.id, values)
        const body = res && res.code !== undefined ? res : (res && res.data) || {}
        if (body.code !== 0) throw new Error(body.message || this.$t('market.saveFailed'))
        uni.showToast({ title: this.$t('market.settingsSaved'), icon: 'none' })
        // 通知打开中的插件面板（PluginPane 只转发给设置所属的插件）
        uni.$emit('awd:plugin-settings-changed', { pluginId: this.spec.id })
        await this.loadContribution()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('market.saveFailed'), icon: 'none' })
      } finally {
        this.settingsBusy = false
      }
    },

    async toggleStyleProfile(p) {
      try {
        const ref = p.selected ? '' : (p.pluginId + ':' + p.id)
        const res = await selectContributedStyleProfile(ref)
        const body = res && res.code !== undefined ? res : (res && res.data) || {}
        if (body.code !== 0) throw new Error(body.message || this.$t('market.saveFailed'))
        await this.loadContribution()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('market.saveFailed'), icon: 'none' })
      }
    },
    // ---- 原生资源包（native pack）：见 docs/NATIVE_PACK_DISTRIBUTION.md §4.3 ----
    async loadPackMetaLazy() {
      if (this.packMeta || !this.packId) return
      try {
        const res = await packInfo(this.packId)
        if (res) this.packMeta = res
      } catch (e) {
        // 静默失败：安装按钮文案退化为不带大小的版本
      }
    },
    async refreshPackStatus() {
      if (!this.packId) return
      try {
        const res = await packStatus(this.packId)
        this.packStatusInfo = (res && res.status) || null
      } catch (e) {
        // 轮询途中网络抖动很常见，不中止——下一拍再试
        return
      }
      const state = this.packStatusInfo && this.packStatusInfo.state
      if (state === 'ready') {
        this.stopPackPoll()
        this.packBusy = false
        if (this.installedInfo) this.installedInfo.packReady = true
        // 到 ready 才走现有 enable 流程；已经启用（如老版本升级自动补下载）则不重复调用
        if (this.installedInfo && !this.installedInfo.enabled) {
          await this.onPanelSkillToggle(true)
        }
      } else if (state === 'failed' || state === 'revoked') {
        this.stopPackPoll()
        this.packBusy = false
      }
    },
    startPackPoll() {
      this.stopPackPoll()
      this.packTimer = setInterval(() => { this.refreshPackStatus() }, 1000)
    },
    stopPackPoll() {
      if (this.packTimer) { clearInterval(this.packTimer); this.packTimer = null }
    },
    async doInstallPack() {
      if (this.packBusy || !this.packId) return
      this.packBusy = true
      this.packStatusInfo = null
      try {
        await packInstall(this.packId)
        await this.refreshPackStatus()
        const state = this.packStatusInfo && this.packStatusInfo.state
        if (state && state !== 'ready' && state !== 'failed') this.startPackPoll()
      } catch (e) {
        this.packBusy = false
        console.error('安装资源包失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      }
    },
    async doUninstallPack() {
      if (this.busy || !this.packId) return
      if (!this.packMeta) await this.loadPackMetaLazy()
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('market.confirmUninstallPackTitle'),
          content: this.uninstallPackHint,
          confirmText: this.$t('market.uninstallBtn'),
          cancelText: this.$t('market.cancelBtn'),
          success: r => resolve(r.confirm),
          fail: () => resolve(false),
        })
      })
      if (!ok) return
      this.busy = true
      try {
        // 先走现有停用流程，再删资源包目录（§6：卸载 = 停用 + 删 packs/<id>/）
        await setSkillActivation(this.spec.id, 'disabled')
        await packUninstall(this.packId)
        this.stopPackPoll()
        this.packStatusInfo = null
        uni.showToast({ title: this.$t('market.uninstalledToast'), icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('卸载资源包失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.uninstallFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 购买走系统浏览器：支付要用用户已登录的浏览器会话，内嵌 tab 里付不了
    openPurchase() {
      openExternalUrl(purchaseUrl(this.spec.kind, this.spec.id))
    },
    goToAccountSettings() {
      uni.navigateTo({ url: '/pages/admin/admin?nav=account' })
    },
    /**
     * 「我已购买，刷新」：先让后端同步一次官网权益（本地缓存不刷新是看不到刚买的东西的），
     * 再重拉广场；确认已购就顺手把安装接上，省得用户再点一次。
     */
    async onPurchasedRefresh() {
      if (this.busy) return
      this.busy = true
      try {
        await refreshEntitlements(true)
        await this.reload()
        this.notifyChanged()
      } finally {
        this.busy = false
      }
      if (this.needsPurchase) {
        uni.showToast({ title: this.$t('market.purchaseNotFoundHint'), icon: 'none' })
        return
      }
      if (this.installedInfo) return
      if (this.spec.kind === 'skill') await this.doInstallSkill()
      else await this.doInstallPlugin()
    },
    async doInstallSkill() {
      if (this.busy) return
      this.busy = true
      try {
        await installMarketSkill(this.spec.id)
        uni.showToast({ title: this.installedInfo ? this.$t('market.updatedToast') : this.$t('market.genericInstalledToast'), icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doUninstallSkill() {
      if (this.busy) return
      this.busy = true
      try {
        await uninstallMarketSkill(this.spec.id)
        uni.showToast({ title: this.$t('market.uninstalledToast'), icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('卸载 Skill 失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.uninstallFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doInstallPlugin() {
      if (this.busy) return
      const m = this.marketInfo || {}
      const perms = (m.permissions || []).map(p => PERMISSION_LABELS[p] || p).join(this.$t('market.listSeparator')) || this.$t('market.noSensitiveCapability')
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('market.confirmInstallPluginTitle'),
          content: this.$t('market.confirmInstallPluginContent', {
            name: m.name || this.spec.id,
            version: m.version,
            author: m.authorDisplayName || m.author || this.$t('market.unknownAuthor'),
            perms,
          }),
          confirmText: this.$t('market.install'),
          cancelText: this.$t('market.cancelBtn'),
          success: r => resolve(r.confirm),
          fail: () => resolve(false),
        })
      })
      if (!ok) return
      this.busy = true
      try {
        await installMarketPlugin(this.spec.id)
        uni.showToast({ title: this.$t('market.installedUsableHint'), icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doUninstallPlugin() {
      if (this.busy) return
      this.busy = true
      try {
        await uninstallMarketPlugin(this.spec.id)
        uni.showToast({ title: this.$t('market.uninstalledToast'), icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('卸载插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.uninstallFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // AwdSwitch 直接抛布尔值
    async onPluginToggle(enabled) {
      try {
        await setPluginEnabled(this.spec.id, enabled)
        if (this.installedInfo) this.installedInfo.enabled = enabled
        uni.showToast({ title: enabled ? this.$t('market.enabledToast') : this.$t('market.disabledToggleToast'), icon: 'none' })
        this.notifyChanged()
      } catch (e) {
        console.error('切换插件状态失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        await this.reload()
      }
    },
    // AwdSelect 直接抛下标
    // 面板型：开 = auto（面板 kick-off prompt 要靠触发词命中），关 = disabled。
    // 「语音」合并插件一次作用于全部成员 skill（启停一体，dev-board#66）。
    async onPanelSkillToggle(enabled) {
      const mode = enabled ? 'auto' : 'disabled'
      const ids = (this.installedInfo && this.installedInfo.groupMemberIds) || [this.spec.id]
      try {
        for (const id of ids) {
          await setSkillActivation(id, mode)
        }
        if (this.installedInfo) {
          this.installedInfo.activationMode = mode
          this.installedInfo.enabled = enabled
        }
        uni.showToast({ title: enabled ? this.$t('market.enabledToast') : this.$t('market.disabledToggleToast'), icon: 'none' })
        this.notifyChanged()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        this.reload()
      }
    },
    async onActivationChange(idx) {
      const mode = ACTIVATION_MODES[Number(idx)]
      if (!mode || !this.installedInfo || mode === ACTIVATION_MODES[this.activationIndex]) return
      try {
        await setSkillActivation(this.spec.id, mode)
        this.installedInfo.activationMode = mode
        this.installedInfo.enabled = mode !== 'disabled'
        uni.showToast({ title: this.$t('market.setActivationTo', { mode: ACTIVATION_LABELS[idx] }), icon: 'none' })
        this.notifyChanged()
      } catch (e) {
        console.error('设置生效方式失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        await this.reload()
      }
    },
  },
}
</script>

<style lang="scss" scoped>
/* VS Code 扩展详情页结构 + 产品浅色编辑排版（衬线标题按官网 DESIGN.md 只用于展示位） */
.mdp {
  width: 100%;
  height: 100%;
  background: var(--awd-surface);
}

.mdp-inner {
  max-width: 760px;
  padding: 28px 36px 48px;
}

.mdp-head {
  display: flex;
  gap: 18px;
  align-items: flex-start;
}

.mdp-glyph {
  width: 72px;
  height: 72px;
  flex-shrink: 0;
  border-radius: 14px;
  background: var(--awd-accent-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--awd-accent-text);

  svg {
    width: 34px;
    height: 34px;
  }

  /* 插件 = 可执行扩展：深底，与 Skill（浅底）一眼区分（同左栏列表约定） */
  &.is-plugin {
    background: var(--awd-accent-hover);
    color: var(--awd-text-on-accent);
  }
}

.mdp-head-main {
  flex: 1;
  min-width: 0;
}

.mdp-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mdp-title {
  font-family: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', 'STSong', serif;
  font-size: 22px;
  font-weight: 700;
  color: var(--awd-text);
  line-height: 1.25;
}

.mdp-kind-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
  border-radius: 4px;
  padding: 1px 6px;
}

.mdp-installed-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-radius: 4px;
  padding: 1px 6px;
}

/* 价格徽章：免费用中性灰，付费用暖色（与「试用版」chip 同族），不做成促销红 */
.mdp-price-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);
  border-radius: 4px;
  padding: 1px 6px;

  &.paid {
    color: var(--awd-warning-text);
    background: var(--awd-bg);
  }
}

.mdp-purchase-hint {
  display: block;
  margin-top: 8px;
  font-size: 11px;
  line-height: 17px;
  color: var(--awd-warning-text);
}

.mdp-byline {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.mdp-byline-item {
  font-size: 12px;
  color: var(--awd-text-2);

  & + .mdp-byline-item::before {
    content: '·';
    margin-right: 6px;
    color: var(--awd-text-3);
  }
}

.mdp-author {
  color: var(--awd-accent-text);
  font-weight: 600;
}

.mdp-kind-note {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  line-height: 16px;
  color: var(--awd-text-2);
}

.mdp-summary {
  display: block;
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--awd-text);
}

.mdp-scenario {
  margin-top: 10px;
}

.mdp-scenario-lead {
  display: block;
  font-size: 12px;
  color: var(--awd-text-2);
  margin-bottom: 6px;
}

.mdp-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.mdp-btn {
  height: 28px;
  line-height: 26px;
  padding: 0 14px;
  border-radius: 6px;
  border: 1px solid var(--awd-border-strong);
  background: var(--awd-surface);
  cursor: pointer;

  text {
    font-size: 12px;
    font-weight: 600;
    color: var(--awd-text);
  }

  &:hover {
    border-color: var(--awd-accent);

    text {
      color: var(--awd-accent-text);
    }
  }

  &.primary {
    background: var(--awd-accent);
    border-color: var(--awd-accent);

    text {
      color: var(--awd-text-on-accent);
    }

    &:hover {
      background: var(--awd-accent-hover);

      text {
        color: var(--awd-text-on-accent);
      }
    }
  }

  &.danger:hover {
    border-color: var(--awd-danger);

    text {
      color: var(--awd-danger-text);
    }
  }

  &.busy {
    opacity: 0.6;
    pointer-events: none;
  }
}

.mdp-switch-row {
  display: flex;
  align-items: center;
  gap: 2px;
}

.mdp-switch-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.mdp-action-hint {
  font-size: 11px;
  color: var(--awd-text-3);
}

/* 原生资源包状态：下架标红、下载中用中性进度条、失败态错误文案 + 重试按钮 */
.mdp-pack-revoked {
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-danger-text);
}

.mdp-pack-progress {
  height: 28px;
  line-height: 28px;
  padding: 0 12px;
  border-radius: 6px;
  background: var(--awd-surface-2);
  font-size: 12px;
  color: var(--awd-text-2);
}

.mdp-pack-error {
  font-size: 12px;
  color: var(--awd-danger-text);
}

.mdp-divider {
  height: 1px;
  background: var(--awd-surface-3);
  margin: 22px 0 18px;
}

.mdp-loading {
  padding: 24px 0;
  font-size: 13px;
  color: var(--awd-text-2);
}

.mdp-section {
  margin-bottom: 22px;
}

.mdp-sec-title {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: var(--awd-text);
  margin-bottom: 8px;
}

.mdp-sec-body {
  display: block;
  font-size: 13px;
  line-height: 20px;
  color: var(--awd-text);
}

/* 插件设置表单（规范 v2.9 P4） */
.mdp-set-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}
.mdp-set-row:last-of-type { border-bottom: 0; }
.mdp-set-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mdp-set-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
}
.mdp-set-input {
  font-size: 13px;
  padding: 6px 10px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 6px;
  background: var(--awd-surface);
  color: var(--awd-text);
}
.mdp-set-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.mdp-set-opt {
  font-size: 12px;
  padding: 4px 12px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 999px;
  color: var(--awd-text-2);
  cursor: pointer;
}
.mdp-set-opt.on {
  border-color: var(--awd-accent);
  color: var(--awd-accent-text);
  font-weight: 600;
}
.mdp-set-save {
  margin-top: 10px;
  align-self: flex-start;
}

.mdp-sec-note {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  line-height: 17px;
  color: var(--awd-text-3);
}

.mdp-triggers {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.mdp-trigger {
  font-size: 13px;
  color: var(--awd-accent-text);
}

.mdp-kv {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mdp-kv-row {
  display: flex;
  gap: 12px;
}

.mdp-k {
  width: 56px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--awd-text-2);
}

.mdp-v {
  font-size: 12px;
  color: var(--awd-text);
  word-break: break-all;

  &.mono {
    font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  }
}

.mdp-link {
  color: var(--awd-accent-text);
  text-decoration: underline;
  cursor: pointer;
}
</style>

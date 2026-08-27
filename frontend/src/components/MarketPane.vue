<template>
  <view class="market-pane" :class="{ 'is-embedded': !standalone }">
    <!-- Hero：深林绿编辑排版，与官网 /skills 同一套语言（见 aiworkdeckweb/DESIGN.md）。
         嵌入设置页时（standalone=false）由 .is-embedded 样式收成与其余设置分区一致的
         浅色页头，见 <style> 末尾「嵌入态」覆盖块。 -->
    <view class="hero">
      <view class="hero-grain"></view>
      <text class="hero-watermark">{{ $t('market.heroWatermark') }}</text>
      <view class="hero-inner">
        <view class="hero-main">
          <view class="eyebrow">
            <text class="eyebrow-line"></text>
            <text class="eyebrow-text">{{ $t('market.heroEyebrow') }}</text>
          </view>
          <text class="hero-title">{{ $t('market.heroTitle') }}</text>
          <text class="hero-sub">{{ $t('market.heroSub') }}</text>
          <view class="hero-stats">
            <view class="stat">
              <text class="stat-num">{{ marketSkills.length }}</text>
              <text class="stat-label">{{ $t('market.statOnlineSkills') }}</text>
            </view>
            <text class="stat-sep"></text>
            <view class="stat">
              <text class="stat-num">{{ marketPlugins.length }}</text>
              <text class="stat-label">{{ $t('market.statOnlinePlugins') }}</text>
            </view>
            <text class="stat-sep"></text>
            <view class="stat">
              <text class="stat-num">{{ installedCount }}</text>
              <text class="stat-label">{{ $t('market.statInstalledLocal') }}</text>
            </view>
          </view>
        </view>
        <view class="hero-actions">
          <!-- 返回位仅整页（standalone）时渲染；嵌入 workbench 时由外壳的 tab 负责关闭 -->
          <view v-if="standalone" class="btn-ghost" @tap="goBack">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.arrowLeft" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text>{{ $t('market.back') }}</text>
          </view>
          <view class="btn-light" :class="{ 'is-busy': rescanning }" @tap="rescan">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.refresh" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text>{{ rescanning ? $t('market.rescanningLabel') : $t('market.rescan') }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 主页签：编辑式下划线，不用胶囊 -->
    <view class="tab-bar">
      <view class="tab-inner">
        <view
          v-for="t in TABS"
          :key="t.key"
          class="tab-item"
          :class="{ active: activeTab === t.key }"
          @tap="activeTab = t.key"
        >
          <text>{{ t.label }}</text>
          <text class="tab-count">{{ tabCount(t.key) }}</text>
        </view>
      </view>
    </view>

    <scroll-view class="content" scroll-y="true">
      <view class="content-inner">

        <!-- ============ Skill 广场 ============ -->
        <template v-if="activeTab === 'skill'">
          <view class="filter-bar">
            <view class="cat-nav">
              <view
                v-for="c in visibleCategories"
                :key="c.id"
                class="cat-item"
                :class="{ active: activeCategory === c.id }"
                @tap="activeCategory = c.id"
              >
                <text>{{ c.label }}</text>
                <text class="cat-num">{{ c.count }}</text>
              </view>
            </view>
            <view class="search-box">
              <svg class="search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, i) in ICONS.search" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <input class="search-input" v-model="searchText" :placeholder="$t('market.searchSkillPlaceholder')" confirm-type="search" />
            </view>
          </view>

          <view v-if="marketError" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.offline" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ $t('market.marketUnavailableTitle') }}</text>
            <text class="empty-hint">{{ $t('market.marketErrorHint', { error: marketError }) }}</text>
          </view>
          <view v-else-if="filteredMarketSkills.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.search" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ marketLoading ? $t('market.loadingMarket') : (marketSkills.length ? $t('market.noMatchingSkill') : $t('market.marketEmptySkill')) }}</text>
            <text v-if="!marketLoading" class="empty-hint">{{ marketSkills.length ? $t('market.tryOtherKeyword') : $t('market.submitFirstSkill') }}</text>
          </view>

          <view v-else class="card-grid">
            <view v-for="m in filteredMarketSkills" :key="m.id" class="ed-card">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in categoryGlyph(m.category)" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ categoryLabel(m.category) }}</text>
                    <template v-if="m.installed">
                      <text class="kicker-sep"></text>
                      <text class="kicker-on">{{ $t('market.installedTag') }}</text>
                    </template>
                  </view>
                  <text class="card-title">{{ m.name || m.id }}</text>
                  <text class="card-id">{{ m.id }} · v{{ m.version || '1.0.0' }} · {{ priceTag(m) }}</text>
                </view>
                <view class="card-actions">
                  <view
                    v-if="marketAction(m) === 'install'"
                    class="act-primary"
                    :class="{ 'is-busy': !!marketBusyId }"
                    @tap="installSkill(m)"
                  >
                    {{ marketBusyId === m.id ? $t('market.processing') : (m.installed ? $t('market.update') : $t('market.install')) }}
                  </view>
                  <view v-else-if="marketAction(m) === 'buy'" class="act-primary" @tap="openPurchase('skill', m.id)">{{ $t('market.buy') }}</view>
                  <view v-else class="act-primary" @tap="goToAccountSettings">{{ $t('market.needAccount') }}</view>
                  <view v-if="m.installed" class="act-remove" @tap="uninstallSkill(m)">{{ $t('market.uninstallBtn') }}</view>
                </view>
              </view>

              <text class="card-desc">{{ m.description || $t('market.noDescription') }}</text>
              <text v-if="triggerLine(m.triggers)" class="card-triggers">{{ triggerLine(m.triggers) }}</text>

              <view class="card-foot">
                <text class="foot-author">{{ m.authorDisplayName || m.author || $t('market.anonymousAuthor') }}</text>
                <view class="foot-meta">
                  <svg class="foot-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, i) in ICONS.download" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <text>{{ m.downloads || 0 }}</text>
                </view>
              </view>
            </view>
          </view>
        </template>

        <!-- ============ 插件广场：经平台审核并签名，安装时验签，见 docs/PLUGIN_DISTRIBUTION.md ============ -->
        <template v-else-if="activeTab === 'plugin'">
          <view v-if="marketPluginError" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.offline" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ $t('market.pluginMarketUnavailableTitle') }}</text>
            <text class="empty-hint">{{ $t('market.pluginMarketErrorHint', { error: marketPluginError }) }}</text>
          </view>
          <view v-else-if="marketPlugins.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ marketPluginLoading ? $t('market.loadingMarket') : $t('market.noPluginsYet') }}</text>
            <text v-if="!marketPluginLoading" class="empty-hint">{{ $t('market.localInstallHint') }}</text>
          </view>

          <view v-else class="card-grid">
            <view v-for="m in marketPlugins" :key="m.id" class="ed-card">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ $t('market.pluginLabel') }}</text>
                    <template v-if="m.installed">
                      <text class="kicker-sep"></text>
                      <text class="kicker-on">{{ $t('market.installedTag') }}</text>
                    </template>
                  </view>
                  <text class="card-title">{{ m.name || m.id }}</text>
                  <text class="card-id">{{ m.id }} · v{{ m.version || '1.0.0' }} · {{ formatSize(m.size) }} · {{ priceTag(m) }}</text>
                </view>
                <view class="card-actions">
                  <view
                    v-if="marketAction(m) === 'install' && (!m.installed || m.updatable)"
                    class="act-primary"
                    :class="{ 'is-busy': !!pluginBusyId }"
                    @tap="installPlugin(m)"
                  >{{ pluginBusyId === m.id ? $t('market.installingShort') : (m.updatable ? $t('market.update') : $t('market.install')) }}</view>
                  <view v-else-if="marketAction(m) === 'buy'" class="act-primary" @tap="openPurchase('plugin', m.id)">{{ $t('market.buy') }}</view>
                  <view v-else-if="marketAction(m) === 'need-account'" class="act-primary" @tap="goToAccountSettings">{{ $t('market.needAccount') }}</view>
                  <view v-if="m.installed" class="act-remove" @tap="uninstallPlugin(m)">{{ $t('market.uninstallBtn') }}</view>
                </view>
              </view>

              <text class="card-desc">{{ m.description || $t('market.noDescription') }}</text>
              <text class="card-caps">{{ capabilityLine(m) }}</text>

              <view class="card-foot">
                <text class="foot-author">{{ m.authorDisplayName || m.author || $t('market.anonymousAuthor') }}</text>
                <view class="foot-meta">
                  <svg class="foot-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, i) in ICONS.download" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <text>{{ m.downloads || 0 }}</text>
                </view>
              </view>
            </view>
          </view>

          <!-- 插件与本机应用同权限，这句不能省 -->
          <view v-if="marketPlugins.length" class="market-note">
            <svg class="note-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.warning" :key="i" :d="d" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="note-text">{{ $t('market.pluginTrustNote') }}</text>
          </view>
        </template>

        <!-- ============ 已安装 ============ -->
        <template v-else>
          <view class="section-head">
            <text class="section-title">{{ $t('market.sectionPluginTitle') }}</text>
            <text class="section-sub">{{ $t('market.sectionPluginSub') }}</text>
          </view>

          <view v-if="loading" class="empty">
            <text class="empty-title">{{ $t('market.loadingLocalPlugins') }}</text>
          </view>
          <view v-else-if="plugins.length === 0 && panelSkills.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ $t('market.noLocalPlugins') }}</text>
            <text class="empty-hint">{{ $t('market.noLocalPluginsHint') }}</text>
          </view>
          <view v-else class="card-grid">
            <view v-for="p in plugins" :key="p.id" class="ed-card" :class="{ off: !p.enabled }">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ $t('market.pluginLabel') }}</text>
                    <text class="kicker-sep"></text>
                    <text :class="p.enabled ? 'kicker-on' : 'kicker-off'">{{ p.enabled ? $t('market.enabledTag') : $t('market.disabledTag') }}</text>
                  </view>
                  <text class="card-title">{{ p.name || p.id }}</text>
                  <text class="card-id">{{ p.id }}<template v-if="p.version"> · v{{ p.version }}</template><template v-if="p.author"> · {{ p.author }}</template></text>
                </view>
                <AwdSwitch
                  class="plugin-switch"
                  :checked="p.enabled"
                  :disabled="switching"
                  @change="onToggle(p, $event)"
                />
              </view>

              <text class="card-desc">{{ p.description || $t('market.noDescription') }}</text>
              <text class="card-caps">{{ capabilityLine(p) }}</text>

              <view class="tool-list" v-if="p.tools && p.tools.length">
                <view v-for="t in p.tools" :key="t.name" class="tool-item">
                  <text class="tool-name">{{ t.name }}</text>
                  <text class="tool-desc">{{ t.description }}</text>
                </view>
              </view>
            </view>
          </view>

          <!-- 面板型 skill：装完是左栏一个图标 + 一整个面板，用户认知里就是插件，
               所以放在插件区、只给启用/停用（判据是 leftSidebarPlugins 的 requiresSkill）。
               技术上它仍是 skills/ 下的一份，启停照样走 setSkillActivation。 -->
          <view v-if="panelSkills.length" class="card-grid">
            <view v-for="s in panelSkills" :key="s.id" class="ed-card" :class="{ off: !s.enabled }">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in ICONS.panelLeft" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ $t('market.panelPluginLabel') }}</text>
                    <text class="kicker-sep"></text>
                    <text :class="s.enabled ? 'kicker-on' : 'kicker-off'">{{ s.enabled ? $t('market.enabledTag') : $t('market.disabledTag') }}</text>
                  </view>
                  <text class="card-title">{{ s.name || s.id }}</text>
                  <text class="card-note">{{ $t('market.panelPluginHint') }}</text>
                </view>
                <AwdSwitch
                  class="plugin-switch"
                  :checked="s.enabled"
                  :disabled="switching"
                  @change="onPanelSkillToggle(s, $event)"
                />
              </view>

              <text class="card-desc">{{ s.description || $t('market.noDescription') }}</text>
            </view>
          </view>

          <!-- Skill 区块（规范见 docs/SKILL_SPEC.md） -->
          <view class="section-head">
            <text class="section-title">{{ $t('market.sectionSkillTitle') }}</text>
            <text class="section-sub">{{ $t('market.sectionSkillSub') }}</text>
          </view>
          <view v-if="conversationSkills.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.skill" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ $t('market.noLocalSkills') }}</text>
            <text class="empty-hint">{{ $t('market.noLocalSkillsHint') }}</text>
          </view>
          <view v-else class="card-grid">
            <view v-for="s in conversationSkills" :key="s.id" class="ed-card" :class="{ off: !s.enabled }">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in categoryGlyph(s.category)" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ categoryLabel(s.category) }}</text>
                    <template v-if="s.sourcePluginId">
                      <text class="kicker-sep"></text>
                      <text>{{ $t('market.fromPlugin', { pluginId: s.sourcePluginId }) }}</text>
                    </template>
                  </view>
                  <text class="card-title">{{ s.name || s.id }}</text>
                  <text class="card-note">{{ activationHint(s) }}</text>
                </view>
                <!-- 插件携带的 Skill 跟随插件启停，不单独设生效方式 -->
                <AwdSelect
                  v-if="!s.sourcePluginId"
                  class="mode-picker"
                  :range="ACTIVATION_LABELS"
                  :value="activationIndex(s)"
                  :disabled="switching"
                  @change="onActivationChange(s, $event)"
                />
                <AwdSwitch v-else class="plugin-switch" :checked="s.enabled" disabled />
              </view>

              <text class="card-desc">{{ s.description || $t('market.noDescription') }}</text>
              <text v-if="triggerLine(s.triggers)" class="card-triggers">{{ triggerLine(s.triggers) }}</text>
            </view>
          </view>
        </template>

      </view>
    </scroll-view>
  </view>
</template>

<script>
import { getPlugins, setPluginEnabled, rescanPlugins, getSkills, setSkillActivation, rescanSkills, getSkillMarket, installMarketSkill, uninstallMarketSkill, getPluginMarket, installMarketPlugin, uninstallMarketPlugin } from '@/services/api.js'
import { paidState, priceLabel, purchaseUrl } from '@/utils/marketPricing.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { ICONS } from '@/config/icons.js'
import { isPanelSkill, isVoiceGroupMember, buildVoiceGroupSkill } from '@/config/leftSidebarPlugins.js'
import { t } from '@/i18n'
import AwdSelect from '@/components/AwdSelect.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'

const PERMISSION_LABELS = {
  file_read: t('market.permFileRead'),
  file_write: t('market.permFileWrite'),
  network: t('market.permNetwork'),
  editor: t('market.permEditor'),
}

// 分类沿用官网 SKILL_CATEGORIES（aiworkdeckweb lib/skill-categories）；registry 未返回 category 时归入"其他"
const SKILL_CATEGORIES = [
  { id: 'all', label: t('market.catAll') },
  { id: 'contract', label: t('market.catContract') },
  { id: 'litigation', label: t('market.catLitigation') },
  { id: 'compliance', label: t('market.catCompliance') },
  { id: 'research', label: t('market.catResearch') },
  { id: 'corporate', label: t('market.catCorporate') },
  { id: 'office', label: t('market.catOffice') },
  { id: 'other', label: t('market.catOther') },
]

// 分类 → 线性图标，与官网 CategoryIcon.tsx 同一套映射
const CATEGORY_GLYPHS = {
  contract: ICONS.catContract,
  litigation: ICONS.catLitigation,
  compliance: ICONS.catCompliance,
  research: ICONS.catResearch,
  corporate: ICONS.catCorporate,
  office: ICONS.catOffice,
  other: ICONS.catOther,
}

const TABS = [
  { key: 'skill', label: t('market.tabSkillMarket') },
  { key: 'plugin', label: t('market.tabPluginMarket') },
  { key: 'installed', label: t('market.tabInstalled') },
]

// Skill 生效方式三档，顺序与 picker 下标一一对应
const ACTIVATION_MODES = ['auto', 'manual', 'disabled']
const ACTIVATION_LABELS = [t('market.activationAuto'), t('market.activationManual'), t('market.activationDisabled')]

// 卡片上最多平铺 3 个触发词，其余折成 +N
const TRIGGER_PREVIEW = 3

export default {
  name: 'MarketPane',
  components: { AwdSelect, AwdSwitch },
  props: {
    /** true=独立页面（渲染返回按钮，返回走 uni.navigateBack）；false=嵌入 workbench 窗格 */
    standalone: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      activeTab: 'skill',
      searchText: '',
      activeCategory: 'all',
      plugins: [],
      skills: [],
      marketSkills: [],
      marketLoading: false,
      marketError: '',
      marketBusyId: '',
      marketPlugins: [],
      marketPluginLoading: false,
      marketPluginError: '',
      pluginBusyId: '',
      // 是否已连接官网账户；随广场列表响应下发（付费未购项据此在「购买」与「需连接账户」之间选）
      accountConnected: false,
      loading: false,
      switching: false,
      rescanning: false,
    }
  },
  computed: {
    TABS() {
      return TABS
    },
    ACTIVATION_LABELS() {
      return ACTIVATION_LABELS
    },
    ICONS() {
      return ICONS
    },
    /** 面板型（长在左栏 rail 上）：广场里按插件呈现。
        「语音」的两个成员 skill 合成一张卡（左栏一个图标 = 一个插件，dev-board#66），
        开关一次作用于全部成员（onPanelSkillToggle）。 */
    panelSkills() {
      // 插件携带的 skill 跟随插件启停，不在这里单列
      const rows = []
      const voiceGroup = buildVoiceGroupSkill(this.skills)
      let voiceEmitted = false
      for (const s of this.skills) {
        if (!isPanelSkill(s.id) || s.sourcePluginId) continue
        if (voiceGroup && isVoiceGroupMember(s.id)) {
          if (!voiceEmitted) {
            voiceEmitted = true
            rows.push(voiceGroup)
          }
          continue
        }
        rows.push(s)
      }
      return rows
    },
    /** 对话型：在对话里被触发词命中的那一类，才有「生效方式三档」 */
    conversationSkills() {
      return this.skills.filter((s) => !isPanelSkill(s.id) || s.sourcePluginId)
    },
    installedCount() {
      return this.plugins.length + this.skills.length
    },
    /** 分类导航带计数，空分类不占位（与官网 catCount 过滤一致） */
    visibleCategories() {
      return SKILL_CATEGORIES
        .map(c => ({
          ...c,
          count: c.id === 'all'
            ? this.marketSkills.length
            : this.marketSkills.filter(m => (m.category || 'other') === c.id).length,
        }))
        .filter(c => c.id === 'all' || c.count > 0)
    },
    filteredMarketSkills() {
      const kw = this.searchText.trim().toLowerCase()
      return this.marketSkills.filter(m => {
        if (this.activeCategory !== 'all' && (m.category || 'other') !== this.activeCategory) return false
        if (!kw) return true
        const haystack = [m.name, m.id, m.description, ...(m.triggers || [])]
          .filter(Boolean).join(' ').toLowerCase()
        return haystack.includes(kw)
      })
    },
  },
  // 原页面在 onLoad 里拉取（无路由参数依赖）；组件化后等价迁到 mounted，
  // 嵌入态每次挂载（含 :key 变化触发的重挂）都会重新拉取四组数据
  mounted() {
    this.reloadAll()
    // 设置页连接/断开账户后广播回来：付费项的按钮形态跟着账户状态变，
    // 而设置页是 navigateTo 打开的、本页并不销毁，不订阅就会停在旧状态转不出去
    uni.$on('awd:market-changed', this.reloadAll)
  },
  beforeUnmount() {
    uni.$off('awd:market-changed', this.reloadAll)
  },
  methods: {
    reloadAll() {
      this.loadPlugins()
      this.loadSkills()
      this.loadMarket()
      this.loadPluginMarket()
    },
    tabCount(key) {
      if (key === 'skill') return this.marketSkills.length
      if (key === 'plugin') return this.marketPlugins.length
      return this.installedCount
    },
    permissionLabel(perm) {
      return PERMISSION_LABELS[perm] || perm
    },
    /** 工具数 + 声明能力合成一行正文，替代原来一排橙色胶囊 */
    capabilityLine(item) {
      const parts = []
      const toolCount = item.toolCount != null ? item.toolCount : (item.tools || []).length
      if (toolCount) parts.push(this.$t('market.toolCount', { count: toolCount }))
      const perms = (item.permissions || []).map(p => this.permissionLabel(p))
      parts.push(perms.length ? this.$t('market.requiresPerms', { perms: perms.join(this.$t('market.listSeparator')) }) : this.$t('market.noSensitiveCapability'))
      return parts.join(' · ')
    },
    /** 触发词按官网排版收成一行「引号」，超出折为 +N */
    triggerLine(triggers) {
      const list = triggers || []
      if (!list.length) return ''
      const head = list.slice(0, TRIGGER_PREVIEW).map(trigger => this.$t('market.triggerWrap', { trigger })).join(' ')
      return list.length > TRIGGER_PREVIEW ? `${head} +${list.length - TRIGGER_PREVIEW}` : head
    },
    categoryGlyph(id) {
      return CATEGORY_GLYPHS[id] || CATEGORY_GLYPHS.other
    },
    // 与导入的 priceLabel 重名会让人误以为递归，模板里统一叫 priceTag
    priceTag(m) {
      return priceLabel(m)
    },
    /**
     * 卡片主按钮形态：'install'（免费 / 已购 / 已安装，行为不变）｜'buy'｜'need-account'。
     * 已安装项一律走 install 分支，更新与卸载不受付费状态影响。
     */
    marketAction(m) {
      if (m.installed) return 'install'
      const state = paidState(m, this.accountConnected)
      return state === 'buy' || state === 'need-account' ? state : 'install'
    },
    // 购买走系统浏览器：支付要用用户已登录的浏览器会话
    openPurchase(kind, id) {
      openExternalUrl(purchaseUrl(kind, id))
    },
    goToAccountSettings() {
      uni.navigateTo({ url: '/pages/admin/admin?nav=account' })
    },
    formatSize(bytes) {
      if (!bytes) return this.$t('market.unknownSize')
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
      return (bytes / 1024 / 1024).toFixed(2) + ' MB'
    },
    async loadPluginMarket() {
      this.marketPluginLoading = true
      this.marketPluginError = ''
      try {
        const res = await getPluginMarket()
        this.marketPlugins = res?.plugins || []
        if (typeof res?.accountConnected === 'boolean') this.accountConnected = res.accountConnected
      } catch (e) {
        // 注册表不可达只在区块内提示，不影响本地插件与 Skill
        console.warn('在线插件广场不可用:', e)
        this.marketPluginError = e?.message || this.$t('market.networkUnavailable')
        this.marketPlugins = []
      } finally {
        this.marketPluginLoading = false
      }
    },
    async installPlugin(plugin) {
      if (this.pluginBusyId) return
      const perms = (plugin.permissions || []).map(p => this.permissionLabel(p)).join(this.$t('market.listSeparator')) || this.$t('market.noSensitiveCapability')
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('market.confirmInstallPluginTitle'),
          content: this.$t('market.confirmInstallPluginContent', {
            name: plugin.name || plugin.id,
            version: plugin.version,
            author: plugin.authorDisplayName || plugin.author || this.$t('market.unknownAuthor'),
            perms,
          }),
          confirmText: this.$t('market.install'),
          cancelText: this.$t('market.cancelBtn'),
          success: r => resolve(r.confirm),
          fail: () => resolve(false)
        })
      })
      if (!ok) return

      this.pluginBusyId = plugin.id
      try {
        await installMarketPlugin(plugin.id)
        uni.showToast({ title: this.$t('market.installedEnableHint'), icon: 'none' })
        await this.loadPlugins()
        await this.loadPluginMarket()
        this.notifyMarketChanged()
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.pluginBusyId = ''
      }
    },
    async uninstallPlugin(plugin) {
      if (this.pluginBusyId) return
      this.pluginBusyId = plugin.id
      try {
        await uninstallMarketPlugin(plugin.id)
        uni.showToast({ title: this.$t('market.uninstalledToast'), icon: 'none' })
        await this.loadPlugins()
        await this.loadPluginMarket()
        this.notifyMarketChanged()
      } catch (e) {
        console.error('卸载插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.uninstallFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.pluginBusyId = ''
      }
    },
    categoryLabel(id) {
      const c = SKILL_CATEGORIES.find(c => c.id === (id || 'other'))
      return c ? c.label : this.$t('market.catOther')
    },
    async loadPlugins() {
      this.loading = true
      try {
        const res = await getPlugins()
        this.plugins = Array.isArray(res) ? res : (res?.data || [])
      } catch (e) {
        console.error('加载插件列表失败:', e)
        uni.showToast({ title: this.$t('market.loadPluginListFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    // AwdSwitch 直接抛布尔值
    async onToggle(plugin, enabled) {
      this.switching = true
      try {
        await setPluginEnabled(plugin.id, enabled)
        plugin.enabled = enabled
        uni.showToast({ title: enabled ? this.$t('market.enabledToast') : this.$t('market.disabledToggleToast'), icon: 'none' })
        this.notifyMarketChanged()
      } catch (e) {
        console.error('切换插件状态失败:', e)
        // 回滚开关显示
        plugin.enabled = !enabled
        uni.showToast({ title: e?.message || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        await this.loadPlugins()
      } finally {
        this.switching = false
      }
    },
    async loadSkills() {
      try {
        const res = await getSkills()
        this.skills = Array.isArray(res) ? res : (res?.data || [])
      } catch (e) {
        console.error('加载 Skill 列表失败:', e)
        uni.showToast({ title: this.$t('market.loadSkillListFailed'), icon: 'none' })
      }
    },
    // 面板型只有启用/停用两态：开 = auto（面板的 kick-off prompt 要靠触发词命中，
    // manual 会让「生成纪要」按钮点了没反应），关 = disabled（左栏图标随之消失）
    async onPanelSkillToggle(skill, enabled) {
      const mode = enabled ? 'auto' : 'disabled'
      this.switching = true
      try {
        // 「语音」合并插件：一次作用于全部成员 skill（启停一体，dev-board#66）
        const ids = skill.groupMemberIds || [skill.id]
        for (const id of ids) {
          await setSkillActivation(id, mode)
        }
        if (skill.groupMemberIds) {
          // 合并卡是 computed 里合成的非响应式对象，乐观更新要写回底层成员
          // （this.skills 的元素）才会触发重算重渲染
          for (const s of this.skills) {
            if (ids.includes(s.id)) {
              s.activationMode = mode
              s.enabled = enabled
            }
          }
        }
        skill.activationMode = mode
        skill.enabled = enabled
        uni.showToast({ title: enabled ? this.$t('market.enabledToast') : this.$t('market.disabledToggleToast'), icon: 'none' })
        this.notifyMarketChanged()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        await this.loadSkills()
      } finally {
        this.switching = false
      }
    },
    // 装/卸/启停改的是全局安装状态：面板型 skill 的启停决定左栏有没有那个图标，
    // 装卸决定左栏广场面板那一行是「安装」还是「已安装」——都必须立刻通知外部重算，
    // 否则同屏的 MarketSidebarPanel 会一直停在旧状态（本页嵌在设置 tab 里时两者共存）。
    // 两个事件都发：广场有两个宿主（左栏列表面板、中栏详情 tab），工作台两个都订。
    notifyMarketChanged() {
      uni.$emit('awd:market-changed')
      uni.$emit('awd:market-changed-from-sidebar')
    },
    activationIndex(skill) {
      const mode = skill.activationMode || (skill.enabled ? 'auto' : 'disabled')
      const idx = ACTIVATION_MODES.indexOf(mode)
      return idx >= 0 ? idx : 0
    },
    activationHint(skill) {
      if (skill.sourcePluginId) return this.$t('market.activationHintSourcePlugin')
      const mode = ACTIVATION_MODES[this.activationIndex(skill)]
      if (mode === 'manual') return this.$t('market.activationHintManual')
      if (mode === 'disabled') return this.$t('market.disabledTag')
      return this.$t('market.activationHintAuto')
    },
    // AwdSelect 直接抛下标
    async onActivationChange(skill, idx) {
      const mode = ACTIVATION_MODES[Number(idx)]
      if (!mode || mode === ACTIVATION_MODES[this.activationIndex(skill)]) return
      const previous = skill.activationMode
      this.switching = true
      try {
        await setSkillActivation(skill.id, mode)
        skill.activationMode = mode
        skill.enabled = mode !== 'disabled'
        uni.showToast({ title: this.$t('market.setActivationTo', { mode: ACTIVATION_LABELS[idx] }), icon: 'none' })
      } catch (e) {
        console.error('设置 Skill 生效方式失败:', e)
        skill.activationMode = previous
        uni.showToast({ title: e?.message || this.$t('market.operationFailedNeedAdmin'), icon: 'none' })
        await this.loadSkills()
      } finally {
        this.switching = false
      }
    },
    async loadMarket() {
      this.marketLoading = true
      this.marketError = ''
      try {
        const res = await getSkillMarket()
        this.marketSkills = res?.skills || []
        if (typeof res?.accountConnected === 'boolean') this.accountConnected = res.accountConnected
      } catch (e) {
        // 注册表不可达只在区块内提示，不弹 toast、不影响本地插件 / Skill 区块
        console.warn('在线广场不可用:', e)
        this.marketError = e?.message || this.$t('market.networkUnavailable')
        this.marketSkills = []
      } finally {
        this.marketLoading = false
      }
    },
    async installSkill(skill) {
      if (this.marketBusyId) return
      this.marketBusyId = skill.id
      try {
        await installMarketSkill(skill.id)
        uni.showToast({ title: skill.installed ? this.$t('market.updatedToast') : this.$t('market.genericInstalledToast'), icon: 'none' })
        await this.loadSkills()
        await this.loadMarket()
        this.notifyMarketChanged()
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.marketBusyId = ''
      }
    },
    async uninstallSkill(skill) {
      if (this.marketBusyId) return
      this.marketBusyId = skill.id
      try {
        await uninstallMarketSkill(skill.id)
        uni.showToast({ title: this.$t('market.uninstalledToast'), icon: 'none' })
        await this.loadSkills()
        await this.loadMarket()
        this.notifyMarketChanged()
      } catch (e) {
        console.error('卸载 Skill 失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.uninstallFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.marketBusyId = ''
      }
    },
    async rescan() {
      if (this.rescanning) return
      this.rescanning = true
      try {
        const res = await rescanPlugins()
        const skillRes = await rescanSkills().catch(() => null)
        uni.showToast({
          title: this.$t('market.scanComplete', { pluginCount: res?.pluginCount ?? 0, skillCount: skillRes?.skillCount ?? 0 }),
          icon: 'none'
        })
        await this.loadPlugins()
        await this.loadSkills()
        await this.loadPluginMarket()
      } catch (e) {
        console.error('重新扫描失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.scanFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.rescanning = false
      }
    },
    goBack() {
      uni.navigateBack({
        fail: () => {
          uni.redirectTo({ url: '/pages/admin/admin' })
        }
      })
    },
  }
}
</script>

<style lang="scss" scoped>
/* 视觉规范：aiworkdeckweb/DESIGN.md（法律刊物式编辑排版）。
   色值与官网 globals.css 的 CSS 变量一一对应，改这里先去改官网。 */

$gray-dark: #2C3338;
$gray-medium: #6C757D;
$gray-light: #E9ECEF;

/* 展示级衬线：大标题 / 区块题名 / 卡片题名 / 统计数字。
   桌面端不打包 Noto Serif SC，回落到系统宋体栈（与官网 .font-display 同一条链） */
@mixin display-serif {
  font-family: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif;
}

@mixin mono {
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
}

/* 组件根：占满宿主（整页壳或 workbench 窗格），滚动收在内部 scroll-view */
.market-pane {
  height: 100%;
  min-height: 0;
  background: var(--awd-bg);
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
}

/* ---------- Hero ---------- */
.hero {
  position: relative;
  overflow: hidden;
  flex-shrink: 0;
  background: linear-gradient(135deg, var(--awd-accent-hover) 0%, var(--awd-accent-hover) 55%, var(--awd-accent-hover) 100%);
}

/* 细颗粒噪点，压住大面积色块的塑料感 */
.hero-grain {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  opacity: 0.05;
  mix-blend-mode: overlay;
  pointer-events: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='160' height='160' filter='url(%23n)' opacity='0.5'/%3E%3C/svg%3E");
}

/* 巨型衬线字水印，做非对称构图 */
.hero-watermark {
  @include display-serif;
  position: absolute;
  right: 24px;
  bottom: -56px;
  font-size: 220px;
  font-weight: 900;
  line-height: 1;
  color: rgba(255, 255, 255, 0.035);
  pointer-events: none;
  user-select: none;
}

.hero-inner {
  position: relative;
  max-width: 1140px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 38px 32px 30px;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.hero-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 眉标：细线 + 全大写字距 */
.eyebrow {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.eyebrow-line {
  width: 32px;
  height: 1px;
  background: var(--awd-mint);
}

.eyebrow-text {
  font-size: 11px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: var(--awd-accent-text);
}

.hero-title {
  @include display-serif;
  font-size: 38px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.01em;
  color: var(--awd-text-on-accent);
  margin-bottom: 10px;
}

.hero-sub {
  font-size: 14px;
  line-height: 1.7;
  color: var(--awd-text-on-accent);
  max-width: 560px;
}

.hero-stats {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 22px;
  margin-top: 26px;
}

.stat {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 7px;
}

.stat-num {
  @include display-serif;
  font-size: 24px;
  font-weight: 700;
  color: var(--awd-text-on-accent);
}

.stat-label {
  font-size: 13px;
  color: var(--awd-text-on-accent);
}

.stat-sep {
  width: 1px;
  height: 18px;
  background: rgba(255, 255, 255, 0.15);
}

.hero-actions {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.btn-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

/* 深色底上：主按钮白底深绿字，次按钮描边幽灵（DESIGN.md 七） */
.btn-ghost,
.btn-light {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 500;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.btn-ghost {
  color: var(--awd-text-on-accent);
  border: 1px solid rgba(255, 255, 255, 0.22);

  &:hover {
    color: var(--awd-text-on-accent);
    border-color: rgba(255, 255, 255, 0.45);
    background: rgba(255, 255, 255, 0.06);
  }
}

.btn-light {
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-surface);
  font-weight: 600;

  &:hover { background: var(--awd-accent-soft); }

  &.is-busy {
    opacity: 0.6;
    pointer-events: none;
  }
}

/* ---------- 主页签：编辑式下划线 ---------- */
.tab-bar {
  flex-shrink: 0;
  background: var(--awd-surface);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid var(--awd-border);
}

.tab-inner {
  max-width: 1140px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 0 32px;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 28px;
}

.tab-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 6px;
  font-size: 14px;
  color: var(--awd-text-2);
  padding: 14px 0;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.2s;
  white-space: nowrap;

  &:hover { color: var(--awd-text); }

  &.active {
    color: var(--awd-accent-text);
    font-weight: 600;
    border-bottom-color: var(--awd-accent);
  }
}

.tab-count {
  @include mono;
  font-size: 10px;
  opacity: 0.6;
}

/* ---------- 内容区 ---------- */
.content {
  flex: 1;
  min-height: 0;
}

.content-inner {
  max-width: 1140px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 26px 32px 48px;
}

/* 分类导航（下划线）+ 搜索 */
.filter-bar {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--awd-border);
}

.cat-nav {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  min-width: 0;
}

.cat-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 5px;
  font-size: 13px;
  color: var(--awd-text-2);
  padding: 8px 0 11px;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.2s;
  white-space: nowrap;

  &:hover { color: var(--awd-text); }

  &.active {
    color: var(--awd-accent-text);
    font-weight: 600;
    border-bottom-color: var(--awd-accent);
  }
}

.cat-num {
  @include mono;
  font-size: 10px;
  opacity: 0.6;
}

.search-box {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  width: 240px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 7px 12px;
  margin-bottom: 8px;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:hover { border-color: var(--awd-border); }

  &:focus-within {
    border-color: var(--awd-mint);
    box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.15);
  }
}

.search-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--awd-text-2);
}

.search-input {
  font-size: 13px;
  color: var(--awd-text);
  flex: 1;
  min-width: 0;
}

/* 区块标题（已安装 tab） */
.section-head {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--awd-border);

  &:not(:first-child) { margin-top: 36px; }
}

.section-title {
  @include display-serif;
  font-size: 22px;
  font-weight: 700;
  color: var(--awd-text);
}

.section-sub {
  font-size: 13px;
  color: var(--awd-text-2);
}

/* ---------- 卡片 ---------- */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}

.ed-card {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
  padding: 22px;
  border-radius: 12px;
  background: var(--awd-surface);
  backdrop-filter: blur(20px);
  border: 1px solid var(--awd-border);
  transition: transform 0.3s ease, border-color 0.3s ease, box-shadow 0.3s ease;

  /* hover 时顶部浮起一条品牌色细线 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    top: 0;
    height: 1px;
    background: linear-gradient(90deg, transparent, var(--awd-accent), transparent);
    opacity: 0;
    transition: opacity 0.3s ease;
  }

  &:hover {
    transform: translateY(-4px);
    border-color: var(--awd-accent-soft);
    box-shadow: 0 18px 40px -18px rgba(18, 58, 38, 0.25);

    &::before { opacity: 1; }
  }

  &.off {
    opacity: 0.55;

    &:hover {
      transform: none;
      box-shadow: none;
      border-color: var(--awd-border);

      &::before { opacity: 0; }
    }
  }
}

.card-head {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.head-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

/* 分类眉标：小图标 + 分类名，替代原来的彩色胶囊 */
.kicker {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 11px;
  letter-spacing: 0.14em;
  color: var(--awd-text-2);
}

.kicker-icon {
  width: 13px;
  height: 13px;
  color: var(--awd-accent-text);
  flex-shrink: 0;
}

.kicker-sep {
  width: 1px;
  height: 11px;
  background: var(--awd-surface-3);
}

.kicker-on {
  color: var(--awd-accent-text);
  font-weight: 600;
}

.kicker-off {
  color: var(--awd-text-3);
}

.card-title {
  @include display-serif;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.35;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-id {
  @include mono;
  font-size: 11px;
  color: var(--awd-text-2);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 中文说明句：与 card-id 同位同色，但不用等宽 */
.card-note {
  font-size: 12px;
  color: var(--awd-text-2);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  flex-shrink: 0;
}

.act-primary {
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border: 1px solid var(--awd-accent);
  border-radius: 6px;
  padding: 5px 16px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover { background: var(--awd-accent-hover); }

  &.is-busy {
    opacity: 0.5;
    pointer-events: none;
  }
}

.act-remove {
  font-size: 12px;
  text-align: center;
  color: var(--awd-text-2);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 5px 16px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    color: var(--awd-danger-text);
    border-color: rgba(192, 57, 43, 0.4);
  }
}

.card-desc {
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 14px;
}

/* 触发词：中文「引号」排版，不用满屏 pill */
.card-triggers {
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-accent-text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 18px;
}

/* 工具数与声明能力：一行正文，敏感能力靠文案而非配色喊话 */
.card-caps {
  font-size: 12px;
  line-height: 1.6;
  color: var(--awd-text-2);
  margin-bottom: 18px;
}

.card-foot {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: auto;
  padding-top: 14px;
  border-top: 1px solid var(--awd-border);
  font-size: 12px;
  color: var(--awd-text-2);
}

.foot-author {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foot-meta {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.foot-icon {
  width: 13px;
  height: 13px;
}

/* 已安装卡片上的控件。AwdSwitch 自己就是小尺寸，不再需要 transform: scale
   （缩放会把描边和圆角一起缩变形，跟旁边的按钮对不齐）。 */
.plugin-switch {
  flex-shrink: 0;
}

.mode-picker {
  flex-shrink: 0;
}

/* 工具清单 */
.tool-list {
  border-top: 1px solid var(--awd-border);
  padding-top: 12px;
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tool-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 10px;
}

.tool-name {
  @include mono;
  font-size: 11px;
  color: var(--awd-accent-text);
  flex-shrink: 0;
}

.tool-desc {
  font-size: 12px;
  color: var(--awd-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 空状态：留白 + 衬线题名，不用虚线框 ---------- */
.empty {
  padding: 44px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.empty-icon {
  width: 38px;
  height: 38px;
  color: rgba(108, 117, 125, 0.45);
  margin-bottom: 10px;
}

.empty-title {
  @include display-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--awd-text);
}

.empty-hint {
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-text-2);
  max-width: 460px;
}

/* 在线插件区块底部的信任提示：插件与本机应用同权限，这句不能省 */
.market-note {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 10px;
  margin-top: 28px;
  padding-top: 18px;
  border-top: 1px solid var(--awd-border);
}

.note-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--awd-warning-text);
}

.note-text {
  font-size: 12px;
  line-height: 1.8;
  color: var(--awd-text-2);
  max-width: 760px;
}

/* 窄窗口（嵌入 workbench 窗格时以组件自身宽度为准的媒体查询仍按视口触发，
   窄窗格下卡片栅格靠 minmax 自适应） */
@media (max-width: 900px) {
  .hero-inner,
  .tab-inner,
  .content-inner {
    padding-left: 20px;
    padding-right: 20px;
  }

  .hero-inner {
    flex-direction: column;
  }

  .hero-title { font-size: 30px; }
  .hero-watermark { display: none; }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-box { width: auto; }

  .card-grid {
    grid-template-columns: 1fr;
  }
}

/* ---------- 嵌入态（standalone=false）：收掉独立页的大 hero/超宽版式，
   与 AdminPane.vue 其余设置分区（.section-card/.section-header/.section-title）
   的卡片式、浅色、统一留白语言对齐。standalone=true 的独立页（/pages/plugin-market）
   不带 .is-embedded，一字不变。色值对照：$gray-dark(#2C3338) = admin 的 $text-main，
   $gray-medium(#6C757D) = admin 的 $text-secondary，$gray-light(#E9ECEF) = admin 的
   $border-color，写法不同是因为两个组件的 scss 变量各自 scoped，数值特意保持一致。 */
.market-pane.is-embedded {
  background: transparent;
}

/* 大 hero 收成与 .section-header 同尺寸的浅色页头：去掉深色渐变、噪点纹理、
   巨型衬线水印与超宽 1140px 版式 */
.market-pane.is-embedded .hero {
  background: var(--awd-surface);
  border-bottom: 1px solid var(--awd-border);
}

.market-pane.is-embedded .hero-grain,
.market-pane.is-embedded .hero-watermark {
  display: none;
}

.market-pane.is-embedded .hero-inner {
  max-width: none;
  padding: 24px 24px 16px;
  align-items: center;
}

.market-pane.is-embedded .hero-title {
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  color: var(--awd-text);
  margin-bottom: 4px;
}

.market-pane.is-embedded .hero-sub {
  font-size: 13px;
  color: var(--awd-text-2);
  max-width: none;
}

.market-pane.is-embedded .hero-stats {
  margin-top: 10px;
  gap: 16px;
}

.market-pane.is-embedded .stat-num {
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}

.market-pane.is-embedded .stat-sep {
  background: var(--awd-surface-3);
}

.market-pane.is-embedded .btn-light {
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);

  &:hover { background: var(--awd-bg); }
}

/* 主页签条与内容区：去掉与 hero 呼应的 1140px 超宽版式，改为跟随嵌入容器的实际宽度，
   内边距对齐 .section-body(24px) */
.market-pane.is-embedded .tab-inner,
.market-pane.is-embedded .content-inner {
  max-width: none;
  padding-left: 24px;
  padding-right: 24px;
}

.market-pane.is-embedded .content-inner {
  padding-top: 20px;
  padding-bottom: 32px;
}

/* 「已安装」子页签内的区块标题：22px 展示衬线收成与 admin 分区小标题同级 */
.market-pane.is-embedded .section-title {
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}
</style>

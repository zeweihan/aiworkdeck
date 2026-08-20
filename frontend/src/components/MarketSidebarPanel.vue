<template>
  <view class="msb">
    <!-- 搜索：过滤全部分组（VS Code 扩展栏语义） -->
    <view class="msb-search">
      <svg class="msb-search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path v-for="(d, gi) in ICONS.search" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <input class="msb-search-input" v-model="searchText" :placeholder="$t('market.searchPlaceholder')" />
      <view v-if="searchText" class="msb-search-clear" @tap="searchText = ''">×</view>
    </view>

    <scroll-view scroll-y class="msb-body">
      <!-- ===== 已安装 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('installed')">
        <text class="msb-sec-chevron" :class="{ open: sections.installed }">›</text>
        <text class="msb-sec-title">{{ $t('market.tabInstalled') }}</text>
        <text class="msb-sec-count">{{ installedRows.length }}</text>
        <view class="msb-sec-spacer"></view>
        <view class="msb-sec-action" :title="$t('market.rescanTooltip')" @tap.stop="rescan">
          <svg class="msb-sec-action-icon" :class="{ spinning: rescanning }" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.refresh" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </view>
      </view>
      <view v-if="sections.installed">
        <view v-if="!installedRows.length" class="msb-empty">
          <text>{{ searchText ? $t('market.noMatchingInstalled') : $t('market.noInstalledYet') }}</text>
        </view>
        <!-- 「已安装」拆两个子分组：插件（面板型 skill + JAR/Web 插件）在前、Skill
             （纯对话型）在后，与 MarketPane.vue 已安装 tab 的分区顺序一致。
             子分组头是折叠头的视觉降级（更小字号、无 chevron、不再折叠一层），
             判据类型已经写在分组标题里，行内不再重复"面板插件/Skill"小字标签。 -->
        <template v-else>
          <template v-for="group in installedGroups" :key="group.key">
            <view v-if="group.rows.length" class="msb-subsec-head">
              <text class="msb-subsec-title">{{ group.title }}</text>
              <text class="msb-sec-count">{{ group.rows.length }}</text>
            </view>
            <view
              v-for="row in group.rows"
              :key="'ins-' + row.kind + '-' + row.id"
              class="msb-row"
              @tap="openDetail(row)"
            >
              <view class="msb-row-glyph" :class="{ 'is-plugin': row.kind === 'plugin' || row.panel }">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in row.glyph" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>
              <view class="msb-row-main">
                <text class="msb-row-name">{{ row.name }}</text>
                <text v-if="row.desc" class="msb-row-desc">{{ row.desc }}</text>
                <text class="msb-row-meta">{{ row.meta }}</text>
              </view>
              <view class="msb-row-state" :class="row.stateClass">
                <text>{{ row.stateLabel }}</text>
              </view>
            </view>
          </template>
        </template>
      </view>

      <!-- ===== Skill 广场 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('skill')">
        <text class="msb-sec-chevron" :class="{ open: sections.skill }">›</text>
        <text class="msb-sec-title">{{ $t('market.tabSkillMarket') }}</text>
        <text class="msb-sec-count">{{ skillRows.length }}</text>
      </view>
      <view v-if="sections.skill">
        <view v-if="marketLoading" class="msb-empty"><text>{{ $t('market.loadingEllipsis') }}</text></view>
        <view v-else-if="marketError" class="msb-empty msb-error">
          <text>{{ $t('market.marketUnavailablePrefixed', { error: marketError }) }}</text>
        </view>
        <view v-else-if="!skillRows.length" class="msb-empty">
          <text>{{ searchText ? $t('market.noMatchingSkill') : $t('market.marketEmptySkillShort') }}</text>
        </view>
        <view
          v-for="row in skillRows"
          :key="'mkt-s-' + row.id"
          class="msb-row"
          @tap="openDetail(row)"
        >
          <view class="msb-row-glyph">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in row.glyph" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
          <view class="msb-row-main">
            <text class="msb-row-name">{{ row.name }}</text>
            <text v-if="row.desc" class="msb-row-desc">{{ row.desc }}</text>
            <text class="msb-row-meta">{{ row.meta }}</text>
          </view>
          <view v-if="row.installed" class="msb-row-state ok"><text>{{ $t('market.installedShort') }}</text></view>
          <view
            v-else-if="row.canInstall"
            class="msb-row-install"
            :class="{ busy: marketBusyId === row.id }"
            @tap.stop="installSkillRow(row)"
          >
            <text>{{ marketBusyId === row.id ? '…' : $t('market.install') }}</text>
          </view>
          <view v-else-if="row.paidState === 'buy'" class="msb-row-install buy" @tap.stop="openPurchase(row)">
            <text>{{ $t('market.buy') }}</text>
          </view>
          <view v-else class="msb-row-state need" @tap.stop="goToAccountSettings">
            <text>{{ $t('market.needAccount') }}</text>
          </view>
        </view>
      </view>

      <!-- ===== 插件广场 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('plugin')">
        <text class="msb-sec-chevron" :class="{ open: sections.plugin }">›</text>
        <text class="msb-sec-title">{{ $t('market.tabPluginMarket') }}</text>
        <text class="msb-sec-count">{{ pluginRows.length }}</text>
      </view>
      <view v-if="sections.plugin">
        <view v-if="marketPluginLoading" class="msb-empty"><text>{{ $t('market.loadingEllipsis') }}</text></view>
        <view v-else-if="marketPluginError" class="msb-empty msb-error">
          <text>{{ $t('market.marketUnavailablePrefixed', { error: marketPluginError }) }}</text>
        </view>
        <view v-else-if="!pluginRows.length" class="msb-empty">
          <text>{{ searchText ? $t('market.noMatchingPlugin') : $t('market.marketEmptyPlugin') }}</text>
        </view>
        <view
          v-for="row in pluginRows"
          :key="'mkt-p-' + row.id"
          class="msb-row"
          @tap="openDetail(row)"
        >
          <view class="msb-row-glyph is-plugin">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in row.glyph" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
          <view class="msb-row-main">
            <text class="msb-row-name">{{ row.name }}</text>
            <text v-if="row.desc" class="msb-row-desc">{{ row.desc }}</text>
            <text class="msb-row-meta">{{ row.meta }}</text>
          </view>
          <view v-if="row.installed" class="msb-row-state ok"><text>{{ $t('market.installedShort') }}</text></view>
          <view
            v-else-if="row.canInstall"
            class="msb-row-install"
            :class="{ busy: pluginBusyId === row.id }"
            @tap.stop="installPluginRow(row)"
          >
            <text>{{ pluginBusyId === row.id ? '…' : $t('market.install') }}</text>
          </view>
          <view v-else-if="row.paidState === 'buy'" class="msb-row-install buy" @tap.stop="openPurchase(row)">
            <text>{{ $t('market.buy') }}</text>
          </view>
          <view v-else class="msb-row-state need" @tap.stop="goToAccountSettings">
            <text>{{ $t('market.needAccount') }}</text>
          </view>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
// 插件广场左栏面板（VS Code 扩展栏形态）：搜索 + 已安装/Skill 广场/插件广场
// 三个折叠分组的紧凑列表；点行在中栏开详情 tab（emit open-detail），行内快捷安装。
// 数据与安装链路复用 MarketPane 同一组 services/api.js 封装。
import { getPlugins, getSkills, getSkillMarket, getPluginMarket, installMarketSkill, installMarketPlugin, rescanPlugins, rescanSkills } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { isPanelSkill } from '@/config/leftSidebarPlugins.js'
import { canInstall, paidState, priceLabel, purchaseUrl } from '@/utils/marketPricing.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { t } from '@/i18n'

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

const ACTIVATION_STATE = {
  auto: t('market.activationStateAuto'),
  manual: t('market.activationStateManual'),
  disabled: t('market.activationDisabled'),
}

function fmtDownloads(n) {
  if (!n) return ''
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

export default {
  name: 'MarketSidebarPanel',
  emits: ['open-detail'],
  data() {
    return {
      searchText: '',
      sections: { installed: true, skill: true, plugin: true },
      plugins: [],
      skills: [],
      marketSkills: [],
      marketPlugins: [],
      marketLoading: false,
      marketError: '',
      marketPluginLoading: false,
      marketPluginError: '',
      marketBusyId: '',
      pluginBusyId: '',
      rescanning: false,
      // 是否已连接官网账户；随广场列表响应一起下发（付费未购项据此显示「购买」还是「需连接账户」）
      accountConnected: false,
    }
  },
  computed: {
    ICONS() {
      return ICONS
    },
    installedRows() {
      const kw = this.searchText.trim().toLowerCase()
      const rows = []
      for (const s of this.skills) {
        const mode = s.activationMode || (s.enabled ? 'auto' : 'disabled')
        // 面板型（背后挂着左栏面板）在列表里也按插件标注：用户看到的是一个面板，
        // 说它是「Skill · 自动触发」只会让人对不上号。判据见 leftSidebarPlugins.js。
        const panel = isPanelSkill(s.id) && !s.sourcePluginId
        // 「面板插件/Skill」标签不再在行内重复——子分组标题（installedGroups）已经
        // 表明了这一行属于哪一类，见下方 installedSkillRows/installedPluginRows。
        const metaParts = []
        if (s.version) metaParts.push('v' + s.version)
        if (s.author) metaParts.push(s.author)
        if (s.sourcePluginId) metaParts.push(this.$t('market.fromPluginTag'))
        // 挂着原生资源包的面板型 skill：已启用但包还没就绪（老版本升级后端自动
        // 补下载的过渡态，见 docs/NATIVE_PACK_DISTRIBUTION.md §5）——如实标「下载中」，
        // 不能显示「已启用」误导用户以为功能已经能用。装未装的初始态不受影响，
        // 那条走 MarketDetailPane 的安装按钮，本行列表不加轮询。
        const packPending = panel && s.packId && s.enabled && s.packReady === false
        rows.push({
          kind: 'skill',
          id: s.id,
          name: s.name || s.id,
          desc: s.description || '',
          glyph: panel ? ICONS.panelLeft : (CATEGORY_GLYPHS[s.category] || ICONS.skill),
          meta: metaParts.join(' · '),
          // 子分组归属：面板型进「插件」组，其余进「Skill」组。见 installedGroups。
          panel,
          stateLabel: packPending
            ? this.$t('market.packDownloadingShort')
            : panel
              ? (s.enabled ? this.$t('market.enabledTag') : this.$t('market.disabledTag'))
              : (ACTIVATION_STATE[mode] || this.$t('market.activationStateAuto')),
          stateClass: packPending ? 'downloading' : (mode === 'disabled' ? 'off' : 'ok'),
          raw: s,
        })
      }
      for (const p of this.plugins) {
        rows.push({
          kind: 'plugin',
          id: p.id,
          name: p.name || p.id,
          desc: p.description || '',
          glyph: ICONS.blocks,
          meta: p.version ? ('v' + p.version) : '',
          stateLabel: p.enabled ? this.$t('market.enabledTag') : this.$t('market.disabledTag'),
          stateClass: p.enabled ? 'ok' : 'off',
          raw: p,
        })
      }
      return kw ? rows.filter(r => (r.name + ' ' + r.id + ' ' + r.desc).toLowerCase().includes(kw)) : rows
    },
    /** 「已安装」子分组：插件 = 面板型 skill + JAR/Web 插件；Skill = 纯对话型。 */
    installedPluginRows() {
      return this.installedRows.filter(r => r.kind === 'plugin' || r.panel)
    },
    installedSkillRows() {
      return this.installedRows.filter(r => r.kind === 'skill' && !r.panel)
    },
    /** 渲染顺序：插件在前、Skill 在后，与 MarketPane.vue 已安装 tab 一致。 */
    installedGroups() {
      return [
        { key: 'plugin', title: this.$t('market.sectionPluginTitle'), rows: this.installedPluginRows },
        { key: 'skill', title: this.$t('market.sectionSkillTitle'), rows: this.installedSkillRows },
      ]
    },
    skillRows() {
      const kw = this.searchText.trim().toLowerCase()
      const rows = this.marketSkills.map(m => {
        const cat = m.category || 'other'
        const metaParts = []
        if (m.version) metaParts.push('v' + m.version)
        const dl = fmtDownloads(m.downloads)
        if (dl) metaParts.push(dl + this.$t('market.downloadsSuffix'))
        metaParts.push(CATEGORY_LABELS[cat] || this.$t('market.catOther'))
        metaParts.push(priceLabel(m))
        const state = paidState(m, this.accountConnected)
        return {
          kind: 'skill',
          id: m.id,
          name: m.name || m.id,
          desc: m.description || '',
          glyph: CATEGORY_GLYPHS[cat] || CATEGORY_GLYPHS.other,
          meta: metaParts.join(' · '),
          installed: !!m.installed,
          paidState: state,
          canInstall: canInstall(state),
          raw: m,
        }
      })
      return kw
        ? rows.filter(r => {
            const hay = [r.name, r.id, r.desc, ...((r.raw.triggers) || [])].filter(Boolean).join(' ').toLowerCase()
            return hay.includes(kw)
          })
        : rows
    },
    pluginRows() {
      const kw = this.searchText.trim().toLowerCase()
      const installedIds = new Set(this.plugins.map(p => p.id))
      const rows = this.marketPlugins.map(m => {
        const metaParts = []
        if (m.version) metaParts.push('v' + m.version)
        const author = m.authorDisplayName || m.author
        if (author) metaParts.push(author)
        metaParts.push(priceLabel(m))
        const state = paidState(m, this.accountConnected)
        return {
          kind: 'plugin',
          id: m.id,
          name: m.name || m.id,
          desc: m.description || '',
          glyph: ICONS.blocks,
          meta: metaParts.join(' · ') || this.$t('market.pluginLabel'),
          installed: installedIds.has(m.id) || !!m.installed,
          paidState: state,
          canInstall: canInstall(state),
          raw: m,
        }
      })
      return kw ? rows.filter(r => (r.name + ' ' + r.id + ' ' + r.desc).toLowerCase().includes(kw)) : rows
    },
  },
  mounted() {
    this.reloadAll()
    // 详情 tab 里装/卸/启停后广播回来刷新列表（组件级订阅，卸载即清理）
    uni.$on('awd:market-changed', this.reloadAll)
  },
  beforeUnmount() {
    uni.$off('awd:market-changed', this.reloadAll)
  },
  methods: {
    toggleSection(key) {
      this.sections[key] = !this.sections[key]
    },
    openDetail(row) {
      this.$emit('open-detail', { kind: row.kind, id: row.id, name: row.name })
    },
    // 购买走系统浏览器：支付要用用户已登录的浏览器会话，内嵌 tab 里付不了
    openPurchase(row) {
      openExternalUrl(purchaseUrl(row.kind, row.id))
      uni.showToast({ title: this.$t('market.openedPurchasePage'), icon: 'none' })
    },
    goToAccountSettings() {
      uni.navigateTo({ url: '/pages/admin/admin?nav=account' })
    },
    async reloadAll() {
      this.loadInstalled()
      this.loadMarketSkills()
      this.loadMarketPlugins()
    },
    async loadInstalled() {
      try {
        const [pRes, sRes] = await Promise.all([getPlugins(), getSkills()])
        this.plugins = Array.isArray(pRes) ? pRes : (pRes?.data || [])
        this.skills = Array.isArray(sRes) ? sRes : (sRes?.data || [])
      } catch (e) {
        console.error('加载已安装列表失败:', e)
      }
    },
    async loadMarketSkills() {
      this.marketLoading = true
      this.marketError = ''
      try {
        const res = await getSkillMarket()
        this.marketSkills = res?.skills || []
        if (typeof res?.accountConnected === 'boolean') this.accountConnected = res.accountConnected
      } catch (e) {
        console.warn('在线 Skill 广场不可用:', e)
        this.marketError = e?.message || this.$t('market.networkUnavailable')
        this.marketSkills = []
      } finally {
        this.marketLoading = false
      }
    },
    async loadMarketPlugins() {
      this.marketPluginLoading = true
      this.marketPluginError = ''
      try {
        const res = await getPluginMarket()
        this.marketPlugins = res?.plugins || []
        if (typeof res?.accountConnected === 'boolean') this.accountConnected = res.accountConnected
      } catch (e) {
        console.warn('在线插件广场不可用:', e)
        this.marketPluginError = e?.message || this.$t('market.networkUnavailable')
        this.marketPlugins = []
      } finally {
        this.marketPluginLoading = false
      }
    },
    async installSkillRow(row) {
      if (this.marketBusyId) return
      this.marketBusyId = row.id
      try {
        await installMarketSkill(row.id)
        uni.showToast({ title: this.$t('market.genericInstalledToast'), icon: 'none' })
        await this.reloadAll()
        uni.$emit('awd:market-changed-from-sidebar')
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.marketBusyId = ''
      }
    },
    async installPluginRow(row) {
      if (this.pluginBusyId) return
      const m = row.raw
      const perms = (m.permissions || []).join(this.$t('market.listSeparator')) || this.$t('market.noSensitiveCapability')
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('market.confirmInstallPluginTitle'),
          content: this.$t('market.confirmInstallPluginContent', {
            name: m.name || m.id,
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
      this.pluginBusyId = row.id
      try {
        await installMarketPlugin(row.id)
        uni.showToast({ title: this.$t('market.installedNoExecuteHint'), icon: 'none' })
        await this.reloadAll()
        uni.$emit('awd:market-changed-from-sidebar')
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.installFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.pluginBusyId = ''
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
          icon: 'none',
        })
        await this.reloadAll()
      } catch (e) {
        console.error('重新扫描失败:', e)
        uni.showToast({ title: e?.message || this.$t('market.scanFailedNeedAdmin'), icon: 'none' })
      } finally {
        this.rescanning = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
/* VS Code 扩展栏密度 + 产品浅色体系（森林绿 #1A5336 / mint #5BD197 点缀） */
.msb {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

.msb-search {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 10px 6px;
  height: 28px;
  padding: 0 8px;
  background: #fff;
  border: 1px solid #E9ECEF;
  border-radius: 6px;

  &:focus-within {
    border-color: #5BD197;
    box-shadow: 0 0 0 2px rgba(91, 209, 151, 0.15);
  }
}

.msb-search-icon {
  width: 13px;
  height: 13px;
  color: #ADB5BD;
  flex-shrink: 0;
}

.msb-search-input {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #2C3338;
  background: transparent;
  border: none;
  outline: none;
  height: 26px;
  line-height: 26px;
}

.msb-search-clear {
  width: 16px;
  height: 16px;
  line-height: 15px;
  text-align: center;
  border-radius: 4px;
  color: #ADB5BD;
  font-size: 13px;
  cursor: pointer;

  &:hover {
    background: #F1F3F5;
    color: #2C3338;
  }
}

.msb-body {
  flex: 1;
  min-height: 0;
}

/* 分组头：VS Code 式全大写小字 + 计数 */
.msb-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px 0 6px;
  cursor: pointer;
  user-select: none;

  &:hover {
    background: rgba(26, 83, 54, 0.04);
  }
}

.msb-sec-chevron {
  width: 12px;
  font-size: 12px;
  color: #868E96;
  transition: transform 0.12s ease;
  transform-origin: center;

  &.open {
    transform: rotate(90deg);
  }
}

.msb-sec-title {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #495057;
}

.msb-sec-count {
  font-size: 10px;
  color: #868E96;
  background: #F1F3F5;
  border-radius: 999px;
  padding: 0 6px;
  line-height: 14px;
  margin-left: 2px;
}

.msb-sec-spacer {
  flex: 1;
}

.msb-sec-action {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  color: #868E96;
  cursor: pointer;

  &:hover {
    background: #E8F3ED;
    color: #1A5336;
  }
}

.msb-sec-action-icon {
  width: 12px;
  height: 12px;

  &.spinning {
    animation: msb-spin 0.9s linear infinite;
  }
}

@keyframes msb-spin {
  to { transform: rotate(360deg); }
}

/* 「已安装」子分组头：折叠头（.msb-sec-head）的视觉降级——更小字号、无 chevron、
   不可折叠。分组类型已经在这写明了，行内不再重复"面板插件/Skill"标签。 */
.msb-subsec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 4px 10px 2px 22px;
}

.msb-subsec-title {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: #ADB5BD;
}

.msb-empty {
  padding: 8px 12px 10px 22px;
  font-size: 11px;
  color: #ADB5BD;

  &.msb-error {
    color: #B4552D;
  }
}

/* 列表行：紧凑三行（名称/描述/元信息），hover 出安装钮 */
.msb-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 10px 6px 12px;
  cursor: pointer;

  &:hover {
    background: rgba(26, 83, 54, 0.05);
  }
}

.msb-row-glyph {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  margin-top: 1px;
  border-radius: 6px;
  background: #E8F3ED;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1A5336;

  svg {
    width: 14px;
    height: 14px;
  }

  /* 插件 = 可执行扩展：深底图标与 Skill（浅底）一眼区分 */
  &.is-plugin {
    background: #123A26;
    color: #fff;
  }
}

.msb-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.msb-row-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #2C3338;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.msb-row-desc {
  font-size: 11px;
  line-height: 15px;
  color: #6C757D;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.msb-row-meta {
  font-size: 10px;
  color: #ADB5BD;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.msb-row-install {
  flex-shrink: 0;
  align-self: center;
  height: 20px;
  line-height: 18px;
  padding: 0 8px;
  border-radius: 4px;
  background: #1A5336;
  border: 1px solid #1A5336;
  cursor: pointer;

  text {
    font-size: 10px;
    font-weight: 600;
    color: #fff;
  }

  &:hover {
    background: #123A26;
  }

  &.busy {
    opacity: 0.6;
    pointer-events: none;
  }

  /* 付费未购：描边而非实心，与「安装」区分开——点它去的是官网，不是本机动作 */
  &.buy {
    background: #fff;
    border-color: #1A5336;

    text {
      color: #1A5336;
    }

    &:hover {
      background: #E8F3ED;
    }
  }
}

.msb-row-state {
  flex-shrink: 0;
  align-self: center;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;

  &.ok {
    background: #E8F3ED;

    text {
      color: #1A5336;
      font-size: 10px;
    }
  }

  &.off {
    background: #F1F3F5;

    text {
      color: #868E96;
      font-size: 10px;
    }
  }

  /* 未连接账户：与顶栏「试用版」chip 同一族暖色，是引导不是报错 */
  &.need {
    background: #FDF7EC;
    cursor: pointer;

    text {
      color: #8A6D2F;
      font-size: 10px;
    }

    &:hover {
      background: #F7EBD5;
    }
  }

  /* 资源包下载中：与 .need 同一族暖色（都是「还没就绪」），不可点 */
  &.downloading {
    background: #FDF7EC;

    text {
      color: #8A6D2F;
      font-size: 10px;
    }
  }
}
</style>

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
            <text class="mdp-kind-badge">{{ spec.kind === 'plugin' ? '插件' : 'Skill' }}</text>
            <text v-if="installedInfo" class="mdp-installed-badge">已安装</text>
            <text v-if="marketInfo" class="mdp-price-badge" :class="{ paid: isPaidItem }">{{ priceBadge }}</text>
          </view>
          <view class="mdp-byline">
            <text v-if="display.author" class="mdp-byline-item mdp-author">{{ display.author }}</text>
            <text v-if="display.version" class="mdp-byline-item">v{{ display.version }}</text>
            <text v-if="display.downloads" class="mdp-byline-item">{{ display.downloads }} 下载</text>
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
                <text>购买 {{ priceText }}</text>
              </view>
              <view v-if="paidStateValue === 'need-account'" class="mdp-btn" @tap="goToAccountSettings">
                <text>去连接账户</text>
              </view>
              <view v-else class="mdp-btn" :class="{ busy }" @tap="onPurchasedRefresh">
                <text>{{ busy ? '刷新中…' : '我已购买，刷新' }}</text>
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
                <text>{{ busy ? '安装中…' : '安装' }}</text>
              </view>
              <view
                v-else-if="marketInfo && installedInfo"
                class="mdp-btn"
                :class="{ busy }"
                @tap="doInstallSkill"
              >
                <text>{{ busy ? '处理中…' : '重新安装 / 更新' }}</text>
              </view>
              <picker
                v-if="installedInfo && !installedInfo.sourcePluginId"
                :range="ACTIVATION_LABELS"
                :value="activationIndex"
                @change="onActivationChange"
              >
                <view class="mdp-btn">
                  <text>生效方式：{{ ACTIVATION_LABELS[activationIndex] }} ▾</text>
                </view>
              </picker>
              <text v-if="installedInfo && installedInfo.sourcePluginId" class="mdp-action-hint">随插件启停，不可单独设置</text>
              <view
                v-if="installedInfo && marketInfo && !installedInfo.sourcePluginId"
                class="mdp-btn danger"
                :class="{ busy }"
                @tap="doUninstallSkill"
              >
                <text>卸载</text>
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
                <text>{{ busy ? '安装中…' : '安装' }}</text>
              </view>
              <view v-if="installedInfo" class="mdp-switch-row">
                <text class="mdp-switch-label">{{ installedInfo.enabled ? '已启用' : '已停用' }}</text>
                <switch :checked="!!installedInfo.enabled" color="#1A5336" style="transform: scale(0.7);" @change="onPluginToggle" />
              </view>
              <view
                v-if="installedInfo && marketInfo"
                class="mdp-btn danger"
                :class="{ busy }"
                @tap="doUninstallPlugin"
              >
                <text>卸载</text>
              </view>
            </template>
          </view>

          <text v-if="purchaseHint" class="mdp-purchase-hint">{{ purchaseHint }}</text>
        </view>
      </view>

      <view class="mdp-divider"></view>

      <view v-if="loading" class="mdp-loading"><text>加载中…</text></view>
      <view v-else-if="!marketInfo && !installedInfo" class="mdp-loading">
        <text>{{ loadError ? '加载失败：' + loadError : '没有找到这一项（可能已从广场下架或本机已卸载）' }}</text>
      </view>

      <template v-else>
        <!-- 适用场景（Skill）：把触发词翻译成「什么时候找它」，工具清单不在这里刷存在感 -->
        <view v-if="spec.kind === 'skill' && triggerList.length" class="mdp-section">
          <text class="mdp-sec-title">什么时候用</text>
          <view class="mdp-scenario">
            <text class="mdp-scenario-lead">在对话里说出类似这些诉求时，它会自动接管：</text>
            <view class="mdp-triggers">
              <text v-for="(t, i) in triggerList" :key="i" class="mdp-trigger">「{{ t }}」</text>
            </view>
            <text class="mdp-sec-note">也可以在输入框用 / 手动选用；生效方式可随时在本页调整。</text>
          </view>
        </view>

        <!-- 声明能力（插件）：安全相关，保持显眼 -->
        <view v-if="spec.kind === 'plugin'" class="mdp-section">
          <text class="mdp-sec-title">声明能力</text>
          <text class="mdp-sec-body">{{ pluginPermissionText }}</text>
          <text class="mdp-sec-note">插件与本机应用同等权限。平台已人工审核并签名，安装后默认停用，启用前不会执行任何插件代码。</text>
        </view>

        <!-- 详细信息：工具权限压缩为一行人话摘要，不再枚举内部工具名 -->
        <view class="mdp-section">
          <text class="mdp-sec-title">详细信息</text>
          <view class="mdp-kv">
            <view v-if="spec.kind === 'skill' && toolSummary" class="mdp-kv-row">
              <text class="mdp-k">工具权限</text><text class="mdp-v">{{ toolSummary }}</text>
            </view>
            <view v-if="marketInfo" class="mdp-kv-row">
              <text class="mdp-k">价格</text><text class="mdp-v">{{ priceDetailText }}</text>
            </view>
            <view class="mdp-kv-row"><text class="mdp-k">标识</text><text class="mdp-v mono">{{ spec.id }}</text></view>
            <view v-if="display.version" class="mdp-kv-row"><text class="mdp-k">版本</text><text class="mdp-v">v{{ display.version }}</text></view>
            <view v-if="display.author" class="mdp-kv-row"><text class="mdp-k">作者</text><text class="mdp-v">{{ display.author }}</text></view>
            <view v-if="display.updatedAt" class="mdp-kv-row"><text class="mdp-k">更新于</text><text class="mdp-v">{{ display.updatedAt }}</text></view>
            <view class="mdp-kv-row"><text class="mdp-k">来源</text><text class="mdp-v">{{ sourceText }}</text></view>
            <view v-if="display.homepage" class="mdp-kv-row">
              <text class="mdp-k">主页</text>
              <text class="mdp-v mdp-link" @tap="$emit('open-url', display.homepage)">{{ display.homepage }}</text>
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
import { getPlugins, getSkills, getSkillMarket, getPluginMarket, installMarketSkill, uninstallMarketSkill, installMarketPlugin, uninstallMarketPlugin, setPluginEnabled, setSkillActivation } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { formatPrice, isPaid, paidState, priceCentsOf, priceLabel, purchaseUrl } from '@/utils/marketPricing.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'

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
  contract: '合同',
  litigation: '诉讼与争议',
  compliance: '合规风控',
  research: '法律研究',
  corporate: '公司与投融资',
  office: '办公效率',
  other: '其他',
}

const PERMISSION_LABELS = {
  file_read: '读取文件',
  file_write: '写入文件',
  network: '网络访问',
  editor: '编辑器',
}

const ACTIVATION_MODES = ['auto', 'manual', 'disabled']
const ACTIVATION_LABELS = ['自动触发', '仅手动', '停用']

export default {
  name: 'MarketDetailPane',
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
      if (!this.isPaidItem) return '免费'
      const base = this.priceText + '（一次性买断）'
      return this.marketInfo && this.marketInfo.purchased ? base + ' · 已购买' : base
    },
    purchaseHint() {
      if (!this.needsPurchase) return ''
      if (this.paidStateValue === 'need-account') {
        return '这是付费项目。请先在设置的「账户与用量」中连接 AI Workdeck 账户，安装时才能校验购买记录。'
      }
      return '这是付费项目。购买在官网完成，付款后回到本页点「我已购买，刷新」即可安装。'
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
        downloads: downloads ? (downloads >= 10000 ? (downloads / 10000).toFixed(1) + 'w' : downloads >= 1000 ? (downloads / 1000).toFixed(1) + 'k' : String(downloads)) : '',
        categoryLabel: this.spec.kind === 'skill' && cat ? (CATEGORY_LABELS[cat] || '其他') : '',
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
        return '插件是可执行扩展：为工作台增加新功能，与本机应用同等权限，经平台人工审核并签名分发。'
      }
      return 'Skill 是提示词工作流：教 AI 助手按一套业务方法做事，纯文本、不含可执行代码。'
    },
    /** 工具名是给机器看的；给用户压缩成能力域的人话摘要 */
    toolSummary() {
      const item = this.marketInfo || this.installedInfo || {}
      const tools = item.allowedTools || []
      if (!tools.length) return ''
      const domains = []
      const has = (re) => tools.some(t => re.test(t))
      if (has(/^doc_|^read_document|^write_docx|_docx$/)) domains.push('文档读写')
      if (has(/^sheet_/)) domains.push('电子表格')
      if (has(/^pptx_/)) domains.push('幻灯片')
      if (has(/^pdf_/)) domains.push('PDF 处理')
      if (has(/^law_|^get_law_/)) domains.push('法律检索')
      if (has(/search_web|browse_url|deep_search/)) domains.push('联网检索')
      if (has(/^read_file$|^write_file$|^list_files$|project_files|extract_file_text/)) domains.push('项目文件')
      if (has(/memory/)) domains.push('工作记忆')
      if (has(/evidence/)) domains.push('证据核查')
      const head = domains.length ? domains.join('、') : '通用对话'
      return `${head} 等 ${tools.length} 项（限定范围，仅在本 Skill 生效时可用）`
    },
    pluginPermissionText() {
      const item = this.marketInfo || this.installedInfo || {}
      const parts = []
      const toolCount = item.toolCount != null ? item.toolCount : (item.tools || []).length
      if (toolCount) parts.push(`提供 ${toolCount} 个工具`)
      const perms = (item.permissions || []).map(p => PERMISSION_LABELS[p] || p)
      parts.push(perms.length ? `需要 ${perms.join('、')}` : '未声明敏感能力')
      return parts.join(' · ')
    },
    activationIndex() {
      const s = this.installedInfo
      if (!s) return 0
      const mode = s.activationMode || (s.enabled ? 'auto' : 'disabled')
      const idx = ACTIVATION_MODES.indexOf(mode)
      return idx >= 0 ? idx : 0
    },
    sourceText() {
      if (this.installedInfo?.sourcePluginId) return `插件内置（${this.installedInfo.sourcePluginId}）`
      if (this.marketInfo) return '官网广场（平台签名分发）'
      return '本机'
    },
  },
  mounted() {
    this.reload()
    uni.$on('awd:market-changed-from-sidebar', this.reload)
  },
  beforeUnmount() {
    uni.$off('awd:market-changed-from-sidebar', this.reload)
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
        if (this.spec.kind === 'skill') {
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
          this.loadError = marketError.message || '在线广场不可用'
        }
      } catch (e) {
        console.error('加载详情失败:', e)
        this.loadError = e?.message || '网络不可用'
      } finally {
        this.loading = false
      }
    },
    notifyChanged() {
      uni.$emit('awd:market-changed')
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
        uni.showToast({ title: '还没查到这一项的购买记录，请确认已在官网完成支付', icon: 'none' })
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
        uni.showToast({ title: this.installedInfo ? '已更新' : '已安装', icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doUninstallSkill() {
      if (this.busy) return
      this.busy = true
      try {
        await uninstallMarketSkill(this.spec.id)
        uni.showToast({ title: '已卸载', icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('卸载 Skill 失败:', e)
        uni.showToast({ title: e?.message || '卸载失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doInstallPlugin() {
      if (this.busy) return
      const m = this.marketInfo || {}
      const perms = (m.permissions || []).map(p => PERMISSION_LABELS[p] || p).join('、') || '未声明敏感能力'
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: '确认安装插件',
          content: `${m.name || this.spec.id} v${m.version}\n作者：${m.authorDisplayName || m.author || '未知'}\n声明能力：${perms}\n\n插件与本机应用同等权限，能读写你的文件并访问网络。安装后默认停用，需你手动启用。`,
          confirmText: '安装',
          cancelText: '取消',
          success: r => resolve(r.confirm),
          fail: () => resolve(false),
        })
      })
      if (!ok) return
      this.busy = true
      try {
        await installMarketPlugin(this.spec.id)
        uni.showToast({ title: '已安装，请启用后使用', icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async doUninstallPlugin() {
      if (this.busy) return
      this.busy = true
      try {
        await uninstallMarketPlugin(this.spec.id)
        uni.showToast({ title: '已卸载', icon: 'none' })
        await this.reload()
        this.notifyChanged()
      } catch (e) {
        console.error('卸载插件失败:', e)
        uni.showToast({ title: e?.message || '卸载失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async onPluginToggle(event) {
      const enabled = !!(event?.detail?.value)
      try {
        await setPluginEnabled(this.spec.id, enabled)
        if (this.installedInfo) this.installedInfo.enabled = enabled
        uni.showToast({ title: enabled ? '已启用' : '已禁用', icon: 'none' })
        this.notifyChanged()
      } catch (e) {
        console.error('切换插件状态失败:', e)
        uni.showToast({ title: e?.message || '操作失败（需要管理员权限）', icon: 'none' })
        await this.reload()
      }
    },
    async onActivationChange(event) {
      const idx = Number(event?.detail?.value)
      const mode = ACTIVATION_MODES[idx]
      if (!mode || !this.installedInfo || mode === ACTIVATION_MODES[this.activationIndex]) return
      try {
        await setSkillActivation(this.spec.id, mode)
        this.installedInfo.activationMode = mode
        this.installedInfo.enabled = mode !== 'disabled'
        uni.showToast({ title: `已设为「${ACTIVATION_LABELS[idx]}」`, icon: 'none' })
        this.notifyChanged()
      } catch (e) {
        console.error('设置生效方式失败:', e)
        uni.showToast({ title: e?.message || '操作失败（需要管理员权限）', icon: 'none' })
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
  background: #fff;
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
  background: #E8F3ED;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1A5336;

  svg {
    width: 34px;
    height: 34px;
  }

  /* 插件 = 可执行扩展：深底，与 Skill（浅底）一眼区分（同左栏列表约定） */
  &.is-plugin {
    background: #123A26;
    color: #fff;
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
  color: #123A26;
  line-height: 1.25;
}

.mdp-kind-badge {
  font-size: 10px;
  font-weight: 600;
  color: #1A5336;
  background: #E8F3ED;
  border-radius: 4px;
  padding: 1px 6px;
}

.mdp-installed-badge {
  font-size: 10px;
  font-weight: 600;
  color: #fff;
  background: #1A5336;
  border-radius: 4px;
  padding: 1px 6px;
}

/* 价格徽章：免费用中性灰，付费用暖色（与「试用版」chip 同族），不做成促销红 */
.mdp-price-badge {
  font-size: 10px;
  font-weight: 600;
  color: #868E96;
  background: #F1F3F5;
  border-radius: 4px;
  padding: 1px 6px;

  &.paid {
    color: #8A6D2F;
    background: #FDF7EC;
  }
}

.mdp-purchase-hint {
  display: block;
  margin-top: 8px;
  font-size: 11px;
  line-height: 17px;
  color: #8A6D2F;
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
  color: #6C757D;

  & + .mdp-byline-item::before {
    content: '·';
    margin-right: 6px;
    color: #CED4DA;
  }
}

.mdp-author {
  color: #1A5336;
  font-weight: 600;
}

.mdp-kind-note {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  line-height: 16px;
  color: #868E96;
}

.mdp-summary {
  display: block;
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: #2C3338;
}

.mdp-scenario {
  margin-top: 10px;
}

.mdp-scenario-lead {
  display: block;
  font-size: 12px;
  color: #6C757D;
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
  border: 1px solid #CED4DA;
  background: #fff;
  cursor: pointer;

  text {
    font-size: 12px;
    font-weight: 600;
    color: #2C3338;
  }

  &:hover {
    border-color: #1A5336;

    text {
      color: #1A5336;
    }
  }

  &.primary {
    background: #1A5336;
    border-color: #1A5336;

    text {
      color: #fff;
    }

    &:hover {
      background: #123A26;

      text {
        color: #fff;
      }
    }
  }

  &.danger:hover {
    border-color: #C0392B;

    text {
      color: #C0392B;
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
  color: #6C757D;
}

.mdp-action-hint {
  font-size: 11px;
  color: #ADB5BD;
}

.mdp-divider {
  height: 1px;
  background: #E9ECEF;
  margin: 22px 0 18px;
}

.mdp-loading {
  padding: 24px 0;
  font-size: 13px;
  color: #868E96;
}

.mdp-section {
  margin-bottom: 22px;
}

.mdp-sec-title {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: #123A26;
  margin-bottom: 8px;
}

.mdp-sec-body {
  display: block;
  font-size: 13px;
  line-height: 20px;
  color: #2C3338;
}

.mdp-sec-note {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  line-height: 17px;
  color: #ADB5BD;
}

.mdp-triggers {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.mdp-trigger {
  font-size: 13px;
  color: #1A5336;
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
  color: #868E96;
}

.mdp-v {
  font-size: 12px;
  color: #2C3338;
  word-break: break-all;

  &.mono {
    font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  }
}

.mdp-link {
  color: #1A5336;
  text-decoration: underline;
  cursor: pointer;
}
</style>

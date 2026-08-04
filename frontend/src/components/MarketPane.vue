<template>
  <view class="market-pane">
    <!-- Hero：深林绿编辑排版，与官网 /skills 同一套语言（见 aiworkdeckweb/DESIGN.md） -->
    <view class="hero">
      <view class="hero-grain"></view>
      <text class="hero-watermark">技</text>
      <view class="hero-inner">
        <view class="hero-main">
          <view class="eyebrow">
            <text class="eyebrow-line"></text>
            <text class="eyebrow-text">EXTENSIONS · 能力扩展</text>
          </view>
          <text class="hero-title">插件广场</text>
          <text class="hero-sub">官网广场的公开 Skill 与经人工审核、平台签名的插件，一键装到本机</text>
          <view class="hero-stats">
            <view class="stat">
              <text class="stat-num">{{ marketSkills.length }}</text>
              <text class="stat-label">在线 Skill</text>
            </view>
            <text class="stat-sep"></text>
            <view class="stat">
              <text class="stat-num">{{ marketPlugins.length }}</text>
              <text class="stat-label">在线插件</text>
            </view>
            <text class="stat-sep"></text>
            <view class="stat">
              <text class="stat-num">{{ installedCount }}</text>
              <text class="stat-label">本机已安装</text>
            </view>
          </view>
        </view>
        <view class="hero-actions">
          <!-- 返回位仅整页（standalone）时渲染；嵌入 workbench 时由外壳的 tab 负责关闭 -->
          <view v-if="standalone" class="btn-ghost" @tap="goBack">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.arrowLeft" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text>返回</text>
          </view>
          <view class="btn-light" :class="{ 'is-busy': rescanning }" @tap="rescan">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.refresh" :key="i" :d="d" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text>{{ rescanning ? '扫描中' : '重新扫描' }}</text>
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
              <input class="search-input" v-model="searchText" placeholder="搜索名称、描述或触发词" confirm-type="search" />
            </view>
          </view>

          <view v-if="marketError" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.offline" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">在线广场暂不可用</text>
            <text class="empty-hint">{{ marketError }}——本机已安装的 Skill 与插件不受影响</text>
          </view>
          <view v-else-if="filteredMarketSkills.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.search" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ marketLoading ? '正在载入广场' : (marketSkills.length ? '没有匹配的 Skill' : '广场暂时空着') }}</text>
            <text v-if="!marketLoading" class="empty-hint">{{ marketSkills.length ? '换个关键词或分类试试' : '去官网 Skill 广场提交你的第一个 Skill' }}</text>
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
                      <text class="kicker-on">已安装</text>
                    </template>
                  </view>
                  <text class="card-title">{{ m.name || m.id }}</text>
                  <text class="card-id">{{ m.id }} · v{{ m.version || '1.0.0' }}</text>
                </view>
                <view class="card-actions">
                  <view class="act-primary" :class="{ 'is-busy': !!marketBusyId }" @tap="installSkill(m)">
                    {{ marketBusyId === m.id ? '处理中' : (m.installed ? '更新' : '安装') }}
                  </view>
                  <view v-if="m.installed" class="act-remove" @tap="uninstallSkill(m)">卸载</view>
                </view>
              </view>

              <text class="card-desc">{{ m.description || '暂无描述' }}</text>
              <text v-if="triggerLine(m.triggers)" class="card-triggers">{{ triggerLine(m.triggers) }}</text>

              <view class="card-foot">
                <text class="foot-author">{{ m.authorDisplayName || m.author || '匿名作者' }}</text>
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
            <text class="empty-title">在线插件广场暂不可用</text>
            <text class="empty-hint">{{ marketPluginError }}——本机已安装的插件不受影响</text>
          </view>
          <view v-else-if="marketPlugins.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">{{ marketPluginLoading ? '正在载入广场' : '还没有上架的插件' }}</text>
            <text v-if="!marketPluginLoading" class="empty-hint">也可本地安装：把插件目录放进服务端 plugins/ 后点「重新扫描」</text>
          </view>

          <view v-else class="card-grid">
            <view v-for="m in marketPlugins" :key="m.id" class="ed-card">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>插件</text>
                    <template v-if="m.installed">
                      <text class="kicker-sep"></text>
                      <text class="kicker-on">已安装</text>
                    </template>
                  </view>
                  <text class="card-title">{{ m.name || m.id }}</text>
                  <text class="card-id">{{ m.id }} · v{{ m.version || '1.0.0' }} · {{ formatSize(m.size) }}</text>
                </view>
                <view class="card-actions">
                  <view
                    v-if="!m.installed || m.updatable"
                    class="act-primary"
                    :class="{ 'is-busy': !!pluginBusyId }"
                    @tap="installPlugin(m)"
                  >{{ pluginBusyId === m.id ? '安装中' : (m.updatable ? '更新' : '安装') }}</view>
                  <view v-if="m.installed" class="act-remove" @tap="uninstallPlugin(m)">卸载</view>
                </view>
              </view>

              <text class="card-desc">{{ m.description || '暂无描述' }}</text>
              <text class="card-caps">{{ capabilityLine(m) }}</text>

              <view class="card-foot">
                <text class="foot-author">{{ m.authorDisplayName || m.author || '匿名作者' }}</text>
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
            <text class="note-text">插件与本机应用同等权限，安装前请确认来源可信。所有在线插件都经过人工审核与平台签名，桌面端安装时验签；安装后默认停用，需你在「已安装」里手动启用。</text>
          </view>
        </template>

        <!-- ============ 已安装 ============ -->
        <template v-else>
          <view class="section-head">
            <text class="section-title">插件</text>
            <text class="section-sub">独立能力：自带工具与界面，启用后显示在左栏</text>
          </view>

          <view v-if="loading" class="empty">
            <text class="empty-title">正在读取本机插件</text>
          </view>
          <view v-else-if="plugins.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">本机还没有插件</text>
            <text class="empty-hint">把插件目录（含 manifest.json）放进服务端 plugins/ 后点「重新扫描」</text>
          </view>
          <view v-else class="card-grid">
            <view v-for="p in plugins" :key="p.id" class="ed-card" :class="{ off: !p.enabled }">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in ICONS.blocks" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>插件</text>
                    <text class="kicker-sep"></text>
                    <text :class="p.enabled ? 'kicker-on' : 'kicker-off'">{{ p.enabled ? '已启用' : '已停用' }}</text>
                  </view>
                  <text class="card-title">{{ p.name || p.id }}</text>
                  <text class="card-id">{{ p.id }}<template v-if="p.version"> · v{{ p.version }}</template><template v-if="p.author"> · {{ p.author }}</template></text>
                </view>
                <switch
                  class="plugin-switch"
                  color="#1A5336"
                  :checked="p.enabled"
                  :disabled="switching"
                  @change="onToggle(p, $event)"
                />
              </view>

              <text class="card-desc">{{ p.description || '暂无描述' }}</text>
              <text class="card-caps">{{ capabilityLine(p) }}</text>

              <view class="tool-list" v-if="p.tools && p.tools.length">
                <view v-for="t in p.tools" :key="t.name" class="tool-item">
                  <text class="tool-name">{{ t.name }}</text>
                  <text class="tool-desc">{{ t.description }}</text>
                </view>
              </view>
            </view>
          </view>

          <!-- Skill 区块（规范见 docs/SKILL_SPEC.md） -->
          <view class="section-head">
            <text class="section-title">Skill</text>
            <text class="section-sub">提示词能力：在对话中生效，可设置生效方式</text>
          </view>
          <view v-if="skills.length === 0" class="empty">
            <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, i) in ICONS.skill" :key="i" :d="d" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <text class="empty-title">本机还没有 Skill</text>
            <text class="empty-hint">去「Skill 广场」装一个，或把 Skill 目录（含 skill.yml）放进服务端 skills/ 后点「重新扫描」</text>
          </view>
          <view v-else class="card-grid">
            <view v-for="s in skills" :key="s.id" class="ed-card" :class="{ off: !s.enabled }">
              <view class="card-head">
                <view class="head-text">
                  <view class="kicker">
                    <svg class="kicker-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, i) in categoryGlyph(s.category)" :key="i" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text>{{ categoryLabel(s.category) }}</text>
                    <template v-if="s.sourcePluginId">
                      <text class="kicker-sep"></text>
                      <text>来自插件 {{ s.sourcePluginId }}</text>
                    </template>
                  </view>
                  <text class="card-title">{{ s.name || s.id }}</text>
                  <text class="card-note">{{ activationHint(s) }}</text>
                </view>
                <!-- 插件携带的 Skill 跟随插件启停，不单独设生效方式 -->
                <picker
                  v-if="!s.sourcePluginId"
                  class="mode-picker"
                  mode="selector"
                  :range="ACTIVATION_LABELS"
                  :value="activationIndex(s)"
                  :disabled="switching"
                  @change="onActivationChange(s, $event)"
                >
                  <view class="mode-value">
                    <text>{{ ACTIVATION_LABELS[activationIndex(s)] }}</text>
                    <text class="mode-caret">▾</text>
                  </view>
                </picker>
                <switch v-else class="plugin-switch" color="#1A5336" :checked="s.enabled" disabled />
              </view>

              <text class="card-desc">{{ s.description || '暂无描述' }}</text>
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
import { ICONS } from '@/config/icons.js'

const PERMISSION_LABELS = {
  file_read: '读取文件',
  file_write: '写入文件',
  network: '网络访问',
  editor: '编辑器',
}

// 分类沿用官网 SKILL_CATEGORIES（aiworkdeckweb lib/skill-categories）；registry 未返回 category 时归入"其他"
const SKILL_CATEGORIES = [
  { id: 'all', label: '全部' },
  { id: 'contract', label: '合同' },
  { id: 'litigation', label: '诉讼与争议' },
  { id: 'compliance', label: '合规风控' },
  { id: 'research', label: '法律研究' },
  { id: 'corporate', label: '公司与投融资' },
  { id: 'office', label: '办公效率' },
  { id: 'other', label: '其他' },
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
  { key: 'skill', label: 'Skill 广场' },
  { key: 'plugin', label: '插件广场' },
  { key: 'installed', label: '已安装' },
]

// Skill 生效方式三档，顺序与 picker 下标一一对应
const ACTIVATION_MODES = ['auto', 'manual', 'disabled']
const ACTIVATION_LABELS = ['自动触发', '仅手动', '停用']

// 卡片上最多平铺 3 个触发词，其余折成 +N
const TRIGGER_PREVIEW = 3

export default {
  name: 'MarketPane',
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
    this.loadPlugins()
    this.loadSkills()
    this.loadMarket()
    this.loadPluginMarket()
  },
  methods: {
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
      if (toolCount) parts.push(`${toolCount} 个工具`)
      const perms = (item.permissions || []).map(p => this.permissionLabel(p))
      parts.push(perms.length ? `需要 ${perms.join('、')}` : '未声明敏感能力')
      return parts.join(' · ')
    },
    /** 触发词按官网排版收成一行「引号」，超出折为 +N */
    triggerLine(triggers) {
      const list = triggers || []
      if (!list.length) return ''
      const head = list.slice(0, TRIGGER_PREVIEW).map(t => `「${t}」`).join(' ')
      return list.length > TRIGGER_PREVIEW ? `${head} +${list.length - TRIGGER_PREVIEW}` : head
    },
    categoryGlyph(id) {
      return CATEGORY_GLYPHS[id] || CATEGORY_GLYPHS.other
    },
    formatSize(bytes) {
      if (!bytes) return '未知大小'
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
      } catch (e) {
        // 注册表不可达只在区块内提示，不影响本地插件与 Skill
        console.warn('在线插件广场不可用:', e)
        this.marketPluginError = e?.message || '网络不可用'
        this.marketPlugins = []
      } finally {
        this.marketPluginLoading = false
      }
    },
    async installPlugin(plugin) {
      if (this.pluginBusyId) return
      const perms = (plugin.permissions || []).map(p => this.permissionLabel(p)).join('、') || '未声明敏感能力'
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: '确认安装插件',
          content: `${plugin.name || plugin.id} v${plugin.version}\n作者：${plugin.authorDisplayName || plugin.author || '未知'}\n声明能力：${perms}\n\n插件与本机应用同等权限，能读写你的文件并访问网络。安装后默认停用，需你手动启用。`,
          confirmText: '安装',
          cancelText: '取消',
          success: r => resolve(r.confirm),
          fail: () => resolve(false)
        })
      })
      if (!ok) return

      this.pluginBusyId = plugin.id
      try {
        await installMarketPlugin(plugin.id)
        uni.showToast({ title: '已安装，请在「已安装」中启用', icon: 'none' })
        await this.loadPlugins()
        await this.loadPluginMarket()
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.pluginBusyId = ''
      }
    },
    async uninstallPlugin(plugin) {
      if (this.pluginBusyId) return
      this.pluginBusyId = plugin.id
      try {
        await uninstallMarketPlugin(plugin.id)
        uni.showToast({ title: '已卸载', icon: 'none' })
        await this.loadPlugins()
        await this.loadPluginMarket()
      } catch (e) {
        console.error('卸载插件失败:', e)
        uni.showToast({ title: e?.message || '卸载失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.pluginBusyId = ''
      }
    },
    categoryLabel(id) {
      const c = SKILL_CATEGORIES.find(c => c.id === (id || 'other'))
      return c ? c.label : '其他'
    },
    async loadPlugins() {
      this.loading = true
      try {
        const res = await getPlugins()
        this.plugins = Array.isArray(res) ? res : (res?.data || [])
      } catch (e) {
        console.error('加载插件列表失败:', e)
        uni.showToast({ title: '加载插件列表失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    async onToggle(plugin, event) {
      const enabled = !!(event?.detail?.value)
      this.switching = true
      try {
        await setPluginEnabled(plugin.id, enabled)
        plugin.enabled = enabled
        uni.showToast({ title: enabled ? '已启用' : '已禁用', icon: 'none' })
      } catch (e) {
        console.error('切换插件状态失败:', e)
        // 回滚开关显示
        plugin.enabled = !enabled
        uni.showToast({ title: e?.message || '操作失败（需要管理员权限）', icon: 'none' })
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
        uni.showToast({ title: '加载 Skill 列表失败', icon: 'none' })
      }
    },
    activationIndex(skill) {
      const mode = skill.activationMode || (skill.enabled ? 'auto' : 'disabled')
      const idx = ACTIVATION_MODES.indexOf(mode)
      return idx >= 0 ? idx : 0
    },
    activationHint(skill) {
      if (skill.sourcePluginId) return '随插件启停，不可单独设置'
      const mode = ACTIVATION_MODES[this.activationIndex(skill)]
      if (mode === 'manual') return '仅在对话中手动选用时生效'
      if (mode === 'disabled') return '已停用'
      return '命中触发词时自动生效'
    },
    async onActivationChange(skill, event) {
      const idx = Number(event?.detail?.value)
      const mode = ACTIVATION_MODES[idx]
      if (!mode || mode === ACTIVATION_MODES[this.activationIndex(skill)]) return
      const previous = skill.activationMode
      this.switching = true
      try {
        await setSkillActivation(skill.id, mode)
        skill.activationMode = mode
        skill.enabled = mode !== 'disabled'
        uni.showToast({ title: `已设为「${ACTIVATION_LABELS[idx]}」`, icon: 'none' })
      } catch (e) {
        console.error('设置 Skill 生效方式失败:', e)
        skill.activationMode = previous
        uni.showToast({ title: e?.message || '操作失败（需要管理员权限）', icon: 'none' })
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
      } catch (e) {
        // 注册表不可达只在区块内提示，不弹 toast、不影响本地插件 / Skill 区块
        console.warn('在线广场不可用:', e)
        this.marketError = e?.message || '网络不可用'
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
        uni.showToast({ title: skill.installed ? '已更新' : '已安装', icon: 'none' })
        await this.loadSkills()
        await this.loadMarket()
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.marketBusyId = ''
      }
    },
    async uninstallSkill(skill) {
      if (this.marketBusyId) return
      this.marketBusyId = skill.id
      try {
        await uninstallMarketSkill(skill.id)
        uni.showToast({ title: '已卸载', icon: 'none' })
        await this.loadSkills()
        await this.loadMarket()
      } catch (e) {
        console.error('卸载 Skill 失败:', e)
        uni.showToast({ title: e?.message || '卸载失败（需要管理员权限）', icon: 'none' })
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
          title: `扫描完成：${res?.pluginCount ?? 0} 个插件、${skillRes?.skillCount ?? 0} 个 Skill`,
          icon: 'none'
        })
        await this.loadPlugins()
        await this.loadSkills()
        await this.loadPluginMarket()
      } catch (e) {
        console.error('重新扫描失败:', e)
        uni.showToast({ title: e?.message || '扫描失败（需要管理员权限）', icon: 'none' })
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
$forest: #1A5336;
$forest-darker: #123A26;
$forest-lightest: #E8F3ED;
$mint: #5BD197;

$dark-bg: #212629;
$gray-dark: #2C3338;
$gray-medium: #6C757D;
$gray-light: #E9ECEF;
$gray-pale: #F8F9FA;

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
  background: $gray-pale;
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
  background: linear-gradient(135deg, $forest-darker 0%, #16452D 55%, $forest-darker 100%);
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
  background: rgba(91, 209, 151, 0.5);
}

.eyebrow-text {
  font-size: 11px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: rgba(91, 209, 151, 0.8);
}

.hero-title {
  @include display-serif;
  font-size: 38px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.01em;
  color: #fff;
  margin-bottom: 10px;
}

.hero-sub {
  font-size: 14px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.6);
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
  color: #fff;
}

.stat-label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
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
  color: rgba(255, 255, 255, 0.75);
  border: 1px solid rgba(255, 255, 255, 0.22);

  &:hover {
    color: #fff;
    border-color: rgba(255, 255, 255, 0.45);
    background: rgba(255, 255, 255, 0.06);
  }
}

.btn-light {
  color: $forest-darker;
  background: #fff;
  border: 1px solid #fff;
  font-weight: 600;

  &:hover { background: #F1F5F2; }

  &.is-busy {
    opacity: 0.6;
    pointer-events: none;
  }
}

/* ---------- 主页签：编辑式下划线 ---------- */
.tab-bar {
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(233, 236, 239, 0.8);
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
  color: $gray-medium;
  padding: 14px 0;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.2s;
  white-space: nowrap;

  &:hover { color: $dark-bg; }

  &.active {
    color: $forest;
    font-weight: 600;
    border-bottom-color: $forest;
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
  border-bottom: 1px solid rgba(233, 236, 239, 0.9);
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
  color: $gray-medium;
  padding: 8px 0 11px;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.2s;
  white-space: nowrap;

  &:hover { color: $dark-bg; }

  &.active {
    color: $forest;
    font-weight: 600;
    border-bottom-color: $forest;
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
  background: #fff;
  border: 1px solid $gray-light;
  border-radius: 6px;
  padding: 7px 12px;
  margin-bottom: 8px;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:hover { border-color: #D3DAD8; }

  &:focus-within {
    border-color: $mint;
    box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.15);
  }
}

.search-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: $gray-medium;
}

.search-input {
  font-size: 13px;
  color: $gray-dark;
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
  border-bottom: 1px solid rgba(233, 236, 239, 0.9);

  &:not(:first-child) { margin-top: 36px; }
}

.section-title {
  @include display-serif;
  font-size: 22px;
  font-weight: 700;
  color: $dark-bg;
}

.section-sub {
  font-size: 13px;
  color: $gray-medium;
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
  background: rgba(255, 255, 255, 0.65);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(233, 236, 239, 0.9);
  transition: transform 0.3s ease, border-color 0.3s ease, box-shadow 0.3s ease;

  /* hover 时顶部浮起一条品牌色细线 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    top: 0;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(26, 83, 54, 0.6), transparent);
    opacity: 0;
    transition: opacity 0.3s ease;
  }

  &:hover {
    transform: translateY(-4px);
    border-color: rgba(26, 83, 54, 0.25);
    box-shadow: 0 18px 40px -18px rgba(18, 58, 38, 0.25);

    &::before { opacity: 1; }
  }

  &.off {
    opacity: 0.55;

    &:hover {
      transform: none;
      box-shadow: none;
      border-color: rgba(233, 236, 239, 0.9);

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
  color: $gray-medium;
}

.kicker-icon {
  width: 13px;
  height: 13px;
  color: rgba(26, 83, 54, 0.7);
  flex-shrink: 0;
}

.kicker-sep {
  width: 1px;
  height: 11px;
  background: $gray-light;
}

.kicker-on {
  color: $forest;
  font-weight: 600;
}

.kicker-off {
  color: #A0A8AD;
}

.card-title {
  @include display-serif;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.35;
  color: $dark-bg;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-id {
  @include mono;
  font-size: 11px;
  color: rgba(108, 117, 125, 0.8);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 中文说明句：与 card-id 同位同色，但不用等宽 */
.card-note {
  font-size: 12px;
  color: rgba(108, 117, 125, 0.9);
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
  color: #fff;
  background: $forest;
  border: 1px solid $forest;
  border-radius: 6px;
  padding: 5px 16px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover { background: $forest-darker; }

  &.is-busy {
    opacity: 0.5;
    pointer-events: none;
  }
}

.act-remove {
  font-size: 12px;
  text-align: center;
  color: $gray-medium;
  border: 1px solid $gray-light;
  border-radius: 6px;
  padding: 5px 16px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    color: #C0392B;
    border-color: rgba(192, 57, 43, 0.4);
  }
}

.card-desc {
  font-size: 13px;
  line-height: 1.7;
  color: $gray-medium;
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
  color: rgba(26, 83, 54, 0.8);
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
  color: rgba(108, 117, 125, 0.9);
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
  border-top: 1px solid rgba(233, 236, 239, 0.7);
  font-size: 12px;
  color: $gray-medium;
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

/* 已安装卡片上的控件 */
.plugin-switch {
  transform: scale(0.78);
  transform-origin: right center;
  flex-shrink: 0;
}

.mode-picker {
  flex-shrink: 0;
}

.mode-value {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: $gray-dark;
  background: #fff;
  border: 1px solid $gray-light;
  border-radius: 6px;
  padding: 5px 10px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;

  &:hover {
    color: $forest;
    border-color: $mint;
  }
}

.mode-caret {
  font-size: 10px;
  color: #94A3B8;
}

/* 工具清单 */
.tool-list {
  border-top: 1px solid rgba(233, 236, 239, 0.7);
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
  color: $forest;
  flex-shrink: 0;
}

.tool-desc {
  font-size: 12px;
  color: $gray-medium;
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
  color: $dark-bg;
}

.empty-hint {
  font-size: 13px;
  line-height: 1.7;
  color: $gray-medium;
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
  border-top: 1px solid rgba(233, 236, 239, 0.9);
}

.note-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  margin-top: 2px;
  color: #B47D2B;
}

.note-text {
  font-size: 12px;
  line-height: 1.8;
  color: $gray-medium;
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
</style>

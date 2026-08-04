<template>
  <view class="msb">
    <!-- 搜索：过滤全部分组（VS Code 扩展栏语义） -->
    <view class="msb-search">
      <svg class="msb-search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path v-for="(d, gi) in ICONS.search" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <input class="msb-search-input" v-model="searchText" placeholder="搜索 Skill 与插件" />
      <view v-if="searchText" class="msb-search-clear" @tap="searchText = ''">×</view>
    </view>

    <scroll-view scroll-y class="msb-body">
      <!-- ===== 已安装 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('installed')">
        <text class="msb-sec-chevron" :class="{ open: sections.installed }">›</text>
        <text class="msb-sec-title">已安装</text>
        <text class="msb-sec-count">{{ installedRows.length }}</text>
        <view class="msb-sec-spacer"></view>
        <view class="msb-sec-action" title="重新扫描本机 Skill 与插件" @tap.stop="rescan">
          <svg class="msb-sec-action-icon" :class="{ spinning: rescanning }" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.refresh" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </view>
      </view>
      <view v-if="sections.installed">
        <view v-if="!installedRows.length" class="msb-empty">
          <text>{{ searchText ? '没有匹配的已安装项' : '还没有安装任何 Skill 或插件' }}</text>
        </view>
        <view
          v-for="row in installedRows"
          :key="'ins-' + row.kind + '-' + row.id"
          class="msb-row"
          @tap="openDetail(row)"
        >
          <view class="msb-row-glyph" :class="{ 'is-plugin': row.kind === 'plugin' }">
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
      </view>

      <!-- ===== Skill 广场 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('skill')">
        <text class="msb-sec-chevron" :class="{ open: sections.skill }">›</text>
        <text class="msb-sec-title">Skill 广场</text>
        <text class="msb-sec-count">{{ skillRows.length }}</text>
      </view>
      <view v-if="sections.skill">
        <view v-if="marketLoading" class="msb-empty"><text>加载中…</text></view>
        <view v-else-if="marketError" class="msb-empty msb-error">
          <text>在线广场不可用：{{ marketError }}</text>
        </view>
        <view v-else-if="!skillRows.length" class="msb-empty">
          <text>{{ searchText ? '没有匹配的 Skill' : '广场暂无 Skill' }}</text>
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
          <view
            v-if="!row.installed"
            class="msb-row-install"
            :class="{ busy: marketBusyId === row.id }"
            @tap.stop="installSkillRow(row)"
          >
            <text>{{ marketBusyId === row.id ? '…' : '安装' }}</text>
          </view>
          <view v-else class="msb-row-state ok"><text>已装</text></view>
        </view>
      </view>

      <!-- ===== 插件广场 ===== -->
      <view class="msb-sec-head" @tap="toggleSection('plugin')">
        <text class="msb-sec-chevron" :class="{ open: sections.plugin }">›</text>
        <text class="msb-sec-title">插件广场</text>
        <text class="msb-sec-count">{{ pluginRows.length }}</text>
      </view>
      <view v-if="sections.plugin">
        <view v-if="marketPluginLoading" class="msb-empty"><text>加载中…</text></view>
        <view v-else-if="marketPluginError" class="msb-empty msb-error">
          <text>在线广场不可用：{{ marketPluginError }}</text>
        </view>
        <view v-else-if="!pluginRows.length" class="msb-empty">
          <text>{{ searchText ? '没有匹配的插件' : '广场暂无插件' }}</text>
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
          <view
            v-if="!row.installed"
            class="msb-row-install"
            :class="{ busy: pluginBusyId === row.id }"
            @tap.stop="installPluginRow(row)"
          >
            <text>{{ pluginBusyId === row.id ? '…' : '安装' }}</text>
          </view>
          <view v-else class="msb-row-state ok"><text>已装</text></view>
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

const ACTIVATION_STATE = {
  auto: '自动',
  manual: '手动',
  disabled: '停用',
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
        rows.push({
          kind: 'skill',
          id: s.id,
          name: s.name || s.id,
          desc: s.description || '',
          glyph: CATEGORY_GLYPHS[s.category] || ICONS.skill,
          meta: 'Skill' + (s.sourcePluginId ? ' · 来自插件' : ''),
          stateLabel: ACTIVATION_STATE[mode] || '自动',
          stateClass: mode === 'disabled' ? 'off' : 'ok',
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
          meta: '插件' + (p.version ? ' · v' + p.version : ''),
          stateLabel: p.enabled ? '已启用' : '已停用',
          stateClass: p.enabled ? 'ok' : 'off',
          raw: p,
        })
      }
      return kw ? rows.filter(r => (r.name + ' ' + r.id + ' ' + r.desc).toLowerCase().includes(kw)) : rows
    },
    skillRows() {
      const kw = this.searchText.trim().toLowerCase()
      const rows = this.marketSkills.map(m => {
        const cat = m.category || 'other'
        const metaParts = []
        if (m.version) metaParts.push('v' + m.version)
        const dl = fmtDownloads(m.downloads)
        if (dl) metaParts.push(dl + ' 下载')
        metaParts.push(CATEGORY_LABELS[cat] || '其他')
        return {
          kind: 'skill',
          id: m.id,
          name: m.name || m.id,
          desc: m.description || '',
          glyph: CATEGORY_GLYPHS[cat] || CATEGORY_GLYPHS.other,
          meta: metaParts.join(' · '),
          installed: !!m.installed,
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
        return {
          kind: 'plugin',
          id: m.id,
          name: m.name || m.id,
          desc: m.description || '',
          glyph: ICONS.blocks,
          meta: metaParts.join(' · ') || '插件',
          installed: installedIds.has(m.id) || !!m.installed,
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
      } catch (e) {
        console.warn('在线 Skill 广场不可用:', e)
        this.marketError = e?.message || '网络不可用'
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
      } catch (e) {
        console.warn('在线插件广场不可用:', e)
        this.marketPluginError = e?.message || '网络不可用'
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
        uni.showToast({ title: '已安装', icon: 'none' })
        await this.reloadAll()
        uni.$emit('awd:market-changed-from-sidebar')
      } catch (e) {
        console.error('安装 Skill 失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
      } finally {
        this.marketBusyId = ''
      }
    },
    async installPluginRow(row) {
      if (this.pluginBusyId) return
      const m = row.raw
      const perms = (m.permissions || []).join('、') || '未声明敏感能力'
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: '确认安装插件',
          content: `${m.name || m.id} v${m.version}\n作者：${m.authorDisplayName || m.author || '未知'}\n声明能力：${perms}\n\n插件与本机应用同等权限，能读写你的文件并访问网络。安装后默认停用，需你手动启用。`,
          confirmText: '安装',
          cancelText: '取消',
          success: r => resolve(r.confirm),
          fail: () => resolve(false),
        })
      })
      if (!ok) return
      this.pluginBusyId = row.id
      try {
        await installMarketPlugin(row.id)
        uni.showToast({ title: '已安装，启用前不会执行任何插件代码', icon: 'none' })
        await this.reloadAll()
        uni.$emit('awd:market-changed-from-sidebar')
      } catch (e) {
        console.error('安装插件失败:', e)
        uni.showToast({ title: e?.message || '安装失败（需要管理员权限）', icon: 'none' })
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
          title: `扫描完成：${res?.pluginCount ?? 0} 个插件、${skillRes?.skillCount ?? 0} 个 Skill`,
          icon: 'none',
        })
        await this.reloadAll()
      } catch (e) {
        console.error('重新扫描失败:', e)
        uni.showToast({ title: e?.message || '扫描失败（需要管理员权限）', icon: 'none' })
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
}
</style>

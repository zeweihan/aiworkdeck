<template>
  <scroll-view scroll-y class="mdp">
    <view class="mdp-inner">
      <!-- 头部：图标 + 名称 + 元信息 + 动作（VS Code 扩展详情页结构） -->
      <view class="mdp-head">
        <view class="mdp-glyph">
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in headGlyph" :key="gi" :d="d" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </view>
        <view class="mdp-head-main">
          <view class="mdp-title-row">
            <text class="mdp-title">{{ display.name }}</text>
            <text class="mdp-kind-badge">{{ spec.kind === 'plugin' ? '插件' : 'Skill' }}</text>
            <text v-if="installedInfo" class="mdp-installed-badge">已安装</text>
          </view>
          <view class="mdp-byline">
            <text v-if="display.author" class="mdp-byline-item mdp-author">{{ display.author }}</text>
            <text v-if="display.version" class="mdp-byline-item">v{{ display.version }}</text>
            <text v-if="display.downloads" class="mdp-byline-item">{{ display.downloads }} 下载</text>
            <text v-if="display.categoryLabel" class="mdp-byline-item">{{ display.categoryLabel }}</text>
          </view>
          <text v-if="display.description" class="mdp-summary">{{ display.description }}</text>

          <!-- 动作区 -->
          <view class="mdp-actions">
            <!-- Skill：安装 / 更新 / 卸载 + 生效方式三档 -->
            <template v-if="spec.kind === 'skill'">
              <view
                v-if="marketInfo && !installedInfo"
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
                v-if="marketInfo && !installedInfo"
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
        </view>
      </view>

      <view class="mdp-divider"></view>

      <view v-if="loading" class="mdp-loading"><text>加载中…</text></view>
      <view v-else-if="!marketInfo && !installedInfo" class="mdp-loading">
        <text>{{ loadError ? '加载失败：' + loadError : '没有找到这一项（可能已从广场下架或本机已卸载）' }}</text>
      </view>

      <template v-else>
        <!-- 触发词（Skill） -->
        <view v-if="spec.kind === 'skill' && triggerList.length" class="mdp-section">
          <text class="mdp-sec-title">触发词</text>
          <view class="mdp-triggers">
            <text v-for="(t, i) in triggerList" :key="i" class="mdp-trigger">「{{ t }}」</text>
          </view>
          <text class="mdp-sec-note">对话命中触发词时自动生效；也可在输入框用 / 手动选用。</text>
        </view>

        <!-- 能力与权限 -->
        <view class="mdp-section">
          <text class="mdp-sec-title">{{ spec.kind === 'plugin' ? '声明能力' : '能力' }}</text>
          <text class="mdp-sec-body">{{ capabilityText }}</text>
          <text v-if="spec.kind === 'plugin'" class="mdp-sec-note">插件与本机应用同等权限。平台已人工审核并签名，安装后默认停用，启用前不会执行任何插件代码。</text>
        </view>

        <!-- 详细信息 -->
        <view class="mdp-section">
          <text class="mdp-sec-title">详细信息</text>
          <view class="mdp-kv">
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
    }
  },
  computed: {
    ACTIVATION_LABELS() {
      return ACTIVATION_LABELS
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
    capabilityText() {
      const item = this.marketInfo || this.installedInfo || {}
      const parts = []
      const toolCount = item.toolCount != null ? item.toolCount : (item.tools || []).length
      if (toolCount) parts.push(`${toolCount} 个工具`)
      if (this.spec.kind === 'skill' && (item.allowedTools || []).length) {
        parts.push(`可用工具：${item.allowedTools.join('、')}`)
      }
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
      try {
        if (this.spec.kind === 'skill') {
          const [mRes, iRes] = await Promise.all([
            getSkillMarket().catch(() => null),
            getSkills().catch(() => null),
          ])
          const marketList = mRes?.skills || []
          const installedList = Array.isArray(iRes) ? iRes : (iRes?.data || [])
          this.marketInfo = marketList.find(s => s.id === this.spec.id) || null
          this.installedInfo = installedList.find(s => s.id === this.spec.id) || null
        } else {
          const [mRes, iRes] = await Promise.all([
            getPluginMarket().catch(() => null),
            getPlugins().catch(() => null),
          ])
          const marketList = mRes?.plugins || []
          const installedList = Array.isArray(iRes) ? iRes : (iRes?.data || [])
          this.marketInfo = marketList.find(p => p.id === this.spec.id) || null
          this.installedInfo = installedList.find(p => p.id === this.spec.id) || null
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

.mdp-summary {
  display: block;
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: #2C3338;
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

<template>
  <view class="page-plugin-market">
    <view class="market-container">
      <!-- 顶部 -->
      <view class="header-card">
        <view class="header-left">
          <view class="header-icon">🧩</view>
          <view class="title-row">
            <text class="page-title">插件广场</text>
            <text class="page-subtitle">管理 plugins/ 目录下已安装的插件（规范见 docs/PLUGIN_SPEC.md）</text>
          </view>
        </view>
        <view class="toolbar">
          <button class="btn-secondary" @tap="goBack">返回</button>
          <button class="btn-primary" :disabled="rescanning" @tap="rescan">
            {{ rescanning ? '扫描中...' : '重新扫描' }}
          </button>
        </view>
      </view>

      <!-- 插件列表 -->
      <scroll-view class="plugin-list" scroll-y="true">
        <view class="section-header">
          <text class="section-title">插件</text>
          <text v-if="plugins.length" class="section-count">{{ plugins.length }}</text>
        </view>

        <view v-if="loading" class="empty">
          <text class="empty-icon">⏳</text>
          <text class="empty-title">加载中...</text>
        </view>
        <view v-else-if="plugins.length === 0" class="empty">
          <text class="empty-icon">🧩</text>
          <text class="empty-title">暂无插件</text>
          <text class="empty-hint">将插件目录（含 manifest.json）放入服务端 plugins/ 目录后，点击"重新扫描"</text>
        </view>
        <view v-else class="card-grid">
          <view v-for="p in plugins" :key="p.id" class="plugin-card" :class="{ disabled: !p.enabled }">
            <view class="card-header">
              <view class="plugin-icon-wrap">
                <image v-if="isImageIcon(p.icon)" :src="p.icon" class="plugin-icon-img" mode="aspectFit" />
                <text v-else class="plugin-icon">{{ p.icon || '🧩' }}</text>
              </view>
              <view class="title-block">
                <view class="name-row">
                  <text class="plugin-name">{{ p.name || p.id }}</text>
                  <text class="plugin-version" v-if="p.version">v{{ p.version }}</text>
                </view>
                <text class="plugin-meta" v-if="p.author">作者：{{ p.author }}</text>
              </view>
              <switch
                class="plugin-switch"
                color="#1A5336"
                :checked="p.enabled"
                :disabled="switching"
                @change="onToggle(p, $event)"
              />
            </view>

            <text class="plugin-desc">{{ p.description || '暂无描述' }}</text>

            <view class="tag-row">
              <text class="tool-count-tag">{{ p.toolCount }} 个工具</text>
              <text
                v-for="perm in p.permissions"
                :key="perm"
                class="perm-tag"
              >{{ permissionLabel(perm) }}</text>
            </view>

            <view class="tool-list" v-if="p.tools && p.tools.length">
              <view v-for="t in p.tools" :key="t.name" class="tool-item">
                <text class="tool-name">{{ t.name }}</text>
                <text class="tool-desc">{{ t.description }}</text>
              </view>
            </view>
          </view>
        </view>

        <!-- Skill 区块（规范见 docs/SKILL_SPEC.md） -->
        <view class="section-header">
          <text class="section-title">Skill</text>
          <text v-if="skills.length" class="section-count">{{ skills.length }}</text>
          <text class="section-subtitle">命中触发词时自动注入提示模板并裁剪可用工具</text>
        </view>
        <view v-if="skills.length === 0" class="empty">
          <text class="empty-icon">🎯</text>
          <text class="empty-title">暂无 Skill</text>
          <text class="empty-hint">将 Skill 目录（含 skill.yml）放入服务端 skills/ 目录后，点击"重新扫描"</text>
        </view>
        <view v-else class="card-grid">
          <view v-for="s in skills" :key="s.id" class="plugin-card" :class="{ disabled: !s.enabled }">
            <view class="card-header">
              <view class="plugin-icon-wrap">
                <text class="plugin-icon">🎯</text>
              </view>
              <view class="title-block">
                <view class="name-row">
                  <text class="plugin-name">{{ s.name || s.id }}</text>
                  <text class="plugin-version" v-if="s.sourcePluginId">来自插件 {{ s.sourcePluginId }}</text>
                </view>
                <text class="plugin-meta">触发方式：关键词匹配</text>
              </view>
              <switch
                class="plugin-switch"
                color="#1A5336"
                :checked="s.enabled"
                :disabled="switching"
                @change="onToggleSkill(s, $event)"
              />
            </view>

            <text class="plugin-desc">{{ s.description || '暂无描述' }}</text>

            <view class="tag-row">
              <text v-for="t in s.triggers" :key="t" class="trigger-tag">{{ t }}</text>
            </view>
          </view>
        </view>

        <!-- 在线广场区块（官网 Skill 注册表，安装写入服务端 skills/ 目录，重装即更新） -->
        <view class="section-header">
          <text class="section-title">在线广场</text>
          <text v-if="marketSkills.length" class="section-count">{{ marketSkills.length }}</text>
          <text class="section-subtitle">来自官网 Skill 广场的公开 Skill，一键安装到本机</text>
        </view>
        <view v-if="marketError" class="empty">
          <text class="empty-icon">📡</text>
          <text class="empty-title">在线广场暂不可用（离线或网络受限）</text>
          <text class="empty-hint">{{ marketError }}</text>
        </view>
        <view v-else-if="marketSkills.length === 0" class="empty">
          <text class="empty-icon">🛍️</text>
          <text class="empty-title">{{ marketLoading ? '加载中...' : '暂无在线 Skill' }}</text>
          <text v-if="!marketLoading" class="empty-hint">去官网 Skill 广场提交你的第一个 Skill 吧</text>
        </view>
        <view v-else class="card-grid">
          <view v-for="m in marketSkills" :key="m.id" class="plugin-card">
            <view class="card-header">
              <view class="plugin-icon-wrap">
                <text class="plugin-icon">{{ m.icon || '🧩' }}</text>
              </view>
              <view class="title-block">
                <view class="name-row">
                  <text class="plugin-name">{{ m.name || m.id }}</text>
                  <text class="plugin-version" v-if="m.version">v{{ m.version }}</text>
                </view>
                <text class="plugin-meta">作者：{{ m.authorDisplayName || m.author || '未知' }} · {{ m.downloads || 0 }} 次安装</text>
              </view>
              <view class="market-actions">
                <button class="btn-primary btn-mini" :disabled="!!marketBusyId" @tap="installSkill(m)">
                  {{ marketBusyId === m.id ? '处理中...' : (m.installed ? '更新' : '安装') }}
                </button>
                <button v-if="m.installed" class="btn-uninstall btn-mini" :disabled="!!marketBusyId" @tap="uninstallSkill(m)">卸载</button>
              </view>
            </view>

            <text class="plugin-desc">{{ m.description || '暂无描述' }}</text>

            <view class="tag-row">
              <text v-if="m.installed" class="installed-tag">已安装</text>
              <text v-for="t in m.triggers" :key="t" class="trigger-tag">{{ t }}</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script>
import { getPlugins, setPluginEnabled, rescanPlugins, getSkills, setSkillEnabled, rescanSkills, getSkillMarket, installMarketSkill, uninstallMarketSkill } from '@/services/api.js'

const PERMISSION_LABELS = {
  file_read: '读取文件',
  file_write: '写入文件',
  network: '网络访问',
  editor: '编辑器',
}

export default {
  name: 'PluginMarketPage',
  data() {
    return {
      plugins: [],
      skills: [],
      marketSkills: [],
      marketLoading: false,
      marketError: '',
      marketBusyId: '',
      loading: false,
      switching: false,
      rescanning: false,
    }
  },
  onLoad() {
    this.loadPlugins()
    this.loadSkills()
    this.loadMarket()
  },
  methods: {
    permissionLabel(perm) {
      return PERMISSION_LABELS[perm] || perm
    },
    isImageIcon(icon) {
      return typeof icon === 'string' && (icon.startsWith('http') || icon.startsWith('/'))
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
    async onToggleSkill(skill, event) {
      const enabled = !!(event?.detail?.value)
      this.switching = true
      try {
        await setSkillEnabled(skill.id, enabled)
        skill.enabled = enabled
        uni.showToast({ title: enabled ? '已启用' : '已禁用', icon: 'none' })
      } catch (e) {
        console.error('切换 Skill 状态失败:', e)
        // 回滚开关显示
        skill.enabled = !enabled
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
/* AI Workdeck Color System（与 admin 页保持一致） */
$brand-forest: #1A5336;
$brand-mint: #5BD197;
$brand-mint-light: #E6F9F0;
$brand-forest-dark: #123A26;

$brand-primary: $brand-forest;
$brand-white: #FFFFFF;
$text-main: #2C3338;
$text-secondary: #6C757D;
$border-color: #E9ECEF;

.page-plugin-market {
  min-height: 100vh;
  background: linear-gradient(135deg, #F8F9FA 0%, #E8F3ED 100%);
  padding: 40px 24px;
  box-sizing: border-box;
}

.market-container {
  max-width: 1080px;
  margin: 0 auto;
  width: 100%;
}

/* 顶部 */
.header-card {
  background: $brand-white;
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.02);
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.05);
  padding: 24px 28px;
  box-sizing: border-box;
  margin-bottom: 24px;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.header-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: $brand-mint-light;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  flex-shrink: 0;
}

.title-row {
  display: flex;
  flex-direction: column;
  row-gap: 4px;
  min-width: 0;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: $text-main;
}

.page-subtitle {
  font-size: 13px;
  color: $text-secondary;
}

.toolbar {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.btn-primary {
  font-size: 13px;
  font-weight: 500;
  background: $brand-primary;
  color: #fff;
  border: none;
  padding: 8px 20px;
  border-radius: 6px;
  line-height: 1.5;
  cursor: pointer;
  box-shadow: 0 2px 4px rgba(26, 83, 54, 0.2);
  transition: background 0.2s;

  &:after { border: none; }

  &:hover { background: $brand-forest-dark; }

  &[disabled] {
    background: #A9C5B6;
    box-shadow: none;
    color: #fff;
  }
}

.btn-secondary {
  font-size: 13px;
  font-weight: 500;
  background: #fff;
  color: $text-secondary;
  border: 1px solid $border-color;
  padding: 8px 20px;
  border-radius: 6px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.2s;

  &:after { border: none; }

  &:hover {
    color: $brand-primary;
    border-color: $brand-primary;
    background: $brand-mint-light;
  }
}

.plugin-list {
  max-height: calc(100vh - 190px);
}

/* 区块标题 */
.section-header {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 10px;
  margin: 8px 4px 14px;

  &:first-child { margin-top: 0; }
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: $text-main;
}

.section-count {
  font-size: 12px;
  font-weight: 600;
  color: $brand-primary;
  background: $brand-mint-light;
  padding: 1px 10px;
  border-radius: 999px;
}

.section-subtitle {
  font-size: 13px;
  color: $text-secondary;
}

/* 空状态 */
.empty {
  background: rgba(255, 255, 255, 0.6);
  border: 1.5px dashed #C9DED2;
  border-radius: 12px;
  padding: 48px 24px;
  margin-bottom: 32px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  row-gap: 8px;
}

.empty-icon {
  font-size: 36px;
  line-height: 1;
  opacity: 0.5;
  margin-bottom: 4px;
}

.empty-title {
  font-size: 14px;
  font-weight: 500;
  color: $text-main;
}

.empty-hint {
  font-size: 13px;
  color: $text-secondary;
}

/* 卡片网格 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.plugin-card {
  background: $brand-white;
  border: 1px solid $border-color;
  border-radius: 12px;
  padding: 20px;
  box-sizing: border-box;
  transition: all 0.2s ease;

  &:hover {
    border-color: $brand-mint;
    box-shadow: 0 8px 24px rgba(26, 83, 54, 0.08);
    transform: translateY(-2px);
  }

  &.disabled {
    opacity: 0.6;
    background: #FCFCFC;

    &:hover {
      transform: none;
      box-shadow: none;
      border-color: $border-color;
    }
  }
}

.card-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 12px;
  margin-bottom: 12px;
}

.plugin-icon-wrap {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: $brand-mint-light;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}

.plugin-icon {
  font-size: 24px;
  line-height: 1;
}

.plugin-icon-img {
  width: 32px;
  height: 32px;
}

.title-block {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  row-gap: 2px;
}

.name-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 8px;
}

.plugin-name {
  font-size: 15px;
  font-weight: 600;
  color: $text-main;
}

.plugin-version {
  font-size: 12px;
  color: $text-secondary;
  background: #F1F3F5;
  padding: 1px 8px;
  border-radius: 999px;
}

.plugin-meta {
  font-size: 12px;
  color: $text-secondary;
}

.plugin-switch {
  transform: scale(0.8);
  flex-shrink: 0;
}

.plugin-desc {
  font-size: 13px;
  line-height: 1.6;
  color: $text-secondary;
  display: block;
  margin-bottom: 12px;
}

.tag-row {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;

  &:last-child { margin-bottom: 0; }
}

.tool-count-tag {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 999px;
  background: $brand-mint-light;
  color: $brand-primary;
}

.perm-tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(230, 162, 60, 0.12);
  color: #B47D2B;
}

.trigger-tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(103, 194, 58, 0.12);
  color: #4F9A2C;
}

.installed-tag {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 999px;
  background: $brand-mint-light;
  color: $brand-primary;
}

/* 在线广场卡片右侧操作列 */
.market-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  row-gap: 6px;
  flex-shrink: 0;
}

.btn-mini {
  padding: 4px 14px;
  font-size: 12px;
}

.btn-uninstall {
  font-size: 12px;
  font-weight: 500;
  background: #fff;
  color: #C0392B;
  border: 1px solid rgba(192, 57, 43, 0.35);
  border-radius: 6px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.2s;

  &:after { border: none; }

  &:hover {
    background: rgba(192, 57, 43, 0.06);
    border-color: #C0392B;
  }

  &[disabled] {
    color: #D9A7A0;
    border-color: #EED4D0;
  }
}

/* 工具清单 */
.tool-list {
  background: #FAFAFA;
  border: 1px solid $border-color;
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  row-gap: 6px;
}

.tool-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  column-gap: 12px;
}

.tool-name {
  font-size: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  color: $brand-primary;
  font-weight: 500;
  flex-shrink: 0;
}

.tool-desc {
  font-size: 12px;
  color: $text-secondary;
}

/* 窄窗口回退为单列 */
@media (max-width: 920px) {
  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>

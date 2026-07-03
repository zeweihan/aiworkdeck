<template>
  <view class="page-plugin-market">
    <!-- 顶部 -->
    <view class="header-card">
      <view class="title-row">
        <text class="page-title">插件广场</text>
        <text class="page-subtitle">管理 plugins/ 目录下已安装的插件（规范见 docs/PLUGIN_SPEC.md）</text>
      </view>
      <view class="toolbar">
        <button class="btn" type="primary" size="mini" :disabled="rescanning" @tap="rescan">
          {{ rescanning ? '扫描中...' : '重新扫描' }}
        </button>
        <button class="btn" type="default" size="mini" @tap="goBack">返回</button>
      </view>
    </view>

    <!-- 插件列表 -->
    <scroll-view class="plugin-list" scroll-y="true">
      <view v-if="loading" class="empty">加载中...</view>
      <view v-else-if="plugins.length === 0" class="empty">
        <text>暂无插件</text>
        <text class="empty-hint">将插件目录（含 manifest.json）放入服务端 plugins/ 目录后，点击"重新扫描"</text>
      </view>
      <view v-else>
        <view v-for="p in plugins" :key="p.id" class="plugin-card" :class="{ disabled: !p.enabled }">
          <view class="card-header">
            <image v-if="isImageIcon(p.icon)" :src="p.icon" class="plugin-icon-img" mode="aspectFit" />
            <text v-else class="plugin-icon">{{ p.icon || '🧩' }}</text>
            <view class="title-block">
              <view class="name-row">
                <text class="plugin-name">{{ p.name || p.id }}</text>
                <text class="plugin-version" v-if="p.version">v{{ p.version }}</text>
              </view>
              <text class="plugin-meta" v-if="p.author">作者：{{ p.author }}</text>
            </view>
            <switch
              class="plugin-switch"
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
        <text class="section-subtitle">命中触发词时自动注入提示模板并裁剪可用工具</text>
      </view>
      <view v-if="skills.length === 0" class="empty">
        <text>暂无 Skill</text>
        <text class="empty-hint">将 Skill 目录（含 skill.yml）放入服务端 skills/ 目录后，点击"重新扫描"</text>
      </view>
      <view v-else>
        <view v-for="s in skills" :key="s.id" class="plugin-card" :class="{ disabled: !s.enabled }">
          <view class="card-header">
            <text class="plugin-icon">🎯</text>
            <view class="title-block">
              <view class="name-row">
                <text class="plugin-name">{{ s.name || s.id }}</text>
                <text class="plugin-version" v-if="s.sourcePluginId">来自插件 {{ s.sourcePluginId }}</text>
              </view>
              <text class="plugin-meta">触发方式：关键词匹配</text>
            </view>
            <switch
              class="plugin-switch"
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
    </scroll-view>
  </view>
</template>

<script>
import { getPlugins, setPluginEnabled, rescanPlugins, getSkills, setSkillEnabled, rescanSkills } from '@/services/api.js'

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
      loading: false,
      switching: false,
      rescanning: false,
    }
  },
  onLoad() {
    this.loadPlugins()
    this.loadSkills()
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

<style lang="scss">
.page-plugin-market {
  min-height: 100vh;
  padding: 24rpx;
  background-color: $uni-bg-color-grey;
  box-sizing: border-box;
}

.header-card {
  background-color: $uni-bg-color;
  border-radius: 16rpx;
  padding: 16rpx;
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.04);
  box-sizing: border-box;
  margin-bottom: 16rpx;
}

.title-row {
  display: flex;
  flex-direction: column;
  row-gap: 4rpx;
  margin-bottom: 12rpx;
}

.page-title {
  font-size: 34rpx;
  font-weight: 500;
  color: $uni-color-title;
}

.page-subtitle {
  font-size: 24rpx;
  color: $uni-text-color-grey;
}

.toolbar {
  display: flex;
  flex-direction: row;
  column-gap: 12rpx;
}

.btn {
  padding: 0 20rpx;
}

.plugin-list {
  max-height: calc(100vh - 220rpx);
}

.empty {
  padding: 40rpx 20rpx;
  text-align: center;
  color: $uni-text-color-grey;
  font-size: 24rpx;
  display: flex;
  flex-direction: column;
  row-gap: 8rpx;
}

.empty-hint {
  font-size: 22rpx;
}

.plugin-card {
  background-color: #ffffff;
  border-radius: 12rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: $uni-border-color;
  padding: 16rpx;
  box-sizing: border-box;
  margin-bottom: 12rpx;

  &.disabled {
    opacity: 0.55;
  }
}

.card-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 12rpx;
  margin-bottom: 8rpx;
}

.plugin-icon {
  font-size: 48rpx;
  line-height: 1;
}

.plugin-icon-img {
  width: 56rpx;
  height: 56rpx;
  border-radius: 8rpx;
}

.title-block {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  row-gap: 2rpx;
}

.name-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 8rpx;
}

.plugin-name {
  font-size: 30rpx;
  font-weight: 500;
  color: $uni-color-title;
}

.plugin-version {
  font-size: 22rpx;
  color: $uni-text-color-grey;
}

.plugin-meta {
  font-size: 22rpx;
  color: $uni-text-color-grey;
}

.plugin-switch {
  transform: scale(0.8);
}

.plugin-desc {
  font-size: 24rpx;
  color: $uni-text-color;
  display: block;
  margin-bottom: 10rpx;
}

.tag-row {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  column-gap: 8rpx;
  row-gap: 8rpx;
  margin-bottom: 8rpx;
}

.tool-count-tag {
  font-size: 22rpx;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  background-color: rgba($uni-color-primary, 0.08);
  color: $uni-color-primary;
}

.perm-tag {
  font-size: 22rpx;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  background-color: rgba(#e6a23c, 0.12);
  color: #b88230;
}

.section-header {
  display: flex;
  flex-direction: column;
  row-gap: 4rpx;
  margin: 24rpx 4rpx 12rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 500;
  color: $uni-color-title;
}

.section-subtitle {
  font-size: 22rpx;
  color: $uni-text-color-grey;
}

.trigger-tag {
  font-size: 22rpx;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  background-color: rgba(#67c23a, 0.12);
  color: #4f9a2c;
}

.tool-list {
  border-top: 1rpx solid $uni-border-color;
  padding-top: 8rpx;
  display: flex;
  flex-direction: column;
  row-gap: 4rpx;
}

.tool-item {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  column-gap: 12rpx;
}

.tool-name {
  font-size: 22rpx;
  font-family: monospace;
  color: $uni-color-primary;
  flex-shrink: 0;
}

.tool-desc {
  font-size: 22rpx;
  color: $uni-text-color-grey;
}
</style>

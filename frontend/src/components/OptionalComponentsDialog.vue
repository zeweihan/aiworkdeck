<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  首次登录后的「可选组件」面板（设计 §4.1）。

  只在四个条件同时成立时由 project-list 打开：桌面端 / 接口真的回了组件 /
  存在未装的 / 本大版本没提示过（判定在 useOptionalComponents.shouldPromptOptionalComponents）。
  「稍后再说」是一等公民——关掉之后本大版本不再打扰，入口留在 设置 → 组件管理。

  「提示过」的标记落 electron prefs（~/.aiworkdeck/prefs.json），不是 localStorage：
  那个随浏览器数据一起被清，也不区分重装。

  下载走应用级单例（services/componentDownloads.js，dev-board#581）：下载中可「后台下载」
  关面板，「稍后再说」/点遮罩在下载中也只是转后台、不中断；进度在 设置 → 组件管理 接着看，
  完成或失败时全局提示。
-->
<template>
  <view class="ocd-mask" @tap.self="onLater">
    <view class="ocd-panel">
      <text class="ocd-title">{{ $t('components.panelTitle') }}</text>
      <text class="ocd-subtitle">{{ $t('components.panelSubtitle') }}</text>

      <scroll-view scroll-y class="ocd-list">
        <OptionalComponentCard
          v-for="item in controller.state.items"
          :key="item.packId"
          :item="item"
          :selectable="true"
          :busy="controller.state.running"
          @toggle="onToggle"
        />
      </scroll-view>

      <view v-if="controller.state.running" class="ocd-total">
        <text class="ocd-total-text">
          {{ $t('components.progressTotal', {
            done: controller.state.doneCount,
            count: controller.state.totalCount,
            percent: overall
          }) }}
        </text>
        <view class="ocd-total-bar"><view class="ocd-total-fill" :style="{ width: overall + '%' }" /></view>
      </view>

      <view class="ocd-actions">
        <view v-if="controller.state.running" class="ocd-btn primary ocd-background" @tap="onBackground">
          {{ $t('components.backgroundDownload') }}
        </view>
        <view v-else class="ocd-btn primary" :class="{ disabled: !anySelected }" @tap="onInstall">
          {{ $t('components.installSelected') }}
        </view>
        <view class="ocd-btn" @tap="onLater">{{ $t('components.later') }}</view>
      </view>
      <text class="ocd-hint">{{ $t('components.laterHint') }}</text>
    </view>
  </view>
</template>

<script>
import OptionalComponentCard from '@/components/OptionalComponentCard.vue'
import { host } from '@/services/host.js'
import { componentDownloads } from '@/services/componentDownloads.js'
import { PROMPTED_PREF_KEY } from '@/composables/useOptionalComponents.js'

export default {
  name: 'OptionalComponentsDialog',
  components: { OptionalComponentCard },
  props: {
    appVersion: { type: String, default: '' },
  },
  emits: ['close'],
  data() {
    return {
      // 应用级单例：状态不跟这个面板走，面板关了下载照样继续
      controller: componentDownloads,
    }
  },
  computed: {
    anySelected() {
      return this.controller.state.items.some((i) => i.selected)
    },
    overall() {
      return this.controller.overallPercent()
    },
  },
  async mounted() {
    // 调用方（project-list）为了判定要不要弹已经拉过一次清单，这里仍然自己再拉一次：
    // 这条端点不发网络请求（体积取快照），比把清单当 prop 传进来再复制一遍映射逻辑便宜。
    await this.controller.load()
    // 体积未知的逐个补一次（会真打 /info，只在面板真的打开时才发）
    for (const item of this.controller.state.items) {
      await this.controller.fillSizes(item)
    }
    // 「模型已装的组件默认勾选」——用户此前已经选择过这个功能（设计 §3.4）
    for (const item of this.controller.state.items) {
      if (item.phase !== 'ready' && item.modelId && item.modelInstalled) item.selected = true
    }
  },
  beforeUnmount() {
    // 被 reLaunch 带走或关掉：结果改由全局提示交代
    this.releaseClaims()
  },
  methods: {
    onToggle(packId, checked) {
      const it = this.controller.state.items.find((i) => i.packId === packId)
      if (it) it.selected = checked
    },
    async onInstall() {
      if (!this.anySelected || this.controller.state.running) return
      // 面板在前台看着：装完由面板自己交代（全成功关面板、有失败留着原地重试）
      this._claims = this.controller.state.items.filter((i) => i.selected)
        .map((i) => this.controller.claim(i.packId))
      // 用户已经作答，先落标记：转后台之后面板就卸载了，等装完再写就写不上
      await this.markPrompted()
      await this.controller.installAll(this.controller.state.items.filter((i) => i.selected))
      if (this._backgrounded) return
      this.releaseClaims()
      // 有失败的就把面板留着，用户能在卡片上原地重试；全成功才关
      if (this.controller.state.items.every((i) => i.phase !== 'failed')) this.$emit('close')
    },
    /** 下载继续、面板关闭；进度在「设置 → 组件管理」，完成或失败时全局提示 */
    onBackground() {
      this._backgrounded = true
      this.releaseClaims()
      uni.showToast({ title: this.$t('components.backgroundStarted'), icon: 'none', duration: 3000 })
      this.$emit('close')
    },
    async onLater() {
      // 下载中「稍后再说」/点遮罩不许中断下载：等同于转后台
      if (this.controller.state.running) return this.onBackground()
      await this.markPrompted()
      this.$emit('close')
    },
    releaseClaims() {
      for (const release of this._claims || []) release()
      this._claims = []
    },
    /** 标记落 electron prefs：重装才重置，下个大版本会再问一次 */
    async markPrompted() {
      try {
        if (host.prefs) await host.prefs.set(PROMPTED_PREF_KEY, this.appVersion)
      } catch (e) {
        console.warn('[OptionalComponents] 写提示标记失败', e)
      }
    },
  },
}
</script>

<style scoped>
.ocd-mask {
  position: fixed;
  inset: 0;
  background: var(--awd-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
}
.ocd-panel {
  width: 640px;
  max-width: 92vw;
  max-height: 82vh;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 12px;
  box-shadow: var(--awd-shadow-lg);
  padding: 20px;
  display: flex;
  flex-direction: column;
}
.ocd-title { font-size: 18px; font-weight: 600; color: var(--awd-text); }
.ocd-subtitle {
  font-size: 13px;
  color: var(--awd-text-2);
  margin: 6px 0 12px;
  line-height: 1.6;
}
.ocd-list { flex: 1; overflow: auto; }
.ocd-total { margin-top: 10px; }
.ocd-total-text { font-size: 12px; color: var(--awd-text-2); }
.ocd-total-bar {
  height: 6px;
  background: var(--awd-surface-3);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 6px;
}
.ocd-total-fill { height: 100%; background: var(--awd-accent); transition: width 0.3s; }
.ocd-actions { display: flex; gap: 10px; margin-top: 14px; }
.ocd-btn {
  padding: 8px 16px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 8px;
  font-size: 13px;
  color: var(--awd-text);
  cursor: pointer;
}
.ocd-btn:hover { background: var(--awd-panel-hover); }
.ocd-btn.primary {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-color: var(--awd-accent);
}
.ocd-btn.primary:hover { background: var(--awd-accent-hover); }
.ocd-btn.disabled { opacity: 0.5; }
.ocd-hint { font-size: 12px; color: var(--awd-text-2); margin-top: 8px; }
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  可选组件卡片（设计 §4.1 / §4.3）。两个宿主复用同一份：首次登录后的「可选组件」面板
  （selectable=true，复选 + 面板底部「立即下载所选」）与 设置→组件管理
  （selectable=false，每张卡自己一个「下载此组件」）。

  卡片自己不发任何请求、不认识 api.js——选中与下载都以事件抛给宿主，编排在
  useOptionalComponents 的控制器里。这是「一份卡片两处用」的前提。

  四件事必须同时出现在卡上（用户硬性要求）：下载什么 / 多大 / 解锁哪些功能 /
  不下载则哪些功能不可用。
-->
<template>
  <view class="oc-card" :class="{ ready: item.phase === 'ready', failed: item.phase === 'failed' }">
    <view class="oc-head">
      <view v-if="selectable" class="oc-check" :class="{ checked: item.selected }" @tap="onToggle"></view>
      <text class="oc-title">{{ title }}</text>
      <text class="oc-state" :class="'is-' + item.phase">{{ stateLine }}</text>
    </view>
    <text class="oc-size">{{ sizeLine }}</text>
    <text class="oc-usage">{{ usageLine }}</text>
    <view v-if="item.phase === 'runtime' || item.phase === 'model'" class="oc-progress">
      <view class="oc-progress-fill" :style="{ width: (item.percent || 0) + '%' }" />
    </view>
    <view class="oc-actions">
      <view v-if="!selectable && item.phase === 'idle'" class="oc-btn primary" :class="{ disabled: busy }" @tap="$emit('install', item.packId)">
        {{ $t('components.installOne') }}
      </view>
      <view v-if="item.phase === 'failed'" class="oc-btn" @tap="$emit('retry', item.packId)">
        {{ $t('components.retry') }}
      </view>
    </view>
  </view>
</template>

<script>
const MB = 1024 * 1024

export default {
  name: 'OptionalComponentCard',
  props: {
    item: { type: Object, required: true },
    // 面板里是复选（批量下载），组件管理页里是逐条按钮
    selectable: { type: Boolean, default: false },
    busy: { type: Boolean, default: false },
  },
  emits: ['toggle', 'install', 'retry'],
  computed: {
    title() {
      return this.$t('components.' + this.item.localeKey + '.name')
    },
    /** 设计 §4.3 第一行：下载什么、多大、落哪、怎么卸载。 */
    sizeLine() {
      const runtime = Math.round((this.item.downloadBytes || 0) / MB)
      if (!runtime) return this.$t('components.sizeUnknown')
      const name = this.title
      if (!this.item.modelId) {
        return this.$t('components.promptNoModel', { name, runtime })
      }
      const total = runtime + Math.round((this.item.modelBytes || 0) / MB)
      return this.$t('components.promptWithModel', { name, runtime, total })
    },
    /** 设计 §4.3 第二行：解锁什么、不装则什么不可用。 */
    usageLine() {
      return this.$t('components.promptUsage', {
        unlocks: this.$t('components.' + this.item.localeKey + '.unlocks'),
        impact: this.$t('components.' + this.item.localeKey + '.impact'),
      })
    },
    stateLine() {
      const p = this.item.percent || 0
      switch (this.item.phase) {
        case 'runtime': return this.$t('components.stateDownloadingRuntime', { percent: p })
        case 'model': return this.$t('components.stateDownloadingModel', { percent: p })
        case 'starting': return this.$t('components.stateStartingService')
        case 'ready': return this.$t('components.stateReady')
        case 'failed': return this.$t('components.stateFailed', { msg: this.item.error || '' })
        default: return this.$t('components.stateNotInstalled')
      }
    },
  },
  methods: {
    onToggle() {
      this.$emit('toggle', this.item.packId, !this.item.selected)
    },
  },
}
</script>

<style scoped>
.oc-card {
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-panel-radius);
  background: var(--awd-surface);
  padding: 12px;
  margin-bottom: 10px;
}
.oc-card.ready { border-color: var(--awd-accent); }
.oc-card.failed { border-color: var(--awd-danger); }
.oc-head { display: flex; align-items: center; gap: 8px; }
.oc-check {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  border: 1px solid var(--awd-border-strong);
  border-radius: 3px;
}
.oc-check.checked { background: var(--awd-accent); border-color: var(--awd-accent); }
.oc-title { font-size: var(--awd-panel-fs); font-weight: 600; color: var(--awd-text); flex: 1; }
.oc-state { font-size: var(--awd-panel-fs-meta); color: var(--awd-text-3); }
.oc-state.is-ready { color: var(--awd-accent-text); }
.oc-state.is-failed { color: var(--awd-danger-text); }
.oc-size, .oc-usage {
  display: block;
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-text-2);
  margin-top: 6px;
  line-height: 1.5;
}
.oc-usage { color: var(--awd-text-3); }
.oc-progress {
  height: 6px;
  background: var(--awd-surface-3);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 8px;
}
.oc-progress-fill { height: 100%; background: var(--awd-accent); transition: width 0.3s; }
.oc-actions { display: flex; gap: 8px; margin-top: 8px; }
.oc-btn {
  padding: 4px 10px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 6px;
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-text);
  cursor: pointer;
}
.oc-btn:hover { background: var(--awd-panel-hover); }
.oc-btn.primary {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-color: var(--awd-accent);
}
.oc-btn.primary:hover { background: var(--awd-accent-hover); }
.oc-btn.disabled { opacity: 0.5; }
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="plugin-summary">
    <text class="plugin-summary-name">{{ plugin.label }}</text>
    <text class="plugin-summary-description">{{ plugin.description || $t('panels.pgWorkspaceHint') }}</text>
    <button class="plugin-summary-open" @tap="$emit('open')">{{ $t('panels.pgOpenWorkspace') }}</button>
    <text class="plugin-summary-hint">{{ $t('panels.pgWorkspaceHint') }}</text>
  </view>
</template>
<script>
export default {
  name: 'PluginWorkspaceSummary',
  props: { plugin: { type: Object, required: true } },
  emits: ['open'],
}
</script>
<style scoped>
.plugin-summary { display: flex; flex-direction: column; gap: 16px; padding: 20px 16px; }
.plugin-summary-name { font-size: 16px; font-weight: 600; color: var(--awd-text); }
.plugin-summary-description { font-size: 13px; line-height: 1.7; color: var(--awd-text); }
.plugin-summary-open { width: 100%; margin: 0; font-size: 13px; color: var(--awd-text-on-accent, #fff); background: var(--awd-accent); }
.plugin-summary-hint { font-size: 12px; line-height: 1.6; color: var(--awd-text-3); }
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="mobile-receive">
    <button class="receive-trigger" :class="{ attention: hasFailure }" @click="open = !open" :aria-expanded="open">
      <span class="receive-dot" :class="{ busy: status.checking, failed: hasFailure }"></span>
      {{ $t('workbench.mobileReceive.title') }} · {{ summary }}
    </button>
    <view v-if="open" class="receive-panel" role="dialog" :aria-label="$t('workbench.mobileReceive.title')">
      <view class="receive-header">
        <strong>{{ $t('workbench.mobileReceive.title') }}</strong>
        <button class="receive-close" @click="open = false" :aria-label="$t('workbench.mobileReceive.close')">×</button>
      </view>
      <p v-if="!status.active">{{ $t('workbench.mobileReceive.connect') }}</p>
      <p v-else-if="hasFailure" class="receive-error">{{ $t('workbench.mobileReceive.connectionError') }}</p>
      <p v-else>{{ $t('workbench.mobileReceive.schedule') }}</p>
      <view class="receive-identity">
        <strong>{{ status.deviceName }}</strong>
        <span>{{ $t('workbench.mobileReceive.device') }}: {{ status.deviceId }}</span>
        <template v-if="projectId">
          <strong>{{ status.project?.name || $t('workbench.mobileReceive.missingProject') }}</strong>
          <span>{{ $t('workbench.mobileReceive.project') }}: {{ status.project?.key || projectId }}</span>
          <span>{{ status.project?.path || $t('workbench.mobileReceive.managedPath') }}</span>
        </template>
        <small>{{ $t('workbench.mobileReceive.matchHint') }}</small>
      </view>
      <view class="receive-controls">
        <span>{{ $t('workbench.mobileReceive.lastCheck') }}: {{ time(status.lastCheckedAt) }}</span>
        <button class="receive-check" :disabled="!status.active || status.checking || requesting" @click="checkNow">{{ $t('workbench.mobileReceive.checkNow') }}</button>
      </view>
      <view class="receive-list">
        <p v-if="!items.length">{{ $t('workbench.mobileReceive.empty') }}</p>
        <view v-for="item in items" :key="item.id" class="receive-item">
          <view class="receive-item-title"><strong>{{ item.fileName }}</strong><span :class="{ 'receive-error': item.phase === 'failed' }">{{ $t('workbench.mobileReceive.' + item.phase) }}</span></view>
          <span>{{ item.project.name || $t('workbench.mobileReceive.missingProject') }} · {{ $t('workbench.mobileReceive.project') }} {{ item.project.key }}</span>
          <span v-if="item.phase === 'saved' || item.phase === 'savedPendingAck'">{{ item.savedPath }}</span>
          <span v-if="item.message" class="receive-error">{{ $t('workbench.mobileReceive.error_' + item.message) }}</span>
          <small>{{ $t('workbench.mobileReceive.updated') }}: {{ dateTime(item.updatedAt) }}</small>
        </view>
      </view>
      <small>{{ $t('workbench.mobileReceive.history') }}</small>
    </view>
  </view>
</template>

<script>
import { getMobileReceiveStatus, checkMobileReceive } from '@/services/api.js'
export default {
  props: { projectId: { type: [String, Number], default: '' } },
  emits: ['received'],
  data: () => ({ open: false, status: { active: false, items: [] }, loadError: false, requesting: false, timer: null, stopped: false, savedIds: new Set(), requestId: 0 }),
  computed: {
    items() { return [...(this.status.items || [])].reverse() },
    hasFailure() { return this.loadError || !!this.status.error || this.items.some(i => i.phase === 'failed') },
    summary() {
      if (this.hasFailure) return this.$t('workbench.mobileReceive.needsAttention')
      if (!this.status.active) return this.$t('workbench.mobileReceive.disconnected')
      const receiving = this.items.filter(i => ['pending', 'receiving'].includes(i.phase)).length
      if (receiving) return this.$t('workbench.mobileReceive.receivingCount', { count: receiving })
      if (this.status.checking) return this.$t('workbench.mobileReceive.checking')
      const saved = this.items.filter(i => ['saved', 'savedPendingAck'].includes(i.phase)).length
      return saved ? this.$t('workbench.mobileReceive.savedCount', { count: saved }) : this.$t('workbench.mobileReceive.automatic')
    },
  },
  watch: { projectId() { this.refresh() }, open() { this.refresh() } },
  mounted() { this.refresh() },
  beforeUnmount() { this.stopped = true; clearTimeout(this.timer) },
  methods: {
    dateTime(value) { return value ? new Date(value).toLocaleString() : '—' },
    time(value) { return value ? new Date(value).toLocaleTimeString() : this.$t('workbench.mobileReceive.notChecked') },
    async refresh() {
      clearTimeout(this.timer)
      const requestId = ++this.requestId
      try {
        const result = await getMobileReceiveStatus(this.projectId || '')
        if (this.stopped || requestId !== this.requestId) return
        this.status = result
        this.loadError = false
        const saved = this.items.filter(i => ['saved', 'savedPendingAck'].includes(i.phase))
        if (saved.some(i => !this.savedIds.has(i.id) && String(i.project.key) === String(this.projectId))) this.$emit('received')
        this.savedIds = new Set(saved.map(i => i.id))
      } catch (_) { if (!this.stopped && requestId === this.requestId) this.loadError = true }
      finally {
        if (!this.stopped && requestId === this.requestId) this.timer = setTimeout(() => this.refresh(), this.open || this.status.checking ? 2000 : 10000)
      }
    },
    async checkNow() {
      this.requesting = true
      try { await checkMobileReceive(); await this.refresh() }
      catch (_) { this.loadError = true }
      finally { this.requesting = false }
    },
  },
}
</script>

<style scoped>
.mobile-receive { position: relative; flex-shrink: 0; }
.receive-trigger { display: flex; align-items: center; gap: 6px; background: transparent; border: 0; color: inherit; font: inherit; padding: 0 10px; margin: 0; line-height: 24px; cursor: pointer; }
.receive-trigger::after { border: 0; }
.receive-dot { width: 6px; height: 6px; border-radius: 50%; background: #42a487; }
.receive-dot.busy { background: #d29636; }
.receive-dot.failed { background: #cf654f; }
.receive-panel { position: fixed; bottom: 34px; left: 64px; width: min(480px, calc(100vw - 80px)); max-height: min(640px, calc(100vh - 80px)); overflow: auto; box-sizing: border-box; padding: 18px; z-index: 1200; border: 1px solid var(--awd-border); border-radius: 12px; background: var(--awd-surface); color: var(--awd-text); box-shadow: 0 8px 32px #0003; font-size: 12px; line-height: 1.6; white-space: normal; }
.receive-panel button { border: 1px solid var(--awd-border); border-radius: 6px; background: transparent; color: inherit; padding: 3px 10px; margin: 0; font-size: 12px; line-height: 22px; cursor: pointer; }
.receive-panel button:disabled { opacity: .5; cursor: default; }
.receive-header, .receive-controls, .receive-item-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.receive-header strong { font-size: 15px; }
.receive-identity { display: flex; flex-direction: column; gap: 3px; padding: 10px; background: var(--awd-surface-3); border-radius: 8px; overflow-wrap: anywhere; }
.receive-identity strong:not(:first-child) { margin-top: 6px; }
.receive-controls { margin: 12px 0; }
.receive-item { display: flex; flex-direction: column; gap: 3px; padding: 10px 0; border-top: 1px solid var(--awd-border); overflow-wrap: anywhere; }
.receive-item-title { align-items: flex-start; }
.receive-item-title span { flex-shrink: 0; }
.receive-item-title strong { min-width: 0; }
.receive-error, .attention { color: #c45843; }
.receive-panel small { opacity: .7; }
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- data-awd-keep-clear：队列在输入卡外面，右下角反馈浮钮要一并避开（utils/keepClear.js） -->
  <view v-if="items.length" class="agent-inbox" data-awd-keep-clear>
    <view class="agent-inbox-head">
      <text class="agent-inbox-title">{{ $t('chat.inboxTitle') }}</text>
      <text class="agent-inbox-count">{{ items.length }}</text>
    </view>
    <view v-for="(item, index) in items" :key="item.id" class="agent-inbox-row">
      <view class="agent-inbox-main">
        <input
          v-if="editingId === item.id"
          v-model="editText"
          class="agent-inbox-edit"
          type="text"
          :maxlength="-1"
          :focus="true"
          @confirm="saveEdit(item, $event)"
        />
        <text v-else class="agent-inbox-message">{{ item.displayText || item.message }}</text>
        <text class="agent-inbox-mode">{{ item.submissionMode === 'steer' ? $t('chat.inboxSteer') : $t('chat.inboxQueued') }}</text>
      </view>
      <view class="agent-inbox-actions">
        <text v-if="editingId === item.id" class="inbox-action primary" @tap="saveEdit(item)">{{ $t('chat.save') }}</text>
        <text v-else class="inbox-action" @tap="beginEdit(item)">{{ $t('chat.inboxEdit') }}</text>
        <text class="inbox-action" :class="{ disabled: index === 0 }" @tap="index > 0 && $emit('move', { item, position: index - 1 })">↑</text>
        <text class="inbox-action" :class="{ disabled: index === items.length - 1 }" @tap="index < items.length - 1 && $emit('move', { item, position: index + 1 })">↓</text>
        <text v-if="item.submissionMode === 'queue'" class="inbox-action primary" @tap="$emit('send-now', item)">{{ $t('chat.inboxSendNow') }}</text>
        <text class="inbox-action danger" @tap="$emit('delete', item)">{{ $t('chat.inboxDelete') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
export default {
  name: 'AgentInbox',
  emits: ['edit', 'delete', 'move', 'send-now'],
  props: {
    items: { type: Array, default: () => [] },
  },
  data() {
    return { editingId: null, editText: '' }
  },
  methods: {
    beginEdit(item) {
      this.editingId = item.id
      this.editText = item.message || ''
    },
    // uni 的 v-model 有 100ms 节流：打完字立刻点保存时 editText 还是旧值，改动会整个丢掉。
    // 取值优先级：confirm 事件自带的值 → 输入框当下的 DOM 值 → v-model 的值。
    saveEdit(item, event) {
      const submitted = event && event.detail ? event.detail.value : undefined
      const field = this.$el && typeof this.$el.querySelector === 'function'
        ? this.$el.querySelector('.agent-inbox-edit input, input.agent-inbox-edit') : null
      const live = typeof submitted === 'string' ? submitted
        : field && typeof field.value === 'string' ? field.value : this.editText
      const message = live.trim()
      if (!message) return
      this.$emit('edit', { item, message })
      this.editingId = null
      this.editText = ''
    },
  },
}
</script>

<style scoped>
.agent-inbox { margin-bottom: 8px; padding: 8px; border: 1px solid var(--awd-border); border-radius: 8px; background: var(--awd-bg); }
.agent-inbox-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.agent-inbox-title { color: var(--awd-text-2); font-size: 12px; font-weight: 600; }
.agent-inbox-count { min-width: 18px; padding: 1px 5px; border-radius: 9px; background: var(--awd-accent-soft); color: var(--awd-accent-text); font-size: 10px; text-align: center; }
.agent-inbox-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 6px 0; border-top: 1px solid var(--awd-border-subtle); }
.agent-inbox-row:first-of-type { border-top: 0; }
.agent-inbox-main { min-width: 0; flex: 1 1 130px; display: flex; align-items: center; gap: 6px; }
.agent-inbox-message { min-width: 0; flex: 1; color: var(--awd-text); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-inbox-mode { flex-shrink: 0; color: var(--awd-text-3); font-size: 10px; }
.agent-inbox-edit { min-width: 0; flex: 1; height: 26px; padding: 0 7px; border: 1px solid var(--awd-accent); border-radius: 5px; background: var(--awd-surface); font-size: 12px; }
.agent-inbox-actions { flex-shrink: 0; display: flex; gap: 5px; align-items: center; }
.inbox-action { color: var(--awd-text-2); font-size: 11px; cursor: pointer; }
.inbox-action.primary { color: var(--awd-accent-text); }
.inbox-action.danger { color: var(--awd-danger); }
.inbox-action.disabled { opacity: .35; cursor: default; }
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="items.length" class="agent-inbox">
    <view class="agent-inbox-head">
      <text class="agent-inbox-title">{{ $t('chat.inboxTitle') }}</text>
      <text class="agent-inbox-count">{{ items.length }}</text>
    </view>
    <!-- 没有正在运行的轮次时这些消息不会被任何人捞走（dev-board#802）。不说一句的话，
         它们挂在这里看着像还会被处理，其实要等到下一轮正常收尾才会以一条陈旧指令被执行。 -->
    <text v-if="!runActive" class="agent-inbox-idle">{{ $t('chat.inboxIdleNotice') }}</text>
    <view v-for="(item, index) in items" :key="item.id" class="agent-inbox-row" :class="{ 'is-quote': inStream(item) }">
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
        <text v-if="inStream(item)" class="inbox-action locate" @tap="$emit('locate', item)">{{ $t('chat.inboxInStream') }}</text>
        <text class="agent-inbox-mode">{{ item.submissionMode === 'steer' ? $t('chat.inboxSteer') : $t('chat.inboxQueued') }}</text>
      </view>
      <view class="agent-inbox-actions">
        <text v-if="editingId === item.id" class="inbox-action primary" @tap="saveEdit(item)">{{ $t('chat.save') }}</text>
        <text v-else class="inbox-action" @tap="beginEdit(item)">{{ $t('chat.inboxEdit') }}</text>
        <text class="inbox-action" :class="{ disabled: index === 0 }" @tap="index > 0 && $emit('move', { item, position: index - 1 })">↑</text>
        <text class="inbox-action" :class="{ disabled: index === items.length - 1 }" @tap="index < items.length - 1 && $emit('move', { item, position: index + 1 })">↓</text>
        <text v-if="canSendNow(item)" class="inbox-action primary" @tap="$emit('send-now', item)">{{ $t('chat.inboxSendNow') }}</text>
        <text class="inbox-action danger" @tap="$emit('delete', item)">{{ $t('chat.inboxDelete') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
export default {
  name: 'AgentInbox',
  emits: ['edit', 'delete', 'move', 'send-now', 'locate'],
  props: {
    items: { type: Array, default: () => [] },
    // 这条插话在对话流里已经有完整气泡了（dev-board#779 K7③）。同一句话同时出现在
    // 对话流和输入框上方，没有任何视觉关联，第一次用的人会以为发重了。正文留在对话流，
    // 这里退成一行引用 + 一个跳过去的入口——这一行本来就是单行省略号形态。
    streamIds: { type: Array, default: () => [] },
    // 当前会话有没有正在运行的轮次（dev-board#802）。steer 项本来靠那一轮在工具边界
    // 把它捞走，轮次没了就没人捞——这时「立即发送」必须给 steer 项也露出来。
    runActive: { type: Boolean, default: false },
  },
  data() {
    return { editingId: null, editText: '' }
  },
  methods: {
    inStream(item) {
      return !!item && this.streamIds.includes(item.id)
    },
    /**
     * 「立即发送」什么时候该出现。
     *
     * queue 项永远可以（它本来就是「排到队尾等」的语义，用户随时可以改主意插到前面）；
     * steer 项只在没有活跃轮次时出现——有轮次在跑时它已经排在下一个工具边界上，
     * 再给一个按钮只会让人以为点了才发。
     */
    canSendNow(item) {
      return !!item && (item.submissionMode === 'queue' || !this.runActive)
    },
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
.agent-inbox-idle { display: block; margin-bottom: 6px; color: var(--awd-text-3); font-size: 11px; line-height: 1.5; }
.agent-inbox-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 6px 0; border-top: 1px solid var(--awd-border-subtle); }
.agent-inbox-row:first-of-type { border-top: 0; }
/* 引用行：对话流里已经有完整气泡的那条，这里只给一条竖线 + 截断正文（K7③） */
.agent-inbox-row.is-quote .agent-inbox-main { padding-left: 6px; border-left: 2px solid var(--awd-border); }
.agent-inbox-row.is-quote .agent-inbox-message { color: var(--awd-text-2); }
.inbox-action.locate { flex-shrink: 0; color: var(--awd-accent-text); }
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

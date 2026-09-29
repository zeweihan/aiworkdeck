<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="prb-bar">
    <text class="prb-title">{{ $t('editor.planReview.title') }}</text>
    <text class="prb-summary">{{ $t('editor.planReview.summary', { hunks, comments: commentCount }) }}</text>
    <view class="prb-actions">
      <view
        class="prb-btn"
        :class="{ disabled: submitting }"
        role="button"
        @tap="!submitting && $emit('discard')"
      >{{ $t('editor.planReview.discard') }}</view>
      <view
        class="prb-btn prb-primary"
        :class="{ disabled: submitting }"
        role="button"
        @tap="!submitting && $emit('submit')"
      >{{ submitting ? $t('editor.planReview.submitting') : $t('editor.planReview.submit') }}</view>
    </view>
  </view>
</template>

<script>
// 计划审阅条（dev-board#1022）：「计划审阅 · N 处改动 · M 条批注」+ 放弃 / 按修订版推进。
// 只负责显示与发事件，提交/放弃的逻辑在 PlainTextEditor。
export default {
  name: 'PlanReviewBar',
  props: {
    hunks: { type: Number, default: 0 },
    added: { type: Number, default: 0 },
    removed: { type: Number, default: 0 },
    commentCount: { type: Number, default: 0 },
    submitting: { type: Boolean, default: false }
  },
  emits: ['submit', 'discard']
}
</script>

<style scoped>
.prb-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--awd-border);
  background: var(--awd-accent-wash);
  flex-shrink: 0;
}
.prb-title { font-size: 12px; font-weight: 600; color: var(--awd-accent-text); }
.prb-summary { font-size: 12px; color: var(--awd-text-2); }
.prb-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.prb-btn {
  font-size: 12px;
  padding: 3px 12px;
  border-radius: 5px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text);
  cursor: pointer;
  user-select: none;
}
.prb-btn:hover { border-color: var(--awd-border-strong); }
.prb-primary { background: var(--awd-accent); border-color: var(--awd-accent); color: var(--awd-text-on-accent); }
.prb-primary:hover { background: var(--awd-accent-hover); border-color: var(--awd-accent-hover); }
.prb-btn.disabled { opacity: 0.55; cursor: default; }
</style>

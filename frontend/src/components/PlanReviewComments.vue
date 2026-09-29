<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="prc-panel">
    <view class="prc-head">
      <text class="prc-head-text">{{ $t('editor.planReview.commentsTitle') }}</text>
      <text class="prc-count">{{ comments.length }}</text>
    </view>
    <view class="prc-list">
      <view v-if="draft" class="prc-draft">
        <text class="prc-quote">{{ clip(draft.quotedText) }}</text>
        <!-- uni textarea 默认 maxlength=140，批注不设上限 -->
        <textarea
          v-model="draftBody"
          class="prc-input"
          :maxlength="-1"
          :focus="true"
          auto-height
          :placeholder="$t('editor.planReview.draftPlaceholder')"
        />
        <view class="prc-draft-actions">
          <view class="prc-btn" role="button" @tap="cancelDraft">{{ $t('editor.planReview.cancel') }}</view>
          <view
            class="prc-btn prc-primary"
            :class="{ disabled: !draftBody.trim() || saving }"
            role="button"
            @tap="saveDraft"
          >{{ $t('editor.planReview.save') }}</view>
        </view>
      </view>
      <view v-if="!draft && !comments.length" class="prc-empty">
        <text>{{ $t('editor.planReview.commentsEmpty') }}</text>
      </view>
      <view v-for="c in comments" :key="c.id" class="prc-item">
        <text class="prc-quote">{{ clip(c.quotedText) }}</text>
        <text v-if="c.found === false" class="prc-lost">{{ $t('editor.planReview.anchorLost') }}</text>
        <text class="prc-body">{{ c.body }}</text>
        <view class="prc-remove" role="button" @tap="$emit('remove', { id: c.id })">
          {{ $t('editor.planReview.remove') }}
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 计划审阅右栏（dev-board#1022）：列出「引用原文 → 评论」，可删；startDraft(sel) 由
// PlainTextEditor 在用户点选区旁的「+」时调用，展开草稿框。只发事件，不直接打接口。
const QUOTE_MAX = 80

export default {
  name: 'PlanReviewComments',
  props: {
    // [{ id, fromLine, toLine, quotedText, body, found }]
    comments: { type: Array, default: () => [] },
    // 父组件在 add 请求在途时置位，防连点重复提交
    saving: { type: Boolean, default: false }
  },
  emits: ['add', 'remove'],
  data() {
    return { draft: null, draftBody: '' }
  },
  methods: {
    clip(text) {
      const s = String(text || '').replace(/\s+/g, ' ').trim()
      return s.length > QUOTE_MAX ? s.slice(0, QUOTE_MAX) + '…' : s
    },
    startDraft(sel) {
      if (!sel) return
      this.draft = { fromLine: sel.fromLine, toLine: sel.toLine, quotedText: sel.quotedText }
      this.draftBody = ''
    },
    cancelDraft() {
      this.draft = null
      this.draftBody = ''
    },
    saveDraft() {
      const body = this.draftBody.trim()
      if (!this.draft || !body || this.saving) return
      this.$emit('add', { ...this.draft, body })
    },
    // 父组件保存成功后调用，收起草稿框
    closeDraft() {
      this.cancelDraft()
    }
  }
}
</script>

<style scoped>
.prc-panel {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--awd-border);
  background: var(--awd-surface-2);
  min-height: 0;
}
.prc-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
}
.prc-head-text { font-size: 12px; font-weight: 600; color: var(--awd-text); }
.prc-count { font-size: 11px; color: var(--awd-text-3); }
.prc-list { flex: 1; min-height: 0; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.prc-empty { font-size: 12px; color: var(--awd-text-3); padding: 8px 4px; line-height: 1.6; }
.prc-item,
.prc-draft {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  border: 1px solid var(--awd-border-subtle);
  border-radius: 6px;
  background: var(--awd-surface);
}
.prc-draft { border-color: var(--awd-accent); }
.prc-quote {
  font-size: 11px;
  color: var(--awd-text-2);
  border-left: 2px solid var(--awd-border-strong);
  padding-left: 6px;
  line-height: 1.5;
  word-break: break-all;
}
.prc-lost { font-size: 10px; color: var(--awd-warning-text); }
.prc-body { font-size: 12px; color: var(--awd-text); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.prc-input {
  width: 100%;
  min-height: 56px;
  box-sizing: border-box;
  font-size: 12px;
  line-height: 1.6;
  padding: 4px 6px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  background: var(--awd-surface);
  color: var(--awd-text);
}
.prc-draft-actions { display: flex; justify-content: flex-end; gap: 6px; }
.prc-btn {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text);
  cursor: pointer;
  user-select: none;
}
.prc-primary { background: var(--awd-accent); border-color: var(--awd-accent); color: var(--awd-text-on-accent); }
.prc-btn.disabled { opacity: 0.55; cursor: default; }
.prc-remove {
  align-self: flex-end;
  font-size: 11px;
  color: var(--awd-text-3);
  cursor: pointer;
  user-select: none;
}
.prc-remove:hover { color: var(--awd-danger-text); }
</style>

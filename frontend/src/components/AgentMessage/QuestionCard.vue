<template>
  <div class="question-card" :class="{ 'is-history': !actionable }">
    <div class="q-head">
      <span class="q-dot"></span>
      <span class="q-title">{{ $t('chat.questionTitle') }}</span>
      <span v-if="answered" class="q-badge">{{ $t('chat.answeredBadge') }}</span>
    </div>

    <!-- 正文：<question> 标签里的提问本身。
         为空是合法的——正文可能还在 bubble.content 里（旧格式的历史消息），
         那种情况下本卡只负责选项，正文由气泡的 main-content 渲染，不重复显示。 -->
    <div v-if="text" class="q-body">
      <MarkdownPreview :content="text" />
    </div>

    <!-- 选项按钮组：形状照 ArtifactCard 的 approval-bar（同一套按钮语汇）。
         选项之间是互斥的平级候选，所以不分主次按钮——谁都可能是正确答案。
         点一下即等于用户自己打了这几个字（发出去的就是选项原文），
         刻意不为它拼装「我选择了 X」这类机器口吻长句。 -->
    <div v-if="options.length" class="q-options">
      <div
        v-for="(opt, i) in options"
        :key="i"
        class="btn-option"
        :class="{ disabled: !actionable }"
        @click.stop="pick(opt)"
      >
        <span>{{ opt }}</span>
      </div>
    </div>

    <!-- 无选项 = 开放式提问：只提示在既有输入框回答，绝不另造第二个输入框
         （两个输入框的界面用户永远不知道该往哪个里打字） -->
    <div v-else-if="actionable" class="q-hint">{{ $t('chat.answerHint') }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps({
  /** <question> 正文（可为空，见模板注释） */
  text: { type: String, default: '' },
  /** <option> 子标签内容，可为空数组（开放式提问） */
  options: { type: Array, default: () => [] },
  /**
   * 是否可操作。沿用 artifact 那条链：只有最新一条助手消息、且流已结束时为真。
   * 历史里的问题卡一律渲染成不可点的痕迹——用户答过的问题不该有个还能再点的按钮。
   */
  actionable: { type: Boolean, default: false },
  /** 已作答（发过下一条消息即为真）：给历史态一个明确的说明，而不是只让按钮变灰 */
  answered: { type: Boolean, default: false }
})

const emit = defineEmits(['answer'])

const options = computed(() => (Array.isArray(props.options) ? props.options : []))

function pick(opt) {
  if (!props.actionable) return
  emit('answer', opt)
}
</script>

<style scoped>
.question-card {
  background: var(--awd-surface);
  border-top: 1px solid var(--awd-border-subtle);
  padding: 10px 16px 12px;
}

/* 历史态：去掉强调底色，只留一条痕迹 */
.question-card.is-history {
  background: var(--awd-surface);
}

.q-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.q-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--awd-warning);
  flex-shrink: 0;
}

.question-card.is-history .q-dot {
  background: #ADB5BD;
}

.q-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-accent-text);
}

.q-badge {
  font-size: 9px;
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.q-body {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--awd-text);
  margin-bottom: 8px;
}

.q-body :deep(.markdown-preview) {
  padding: 0 !important;
  background: transparent !important;
  min-height: auto;
  height: auto;
  margin: 0;
  overflow: visible;
}

.q-body :deep(.markdown-body) {
  font-size: 12.5px;
  line-height: 1.55;
  margin: 0;
  padding: 0;
  color: var(--awd-text);
}

/* 选项按钮组（对齐 approval-bar 的 flex + 8px 间距；窄栏里换行堆叠） */
.q-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.btn-option {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  color: var(--awd-text);
  font-size: 12px;
  padding: 5px 14px;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.15s;
  max-width: 100%;
  word-break: break-word;
}

.btn-option:hover {
  border-color: var(--awd-mint);
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

.btn-option.disabled {
  cursor: default;
  color: var(--awd-text-2);
  background: var(--awd-bg);
}

.btn-option.disabled:hover {
  border-color: var(--awd-border);
  color: var(--awd-text-2);
  background: var(--awd-bg);
}

.q-hint {
  font-size: 11px;
  color: var(--awd-text-2);
}
</style>

<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  ask_user 工具的问题卡（dev-board#868），与桌面端 QuestionCard.vue 的 ask_user 变体同一套交互：
  - 单选：点一下即作答；
  - 多选：勾完点「发送回答」；
  - 「其他」：点开才出现的文本框（开放式提问没有选项，文本框直接在）；
  - 作答后整卡只读，高亮当时的选择，「其他」里写的内容作为一行补充显示。
  只负责交互与渲染；发什么、怎么发由 chatSession.answerAskUser 决定（emit('answer')）。
  窗格最窄约 320px：选项纵向堆叠、长文本折行，任何一行都不许把卡片撑出横向滚动。
-->
<template>
  <div class="ask-card" :class="{ 'is-locked': !actionable }">
    <div v-if="question.header || question.answered" class="ask-head">
      <span v-if="question.header" class="ask-chip" :title="question.header">{{ question.header }}</span>
      <span v-if="question.answered" class="ask-badge">{{ t('answered') }}</span>
    </div>

    <!-- renderMarkdown 先整体 HTML 转义再套标签，v-html 无注入面 -->
    <div v-if="question.text" class="ask-text md" v-html="renderMarkdown(question.text)"></div>

    <div v-if="options.length" class="ask-choices" role="group" :aria-label="question.text">
      <button
        v-for="(opt, oi) in options"
        :key="'o' + oi"
        type="button"
        class="ask-choice"
        :class="{ 'is-chosen': isChosen(oi) }"
        :aria-pressed="isChosen(oi) ? 'true' : 'false'"
        :disabled="!actionable"
        @click="choose(oi)"
      >
        <span class="ask-mark" :class="question.multiSelect ? 'is-check' : 'is-radio'" aria-hidden="true"></span>
        <span class="ask-choice-text">
          <span class="ask-choice-label">{{ opt }}</span>
          <span v-if="descriptions[oi]" class="ask-choice-desc">{{ descriptions[oi] }}</span>
        </span>
      </button>
      <button
        v-if="actionable"
        type="button"
        class="ask-choice ask-choice-other"
        :class="{ 'is-chosen': otherOpen }"
        :aria-pressed="otherOpen ? 'true' : 'false'"
        @click="toggleOther"
      >
        <span class="ask-mark" :class="question.multiSelect ? 'is-check' : 'is-radio'" aria-hidden="true"></span>
        <span class="ask-choice-text"><span class="ask-choice-label">{{ t('askUserOther') }}</span></span>
      </button>
    </div>

    <input
      v-if="actionable && (otherOpen || !options.length)"
      ref="otherInput"
      v-model="otherText"
      class="ask-other-input"
      type="text"
      maxlength="500"
      :placeholder="t('askUserOtherPlaceholder')"
      @keydown.enter="onOtherEnter"
    />

    <div v-if="actionable && submitNeeded" class="ask-actions">
      <button type="button" class="ask-submit" :disabled="!canSubmit" @click="submit">{{ t('askUserSubmit') }}</button>
    </div>

    <div v-if="!actionable && question.answer && question.answer.other" class="ask-answer-other">
      {{ t('askUserOtherAnswer', { text: question.answer.other }) }}
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '../lib/i18n.js'
import { renderMarkdown } from '../lib/markdown.js'
import { toggleChoice, needsSubmit, collectAskUserAnswer } from '../lib/askUser.js'

const props = defineProps({
  /** chatSession 的 ask_user question 模型（askUser.questionFromParsed / normalizeAskUserEvent） */
  question: { type: Object, required: true },
  /** 只有最末一条、未作答、且不在生成中的那一问可操作（由 ChatView 判定） */
  actionable: { type: Boolean, default: false }
})
const emit = defineEmits(['answer'])

const options = computed(() => (Array.isArray(props.question.options) ? props.question.options : []))
const descriptions = computed(() => (Array.isArray(props.question.descriptions) ? props.question.descriptions : []))

const chosen = ref([])
const otherOpen = ref(false)
const otherText = ref('')
const otherInput = ref(null)
// 同一张卡只发一次：按钮可能被连点，回车与点击也可能前后脚
const sent = ref(false)

// 换了一个问题（一轮里第二次 ask_user、或事件整块覆盖成新 id）就清掉上一问的草稿
watch(() => props.question.id, () => {
  chosen.value = []
  otherOpen.value = false
  otherText.value = ''
  sent.value = false
})

function isChosen(i) {
  if (!props.actionable) {
    const a = props.question.answer
    return !!(a && Array.isArray(a.selected) && a.selected.includes(options.value[i]))
  }
  return chosen.value.includes(i)
}

const submitNeeded = computed(() => needsSubmit(props.question, otherOpen.value))
const canSubmit = computed(() => chosen.value.length > 0 || otherText.value.trim().length > 0)

function choose(i) {
  if (!props.actionable || sent.value) return
  chosen.value = toggleChoice(chosen.value, i, props.question.multiSelect)
  if (props.question.multiSelect) return
  otherOpen.value = false
  submit()
}

function toggleOther() {
  if (!props.actionable || sent.value) return
  otherOpen.value = !otherOpen.value
  // 单选时「其他」与选项互斥：打开「其他」就撤掉已点的那一项
  if (otherOpen.value && !props.question.multiSelect) chosen.value = []
  if (otherOpen.value) nextTick(() => { if (otherInput.value) otherInput.value.focus() })
}

function onOtherEnter(e) {
  // 输入法组合中的回车是在上屏候选词，不是提交
  if (e && (e.isComposing || e.keyCode === 229)) return
  if (e) e.preventDefault()
  submit()
}

function submit() {
  if (!props.actionable || sent.value) return
  const answer = collectAskUserAnswer(props.question, {
    chosen: chosen.value, otherOpen: otherOpen.value, otherText: otherText.value
  })
  if (!answer) return
  sent.value = true
  emit('answer', answer)
}
</script>

<style scoped>
.ask-card {
  margin-top: 6px;
  max-width: 92%;
  box-sizing: border-box;
  padding: 9px 10px 10px;
  border: 1px solid var(--awd-border);
  border-left: 3px solid var(--awd-primary);
  border-radius: 8px;
  background: var(--awd-surface);
  min-width: 0;
}

.ask-card.is-locked { border-left-color: var(--awd-border-strong); }

.ask-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.ask-chip {
  font-size: 10.5px;
  line-height: 17px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--awd-mint-pale);
  color: var(--awd-primary);
  font-weight: 600;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ask-card.is-locked .ask-chip {
  background: var(--awd-bone);
  color: var(--awd-text-secondary);
}

.ask-badge {
  font-size: 10.5px;
  color: var(--awd-text-secondary);
}

.ask-text {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--awd-text);
  word-break: break-word;
  margin-bottom: 8px;
}

.ask-text :deep(p) { margin: 0 0 6px; }
.ask-text :deep(p:last-child) { margin-bottom: 0; }

.ask-choices {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.ask-choice,
.ask-submit {
  appearance: none;
  -webkit-appearance: none;
  font: inherit;
  margin: 0;
  line-height: 1.45;
}

.ask-choice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  text-align: left;
  padding: 6px 9px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  color: var(--awd-text);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.ask-choice:hover:not(:disabled) {
  border-color: var(--awd-primary);
  background: var(--awd-mint-pale);
}

.ask-choice:focus-visible,
.ask-submit:focus-visible,
.ask-other-input:focus-visible {
  outline: 2px solid var(--awd-primary);
  outline-offset: 1px;
}

.ask-choice.is-chosen {
  border-color: var(--awd-primary);
  background: var(--awd-mint-pale);
}

.ask-choice:disabled {
  cursor: default;
  color: var(--awd-text-secondary);
  background: var(--awd-bg);
}

.ask-choice:disabled.is-chosen {
  color: var(--awd-text);
  border-color: var(--awd-primary);
  background: var(--awd-mint-pale);
}

.ask-mark {
  flex-shrink: 0;
  width: 12px;
  height: 12px;
  margin-top: 3px;
  box-sizing: border-box;
  border: 1.5px solid var(--awd-border-strong);
  background: var(--awd-surface);
}

.ask-mark.is-radio { border-radius: 50%; }
.ask-mark.is-check { border-radius: 3px; }

.ask-choice.is-chosen .ask-mark {
  border-color: var(--awd-primary);
  background: var(--awd-primary);
  box-shadow: inset 0 0 0 2px var(--awd-surface);
}

.ask-choice-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.ask-choice-label {
  font-size: 12.5px;
  font-weight: 500;
}

.ask-choice.is-chosen .ask-choice-label { color: var(--awd-primary); }

.ask-choice-desc {
  font-size: 11.5px;
  color: var(--awd-text-secondary);
}

.ask-other-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 6px;
  height: 30px;
  padding: 0 9px;
  font: inherit;
  font-size: 12.5px;
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  outline: none;
}

.ask-other-input::placeholder { color: var(--awd-text-secondary); }

.ask-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.ask-submit {
  font-size: 12px;
  font-weight: 500;
  padding: 5px 14px;
  border-radius: var(--awd-radius-sm);
  border: 1px solid var(--awd-primary);
  background: var(--awd-primary);
  color: #fff;
  cursor: pointer;
}

.ask-submit:hover:not(:disabled) {
  background: var(--awd-primary-hover);
  border-color: var(--awd-primary-hover);
}

.ask-submit:disabled {
  cursor: default;
  background: var(--awd-bone);
  border-color: var(--awd-border);
  color: var(--awd-text-secondary);
}

.ask-answer-other {
  margin-top: 6px;
  font-size: 12px;
  color: var(--awd-text-secondary);
  word-break: break-word;
}
</style>

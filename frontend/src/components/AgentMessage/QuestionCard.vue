<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="question-card" :class="{ 'is-history': !actionable, 'is-ask-user': isAskUser }">
    <div class="q-head">
      <span class="q-dot"></span>
      <span class="q-title">{{ $t('chat.questionTitle') }}</span>
      <span v-if="isAskUser && header" class="q-chip">{{ header }}</span>
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
    <div v-if="!isAskUser && options.length" class="q-options">
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
    <div v-else-if="!isAskUser && actionable" class="q-hint">{{ $t('chat.answerHint') }}</div>

    <!-- ask_user 工具的提问（dev-board#868，对标 Claude Code 的 AskUserQuestion）：
         可点选的选项（单选点一下即作答；多选勾完再提交）+ 一个「其他」自由输入。
         「其他」只在用户点开时出现、而且只在问题卡里——作答后整卡变只读，不会与主输入框并存
         成两个都能打字的地方。 -->
    <template v-if="isAskUser">
      <div
        v-if="options.length || actionable"
        class="q-choices"
        role="group"
        :aria-label="text"
      >
        <button
          v-for="(opt, i) in options"
          :key="'o' + i"
          type="button"
          class="q-choice"
          :class="{ 'is-chosen': isChosen(i), 'is-locked': !actionable }"
          :aria-pressed="isChosen(i) ? 'true' : 'false'"
          :disabled="!actionable"
          @click.stop="choose(i)"
        >
          <span class="q-mark" :class="multiSelect ? 'is-check' : 'is-radio'" aria-hidden="true"></span>
          <span class="q-choice-text">
            <span class="q-choice-label">{{ opt }}</span>
            <span v-if="descriptions[i]" class="q-choice-desc">{{ descriptions[i] }}</span>
          </span>
        </button>
        <button
          v-if="actionable && options.length"
          type="button"
          class="q-choice q-choice-other"
          :class="{ 'is-chosen': otherOpen }"
          :aria-pressed="otherOpen ? 'true' : 'false'"
          @click.stop="toggleOther"
        >
          <span class="q-mark" :class="multiSelect ? 'is-check' : 'is-radio'" aria-hidden="true"></span>
          <span class="q-choice-text"><span class="q-choice-label">{{ $t('chat.askUserOther') }}</span></span>
        </button>
      </div>

      <div v-if="actionable && (otherOpen || !options.length)" class="q-other">
        <input
          ref="otherInput"
          v-model="otherText"
          class="q-other-input"
          type="text"
          :maxlength="500"
          :placeholder="$t('chat.askUserOtherPlaceholder')"
          @keydown.enter="onOtherEnter"
        />
      </div>

      <div v-if="actionable && needsSubmit" class="q-actions">
        <button type="button" class="q-submit" :disabled="!canSubmit" @click.stop="submit">
          {{ $t('chat.askUserSubmit') }}
        </button>
      </div>

      <div v-if="!actionable && answer && answer.other" class="q-answer-other">
        {{ $t('chat.askUserOtherAnswer', { text: answer.other }) }}
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
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
  answered: { type: Boolean, default: false },
  /** 'ask_user' = ask_user 工具的提问（选项可带说明、可多选、有「其他」）；空 = 模型自己写的 <question> 标签 */
  kind: { type: String, default: '' },
  /** ask_user 的提问 id：回答消息靠它指明「答的是哪一问」 */
  questionId: { type: String, default: '' },
  /** ask_user 的短标签（如「清理范围」） */
  header: { type: String, default: '' },
  /** 与 options 按下标对齐的选项说明 */
  descriptions: { type: Array, default: () => [] },
  multiSelect: { type: Boolean, default: false },
  /** 已作答时当时的选择 {selected: string[], other: string}，只读态据此高亮 */
  answer: { type: Object, default: null }
})

const emit = defineEmits(['answer'])

const options = computed(() => (Array.isArray(props.options) ? props.options : []))
const descriptions = computed(() => (Array.isArray(props.descriptions) ? props.descriptions : []))
const isAskUser = computed(() => props.kind === 'ask_user')

function pick(opt) {
  if (!props.actionable) return
  emit('answer', opt)
}

// ---- ask_user ----
const chosen = ref([]) // 选中的下标
const otherOpen = ref(false)
const otherText = ref('')
const otherInput = ref(null)
// 同一张卡只发一次：uni 的 input 可能同时派发 confirm 与 keydown，按钮也可能被连点
const sent = ref(false)

// 换了一个问题（新一轮 ask_user 覆盖了这张卡）就清掉上一问的草稿
watch(() => props.questionId, () => {
  chosen.value = []
  otherOpen.value = false
  otherText.value = ''
  sent.value = false
})

function isChosen(i) {
  if (!props.actionable) {
    return !!(props.answer && Array.isArray(props.answer.selected) && props.answer.selected.includes(options.value[i]))
  }
  return chosen.value.includes(i)
}

// 单选且没打开「其他」时点一下就作答；多选、打开了「其他」或开放式提问才需要一个明确的提交
const needsSubmit = computed(() => props.multiSelect || otherOpen.value || !options.value.length)

// 控件本体（uni-h5 下 ref 拿到的是组件实例，原生 input 在它里面）
function otherField() {
  const r = otherInput.value
  const root = r && (r.$el || r)
  if (!root) return null
  if (typeof root.matches === 'function' && root.matches('input')) return root
  return typeof root.querySelector === 'function' ? root.querySelector('input') : null
}

// uni 的 v-model 有 100ms 节流：打完字立刻回车/点提交时 data 里还是旧值。以控件当下的 DOM 值为准
function liveOtherText() {
  const field = otherField()
  return field && typeof field.value === 'string' ? field.value : otherText.value
}

const canSubmit = computed(() => chosen.value.length > 0 || otherText.value.trim().length > 0)

function choose(i) {
  if (!props.actionable || sent.value) return
  if (props.multiSelect) {
    chosen.value = chosen.value.includes(i) ? chosen.value.filter(x => x !== i) : [...chosen.value, i]
    return
  }
  chosen.value = [i]
  otherOpen.value = false
  submit()
}

function toggleOther() {
  if (!props.actionable || sent.value) return
  otherOpen.value = !otherOpen.value
  // 单选时「其他」与选项互斥：打开「其他」就撤掉已点的那一项
  if (otherOpen.value && !props.multiSelect) chosen.value = []
  if (otherOpen.value) {
    nextTick(() => {
      const field = otherField()
      if (field && typeof field.focus === 'function') field.focus()
    })
  }
}

function onOtherEnter(e) {
  // 输入法组合中的回车是在上屏候选词，不是提交（同 ChatInterface.handleEnterKey 那条闩）
  if (e && (e.isComposing || e.keyCode === 229)) return
  if (e && typeof e.preventDefault === 'function') e.preventDefault()
  submit()
}

function submit() {
  if (!props.actionable || sent.value) return
  const other = (otherOpen.value || !options.value.length) ? liveOtherText().trim() : ''
  const selected = chosen.value.map(i => options.value[i]).filter(Boolean)
  if (!selected.length && !other) return
  sent.value = true
  emit('answer', {
    kind: 'ask_user',
    id: props.questionId,
    question: props.text,
    selected,
    other
  })
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
  background: var(--awd-text-3);
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

/* ---- ask_user（dev-board#868）---- */
.q-chip {
  font-size: 10px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  color: var(--awd-text-2);
  background: var(--awd-surface-2);
  max-width: 10em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.q-choices {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 真 <button>：打平 uni-h5 与浏览器的默认按钮样式（同 ChatInterface 的 .attention-locator） */
.q-choice,
.q-submit {
  appearance: none;
  -webkit-appearance: none;
  font: inherit;
  margin: 0;
  line-height: 1.45;
}

.q-choice::after,
.q-submit::after {
  border: 0;
}

.q-choice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 7px 10px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-surface);
  color: var(--awd-text);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  box-sizing: border-box;
}

.q-choice:hover:not(:disabled) {
  border-color: var(--awd-accent);
  background: var(--awd-accent-wash);
}

.q-choice:focus-visible,
.q-submit:focus-visible,
.q-other-input:focus-visible {
  outline: 2px solid var(--awd-accent);
  outline-offset: 1px;
}

.q-choice.is-chosen {
  border-color: var(--awd-accent);
  background: var(--awd-accent-soft);
}

.q-choice.is-locked {
  cursor: default;
  color: var(--awd-text-2);
}

.q-choice.is-locked.is-chosen {
  color: var(--awd-text);
}

.q-choice.is-locked:not(.is-chosen) {
  background: var(--awd-bg);
}

.q-mark {
  flex-shrink: 0;
  width: 12px;
  height: 12px;
  margin-top: 3px;
  border: 1.5px solid var(--awd-border-strong);
  background: var(--awd-surface);
  box-sizing: border-box;
}

.q-mark.is-radio {
  border-radius: 50%;
}

.q-mark.is-check {
  border-radius: 3px;
}

.q-choice.is-chosen .q-mark {
  border-color: var(--awd-accent);
  background: var(--awd-accent);
  box-shadow: inset 0 0 0 2px var(--awd-surface);
}

.q-choice-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  word-break: break-word;
}

.q-choice-label {
  font-size: 12.5px;
  font-weight: 500;
}

.q-choice.is-chosen .q-choice-label {
  color: var(--awd-accent-text);
}

.q-choice-desc {
  font-size: 11.5px;
  color: var(--awd-text-2);
}

.q-other {
  margin-top: 6px;
}

.q-other-input {
  width: 100%;
  box-sizing: border-box;
  height: 30px;
  padding: 0 10px;
  font-size: 12.5px;
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  outline: none;
}

.q-other-input::placeholder {
  color: var(--awd-text-3);
}

.q-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.q-submit {
  font-size: 12px;
  font-weight: 500;
  padding: 5px 16px;
  border-radius: 6px;
  border: 1px solid var(--awd-accent);
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  cursor: pointer;
}

.q-submit:hover:not(:disabled) {
  background: var(--awd-accent-hover);
  border-color: var(--awd-accent-hover);
}

.q-submit:disabled {
  cursor: default;
  background: var(--awd-surface-2);
  border-color: var(--awd-border);
  color: var(--awd-text-3);
}

.q-answer-other {
  margin-top: 6px;
  font-size: 12px;
  color: var(--awd-text-2);
  word-break: break-word;
}
</style>

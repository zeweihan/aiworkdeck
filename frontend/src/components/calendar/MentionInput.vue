<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  MentionInput — 事项标题/备注的输入框，支持 `@` 关联文件或成员（dev-board#896）。

  基于原生 <textarea>（单行模式 rows=1 自动增高），不用 contenteditable：文字里只留
  `@名字` 纯文本，结构化关系由宿主落到字段（选文件 = 加关联文件，选成员 = 设负责人）。

  **标签选择不是风格问题**：uni-h5 编译器会把模板里的 textarea 换成 uni 内置组件（默认
  maxlength 140、v-model 100ms 节流、不透传 keydown），所以写成 `<component :is="'textarea'">`
  拿真原生元素（同 FeedbackWidget.vue 的做法）。

  键盘：选择器开着时，↑↓ 移动、Enter/Tab 选中、Esc 只关选择器——这几个键在 window 捕获段
  截停，免得宿主弹窗的 Esc 关窗或工作台快捷键（document 捕获段）先把它吃掉。宿主弹窗
  自己的 window 捕获监听遇到 `.mention-input.is-picking` 内的事件应当放行（TaskDialog 已这样做）。
  单行模式下 Enter（非输入法上屏）不换行，emit submit。

  查询词提取、文本替换、成员匹配是纯函数，在同目录 mentionText.js（node 测试直接导入）。
-->
<template>
  <view class="mention-input" :class="{ 'is-picking': open, 'is-multiline': multiline }">
    <component
      :is="'textarea'"
      ref="ta"
      class="mi-textarea"
      :value="modelValue"
      :placeholder="placeholder"
      :rows="multiline ? rows : 1"
      :maxlength="maxlength > 0 ? maxlength : null"
      @input="onInput"
      @keydown="onKeydown"
      @click="refreshQuery"
      @keyup="onKeyup"
      @blur="onBlur"
      @compositionstart="composing = true"
      @compositionend="onCompositionEnd"
    />
    <MentionPicker
      v-if="open"
      ref="picker"
      placement="bottom"
      :files="files"
      :members="members"
      :query="query"
      :loading="loading"
      :title="$t('calendar.mentionTitle')"
      :hint="$t('calendar.mentionHint')"
      :empty-text="$t('calendar.mentionNoMatch')"
      @select="pick"
    />
  </view>
</template>

<script>
import MentionPicker from '@/components/AgentMessage/MentionPicker.vue'
import { extractMentionQuery, applyMention, memberName } from '@/components/calendar/mentionText.js'

export default {
  name: 'MentionInput',
  components: { MentionPicker },
  props: {
    modelValue: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    /** 候选文件（扁平、已剔除系统文件夹）：[{id, name, dirLabel?, isDir?}] */
    files: { type: Array, default: () => [] },
    /** 候选成员：[{userId, displayName, username, role, roleLabel?}] */
    members: { type: Array, default: () => [] },
    multiline: { type: Boolean, default: false },
    /** 多行模式的起始行数 */
    rows: { type: Number, default: 3 },
    /** 0 表示不限（注意 uni 的 140 默认值在原生元素上不存在，这里显式给上限） */
    maxlength: { type: Number, default: 0 },
    loading: { type: Boolean, default: false },
  },
  emits: ['update:modelValue', 'mention-file', 'mention-member', 'submit'],
  data() {
    return {
      open: false,
      query: '',
      start: -1,
      composing: false,
    }
  },
  watch: {
    modelValue() {
      this.$nextTick(this.autoGrow)
    },
    open(v) {
      if (v) this.attachKeys()
      else this.detachKeys()
    },
  },
  mounted() {
    this.autoGrow()
  },
  beforeUnmount() {
    this.detachKeys()
  },
  methods: {
    el() {
      const r = this.$refs.ta
      return r && r.$el ? r.$el : r
    },
    focus() {
      const el = this.el()
      if (!el) return
      el.focus()
      const n = el.value.length
      try { el.setSelectionRange(n, n) } catch (e) { /* 不支持选区的环境忽略 */ }
    },
    autoGrow() {
      const el = this.el()
      if (!el || !el.style) return
      el.style.height = 'auto'
      el.style.height = el.scrollHeight + 'px'
    },
    onInput(e) {
      let value = e.target.value
      if (!this.multiline && /[\r\n]/.test(value)) {
        // 单行模式粘贴进来的换行折成空格
        const caret = e.target.selectionStart
        value = value.replace(/\r?\n/g, ' ')
        e.target.value = value
        try { e.target.setSelectionRange(caret, caret) } catch (err) { /* ignore */ }
      }
      this.$emit('update:modelValue', value)
      this.refreshQuery()
      this.autoGrow()
    },
    onCompositionEnd(e) {
      this.composing = false
      this.onInput(e)
    },
    onKeyup(e) {
      // 方向键左右/Home/End 移动光标后重新判断是否还在 @ 查询里
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) this.refreshQuery()
    },
    refreshQuery() {
      const el = this.el()
      if (!el) return
      const hit = extractMentionQuery(el.value, el.selectionStart)
      if (!hit) {
        this.closePicker()
        return
      }
      this.query = hit.query
      this.start = hit.start
      this.open = true
    },
    closePicker() {
      this.open = false
      this.query = ''
      this.start = -1
    },
    onBlur() {
      // 条目用 @mousedown.prevent，点选不会先 blur；真失焦就收起
      this.closePicker()
    },
    onKeydown(e) {
      if (this.open) return // 选择器开着时由 window 捕获段处理
      if (!this.multiline && e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
        if (e.isComposing || this.composing || e.keyCode === 229) return
        e.preventDefault()
        this.$emit('submit')
      }
    },
    attachKeys() {
      if (this._keyHandler) return
      this._keyHandler = (e) => {
        if (!this.open || e.target !== this.el()) return
        if (e.isComposing || this.composing || e.keyCode === 229) return
        const picker = this.$refs.picker
        let handled = true
        if (e.key === 'ArrowDown') picker && picker.moveActive(1)
        else if (e.key === 'ArrowUp') picker && picker.moveActive(-1)
        else if (e.key === 'Enter' || e.key === 'Tab') {
          const item = picker && picker.activeItem()
          if (item) this.pick(item)
          else this.closePicker()
        } else if (e.key === 'Escape') this.closePicker()
        else handled = false
        if (handled) {
          e.preventDefault()
          e.stopPropagation()
        }
      }
      window.addEventListener('keydown', this._keyHandler, true)
    },
    detachKeys() {
      if (!this._keyHandler) return
      window.removeEventListener('keydown', this._keyHandler, true)
      this._keyHandler = null
    },
    pick(item) {
      const el = this.el()
      if (!item || !el || this.start < 0) {
        this.closePicker()
        return
      }
      const isMember = item.kind === 'member'
      const name = isMember ? memberName(item) : item.name
      const next = applyMention(el.value, this.start, el.selectionStart, name)
      el.value = next.text
      this.$emit('update:modelValue', next.text)
      if (isMember) this.$emit('mention-member', item)
      else this.$emit('mention-file', item)
      this.closePicker()
      this.$nextTick(() => {
        el.focus()
        try { el.setSelectionRange(next.caret, next.caret) } catch (e) { /* ignore */ }
        this.autoGrow()
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.mention-input {
  position: relative;
  display: block;
  min-width: 0;
}

.mi-textarea {
  display: block;
  width: 100%;
  min-height: 36px;
  padding: 8px 12px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-surface);
  font-size: 13px;
  line-height: 18px;
  font-family: inherit;
  color: var(--awd-text);
  box-sizing: border-box;
  resize: none;
  overflow: hidden;
  outline: none;
  transition: border-color 0.15s ease;

  &::placeholder {
    color: var(--awd-text-3);
  }

  &:focus {
    border-color: var(--awd-mint);
  }
}

.mention-input.is-multiline .mi-textarea {
  min-height: 72px;
  max-height: 200px;
  overflow-y: auto;
}
</style>

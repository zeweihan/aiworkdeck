<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  应用内对话框视图（dev-board#849，视觉定稿见设计稿 v2 的「对话框」一节）。
  两种形态：kind='modal' 确认框（接管 uni.showModal）、kind='sheet' 选项表
  （接管 uni.showActionSheet）。由 AwdDialogHost 渲染，不直接使用——业务代码调
  utils/dialog.js 的 showDialog / showSheet，或照旧调 uni.showModal。

  **标签选择不是风格问题**（同 FeedbackWidget.vue 的说明）：uni 的 H5 编译器会把模板里的
  button / input 改写成 uni 内置组件，而本组件挂在页面树之外的独立 app 上，那些组件
  在这里根本不存在。所以按钮与输入框一律写成 `<component :is="'button'">`，
  绕开标签名映射拿到真原生元素（要原生 button 的焦点与可访问性语义）。

  键位（语义见 utils/dialogCore.js 的 resolveDialogKey）：Esc 取消；Enter 按下焦点所在
  的按钮，焦点不在按钮上时等于确认；Tab 在对话框内循环。监听挂在 window 捕获段并
  截停这三个键，免得工作台自己的快捷键（document 捕获段）先把它吃掉。
-->
<template>
  <div class="awd-dlg-mask" @mousedown="onMaskDown">
    <div
      ref="panel"
      class="awd-dlg"
      :class="{ 'awd-dlg--sheet': kind === 'sheet' }"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      :aria-labelledby="labelId"
      :aria-describedby="kind === 'modal' && content && !editable && title ? bodyId : null"
    >
      <template v-if="kind === 'sheet'">
        <div v-if="title" :id="titleId" class="awd-dlg__head awd-dlg__title">{{ title }}</div>
        <div class="awd-dlg__list">
          <component
            :is="'button'"
            v-for="(item, i) in itemList"
            :key="i"
            type="button"
            class="awd-dlg__item"
            :style="itemColor ? { color: itemColor } : null"
            @click="pick(i)"
          >{{ item }}</component>
        </div>
        <div class="awd-dlg__actions">
          <component
            :is="'button'"
            ref="cancelBtn"
            type="button"
            class="awd-dlg__btn awd-dlg__btn--ghost awd-dlg__cancel"
            @click="cancel"
          >{{ cancelText }}</component>
        </div>
      </template>

      <template v-else>
        <div v-if="title || danger" class="awd-dlg__head">
          <span v-if="danger" class="awd-dlg__icon" aria-hidden="true">!</span>
          <div v-if="title" :id="titleId" class="awd-dlg__title">{{ title }}</div>
        </div>
        <component
          :is="'input'"
          v-if="editable"
          ref="input"
          type="text"
          class="awd-dlg__input"
          :value="draft"
          :placeholder="placeholderText"
          :aria-labelledby="title ? titleId : null"
          @input="draft = $event.target.value"
        />
        <div v-else-if="content" :id="bodyId" class="awd-dlg__body">{{ content }}</div>
        <div class="awd-dlg__actions">
          <component
            :is="'button'"
            v-if="showCancel"
            ref="cancelBtn"
            type="button"
            class="awd-dlg__btn awd-dlg__btn--ghost awd-dlg__cancel"
            @click="cancel"
          >{{ cancelText }}</component>
          <component
            :is="'button'"
            ref="confirmBtn"
            type="button"
            class="awd-dlg__btn awd-dlg__confirm"
            :class="danger ? 'awd-dlg__btn--danger' : 'awd-dlg__btn--primary'"
            @click="confirm"
          >{{ confirmText }}</component>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import { resolveDialogKey } from '@/utils/dialogCore.js'

let uid = 0

export default {
  name: 'AwdDialog',
  props: {
    kind: { type: String, default: 'modal' },
    title: { type: String, default: '' },
    content: { type: String, default: '' },
    showCancel: { type: Boolean, default: true },
    confirmText: { type: String, default: '' },
    cancelText: { type: String, default: '' },
    danger: { type: Boolean, default: false },
    editable: { type: Boolean, default: false },
    placeholderText: { type: String, default: '' },
    itemList: { type: Array, default: () => [] },
    itemColor: { type: String, default: '' },
  },
  emits: ['close'],
  data() {
    uid += 1
    return {
      // 与 uni 一致：editable 时输入框的初值就是 content
      draft: this.editable ? this.content : '',
      titleId: 'awd-dlg-title-' + uid,
      bodyId: 'awd-dlg-body-' + uid,
      closed: false,
      restoreFocusTo: null,
    }
  },
  computed: {
    labelId() {
      if (this.title) return this.titleId
      if (this.kind === 'modal' && this.content && !this.editable) return this.bodyId
      return null
    },
  },
  mounted() {
    try { this.restoreFocusTo = document.activeElement } catch (e) { /* ignore */ }
    window.addEventListener('keydown', this.onKeydown, true)
    document.addEventListener('focusin', this.onFocusIn, true)
    this.$nextTick(() => this.focusInitial())
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this.onKeydown, true)
    document.removeEventListener('focusin', this.onFocusIn, true)
    const prev = this.restoreFocusTo
    // 队列里下一个对话框会自己抢焦点；这里只在它是最后一个时把焦点还回去
    setTimeout(() => {
      try {
        const active = document.activeElement
        const stillInDialog = active && active.closest && active.closest('.awd-dlg-mask')
        if (!stillInDialog && prev && prev.isConnected && typeof prev.focus === 'function') prev.focus()
      } catch (e) { /* ignore */ }
    }, 0)
  },
  methods: {
    el(ref) {
      const r = this.$refs[ref]
      return (r && r.$el) || r || null
    },
    focusInitial() {
      let target = null
      if (this.kind === 'sheet') {
        target = this.$refs.panel && this.$refs.panel.querySelector('.awd-dlg__item')
      } else if (this.editable) {
        target = this.el('input')
      } else if (this.danger && this.showCancel) {
        // 危险动作默认落在「取消」上：回车按的是焦点所在的按钮，不会误触确认
        target = this.el('cancelBtn')
      } else {
        target = this.el('confirmBtn')
      }
      target = target || this.$refs.panel
      try {
        target.focus()
        if (this.editable && target.select) target.select()
      } catch (e) { /* ignore */ }
    },
    focusables() {
      const panel = this.$refs.panel
      if (!panel) return []
      return [...panel.querySelectorAll('button, input')].filter((n) => !n.disabled)
    },
    onKeydown(e) {
      if (this.closed) return
      const panel = this.$refs.panel
      const active = document.activeElement
      const focusOnButton = !!(active && active.tagName === 'BUTTON' && panel && panel.contains(active))
      const action = resolveDialogKey({
        key: e.key,
        isComposing: e.isComposing || e.keyCode === 229,
        kind: this.kind,
        focusOnButton,
      })
      if (!action) return
      e.preventDefault()
      e.stopPropagation()
      if (action === 'cancel') this.cancel()
      else if (action === 'confirm') this.confirm()
      else if (action === 'activate') active.click()
      else if (action === 'trap') this.cycleFocus(e.shiftKey)
    },
    cycleFocus(backward) {
      const list = this.focusables()
      if (!list.length) return
      const i = list.indexOf(document.activeElement)
      const next = backward
        ? list[(i <= 0 ? list.length : i) - 1]
        : list[(i + 1) % list.length]
      try { next.focus() } catch (e) { /* ignore */ }
    },
    onFocusIn(e) {
      // 焦点困在对话框内：点到遮罩外、或别处代码抢焦点，都拉回来
      const panel = this.$refs.panel
      if (!panel || this.closed) return
      if (e.target && panel.contains(e.target)) return
      this.$nextTick(() => this.focusInitial())
    },
    onMaskDown(e) {
      if (e.target !== e.currentTarget) return
      // 点遮罩不抢走焦点；选项表点遮罩 = 取消（与 uni 原生一致），确认框不响应
      e.preventDefault()
      if (this.kind === 'sheet') this.cancel()
    },
    finish(result) {
      if (this.closed) return
      this.closed = true
      this.$emit('close', result)
    },
    confirm() {
      this.finish({ confirm: true, cancel: false, content: this.draft })
    },
    cancel() {
      this.finish(this.kind === 'sheet' ? { tapIndex: -1 } : { confirm: false, cancel: true })
    },
    pick(i) {
      this.finish({ tapIndex: i })
    },
  },
}
</script>

<style>
/* 不 scoped：宿主是独立 app，类名全部带 awd-dlg 前缀，不会与页面冲突。
   颜色一律取 App.vue 的 --awd-* 令牌，深色主题由 html[data-theme='dark'] 那一套自动接管。
   z-index 10000 高于全仓自绘弹窗遮罩（.awd-mask 等 9999），所以在别的弹窗里再弹确认框
   也不会被压在底下；uni-toast 在 App.vue 里是 10001，仍能浮在对话框之上。 */
.awd-dlg-mask {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  box-sizing: border-box;
  background: var(--awd-overlay);
  -webkit-backdrop-filter: blur(3px);
  backdrop-filter: blur(3px);
  font-family: var(--awd-font-sans);
  animation: awd-dlg-fade 160ms ease-out;
}

.awd-dlg {
  position: relative;
  width: 440px;
  max-width: calc(100vw - 32px);
  max-height: calc(100vh - 32px);
  overflow-y: auto;
  box-sizing: border-box;
  padding: 26px 28px 22px;
  border-radius: 16px;
  background: var(--awd-surface);
  color: var(--awd-text);
  box-shadow: 0 24px 64px rgba(35, 32, 26, 0.28);
  text-align: left;
  outline: none;
  animation: awd-dlg-in 160ms cubic-bezier(0.2, 0.8, 0.2, 1);
}
html[data-theme='dark'] .awd-dlg {
  box-shadow: var(--awd-shadow-lg);
}

.awd-dlg__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 10px;
}

.awd-dlg__title {
  font: 600 17px/1.35 var(--awd-font-sans);
  letter-spacing: -0.01em;
  color: var(--awd-text);
  word-break: break-word;
}

.awd-dlg__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--awd-danger-soft);
  color: var(--awd-danger);
  font: 700 13px/1 var(--awd-font-sans);
}

.awd-dlg__body {
  margin: 0;
  font: 400 14px/1.65 var(--awd-font-sans);
  color: var(--awd-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.awd-dlg__input {
  display: block;
  width: 100%;
  height: 38px;
  box-sizing: border-box;
  margin: 4px 0 0;
  padding: 0 12px;
  border: 1px solid var(--awd-border);
  border-radius: 9px;
  background: var(--awd-bg);
  color: var(--awd-text);
  font: 400 14px/38px var(--awd-font-sans);
  outline: none;
}
.awd-dlg__input::placeholder {
  color: var(--awd-text-3);
}
.awd-dlg__input:focus {
  border-color: var(--awd-accent);
}

.awd-dlg__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 24px;
}

.awd-dlg__btn {
  height: 38px;
  min-width: 88px;
  box-sizing: border-box;
  margin: 0;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: 9px;
  font: 500 14px/36px var(--awd-font-sans);
  text-align: center;
  white-space: nowrap;
  cursor: pointer;
  -webkit-appearance: none;
  appearance: none;
}
.awd-dlg__btn--ghost {
  border-color: var(--awd-border);
  background: transparent;
  color: var(--awd-text);
}
.awd-dlg__btn--ghost:hover {
  background: var(--awd-surface-2);
}
.awd-dlg__btn--primary {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
}
.awd-dlg__btn--primary:hover {
  background: var(--awd-accent-hover);
}
.awd-dlg__btn--danger {
  background: var(--awd-danger);
  color: var(--awd-text-on-accent);
}
.awd-dlg__btn--danger:hover {
  filter: brightness(0.94);
}
.awd-dlg__btn:focus-visible,
.awd-dlg__item:focus-visible {
  outline: 2px solid var(--awd-bamboo);
  outline-offset: 2px;
}

/* 选项表：选项是整行按钮，取消单独落在底部 */
.awd-dlg--sheet .awd-dlg__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.awd-dlg--sheet .awd-dlg__actions {
  margin-top: 16px;
}
.awd-dlg__item {
  display: block;
  width: 100%;
  min-height: 38px;
  box-sizing: border-box;
  margin: 0;
  padding: 8px 12px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--awd-text);
  font: 400 14px/1.5 var(--awd-font-sans);
  text-align: left;
  word-break: break-word;
  cursor: pointer;
  -webkit-appearance: none;
  appearance: none;
}
.awd-dlg__item:hover {
  background: var(--awd-surface-2);
}

@keyframes awd-dlg-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes awd-dlg-in {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .awd-dlg-mask,
  .awd-dlg {
    animation: none;
  }
}
</style>

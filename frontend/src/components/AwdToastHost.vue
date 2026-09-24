<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  统一 toast/loading 的单例宿主（dev-board#891）。由 utils/toast.js 在 <body> 下单独
  createApp 挂载（App.vue 的模板会被 uni 的 LayoutComponent 整个替换，挂不进去），
  全应用一个实例；队列在 toast.js 里，这里只负责渲染 + 各条自己的到时收起。

  与 AwdDialogHost 的差别：对话框同一时刻只显示队首那一个，toast 允许多条纵向堆叠
  （state.items 是数组，见 utils/toastCore.js 的 pushToastItem）；loading 是持久态，
  没有 duration，靠 hideLoading 显式收起，所以单独存一份不进 items 数组。
-->
<template>
  <div>
    <div v-if="showMask" class="awd-toast-mask" aria-hidden="true"></div>
    <transition-group tag="div" name="awd-toast" class="awd-toast-stack" role="status" aria-live="polite">
      <div
        v-for="item in state.items"
        :key="item.id"
        class="awd-toast"
        :class="'awd-toast--' + semantic(item.icon)"
      >
        <span class="awd-toast__icon" aria-hidden="true">
          <svg v-if="item.icon === 'success'" viewBox="0 0 20 20" width="16" height="16" fill="none">
            <path d="M4 10.5 8 14.5 16 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <svg v-else-if="item.icon === 'error'" viewBox="0 0 20 20" width="16" height="16" fill="none">
            <path d="M5 5 15 15M15 5 5 15" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <svg v-else-if="item.icon === 'loading'" class="awd-toast__spin" viewBox="0 0 20 20" width="16" height="16" fill="none">
            <circle cx="10" cy="10" r="7.5" stroke="currentColor" stroke-width="2" opacity="0.25"/>
            <path d="M17.5 10a7.5 7.5 0 0 0-7.5-7.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <svg v-else viewBox="0 0 20 20" width="16" height="16" fill="none">
            <circle cx="10" cy="10" r="8" stroke="currentColor" stroke-width="1.6"/>
            <path d="M10 9.2v4.6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            <circle cx="10" cy="6.4" r="1" fill="currentColor"/>
          </svg>
        </span>
        <span class="awd-toast__text">{{ item.title }}</span>
      </div>
      <div v-if="state.loading" key="__loading__" class="awd-toast awd-toast--info awd-toast--loading">
        <span class="awd-toast__icon" aria-hidden="true">
          <svg class="awd-toast__spin" viewBox="0 0 20 20" width="16" height="16" fill="none">
            <circle cx="10" cy="10" r="7.5" stroke="currentColor" stroke-width="2" opacity="0.25"/>
            <path d="M17.5 10a7.5 7.5 0 0 0-7.5-7.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </span>
        <span class="awd-toast__text">{{ state.loading.title }}</span>
      </div>
    </transition-group>
  </div>
</template>

<script>
import { toastSemantic } from '@/utils/toastCore.js'

export default {
  name: 'AwdToastHost',
  props: {
    // utils/toast.js 的 reactive({ items: [], loading: null })
    state: { type: Object, required: true },
  },
  data() {
    return {
      // id -> setTimeout 句柄，纯 JS 侧记账，不进 Vue 响应式（不靠它渲染任何东西）
      timers: {},
    }
  },
  computed: {
    showMask() {
      return this.state.items.some((it) => it.mask) || !!(this.state.loading && this.state.loading.mask)
    },
  },
  watch: {
    // 特意不用 deep：新增/hideToast 整体清空都是重新赋值（见 utils/toast.js 的
    // pushToastItem / hideAll），引用一定变化，浅监听就够；单条到时的 splice 由
    // dismiss() 自己管定时器账目，不需要靠这里的 watcher 兜底。
    'state.items'(items) {
      // 新条目各自挂一个到时自动收起的定时器
      items.forEach((it) => {
        if (this.timers[it.id]) return
        this.timers[it.id] = setTimeout(() => this.dismiss(it.id), it.duration)
      })
      // 条目被整体清空（hideToast）时，清掉孤儿定时器
      const alive = new Set(items.map((it) => it.id))
      Object.keys(this.timers).forEach((idStr) => {
        const id = Number(idStr)
        if (!alive.has(id)) {
          clearTimeout(this.timers[id])
          delete this.timers[id]
        }
      })
    },
  },
  beforeUnmount() {
    Object.values(this.timers).forEach(clearTimeout)
    this.timers = {}
  },
  methods: {
    semantic(icon) {
      return toastSemantic(icon)
    },
    dismiss(id) {
      delete this.timers[id]
      const idx = this.state.items.findIndex((it) => it.id === id)
      if (idx !== -1) this.state.items.splice(idx, 1)
    },
  },
}
</script>

<style>
/* 不 scoped：宿主是独立 app，类名全部带 awd-toast 前缀，不会与页面冲突。
   z-index 10001：要盖过应用内对话框 AwdDialog 的遮罩（10000）——在确认框里触发的
   提示也要看得见（沿用 App.vue 里旧 uni-toast zfix 的同一条理由）。 */
.awd-toast-mask {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: transparent;
}

.awd-toast-stack {
  position: fixed;
  top: 72px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10001;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  pointer-events: none;
  font-family: var(--awd-font-sans);
}

.awd-toast {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
  max-width: 360px;
  box-sizing: border-box;
  padding: 12px 16px;
  border: 1px solid var(--awd-border);
  border-left-width: 4px;
  border-left-style: solid;
  border-radius: 10px;
  background: var(--awd-surface);
  box-shadow: var(--awd-shadow-md);
  pointer-events: auto;
}

.awd-toast--success { border-left-color: var(--awd-accent); }
.awd-toast--success .awd-toast__icon { color: var(--awd-accent); }
.awd-toast--error { border-left-color: var(--awd-danger); }
.awd-toast--error .awd-toast__icon { color: var(--awd-danger); }
.awd-toast--info { border-left-color: var(--awd-info); }
.awd-toast--info .awd-toast__icon { color: var(--awd-info); }

.awd-toast__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
}

.awd-toast__text {
  font: 400 14px/1.5 var(--awd-font-sans);
  color: var(--awd-text);
  word-break: break-word;
  white-space: pre-wrap;
}

.awd-toast__spin {
  animation: awd-toast-spin 0.8s linear infinite;
}
@keyframes awd-toast-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.awd-toast-enter-active,
.awd-toast-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.awd-toast-enter-from,
.awd-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
.awd-toast-move {
  transition: transform 0.18s ease;
}

@media (prefers-reduced-motion: reduce) {
  .awd-toast-enter-active,
  .awd-toast-leave-active,
  .awd-toast-move,
  .awd-toast__spin {
    transition: none;
    animation: none;
  }
}
</style>

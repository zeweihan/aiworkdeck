<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  应用内对话框的单例宿主（dev-board#849）。由 utils/dialog.js 在 <body> 下单独
  createApp 挂载（App.vue 的模板会被 uni 的 LayoutComponent 整个替换，挂不进去），
  全应用一个实例；队列在 dialog.js 里，这里只渲染队首那一个。
-->
<template>
  <AwdDialog
    v-if="current"
    :key="current.id"
    :kind="current.kind"
    :title="current.opts.title"
    :content="current.opts.content || ''"
    :show-cancel="current.opts.showCancel !== false"
    :confirm-text="current.opts.confirmText || ''"
    :cancel-text="current.opts.cancelText || ''"
    :danger="!!current.opts.danger"
    :editable="!!current.opts.editable"
    :placeholder-text="current.opts.placeholderText || ''"
    :item-list="current.opts.itemList || []"
    :item-color="current.opts.itemColor || ''"
    @close="onClose"
  />
</template>

<script>
import AwdDialog from './AwdDialog.vue'

export default {
  name: 'AwdDialogHost',
  components: { AwdDialog },
  props: {
    // utils/dialog.js 的 reactive({ queue: [] })
    state: { type: Object, required: true },
  },
  computed: {
    current() {
      return this.state.queue.length ? this.state.queue[0] : null
    },
  },
  methods: {
    onClose(result) {
      const item = this.state.queue.shift()
      if (item && typeof item.resolve === 'function') item.resolve(result)
    },
  },
}
</script>

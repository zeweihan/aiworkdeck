<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="mention-picker" role="listbox" :aria-label="$t('chat.mentionTitle')">
    <view class="mp-head">
      <text class="mp-title">{{ $t('chat.mentionTitle') }}</text>
      <text class="mp-hint">{{ $t('chat.mentionHint') }}</text>
    </view>
    <scroll-view v-if="matches.length" class="mp-list" scroll-y :scroll-into-view="'mp-item-' + activeIndex">
      <view
        v-for="(item, idx) in matches"
        :id="'mp-item-' + idx"
        :key="item.id"
        class="mp-item"
        :class="{ 'is-active': idx === activeIndex }"
        role="option"
        :aria-selected="idx === activeIndex ? 'true' : 'false'"
        @mousedown.prevent="$emit('select', item)"
        @mousemove="activeIndex = idx"
      >
        <image class="mp-icon" :src="item.isDir ? '/static/folder-closed.png' : '/static/document.png'" mode="aspectFit" />
        <text class="mp-name">{{ item.name }}</text>
        <text v-if="item.dirLabel" class="mp-path">{{ item.dirLabel }}</text>
      </view>
    </scroll-view>
    <view v-else class="mp-empty">
      <text>{{ loading ? $t('files.loadingFileList') : $t('chat.mentionNoMatch') }}</text>
    </view>
  </view>
</template>

<script>
// 输入框 `@` 引用选择器（dev-board#794 K15）。
//
// 刻意做成纯展示 + 过滤：文件清单由 ChatInterface 加载并按 props 传入，选中只 emit
// 出去——插内联标签、同步 contextFiles、文件夹上限都是宿主既有的那一套，这里一行不碰。
//
// 键盘由宿主的 handleInputKeydown 驱动（contenteditable 的 keydown 是透传的，不需要像
// QuickOpenPanel 那样在 document 捕获段拦键）；宿主经 ref 调 moveActive / activeItem。
// 行点击走 @mousedown.prevent 而不是 @tap：点一下先 blur 掉 contenteditable 的话，
// 记着 `@查询` 在哪的那个 Range 就没了，标签会插到文档末尾去。
import { matchProjectFiles } from '@/utils/aiContextFiles.js'

export default {
  name: 'MentionPicker',
  props: {
    files: { type: Array, default: () => [] },
    query: { type: String, default: '' },
    loading: { type: Boolean, default: false },
  },
  emits: ['select'],
  data() {
    return { activeIndex: 0 }
  },
  computed: {
    matches() {
      return matchProjectFiles(this.files, this.query)
    },
  },
  watch: {
    query() {
      this.activeIndex = 0
    },
  },
  methods: {
    moveActive(delta) {
      const n = this.matches.length
      if (!n) return
      this.activeIndex = (this.activeIndex + delta + n) % n
    },
    activeItem() {
      return this.matches[this.activeIndex] || null
    },
  },
}
</script>

<style lang="scss" scoped>
.mention-picker {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(100% + 6px);
  z-index: 60;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(18, 52, 77, 0.14);
  overflow: hidden;
}

.mp-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.mp-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--awd-text-2);
}

.mp-hint {
  font-size: 11px;
  color: var(--awd-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-list {
  max-height: 220px;
}

.mp-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;

  &.is-active {
    background: var(--awd-accent-soft);
  }
}

.mp-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.mp-name {
  font-size: 13px;
  color: var(--awd-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-path {
  font-size: 11px;
  color: var(--awd-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 1;
  margin-left: auto;
}

.mp-empty {
  padding: 14px 12px;
  font-size: 12px;
  color: var(--awd-text-3);
  text-align: center;
}
</style>

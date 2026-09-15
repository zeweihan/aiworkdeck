<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="dlp-mask" @tap="$emit('close')">
    <view ref="card" class="dlp-card" role="dialog" aria-modal="true" tabindex="-1" :aria-label="words.title" :style="position" @tap.stop>
      <view class="dlp-head"><strong>{{ words.title }}</strong><button :aria-label="words.close" @tap="$emit('close')">×</button></view>
      <text v-if="preview.error" class="dlp-note" role="alert">{{ preview.error }}</text>
      <template v-if="preview.target">
        <text class="dlp-name">{{ preview.target.name }}</text>
        <text class="dlp-text">{{ preview.target.text || words.unavailable }}</text>
        <text v-if="preview.target.truncated" class="dlp-note">{{ words.truncated }}</text>
      </template>
      <template v-else-if="preview.targets.length">
        <view v-for="target in preview.targets" :key="target.id" class="dlp-target">
          <text class="dlp-name">{{ target.file?.name || target.file?.fileName || target.fileName || words.document }}</text>
          <text class="dlp-text">{{ target.locator?.quote || words.noExcerpt }}</text>
          <button v-if="Number(target.fileId) !== Number(preview.fileId)" :disabled="preview.opening" @tap="$emit('open', target)">{{ words.aside }}</button>
        </view>
      </template>
      <template v-else>
        <text class="dlp-url">{{ preview.url }}</text>
        <text v-if="!preview.error" class="dlp-note">{{ preview.loading ? words.loading : words.noWebPreview }}</text>
        <button v-if="external && !preview.loading && !preview.error" @tap="$emit('open')">{{ words.aside }}</button>
      </template>
    </view>
  </view>
</template>
<script>
import { hoverCardPosition } from '@/utils/insightPopup.js'
import { parseFileLinkUrl } from '@/utils/evidenceLocator.js'
export default {
  props: { preview: { type: Object, required: true } },
  emits: ['close', 'open'],
  computed: {
    external() { return /^https?:\/\//i.test(this.preview.url) && !parseFileLinkUrl(this.preview.url) },
    words() {
      return String(this.$i18n.locale).startsWith('en')
        ? { title: 'Link preview', close: 'Close', unavailable: 'This reference cannot be previewed. Your reading position is unchanged.', truncated: 'Excerpt truncated', document: 'Linked document', noExcerpt: 'No saved excerpt. Open alongside to read the source.', aside: 'Open alongside', loading: 'Loading…', noWebPreview: 'Web content is not loaded in this preview. Open alongside to read it.' }
        : { title: '链接预览', close: '关闭', unavailable: '暂不能预览此引用，已保留原文位置。', truncated: '摘录已截断', document: '关联文档', noExcerpt: '没有保存的摘录，可分屏查看原文。', aside: '分屏打开', loading: '正在读取…', noWebPreview: '此处仅预览链接地址，可分屏查看网页内容。' }
    },
    position() {
      const w = window.innerWidth, h = window.innerHeight
      const p = hoverCardPosition({ x: this.preview.x ?? w / 2, y: this.preview.y ?? h / 3, width: Math.min(380, w - 16), height: Math.min(360, h - 16), viewportWidth: w, viewportHeight: h })
      return { left: p.left + 'px', top: p.top + 'px' }
    },
  },
  mounted() {
    document.addEventListener('keydown', this.onKey)
    this.$nextTick(() => {
      const card = this.$refs.card?.$el || this.$refs.card
      if (typeof card?.focus === 'function') card.focus({ preventScroll: true })
    })
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey) },
  methods: { onKey(e) { if (e.key === 'Escape') this.$emit('close') } },
}
</script>
<style scoped>
.dlp-mask{position:fixed;inset:0;z-index:10000}.dlp-card{position:fixed;width:380px;max-width:calc(100vw - 16px);max-height:min(360px,calc(100vh - 16px));overflow:auto;box-sizing:border-box;padding:16px;border:1px solid var(--awd-border,#ddd);border-radius:10px;background:var(--awd-surface,#fff);color:var(--awd-text,#222);box-shadow:0 8px 30px #0003;font-size:13px}.dlp-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.dlp-head button{margin:0;padding:0 8px}.dlp-name,.dlp-text,.dlp-url,.dlp-note{display:block;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6;margin:8px 0}.dlp-name{font-weight:600}.dlp-note{opacity:.7}.dlp-target+.dlp-target{border-top:1px solid var(--awd-border,#ddd);padding-top:12px}.dlp-card button{font-size:13px;cursor:pointer}
</style>

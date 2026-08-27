<template>
  <div class="walkthrough-card">
    <div class="card-header" v-if="content && showHeader">
      <svg class="icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path v-for="(d, gi) in ICONS.docText" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span class="title">Walkthrough</span>
    </div>
    <div class="card-body">
       <div :class="{ 'summary-view': isLongContent && !expanded }">
         <MarkdownPreview :content="displayContent || '...'" />
       </div>
       
       <div v-if="isLongContent && !expanded" class="expand-action" @tap="handleOpen">
         <text class="expand-btn">{{ $t('chat.viewFullContent') }}</text>
       </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import { ICONS } from '@/config/icons.js'

const props = defineProps({
  content: { type: String, default: '' },
  isStreaming: { type: Boolean, default: false },
  showHeader: { type: Boolean, default: false }
})

const emit = defineEmits(['open-tab'])

const expanded = ref(false)

// Threshold for "Long Content"
const LENGTH_THRESHOLD = 100

const cleanedContent = computed(() => {
   let text = props.content || ''
   if (text.trim().startsWith('```')) {
       text = text.replace(/^```(xml)?\s*/, '')
       text = text.replace(/```\s*$/, '')
   }
   return text
})

const isLongContent = computed(() => {
  return cleanedContent.value.length > LENGTH_THRESHOLD
})

const displayContent = computed(() => {
  if (isLongContent.value && !expanded.value) {
    // Take first 300 chars or first 3 lines
    const text = cleanedContent.value
    const lines = text.split('\n').slice(0, 5).join('\n')
    if (lines.length < text.length) return lines + '\n...'
    return text
  }
  return cleanedContent.value
})

const handleOpen = () => {
  // Emit event to open in new tab
  emit('open-tab', {
    type: 'walkthrough',
    content: cleanedContent.value,
    fileName: 'Walkthrough',
    id: `walk-${Date.now()}`
  })
}
</script>

<style scoped>
.walkthrough-card {
  padding: 6px 12px 10px; /* 减小内边距 */
}

.card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 10px;
  font-weight: 600;
  color: var(--awd-text-2);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.card-header .icon {
  font-size: 12px;
}

.card-body {
  font-size: 12px; /* 减小字体 */
  line-height: 1.45; /* 紧凑行距 */
  color: var(--awd-text);
}

.summary-view {
  max-height: 150px;
  overflow: hidden;
  position: relative;
  mask-image: linear-gradient(to bottom, black 70%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, black 70%, transparent 100%);
}

.expand-action {
  display: flex;
  justify-content: center;
  margin-top: -16px;
  position: relative;
  z-index: 10;
  padding-bottom: 8px;
}

.expand-btn {
  background: var(--awd-surface-2);
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 11px;
  color: var(--awd-text-2);
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
  transition: background 0.15s;
}
.expand-btn:hover {
  background: var(--awd-surface-3);
}

/* Override MarkdownPreview styles for compact walkthrough */
.walkthrough-card :deep(.markdown-preview) {
    padding: 0 !important;
    background: transparent !important;
    min-height: auto;
    height: auto;
    margin: 0;
    overflow: visible;
}

.walkthrough-card :deep(.markdown-body) {
    font-size: 12px;
    line-height: 1.4;
    margin: 0;
    padding: 0;
}

.walkthrough-card :deep(.markdown-body p) {
    margin: 2px 0 !important;
    padding: 0;
}

.walkthrough-card :deep(.markdown-body ul),
.walkthrough-card :deep(.markdown-body ol) {
    margin: 2px 0 !important;
    padding-left: 16px !important;
}

.walkthrough-card :deep(.markdown-body li) {
    margin: 1px 0 !important;
}

.walkthrough-card :deep(.markdown-body blockquote) {
    margin: 2px 0 !important;
    padding-left: 8px !important;
}

.walkthrough-card :deep(.markdown-body h1),
.walkthrough-card :deep(.markdown-body h2),
.walkthrough-card :deep(.markdown-body h3) {
    margin-top: 4px !important;
    margin-bottom: 2px !important;
    font-size: 13px !important;
}

.walkthrough-card :deep(.markdown-body pre) {
    margin: 2px 0 !important;
    padding: 6px !important;
}

.walkthrough-card :deep(.markdown-body table) {
    margin: 2px 0 !important;
}

.walkthrough-card :deep(.markdown-body th),
.walkthrough-card :deep(.markdown-body td) {
    padding: 3px 6px !important;
}

.icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}
</style>

<template>
  <!-- Inline Variant (Nested) -->
  <div v-if="variant === 'inline'" class="thinking-inline">
     <div class="h-wrap" @click="isExpanded = !isExpanded">
        <!-- Removed Emoji Icon -->
         <span class="label">
            <span v-if="status === 'thinking'">{{ $t('chat.thinkingLive', { seconds: liveSeconds }) }}</span>
            <span v-else-if="duration > 0">{{ $t('chat.thinkingProcessWithDuration', { duration: displayDuration }) }}</span>
            <span v-else>{{ $t('chat.thinkingProcess') }}</span>
         </span>
         <span class="chevron" :class="{ 'open': isExpanded }"></span>
     </div>
     <div v-if="isExpanded" class="content-body">
        <MarkdownPreview :content="content" />
     </div>
  </div>

  <!-- Standard Card Variant (Root) -->
  <div v-else class="thinking-card" :class="{ 'is-done': status === 'done', 'ghost': variant === 'ghost' }">
    <div class="header" @click="toggle">
      <div class="left">
        <div class="status-indicator">
          <span v-if="status === 'thinking'" class="pulse-ring"></span>
          <!-- Removed Emoji Icon -->
        </div>
        <span class="title">
          <span v-if="status === 'thinking'">{{ $t('chat.thinkingLive', { seconds: liveSeconds }) }}</span>
          <span v-else-if="duration > 0">{{ $t('chat.thoughtFor', { duration: displayDuration }) }}</span>
          <span v-else>{{ $t('chat.thinkingProcess') }}</span>
        </span>
      </div>
      <div class="right">
        <span class="chevron-icon" :class="{ 'expanded': isExpanded }"></span>
      </div>
    </div>

    <transition name="expand">
      <div class="body" v-if="isExpanded">
        <div class="content">
          <MarkdownPreview :content="content" />
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import { t } from '@/i18n'

const props = defineProps({
  status: { type: String, default: 'idle' }, // 'idle' | 'thinking' | 'done'
  duration: { type: Number, default: 0 },
  content: { type: String, default: '' },
  variant: { type: String, default: 'card' }, // 'card' | 'inline'
  startTime: { type: Number, default: 0 }
})

const isExpanded = ref(true)
const liveSeconds = ref(0)
let timerInterval = null

// Auto-collapse when done
watch(() => props.status, (newVal) => {
  if (newVal === 'done') {
    isExpanded.value = false
    stopTimer()
  } else if (newVal === 'thinking') {
    isExpanded.value = true
    startTimer()
  }
})

onMounted(() => {
    if (props.status === 'thinking') {
        startTimer()
    }
})

onUnmounted(() => {
    stopTimer()
})

const startTimer = () => {
    stopTimer()
    // Initial calc
    updateTime()
    timerInterval = setInterval(updateTime, 1000)
}

const stopTimer = () => {
    if (timerInterval) clearInterval(timerInterval)
    timerInterval = null
}

const updateTime = () => {
    if (!props.startTime) {
        liveSeconds.value = 0
        return
    }
    const diff = Math.floor((Date.now() - props.startTime) / 1000)
    liveSeconds.value = diff > 0 ? diff : 0
}

const displayDuration = computed(() => {
    // If actively thinking, show live timer
    if (props.status === 'thinking') {
        return t('chat.secondsUnit', { n: liveSeconds.value })
    }
    // Otherwise show the final recorded duration for this segment
    const dur = props.duration || 0
    if (dur <= 0) return ''
    // 10 秒以内保留一位小数，更真实；更长就取整
    return t('chat.secondsUnit', { n: dur < 10 ? dur.toFixed(1) : Math.round(dur) })
})

const toggle = () => {
  isExpanded.value = !isExpanded.value
}
</script>

<style scoped>
/* Standard Card Styles */
.thinking-card {
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #E9ECEF;
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  background: transparent;
  border: none;
  box-shadow: none;
  margin-bottom: 0;
}

.thinking-card.ghost {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  margin: 0 !important; /* 完全无外边距 */
  padding: 0 !important; /* 完全无内边距 */
  height: auto; /* 高度自适应 */
  border-radius: 0 !important; /* 移除圆角 */
  overflow: visible !important; /* 防止内容被裁切 */
}

.thinking-card.is-done {
  border-color: #f3f4f6;
  box-shadow: none;
  background: #f9fafb;
}

.thinking-card.ghost.is-done {
   background: transparent !important;
   border: none !important;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  cursor: pointer;
  background: #fff;
  transition: background 0.2s;
}

.thinking-card.ghost .header {
  background: transparent !important;
  padding: 0; /* 完全无边距 */
  margin: 0;
}

.thinking-card.ghost .left {
  gap: 0; /* 移除间距 */
  padding: 0;
  margin: 0;
}

.thinking-card.ghost .status-indicator {
  display: none !important; /* 完全隐藏状态指示器 */
  width: 0 !important;
  height: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
}

.thinking-card.is-done .header {
  background: transparent;
  padding: 8px 12px;
}

/* Ghost模式下即使is-done也保持无边距 */
.thinking-card.ghost.is-done .header {
  background: transparent !important;
  padding: 0 !important;
  margin: 0 !important;
}

.header:hover {
  background-color: #F8F9FA;
}

.thinking-card.ghost .header:hover {
  background-color: transparent !important;
}

.left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-indicator {
  position: relative;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: -4px; /* Adjust spacing since icon is gone */
}

.pulse-ring {
  position: absolute;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  border: 2px solid #5BD197; /* Mint Green */
  opacity: 0;
  animation: pulse-ring 2s cubic-bezier(0.215, 0.61, 0.355, 1) infinite;
}

@keyframes pulse-ring {
  0% { transform: scale(0.8); opacity: 0.5; }
  100% { transform: scale(2); opacity: 0; }
}

.icon {
  font-size: 14px;
  z-index: 1;
}

.title {
  font-size: 11px; /* Slightly easier to read */
  font-weight: 500;
  color: #6C757D; /* Gray-Medium */
  font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;
}

.thinking-card.is-done .title {
  color: #ADB5BD; /* Lighter gray when done */
  font-weight: 500;
}

.chevron-icon {
  width: 6px;
  height: 6px;
  border-right: 1.5px solid #ADB5BD;
  border-bottom: 1.5px solid #ADB5BD;
  transform: rotate(45deg);
  transition: transform 0.3s;
  display: block;
}

.chevron-icon.expanded {
  transform: rotate(-135deg);
  margin-top: 4px;
}

.body {
  border-top: 1px solid #E9ECEF;
  background: #fff;
}

.content {
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.5;
  color: #495057; /* Gray-Dark */
}

.thinking-card.ghost .content {
    padding: 0; /* 无边距 */
    color: #6C757D; /* Lighter text for ghost */
    margin: 0;
    background: transparent !important;
    text-align: left; /* 左对齐 */
}

.thinking-card.ghost .body {
    border-top: none !important;
    background: transparent !important;
    margin: 0;
    padding: 0;
    height: auto; /* 高度自适应 */
}

/* 覆盖ghost模式下MarkdownPreview组件的样式 */
.thinking-card.ghost :deep(.markdown-preview) {
    padding: 0 !important;
    background: transparent !important;
    min-height: auto;
    height: auto;
    margin: 0;
}

.thinking-card.ghost :deep(.markdown-body) {
    text-align: left;
    margin: 0;
    padding: 0;
    font-size: 13px;
    line-height: 1.5;
    color: #6C757D;
}

.thinking-card.ghost :deep(.markdown-body p) {
    margin: 2px 0 !important; /* 极小段落边距 */
    padding: 0;
}

.thinking-card.ghost :deep(.markdown-body ul),
.thinking-card.ghost :deep(.markdown-body ol) {
    margin: 2px 0 !important;
    padding-left: 16px !important;
}

.thinking-card.ghost :deep(.markdown-body li) {
    margin: 1px 0 !important;
}

.thinking-card.ghost :deep(.markdown-body blockquote) {
    margin: 2px 0 !important;
    padding-left: 8px !important;
    color: #ADB5BD;
    border-left: 2px solid #E9ECEF !important;
}

.thinking-card.ghost :deep(.markdown-body h1),
.thinking-card.ghost :deep(.markdown-body h2),
.thinking-card.ghost :deep(.markdown-body h3) {
    margin-top: 6px !important;
    margin-bottom: 2px !important;
    font-size: 13px !important;
    color: #495057;
}

.thinking-card.ghost :deep(.markdown-body pre) {
    margin: 4px 0 !important;
    padding: 8px !important;
    background: #F8F9FA;
    border: 1px solid #E9ECEF;
    border-radius: 4px;
}

.thinking-card.ghost :deep(.markdown-body table) {
    margin: 2px 0 !important;
}

.thinking-card.ghost :deep(.markdown-body th),
.thinking-card.ghost :deep(.markdown-body td) {
    padding: 4px 8px !important;
}

/* Inline Variant Styles */
.thinking-inline {
    font-size: 12px;
    color: #6C757D;
    margin: 4px 0;
    background: transparent; /* Fix white background issue */
}
.h-wrap {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    user-select: none;
}
.h-wrap:hover {
    color: #495057;
}
.h-wrap .icon {
    font-size: 12px;
}
.h-wrap .icon.spin {
    animation: spin 3s linear infinite;
    display: inline-block;
}
@keyframes spin { 100% { transform: rotate(360deg); } }

.h-wrap .chevron {
    width: 5px;
    height: 5px;
    border-right: 1.5px solid currentColor;
    border-bottom: 1.5px solid currentColor;
    transform: rotate(45deg);
    transition: transform 0.2s;
    margin-left: auto;
}
.h-wrap .chevron.open {
    transform: rotate(-135deg);
}

.content-body {
    margin-top: 4px;
    margin-bottom: 4px;
    padding-left: 0;
    border-left: none;
    background: transparent;
}

/* Inline Variant: Override MarkdownPreview styles for compact display */
.thinking-inline :deep(.markdown-preview) {
    padding: 0 !important;
    background: transparent !important;
    min-height: auto;
    height: auto;
    margin: 0;
    overflow: visible;
}

.thinking-inline :deep(.markdown-body) {
    font-size: 12px;
    line-height: 1.45;
    color: #495057;
    margin: 0;
    padding: 0;
}

.thinking-inline :deep(.markdown-body p) {
    margin: 2px 0 !important;
    padding: 0;
}

.thinking-inline :deep(.markdown-body ul),
.thinking-inline :deep(.markdown-body ol) {
    margin: 2px 0 !important;
    padding-left: 16px !important;
}

.thinking-inline :deep(.markdown-body li) {
    margin: 1px 0 !important;
}

.thinking-inline :deep(.markdown-body blockquote) {
    margin: 2px 0 !important;
    padding-left: 8px !important;
    border-left-width: 2px !important;
    color: #ADB5BD;
}

.thinking-inline :deep(.markdown-body h1),
.thinking-inline :deep(.markdown-body h2),
.thinking-inline :deep(.markdown-body h3) {
    margin-top: 4px !important;
    margin-bottom: 2px !important;
    font-size: 12px !important;
    font-weight: 600;
}

.thinking-inline :deep(.markdown-body pre) {
    margin: 2px 0 !important;
    padding: 6px !important;
    font-size: 11px;
    background: #F8F9FA;
    border: 1px solid #E9ECEF;
}

.thinking-inline :deep(.markdown-body table) {
    margin: 1px 0 !important;
}

.thinking-inline :deep(.markdown-body th),
.thinking-inline :deep(.markdown-body td) {
    padding: 2px 6px !important;
    font-size: 11px;
}

/* Enter/Leave Transitions */
.expand-enter-active,
.expand-leave-active {
  transition: all 0.3s ease-out;
  max-height: 500px;
  opacity: 1;
}

.expand-enter-from,
.expand-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>

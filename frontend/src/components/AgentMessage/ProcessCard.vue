<template>
  <div
    class="process-card"
    :class="{
      'is-expanded': (isExpanded || isHeadless),
      'is-system-actions': isSystemActions,
      'headless-process': isHeadless
    }"
  >
    <!-- Header Area -->
    <div class="process-header" @click="toggle" v-if="!isHeadless">
      <div class="left">
        <div class="header-icon-wrapper">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="header-action-icon">
            <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
          </svg>
        </div>
        <span class="title">{{ processTitle }}</span>
      </div>
      <div class="right">

        <div v-if="hasError" class="status-badge error">出错</div>
        <div v-else-if="isFinished" class="status-badge success">成功</div>
        <div v-else class="status-badge processing">执行中</div>
        <div class="chevron-wrapper" :class="{ 'is-rotated': isExpanded }">
          <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
      </div>
    </div>

    <div class="process-body" v-if="isExpanded || isHeadless">
      <!-- Items List -->
      <div class="items-list" v-if="process.items && process.items.length > 0">
        <div v-for="(item, idx) in process.items" :key="idx" class="process-item">

            <!-- CASE 1: Normal Step or File Attachment -->
            <div v-if="item.type === 'step'" class="step-container">
                <template v-if="detectFile(item.text)">
                    <div class="file-attachment-card">
                        <div class="file-icon-area">
                            <FileTypeIcon :type="getFileExtension(detectFile(item.text))" />
                        </div>
                        <div class="file-details">
                            <div class="file-name">{{ detectFile(item.text) }}</div>
                            <div class="file-meta">{{ item.text.replace(`《${detectFile(item.text)}》`, '').trim() }}</div>
                        </div>
                        <div class="file-actions">
                            <div class="action-btn">
                                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                                    <polyline points="7 10 12 15 17 10"></polyline>
                                    <line x1="12" y1="15" x2="12" y2="3"></line>
                                </svg>
                            </div>
                        </div>
                    </div>
                </template>
                <div v-else class="step-row">
                    <span class="step-dot" :class="{ 'done': item.status !== 'doing' }"></span>
                    <span class="step-text" :class="{ 'is-meta': isSecondaryContent(item.text) }">{{ item.text || '处理中…' }}</span>
                </div>
            </div>

            <!-- CASE 2: Nested Thinking -->
            <div v-else-if="item.type === 'thinking'" class="thinking-row">
                <ThinkingCard
                   variant="inline"
                   :status="item.status"
                   :content="item.content"
                   :duration="item.duration || 0"
                   :start-time="item.startTime"
                />
            </div>

            <!-- CASE 3: Tool Execution（单行：人性化名称 + 状态；原始代号收进 title 提示） -->
            <div v-else-if="item.type === 'tool'" class="tool-row" :title="rawToolName(item.code)">
                <div class="tool-content">
                     <span class="tool-name">{{ formatToolName(item.code) }}</span>
                </div>
                <div class="tool-status">
                     <span v-if="item.status === 'loading'" class="status-loading">正在调用...</span>
                     <span v-else-if="item.status === 'success'" class="status-success">已完成</span>
                     <span v-else class="status-error">出错</span>
                </div>
            </div>

        </div>
      </div>

      <!-- Fallback for legacy 'steps' array -->
      <div class="steps-list" v-else-if="process.steps && process.steps.length > 0">
         <div class="step-item" v-for="(step, idx) in process.steps" :key="idx">
            <span class="step-dot done"></span>
            <span class="step-text">{{ step.text }}</span>
         </div>
      </div>
      <div style="width: 100%; border-bottom: 1px solid #eee; margin: 0 auto; margin-top: 6px;"></div>


    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ThinkingCard from './ThinkingCard.vue'
import FileTypeIcon from '../FileTypeIcon.vue'
import { toolDisplayName, toolRawName } from '@/utils/toolDisplayNames.js'

const props = defineProps({
  process: { type: Object, required: true }
})

const isExpanded = ref(false)

const isSystemActions = computed(() => {
  // '系统操作' 是现名；'System Actions' 兼容历史数据
  return props.process.title === '系统操作' || props.process.title === 'System Actions'
})

const isHeadless = computed(() => {
    return !props.process.title || props.process.title === 'Processing...'
})

const processTitle = computed(() => {
    const t = props.process.title || 'Processing...'
    // 模型偶尔把 process 命名成笼统的「工具执行」——与内部的「执行工具 xxx」行
    // 冗余（用户反馈）。此时直接用第一个工具的人性化名称当标题。
    if (t === '工具执行' || t === 'Processing...' || t === 'Tool Execution') {
        const firstTool = (props.process.items || []).find(it => it.type === 'tool' && it.code)
        if (firstTool) return toolDisplayName(firstTool.code)
    }
    return t
})

const isFinished = computed(() => {
    // 有任何条目仍在进行中（步骤 doing / 工具 loading / 思考 thinking）都算未完成
    if (!props.process.items || props.process.items.length === 0) return false
    return !props.process.items.some(item =>
        item.status === 'doing' || item.status === 'loading' || item.status === 'thinking'
    )
})

const hasError = computed(() => {
    if (!props.process.items) return false
    return props.process.items.some(item => item.status === 'error')
})

const userHasToggled = ref(false)

const toggle = () => {
  userHasToggled.value = true
  isExpanded.value = !isExpanded.value
}

watch(() => props.process.items?.length, (newLen, oldLen) => {
    if (newLen > 0 && (!oldLen || oldLen === 0) && !userHasToggled.value) {
        isExpanded.value = true
    }
}, { immediate: true })

const formatToolName = (code) => {
    if (!code) return '工具调用'
    const name = toolDisplayName(code)
    return name.length > 40 ? name.substring(0, 37) + '...' : name
}

const rawToolName = (code) => toolRawName(code)

const detectFile = (text) => {
    if (!text) return null
    const match = text.match(/《([^》]+)》/)
    return match ? match[1] : null
}

const getFileExtension = (filename) => {
    if (!filename) return 'file'
    const parts = filename.split('.')
    return parts.length > 1 ? parts.pop().toLowerCase() : 'file'
}

const isSecondaryContent = (text) => {
    if (!text) return false
    return text.includes('主要内容') || text.includes('摘要')
}
</script>

<style scoped>
.process-card {
  background: #ffffff;
  border-radius: 12px 12px 0 0;
  /* border-bottom: 1px solid #1A5336; */
  /* box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05), 0 1px 2px rgba(0, 0, 0, 0.1); */
  /* margin-bottom: 12px; */
  overflow: hidden;
  transition: all 0.2s ease;
}

.process-card.headless-process {
    background: transparent;
    box-shadow: none;
    margin-top: -8px;
}

.process-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px;
  cursor: pointer;
  background: #ffffff;
  transition: background 0.15s;
}

.process-header:hover {
  background: #F8F9FA; /* Gray-Pale */
}

.left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon-wrapper {
  color: #1A5336; /* Forest Green */
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  font-size: 13px;
  font-weight: 600;
  color: #1A5336; /* Forest Green */
}

.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 99px;
}

.status-badge.success {
  background: #E6F9F0; /* Mint Lightest */
  color: #1A5336; /* Forest Green */
}

.status-badge.processing {
  background: #E9ECEF; /* Gray-Light */
  color: #6C757D; /* Gray-Medium */
}

.status-badge.error {
  background: #FDEDEC;
  color: #C0392B;
}

.chevron-wrapper {
  color: #ADB5BD;
  transition: transform 0.2s ease;
}

.chevron-wrapper.is-rotated {
  transform: rotate(180deg);
}

.process-body {
  padding: 4px 12px 4px 12px;
}

.process-item {
    margin-bottom: 4px;
}

/* Step Styling */
.step-container {
    width: 100%;
}

.step-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-left: 2px;
}

.step-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #E9ECEF;
  margin-top: 6px;
  flex-shrink: 0;
}

.step-dot.done {
    background: #5BD197; /* Mint Green */
}

.step-text {
  font-size: 12px;
  color: #2C3338; /* Gray-Dark */
  line-height: 1.45;
}

.step-text.is-meta {
    color: #6C757D; /* Gray-Medium */
    font-size: 11px;
}

/* File Attachment Card */
.file-attachment-card {
    display: flex;
    align-items: center;
    background: #F8F9FA; /* Gray-Pale */
    border: 1px solid #E9ECEF; /* Gray-Light */
    border-radius: 8px;
    padding: 7px 10px;
    gap: 10px;
    margin: 3px 0 5px 0;
}

.file-icon-area {
    flex-shrink: 0;
}

.file-details {
    flex: 1;
    min-width: 0;
}

.file-name {
    font-size: 13px;
    font-weight: 600;
    color: #2C3338;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.file-meta {
    font-size: 11px;
    color: #6C757D;
    margin-top: 1px;
}

.file-actions {
    display: flex;
    align-items: center;
}

.action-btn {
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    color: #6C757D;
    cursor: pointer;
    transition: all 0.2s;
}

.action-btn:hover {
    background: #E9ECEF;
    color: #1A5336;
}

/* Tool Row */
.tool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 8px;
  background: #F8F9FA;
  border-radius: 5px;
  margin-left: 0;
}

.tool-content {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}

.tool-name {
    font-size: 12px;
    color: #1A5336;
    font-weight: 500;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.tool-status {
    font-size: 10px;
    font-weight: 500;
    flex-shrink: 0;
}

.status-loading { color: #6C757D; }
.status-success { color: #5BD197; }
.status-error { color: #E74C3C; }

/* Thinking Row */
.thinking-row {
    margin-bottom: 4px;
}
</style>

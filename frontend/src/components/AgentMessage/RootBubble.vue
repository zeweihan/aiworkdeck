<template>
  <div class="root-bubble-wrapper">

    <!-- GHOST STATE: Only Show Thinking if not ready -->
    <div v-if="!isReady && bubble.thinking.status === 'thinking'" class="ghost-thinking">
        <ThinkingCard
           :status="bubble.thinking.status"
           :duration="bubble.thinking.duration"
           :content="bubble.thinking.content"
           :start-time="bubble.thinking.startTime"
           variant="card"
        />
    </div>

    <!-- ACTIVE STATE: Full Card -->
    <div v-else class="active-bubble-wrapper">
        <!-- 1. Thinking Card (Moved Out as Ghost) -->
        <div class="ghost-thinking-wrapper">
             <ThinkingCard
               :status="bubble.thinking.status"
               :duration="bubble.thinking.duration"
               :content="bubble.thinking.content"
               :start-time="bubble.thinking.startTime"
               variant="ghost"
            />
        </div>

        <div v-if="hasContent" class="root-bubble-container">
            <!-- 2. Title -->
            <TitleCard v-if="bubble.title" :title="bubble.title" />

            <!-- 3. Process Stream -->
            <div class="process-stream">
               <ProcessCard
                 v-for="proc in bubble.processes"
                 :key="proc.id"
                 :process="proc"
               />
            </div>

            <!-- 4. Artifacts -->
            <div class="artifacts-stream" v-if="bubble.artifacts.length > 0">
               <div v-for="art in bubble.artifacts" :key="art.id" class="artifact-wrapper">
                  <ArtifactCard
                    :artifact="art"
                    :id="art.id"
                    :type="art.type"
                    :status="art.status"
                    :file-name="art.fileName"
                    :data="art.data"
                    @open-tab="$emit('open-artifact-tab', $event)"
                    @approve="$emit('approve', $event)"
                  />
               </div>
            </div>

            <!-- 5. Main Content (The Answer) -->
            <div v-if="bubble.content" class="main-content">
               <MarkdownPreview :content="bubble.content" />
            </div>

            <!-- 5b. Message Actions（旧 UI 的插入/替换/导出，ChatInterface 重写时丢失后恢复） -->
            <div v-if="bubble.content && !bubble.isStreaming" class="message-actions">
               <span class="msg-act-btn primary" @click="sendAction('insert')">插入当前文档</span>
               <span class="msg-act-btn" @click="sendAction('replace')">替换选区</span>
               <span class="msg-act-btn" @click="sendAction('export')">导出为Word</span>
            </div>

            <!-- 6. Walkthrough (Summary) - Temporarily hidden as per user request -->
            <!-- <WalkthroughCard
              v-if="bubble.walkthrough"
              :content="bubble.walkthrough"
              :is-streaming="bubble.isStreaming"
              :show-header="true"
              @open-tab="$emit('open-artifact-tab', $event)"
            /> -->
        </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import ThinkingCard from './ThinkingCard.vue'
import TitleCard from './TitleCard.vue'
import ProcessCard from './ProcessCard.vue'
import WalkthroughCard from './WalkthroughCard.vue'
import ArtifactCard from '../ArtifactCard.vue'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps({
  bubble: { type: Object, required: true }
})

const emit = defineEmits(['open-artifact-tab', 'approve', 'message-action'])

// 载荷形状对齐 project-overview.handleChatInterfaceAction({ type, msg })，msg 只需 content
function sendAction(type) {
  emit('message-action', { type, msg: { content: props.bubble.content } })
}

const isReady = computed(() => {
    // Show full card if we have a Title OR Processes OR Main Content
    // If only "Thinking", remain in Ghost state (unless it's done thinking and has no other content? No, unlikely)
    return !!(props.bubble.title || props.bubble.processes.length > 0 || props.bubble.content)
})

const hasContent = computed(() => {
    // Check if the bubble has any content to display
    return !!(
        props.bubble.title ||
        props.bubble.processes.length > 0 ||
        props.bubble.artifacts.length > 0 ||
        props.bubble.content
    )
})
</script>

<style scoped>
.root-bubble-wrapper {
    width: 100%;
}

.ghost-thinking {
    max-width: 100%;
    margin-left: 0;
    padding: 0;
}

.active-bubble-wrapper {
    width: 100%;
}

.ghost-thinking-wrapper {
    margin-left: 0;
    padding: 0;
    margin-bottom: 8px;
}

.root-bubble-container {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border: 1px solid rgba(233, 236, 239, 0.8); /* Very subtle border */
  border-radius: 12px; /* rounded-xl */
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05), 0 1px 2px rgba(0, 0, 0, 0.08); /* shadow-sm plus */
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  word-wrap: break-word;
  overflow-wrap: break-word;
  margin-bottom: 32px; /* Breathable spacing between bubbles */
  user-select: text;
  -webkit-user-select: text;
}

/* Connect artifacts visually */
.artifact-wrapper {
  border-bottom: 1px solid #f1f5f9;
}

.artifact-wrapper:last-child {
    border-bottom: none;
}

.main-content {
  padding: 6px 16px; /* Increased padding */
  font-size: 14px;
  line-height: 1.6;
  color: #2C3338; /* Gray-Dark */
}

.message-actions {
  display: flex;
  gap: 8px;
  padding: 8px 16px 12px;
  border-top: 1px solid #f1f5f9;
  margin-top: 6px;
}

.msg-act-btn {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 5px;
  border: 1px solid #E9ECEF;
  color: #6C757D;
  background: #FFFFFF;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}

.msg-act-btn:hover {
  border-color: #5BD197;
  color: #1A5336;
  background: #E6F9F0;
}

.msg-act-btn.primary {
  border-color: rgba(91, 209, 151, 0.5);
  color: #1A5336;
  background: rgba(91, 209, 151, 0.08);
}

.main-content:deep(p) {
  margin: 0 0 12px 0;
}

.main-content:deep(p:last-child) {
  margin-bottom: 0;
}

.main-content:deep(ul), .main-content:deep(ol) {
  /* margin: 8px 0; */
  padding-left: 20px;
}

/* Inline Code Style - Mint Green Tint */
.main-content:deep(code) {
  background: rgba(91, 209, 151, 0.1); /* Subtle Mint Green Tint */
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 85%;
  color: #1A5336; /* Forest Green */
  font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;
}

/* Block Code Style */
.main-content:deep(pre) {
  background: #F8F9FA; /* Gray-Pale */
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  border: 1px solid #E9ECEF;
  font-size: 13px;
  margin: 12px 0;
}

.main-content:deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

/* Override MarkdownPreview default padding in main-content */
.main-content :deep(.markdown-preview) {
  padding: 0 !important;
  background: transparent !important;
  min-height: auto;
  height: auto;
  margin: 0;
  overflow: visible;
}

.main-content :deep(.markdown-body) {
  font-size: 14px;
  line-height: 1.6;
  margin: 0;
  padding: 0;
  color: #2C3338;
}

/* Headings */
.main-content :deep(.markdown-body h1),
.main-content :deep(.markdown-body h2),
.main-content :deep(.markdown-body h3) {
  margin-top: 20px !important;
  margin-bottom: 10px !important;
  font-weight: 600;
  color: #1A5336; /* Forest Green for headings */
}
</style>

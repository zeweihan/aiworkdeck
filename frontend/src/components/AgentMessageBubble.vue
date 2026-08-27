<template>
  <view 
    v-if="hasContent" 
    class="agent-message-bubble" 
    :class="[type, { 'is-streaming': isStreaming }]"
  >
    
    <!-- MODE 1: THINKING (Collapsible Accordion) -->
    <view v-if="type === 'thinking'" class="thinking-accordion">
      <view class="thinking-header" @tap="toggleThought">
        <text class="thinking-icon">{{ thoughtExpanded ? '▼' : '▶' }}</text>
        <text class="thinking-title">Thought Process</text>
      </view>
      <view v-if="thoughtExpanded" class="thinking-body">
        <text class="thought-text">{{ thinkingContent || 'Thinking...' }}</text>
      </view>
    </view>

    <!-- MODE 2: TASK CARD (Plan / Execution) -->
    <view v-else-if="type === 'plan' || type === 'execution'" class="task-card">
       <!-- Header: Title -->
       <view class="task-header" v-if="title || type === 'execution'">
          <text class="task-title-text">{{ title || 'Processing...' }}</text>
          <text v-if="timestamp" class="task-time">{{ timestamp }}</text>
       </view>

       <!-- Body: Summary/Description -->
       <view class="task-body" v-if="finalContent">
          <view class="markdown-body" v-html="renderedContent"></view>
       </view>

       <!-- Footer: Progress Steps -->
       <view class="task-steps-container" v-if="steps && steps.length > 0">
          <view class="task-steps-header" @tap="toggleSteps">
             <text class="section-label">Progress Updates</text>
             <text class="section-toggle">{{ stepsExpanded ? 'Hide' : 'Show' }}</text>
          </view>
          
          <view v-if="stepsExpanded" class="task-steps-list">
             <view v-for="(step, index) in steps" :key="step.id || index" class="task-step-row">
                <view class="step-index">{{ index + 1 }}</view>
                <view class="step-content">
                   <text class="step-title">{{ step.title }}</text>
                </view>
                <view class="step-status-icon">
                   <text v-if="step.status === 'loading'" class="spinner">⟳</text>
                   <text v-else-if="step.status === 'success'" class="check">✓</text>
                   <text v-else class="dot">•</text>
                </view>
             </view>
          </view>
       </view>
    </view>

    <!-- MODE 3: CHAT (Final Answer) -->
    <view v-else class="chat-bubble-content">
       <view class="markdown-body" v-html="renderedContent"></view>
    </view>

    <!-- Streaming Cursor (Global) -->
    <view v-if="isStreaming && !finalContent && type !== 'thinking'" class="streaming-indicator">
       <text class="cursor">|</text>
    </view>

  </view>
</template>

<script>
import MarkdownIt from 'markdown-it'

export default {
  name: 'AgentMessageBubble',
  props: {
    title: { type: String, default: '' },
    type: { type: String, default: 'plan' }, // thinking | plan | execution | chat
    steps: { type: Array, default: () => [] },
    thinkingContent: { type: String, default: '' },
    finalContent: { type: String, default: '' },
    isStreaming: { type: Boolean, default: false },
    showActions: { type: Boolean, default: false },
    timestamp: { type: String, default: '' }
  },
  data() {
    return {
      md: new MarkdownIt({
        html: true,
        breaks: true,
        linkify: true
      }),
      stepsExpanded: true,
      thoughtExpanded: false
    }
  },
  computed: {
    renderedContent() {
      return this.md.render(this.finalContent || '')
    },
    hasContent() {
      // Simplified: Never render empty bubbles
      // Must have at least some actual content
      
      const hasTitle = this.title && this.title.trim() !== '' && this.title !== 'Processing...'
      const hasFinal = this.finalContent && this.finalContent.trim() !== ''
      const hasThinking = this.thinkingContent && this.thinkingContent.trim() !== ''
      const hasSteps = this.steps && this.steps.length > 0
      
      // For any type: Must have at least some content
      if (!hasTitle && !hasFinal && !hasThinking && !hasSteps) {
        return false
      }
      
      return true
    }
  },
  methods: {
    toggleSteps() {
      this.stepsExpanded = !this.stepsExpanded
    },
    toggleThought() {
      this.thoughtExpanded = !this.thoughtExpanded
    }
  }
}
</script>

<style scoped>
.agent-message-bubble {
  width: 100%;
  margin-bottom: 16px;
  position: relative;
  box-sizing: border-box;
}

/* --- THINKING MODE --- */
.thinking-accordion {
  margin-bottom: 8px;
}
.thinking-header {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 0;
  opacity: 0.9;
  transition: opacity 0.2s;
}
.thinking-header:hover {
  opacity: 1;
}
.thinking-icon {
  font-size: 10px;
  color: var(--awd-text-3);
  width: 12px;
  text-align: center;
}
.thinking-title {
  font-size: 12px;
  color: var(--awd-text-3);
  font-weight: 500;
}
.thinking-body {
  margin-left: 18px;
  padding: 6px 12px;
  border-left: 2px solid var(--awd-border);
  margin-top: 2px;
}
.thought-text {
  font-family: 'SF Mono', 'Menlo', monospace;
  font-size: 12px; /* Small, as requested */
  color: var(--awd-text-3);
  font-style: italic;
  white-space: pre-wrap;
  line-height: 1.4;
}

/* --- TASK CARD MODE (Plan/Execution) --- */
.task-card {
  background-color: var(--awd-bg);
  border: 1px solid var(--awd-border); /* Mint Green Border */
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0,0,0,0.02);
  margin-bottom: 12px;
}

/* Header */
.task-header {
  background-color: var(--awd-surface); /* Very light mint */
  padding: 8px 14px;
  border-bottom: 1px solid var(--awd-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.task-title-text {
  font-size: 12px;
  font-weight: 700;
  color: var(--awd-accent-text); /* Forest Green */
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.task-time {
  font-size: 10px;
  color: var(--awd-text-3);
}

/* Body */
.task-body {
  padding: 12px 14px;
  font-size: 13.5px; /* Match input size better */
  color: var(--awd-text);
  line-height: 1.6;
}

/* Steps Container */
.task-steps-container {
  background: var(--awd-surface);
  border-top: 1px solid var(--awd-border);
  padding: 0;
}

.task-steps-header {
  padding: 6px 14px;
  background-color: var(--awd-bg);
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  border-bottom: 1px solid var(--awd-border-subtle);
}
.section-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-3);
  text-transform: uppercase;
}
.section-toggle {
  font-size: 10px;
  color: var(--awd-text-3);
}

.task-steps-list {
  padding: 6px 0;
}

.task-step-row {
  display: flex;
  align-items: flex-start;
  padding: 4px 14px;
  font-size: 12px;
  line-height: 1.4;
  gap: 10px;
}
.step-index {
  color: var(--awd-text-3);
  min-width: 14px;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 11px;
}
.step-content {
  flex: 1;
}
.step-title {
  color: var(--awd-text-2);
}
.step-status-icon {
  width: 14px;
  text-align: center;
}

/* --- CHAT MODE (Final Answer) --- */
.chat-bubble-content {
  background-color: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 12px 12px 12px 2px;
  padding: 12px 16px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.03);
  font-size: 13.5px; /* Adjusted to be compact */
  line-height: 1.6;
  color: var(--awd-text);
}

/* --- GLOBAL UTILS --- */
.spinner {
  display: inline-block;
  animation: spin 1s linear infinite;
  color: var(--awd-mint);
}
.check {
  color: var(--awd-mint);
  font-weight: bold;
}
.dot {
  color: var(--awd-text-on-accent);
}
.cursor {
  display: inline-block;
  animation: blink 1s step-end infinite;
  color: var(--awd-mint);
  font-weight: bold;
  margin-left: 4px;
}

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
@keyframes blink { 50% { opacity: 0; } }

/* MARKDOWN OVERRIDES FOR ALL MODES */
:deep(.markdown-body p) { margin-bottom: 8px; }
:deep(.markdown-body h1) { font-size: 1.3em; margin-top: 12px; margin-bottom: 6px; font-weight: 700; color: var(--awd-text); }
:deep(.markdown-body h2) { font-size: 1.2em; margin-top: 10px; margin-bottom: 6px; font-weight: 600; color: var(--awd-text); }
:deep(.markdown-body h3) { font-size: 1.1em; margin-top: 8px; margin-bottom: 4px; font-weight: 600; color: var(--awd-text); }
:deep(.markdown-body ul), :deep(.markdown-body ol) { 
  margin-left: 18px; margin-bottom: 8px; padding-left: 0;
}
:deep(.markdown-body li) { margin-bottom: 4px; }
:deep(.markdown-body code) {
  background-color: rgba(0,0,0,0.04);
  padding: 1px 4px;
  border-radius: 4px;
  font-family: 'DM Mono', monospace;
  font-size: 0.9em;
  color: var(--awd-danger-text);
}
:deep(.markdown-body pre) {
  background-color: var(--awd-surface);
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  border: 1px solid var(--awd-border);
  margin-bottom: 8px;
}
:deep(.markdown-body pre code) {
  background-color: transparent;
  padding: 0;
  color: inherit;
  font-size: 0.85em;
}
</style>

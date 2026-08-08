<template>
  <div class="chat">
    <div ref="listEl" class="message-list">
      <div v-if="!messages.length" class="empty">
        <p>与 AI 讨论当前文档或项目事务。</p>
        <p v-if="!configured" class="empty-warn">连接未就绪：点击右上角「设置」填入官网 API Key。</p>
        <p v-else-if="!projectId" class="empty-warn">尚未选择项目：在顶部下拉中选一个项目。</p>
      </div>

      <div v-for="(msg, i) in messages" :key="i" class="message" :class="msg.role">
        <template v-if="msg.role === 'user'">
          <div class="bubble user-bubble">{{ msg.text }}</div>
        </template>
        <template v-else>
          <details v-if="msg.thinking" class="thinking">
            <summary>思考过程</summary>
            <div class="thinking-body">{{ msg.thinking }}</div>
          </details>
          <div v-if="msg.tools && msg.tools.length" class="tool-chips">
            <span v-for="(tool, ti) in msg.tools" :key="ti" class="tool-chip" :class="tool.status">
              {{ tool.label }}<span v-if="tool.status === 'running'">…</span><span v-else-if="tool.status === 'failed'">（失败）</span>
            </span>
          </div>
          <div class="bubble assistant-bubble">
            <span>{{ msg.text }}</span>
            <span v-if="msg.streaming" class="cursor"></span>
          </div>
          <div v-if="msg.error" class="msg-error">{{ msg.error }}</div>
        </template>
      </div>
    </div>

    <footer class="composer">
      <label class="doc-toggle">
        <input v-model="includeDocument" type="checkbox"/>
        <span>随消息附带当前文档正文</span>
      </label>
      <div class="input-row">
        <textarea
          v-model="input"
          rows="2"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div class="btn-col">
          <button v-if="streaming" class="btn stop" @click="stop">停止</button>
          <button v-else class="btn send" :disabled="!canSend" @click="send">发送</button>
          <button class="btn reset" title="开始新对话" :disabled="streaming" @click="newConversation">新对话</button>
        </div>
      </div>
      <p v-if="reconnecting" class="banner conn">连接中断，正在自动重连……</p>
      <p v-else-if="notice" class="banner conn">{{ notice }}</p>
      <p v-if="banner" class="banner">{{ banner }}</p>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  messages, input, streaming, reconnecting, banner, notice, includeDocument, scrollSignal,
  activateSession, send as sendMessage, stop as stopRun, newConversation
} from '../lib/chatSession.js'

/**
 * 纯渲染与交互层：会话态、SSE 连接与 office_command 执行链都在 lib/chatSession.js。
 * 组件卸载（切到设置视图）不再关连接、不再丢消息——那正是「切一次页面就收不到回复」的根因。
 */
const props = defineProps({
  settings: { type: Object, required: true },
  projectId: { type: String, default: '' },
  configured: { type: Boolean, default: false }
})
const emit = defineEmits(['need-settings'])

const listEl = ref(null)

const canSend = computed(() =>
  props.configured && props.projectId && input.value.trim().length > 0 && !streaming.value)

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

// 连接配置或项目变化时重新激活会话（同一身份是空操作，不打断进行中的对话）
watch(
  () => [props.settings.serverUrl, props.settings.token, props.projectId],
  () => { activateSession({ settings: props.settings, projectId: props.projectId }) },
  { immediate: true }
)

// store 不碰 DOM：由它发出滚动信号，视图负责滚
watch(scrollSignal, scrollToBottom)

// 重新挂载（从设置视图切回来）时恢复到最新一条消息
onMounted(scrollToBottom)

async function send() {
  const result = await sendMessage()
  if (result && result.needSettings) emit('need-settings')
}

function stop() {
  stopRun()
}
</script>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  min-height: 0;
}

.empty {
  color: var(--awd-text-secondary);
  text-align: center;
  margin-top: 32px;
  font-size: 12px;
}

.empty-warn { color: var(--awd-danger); }

.message { margin-bottom: 12px; }

.message.user { text-align: right; }

.bubble {
  display: inline-block;
  max-width: 92%;
  padding: 8px 11px;
  border-radius: 8px;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-bubble {
  background: var(--awd-user-bubble);
  border: 1px solid var(--awd-border);
}

.assistant-bubble {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
}

.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 6px;
}

.tool-chip {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
}

.tool-chip.done { color: var(--awd-text-secondary); }
.tool-chip.failed { color: var(--awd-danger); border-color: var(--awd-danger); }

.thinking {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--awd-text-secondary);
}

.thinking summary { cursor: pointer; user-select: none; }

.thinking-body {
  white-space: pre-wrap;
  word-break: break-word;
  border-left: 2px solid var(--awd-border);
  padding-left: 8px;
  margin-top: 4px;
}

.msg-error {
  margin-top: 4px;
  color: var(--awd-danger);
  font-size: 12px;
}

.cursor {
  display: inline-block;
  width: 7px;
  height: 13px;
  margin-left: 2px;
  background: var(--awd-primary);
  vertical-align: text-bottom;
  animation: blink 1s step-start infinite;
}

@keyframes blink { 50% { opacity: 0; } }

.composer {
  border-top: 1px solid var(--awd-border);
  background: var(--awd-surface);
  padding: 8px 12px 10px;
  flex-shrink: 0;
}

.doc-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--awd-text-secondary);
  font-size: 12px;
  margin-bottom: 6px;
  user-select: none;
}

.input-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

textarea {
  flex: 1;
  padding: 7px 9px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  resize: none;
}

textarea:focus {
  outline: none;
  border-color: var(--awd-primary);
}

.btn-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: flex-end;
}

.btn {
  padding: 5px 12px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  white-space: nowrap;
}

.btn.send {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.btn.send:hover:not(:disabled) { background: var(--awd-primary-hover); }
.btn.send:disabled { opacity: 0.5; cursor: default; }

.btn.stop {
  color: var(--awd-danger);
  border-color: var(--awd-danger);
}

.btn.reset {
  color: var(--awd-text-secondary);
  font-size: 12px;
}

.btn.reset:disabled { opacity: 0.5; cursor: default; }

.banner {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--awd-danger);
}

.banner.conn { color: var(--awd-text-secondary); }
</style>

<template>
  <div class="chat">
    <div ref="listEl" class="message-list">
      <div v-if="!messages.length" class="empty">
        <p>与 AI 讨论当前文档或项目事务。</p>
        <p v-if="!configured" class="empty-warn">连接未就绪：请点击右上角「设置」配置后端地址与设备令牌。</p>
        <p v-else-if="!projectId" class="empty-warn">请先在顶部选择一个项目。</p>
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
      <p v-if="banner" class="banner">{{ banner }}</p>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { postChat, postCancel } from '../lib/api.js'
import { createSseConnection, createTagStreamParser } from '../lib/sse.js'
import { readActiveDocument } from '../lib/wordDoc.js'

const props = defineProps({
  settings: { type: Object, required: true },
  projectId: { type: String, default: '' },
  configured: { type: Boolean, default: false }
})
const emit = defineEmits(['need-settings'])

const messages = ref([])
const input = ref('')
const streaming = ref(false)
const includeDocument = ref(true)
const banner = ref('')
const listEl = ref(null)

// conversationId 客户端生成（conv-<毫秒> 格式，与主前端一致）；插件会话独立
let conversationId = `conv-${Date.now()}`
let connection = null
let parser = null
let currentAssistant = null

const canSend = computed(() =>
  props.configured && props.projectId && input.value.trim().length > 0 && !streaming.value)

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function finishStreaming() {
  if (currentAssistant) currentAssistant.streaming = false
  streaming.value = false
}

function handleEvent(evt, dataStr) {
  if (evt === 'text_delta') {
    let content = dataStr
    try { content = JSON.parse(dataStr).content || '' } catch (e) { /* 按原文处理 */ }
    if (parser) parser.feed(content)
    scrollToBottom()
  } else if (evt === 'bubble_end') {
    if (parser) parser.flush()
    finishStreaming()
  } else if (evt === 'error') {
    let msg = '执行出错'
    try { msg = JSON.parse(dataStr).message || msg } catch (e) { /* ignore */ }
    if (currentAssistant) currentAssistant.error = msg
    finishStreaming()
  } else if (evt === 'cancelled') {
    if (currentAssistant && !currentAssistant.text) currentAssistant.text = '（已停止）'
    finishStreaming()
  }
  // connected/heartbeat/client_action/plan_update 等其余事件：MVP 先忽略
}

async function ensureConnection() {
  if (connection) return
  const conn = createSseConnection({
    baseUrl: props.settings.serverUrl,
    token: props.settings.token,
    conversationId,
    onEvent: handleEvent,
    onClose: () => {
      if (connection === conn) connection = null
      // 连接断开时不静默卡死输入框
      if (streaming.value) finishStreaming()
    }
  })
  connection = conn
  try {
    await conn.ready
  } catch (e) {
    if (connection === conn) connection = null
    throw e
  }
}

async function send() {
  banner.value = ''
  if (!props.configured) { emit('need-settings'); return }
  if (!props.projectId) { banner.value = '请先在顶部选择一个项目'; return }
  const prompt = input.value.trim()
  if (!prompt || streaming.value) return

  input.value = ''
  messages.value.push({ role: 'user', text: prompt })

  const assistant = reactive({ role: 'assistant', text: '', thinking: '', streaming: true, error: '' })
  messages.value.push(assistant)
  currentAssistant = assistant
  parser = createTagStreamParser({
    onMainText: (t) => { assistant.text += t },
    onThinkingText: (t) => { assistant.thinking += t }
  })
  streaming.value = true
  scrollToBottom()

  try {
    await ensureConnection()

    // 当前文档正文以内联形式随请求上送（activeContext.inlineContent）
    let activeContext = null
    if (includeDocument.value) {
      activeContext = await readActiveDocument()
      if (!activeContext) banner.value = '未能读取文档正文，本条消息不附带文档内容'
    }

    await postChat(props.settings, {
      projectId: parseInt(props.projectId, 10),
      conversationId,
      message: prompt,
      mode: 'AGENT',
      activeContext
    })
  } catch (e) {
    assistant.error = e.message || '消息发送失败'
    finishStreaming()
  }
}

async function stop() {
  await postCancel(props.settings, conversationId)
  if (connection) { connection.close(); connection = null }
  if (currentAssistant && !currentAssistant.text) currentAssistant.text = '（已停止）'
  finishStreaming()
}

function newConversation() {
  if (connection) { connection.close(); connection = null }
  conversationId = `conv-${Date.now()}`
  messages.value = []
  currentAssistant = null
  parser = null
  banner.value = ''
}

onBeforeUnmount(() => {
  if (connection) connection.close()
})
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
</style>

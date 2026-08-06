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
      <p v-if="banner" class="banner">{{ banner }}</p>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { postChat, postCancel, postOfficeResult, createConversation } from '../lib/api.js'
import { createSseConnection, createTagStreamParser } from '../lib/sse.js'
import { readActiveDocument, detectHost } from '../lib/wordDoc.js'
import { executeOfficeCommand, commandDisplayName } from '../lib/officeExecutor.js'

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

// 会话 ID 优先由服务端签发（POST /api/agent/conversations，契约与后端并行分支约定）；
// 端点不存在或失败时静默回退客户端生成的 conv-<毫秒>（与主前端一致）。插件会话独立。
let conversationId = null
let connection = null
let parser = null
let currentAssistant = null
// SSE 是否发生过断线重连：只有重连后的 run_state 才用于兜底解锁
// （首连的 run_state 在 send 已置 streaming 之后到达，不能当终态看）
let everReconnected = false
const reconnecting = ref(false)

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
  } else if (evt === 'client_action') {
    handleClientAction(dataStr)
  } else if (evt === 'run_state') {
    // 建连时后端推送当前运行状态。仅在断线重连后用作兜底：
    // 断线期间漏掉了 bubble_end/error 等终态事件时，靠它解锁输入框
    if (everReconnected && streaming.value) {
      let status = null
      try { status = JSON.parse(dataStr).status } catch (e) { /* ignore */ }
      const stillRunning = status === 'RUNNING' || status === 'PAUSED' || status === 'AWAITING_APPROVAL'
      if (!stillRunning) {
        if (parser) parser.flush()
        finishStreaming()
      }
    }
  }
  // connected/heartbeat/plan_update 等其余事件：先忽略
}

/**
 * office_command 执行链（Phase C 工具桥）：
 * 后端 OfficeBridgeService 下发 {tool:'office_command', requestId, command, args}
 * → Office.js 执行 → POST /api/agent/office/result 回传。
 * 其余 client_action（editor_command 等 LOWA 契约）与本插件无关，忽略。
 */
async function handleClientAction(dataStr) {
  let action = null
  try { action = JSON.parse(dataStr) } catch (e) { return }
  if (!action || action.tool !== 'office_command' || !action.requestId) return

  const chip = reactive({ label: commandDisplayName(action.command), status: 'running' })
  if (currentAssistant) {
    if (!currentAssistant.tools) currentAssistant.tools = []
    currentAssistant.tools.push(chip)
    scrollToBottom()
  }

  const result = await executeOfficeCommand(action.command, action.args)
  chip.status = result.ok ? 'done' : 'failed'
  await postOfficeResult(props.settings, {
    requestId: action.requestId,
    ok: result.ok,
    data: result.ok ? result.data : null,
    error: result.ok ? null : result.error
  })
}

async function ensureConnection() {
  if (connection) return
  const conn = createSseConnection({
    baseUrl: props.settings.serverUrl,
    token: props.settings.token,
    conversationId,
    onEvent: handleEvent,
    onStatus: (status) => {
      if (connection !== conn) return
      if (status === 'reconnecting') {
        everReconnected = true
        reconnecting.value = true
      } else if (status === 'connected') {
        reconnecting.value = false
      }
    },
    onClose: () => {
      if (connection === conn) connection = null
      reconnecting.value = false
      // 连接彻底关闭时不静默卡死输入框（断线重连由 sse.js 内部处理，不走这里）
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

  const assistant = reactive({ role: 'assistant', text: '', thinking: '', streaming: true, error: '', tools: [] })
  messages.value.push(assistant)
  currentAssistant = assistant
  parser = createTagStreamParser({
    onMainText: (t) => { assistant.text += t },
    onThinkingText: (t) => { assistant.thinking += t }
  })
  streaming.value = true
  scrollToBottom()

  try {
    // 首条消息前先请求服务端签发会话 ID；旧后端无该端点时静默回退客户端生成
    if (!conversationId) {
      conversationId = (await createConversation(props.settings, parseInt(props.projectId, 10)))
        || `conv-${Date.now()}`
    }
    await ensureConnection()

    // 当前文档内容以内联形式随请求上送（activeContext.inlineContent）
    let activeContext = null
    if (includeDocument.value) {
      activeContext = await readActiveDocument()
      if (!activeContext) banner.value = '未能读取文档内容，本条消息不附带文档内容'
    }

    await postChat(props.settings, {
      projectId: parseInt(props.projectId, 10),
      conversationId,
      message: prompt,
      mode: 'AGENT',
      activeContext,
      // 声明客户端能力（Phase C）：后端据此让本会话只见 office_* 工具、隐藏 doc_*；
      // officeHost 再按宿主细分（word/excel/powerpoint），点名对应工具面
      clientCapability: 'office',
      officeHost: detectHost() || 'word'
    })
  } catch (e) {
    assistant.error = e.message || '消息发送失败'
    finishStreaming()
  }
}

async function stop() {
  if (conversationId) await postCancel(props.settings, conversationId)
  if (connection) { connection.close(); connection = null }
  if (currentAssistant && !currentAssistant.text) currentAssistant.text = '（已停止）'
  finishStreaming()
}

function newConversation() {
  if (connection) { connection.close(); connection = null }
  conversationId = null
  messages.value = []
  currentAssistant = null
  parser = null
  banner.value = ''
  reconnecting.value = false
  everReconnected = false
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

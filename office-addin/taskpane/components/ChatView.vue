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
            <span
              v-for="(tool, ti) in msg.tools"
              :key="ti"
              class="tool-chip"
              :class="tool.status"
              :title="tool.error || ''"
            >
              <span v-if="tool.status === 'running'" class="chip-spinner"></span>
              {{ tool.label }}<span v-if="tool.status === 'failed'">（失败）</span>
            </span>
          </div>
          <!-- 失败详情不能只回传给模型：用户要看得到哪一步、为什么失败（dev-board#147/#149） -->
          <div v-for="(tool, ti) in failedTools(msg)" :key="'e' + ti" class="tool-error">
            {{ tool.label }}：{{ tool.error }}
          </div>
          <!-- 首 token 前不再是「空气泡+光标」：给一句状态，别让人以为卡死了 -->
          <div v-if="msg.streaming && !msg.text" class="bubble assistant-bubble pending-bubble">
            {{ msg.tools && msg.tools.length ? '正在操作文档…' : '正在思考…' }}
          </div>
          <div v-else class="bubble assistant-bubble">
            <span>{{ msg.text }}</span>
            <span v-if="msg.streaming" class="cursor"></span>
          </div>
          <!-- 显式完成态：光标消失太隐晦，最新一轮收尾后明示（dev-board#147） -->
          <div v-if="msg.done && !msg.streaming && i === messages.length - 1" class="done-line">
            已完成<template v-if="msg.durationMs"> · {{ Math.round(msg.durationMs / 1000) }} 秒</template>
          </div>
          <!-- 反问选项：正文已在上面的气泡里（解析器把 <question> 正文并进主文本），
               选项刻意不进正文以免显示两遍，所以必须在这里渲染成按钮，否则用户
               看得到问题、看不到备选项。窄栏纵向堆叠；选项之间不分主次——它们是
               互斥的平级候选，给一个主色按钮会诱导用户点第一个。
               只有最末一条未作答的可点（sealStaleQuestions 已封掉旧的）。 -->
          <div v-if="msg.question && msg.question.options.length" class="question-options">
            <button
              v-for="(opt, oi) in msg.question.options"
              :key="oi"
              class="option-btn"
              :disabled="msg.question.answered || streaming"
              @click="answerQuestion(opt)"
            >{{ opt }}</button>
            <span v-if="msg.question.answered" class="option-answered">已回答</span>
          </div>
          <div v-if="msg.error" class="msg-error">{{ msg.error }}</div>
        </template>
      </div>
    </div>

    <!-- 历史会话面板：窄窗格用覆盖层而不是常驻侧栏 -->
    <div v-if="historyOpen" class="overlay" @click.self="historyOpen = false">
      <div class="panel">
        <div class="panel-head">
          <span>历史对话</span>
          <button class="panel-close" @click="historyOpen = false">x</button>
        </div>
        <div v-if="historyLoading" class="panel-empty">加载中…</div>
        <div v-else-if="!conversations.length" class="panel-empty">本项目还没有历史对话</div>
        <button
          v-for="c in conversations"
          :key="c.conversationId"
          class="conv-item"
          @click="pickConversation(c)"
        >
          <span class="conv-title">{{ c.title || c.lastMessage || '（未命名对话）' }}</span>
          <span class="conv-meta">{{ formatTime(c.updatedAt) }}<template v-if="c.runStatus === 'RUNNING'"> · 进行中</template></span>
        </button>
      </div>
    </div>

    <!-- 技能面板：勾选的 skillIds 随每条消息上送（后端与触发词并集激活） -->
    <div v-if="skillsOpen" class="overlay" @click.self="skillsOpen = false">
      <div class="panel">
        <div class="panel-head">
          <span>技能</span>
          <button class="panel-close" @click="skillsOpen = false">x</button>
        </div>
        <div v-if="!skillList.length" class="panel-empty">服务器上还没有可用技能</div>
        <label v-for="s in skillList" :key="s.id" class="skill-item">
          <input
            type="checkbox"
            :checked="selectedSkillIds.includes(s.id)"
            @change="toggleSkill(s.id)"
          />
          <span class="skill-name">{{ s.name || s.id }}</span>
          <span v-if="s.description" class="skill-desc">{{ s.description }}</span>
        </label>
      </div>
    </div>

    <footer class="composer">
      <!-- 上下文与能力一排 pill：取代「随消息附带当前文档正文」检查框——
           勾不勾的真实差别（附不附全文）用文档名 pill 的实/虚态表达（dev-board#150） -->
      <div class="context-row">
        <button
          class="pill doc-pill"
          :class="{ off: !includeDocument }"
          :title="includeDocument ? '每条消息附带当前文档正文（点击改为不附带）' : '当前不附带文档正文，AI 仍可用工具按需读取（点击恢复附带）'"
          @click="includeDocument = !includeDocument"
        >{{ docLabel }}</button>
        <button
          v-if="skillList.length"
          class="pill"
          :class="{ active: selectedSkillIds.length }"
          title="选择随对话生效的技能"
          @click="skillsOpen = true"
        >技能<template v-if="selectedSkillIds.length"> {{ selectedSkillIds.length }}</template></button>
        <select
          v-if="modelCatalog && modelCatalog.models.length"
          class="model-select"
          :value="selectedModel"
          title="本轮使用的模型"
          @change="chooseModel($event.target.value)"
        >
          <option value="">默认模型</option>
          <option v-for="m in modelCatalog.models" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
        <span class="context-spacer"></span>
        <button class="pill" title="查看本项目的历史对话" @click="openHistory">历史</button>
        <button class="pill" title="开始新对话" :disabled="streaming" @click="newConversation">新对话</button>
      </div>
      <div class="input-row">
        <textarea
          v-model="input"
          rows="2"
          placeholder="输入消息，Enter 发送，/ 选技能"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button v-if="streaming" class="btn stop" @click="stop">停止</button>
        <button v-else class="btn send" :disabled="!canSend" @click="send">发送</button>
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
  activateSession, send as sendMessage, stop as stopRun, newConversation,
  answerQuestion, modelCatalog, selectedModel, chooseModel, skillList, selectedSkillIds,
  toggleSkill, loadConversationList, switchConversation
} from '../lib/chatSession.js'
import { readDocumentMeta } from '../lib/wordDoc.js'

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

// ==================== 历史 / 技能 / 上下文 pill ====================

const historyOpen = ref(false)
const historyLoading = ref(false)
const conversations = ref([])
const skillsOpen = ref(false)
const docMeta = ref(null)

onMounted(() => { docMeta.value = readDocumentMeta() })

const docLabel = computed(() => {
  const name = docMeta.value && docMeta.value.name ? docMeta.value.name : '当前文档'
  return (includeDocument.value ? '' : '不附带 ') + name
})

async function openHistory() {
  historyOpen.value = true
  historyLoading.value = true
  try {
    conversations.value = await loadConversationList()
  } finally {
    historyLoading.value = false
  }
}

async function pickConversation(c) {
  historyOpen.value = false
  if (streaming.value) return
  await switchConversation(c.conversationId)
}

function failedTools(msg) {
  return (msg.tools || []).filter((t) => t.status === 'failed' && t.error)
}

function formatTime(v) {
  if (!v) return ''
  try {
    const d = new Date(v)
    if (Number.isNaN(d.getTime())) return ''
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch (e) {
    return ''
  }
}

// 输入框敲 "/"（且只有这一个字符）时唤起技能面板，与 Claude 类插件的习惯一致
watch(input, (v) => {
  if (v === '/' && skillList.value.length) {
    skillsOpen.value = true
    input.value = ''
  }
})
</script>

<style scoped>
.chat {
  position: relative; /* 历史/技能覆盖层的定位锚 */
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

/* 三态要一眼可辨（dev-board#147）：running 有转圈、done 变主色勾边、failed 红 */
.tool-chip.running { color: var(--awd-text); border-color: var(--awd-primary); }
.tool-chip.done { color: var(--awd-primary); border-color: var(--awd-primary); background: var(--awd-user-bubble); }
.tool-chip.failed { color: var(--awd-danger); border-color: var(--awd-danger); }

.chip-spinner {
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 3px;
  border: 1.5px solid var(--awd-border);
  border-top-color: var(--awd-primary);
  border-radius: 50%;
  vertical-align: -1px;
  animation: spin 0.9s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.tool-error {
  color: var(--awd-danger);
  font-size: 11px;
  margin: 2px 0 4px;
  word-break: break-word;
}

.pending-bubble { color: var(--awd-text-secondary); animation: pulse 1.6s ease-in-out infinite; }

@keyframes pulse { 50% { opacity: 0.55; } }

.done-line {
  margin-top: 3px;
  font-size: 11px;
  color: var(--awd-text-secondary);
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

/* 反问选项：任务窗格很窄，纵向堆叠而不是横排挤成两行 */
.question-options {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin-top: 6px;
}

.option-btn {
  max-width: 92%;
  padding: 6px 11px;
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.option-btn:hover:not(:disabled) {
  border-color: var(--awd-primary);
  color: var(--awd-primary);
}

.option-btn:disabled {
  color: var(--awd-text-secondary);
  cursor: default;
}

.option-answered {
  color: var(--awd-text-secondary);
  font-size: 11px;
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

.context-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
}

.context-spacer { flex: 1; min-width: 0; }

.pill {
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.pill:hover:not(:disabled) { border-color: var(--awd-primary); color: var(--awd-primary); }
.pill:disabled { opacity: 0.45; cursor: not-allowed; }
.pill.active { border-color: var(--awd-primary); color: var(--awd-primary); background: var(--awd-user-bubble); }

/* 文档 pill：实线=附带正文，虚线灰=不附带（取代旧检查框的勾选语义） */
.doc-pill {
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  border-color: var(--awd-primary);
  color: var(--awd-primary);
}

.doc-pill.off {
  border-style: dashed;
  border-color: var(--awd-border);
  color: var(--awd-text-secondary);
}

.model-select {
  max-width: 40%;
  padding: 2px 4px;
  border: 1px solid var(--awd-border);
  border-radius: 999px;
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
}

.input-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

/* 覆盖层面板（历史/技能）：窄窗格从底部铺开 */
.overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.28);
  display: flex;
  align-items: flex-end;
  z-index: 20;
}

.panel {
  width: 100%;
  max-height: 65%;
  overflow-y: auto;
  background: var(--awd-surface);
  border-top: 1px solid var(--awd-border);
  border-radius: 10px 10px 0 0;
  padding: 8px 10px 12px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
}

.panel-close {
  border: none;
  background: none;
  color: var(--awd-text-secondary);
  font-family: monospace;
  cursor: pointer;
  padding: 0 4px;
}

.panel-empty {
  color: var(--awd-text-secondary);
  font-size: 12px;
  text-align: center;
  padding: 14px 0;
}

.conv-item {
  display: block;
  width: 100%;
  text-align: left;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-surface);
  padding: 6px 9px;
  margin-bottom: 5px;
  cursor: pointer;
}

.conv-item:hover { border-color: var(--awd-primary); }

.conv-title {
  display: block;
  font-size: 12px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-meta { font-size: 11px; color: var(--awd-text-secondary); }

.skill-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 5px 2px;
  font-size: 12px;
  cursor: pointer;
}

.skill-name { flex-shrink: 0; }

.skill-desc {
  color: var(--awd-text-secondary);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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


.banner {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--awd-danger);
}

.banner.conn { color: var(--awd-text-secondary); }
</style>

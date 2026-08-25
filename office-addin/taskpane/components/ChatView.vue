<template>
  <div class="chat">
    <div ref="listEl" class="message-list">
      <div v-if="!messages.length" class="empty">
        <p>{{ t('emptyHint') }}</p>
        <p v-if="!configured" class="empty-warn">{{ t('connectionNotReady') }}</p>
        <p v-else-if="!projectId" class="empty-warn">{{ t('noProjectSelected') }}</p>
        <div v-else class="quick-prompts">
          <button v-for="q in quickPrompts" :key="q.label" class="quick-btn" @click="sendQuick(q.text)">{{ q.label }}</button>
        </div>
      </div>

      <div v-for="(msg, i) in messages" :key="i" class="message" :class="msg.role">
        <template v-if="msg.role === 'user'">
          <div class="bubble user-bubble">{{ msg.text }}</div>
        </template>
        <template v-else>
          <details v-if="msg.thinking" class="thinking">
            <summary>{{ t('thinkingProcess') }}</summary>
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
              {{ tool.label }}<span v-if="tool.status === 'failed'">{{ t('toolFailedSuffix') }}</span>
            </span>
          </div>
          <!-- 失败详情不能只回传给模型：用户要看得到哪一步、为什么失败（dev-board#147/#149） -->
          <div v-for="(tool, ti) in failedTools(msg)" :key="'e' + ti" class="tool-error">
            {{ tool.label }}：{{ tool.error }}
          </div>
          <!-- 计划/交付物卡（<artifact> 整块）：此前直接丢弃，审批型计划在插件端看不到本体 -->
          <div v-if="msg.artifact" class="artifact-card">
            <div class="artifact-head">{{ t('planLabel') }}</div>
            <div class="artifact-body">{{ msg.artifact }}</div>
            <div v-if="i === messages.length - 1 && !streaming" class="artifact-actions">
              <button class="option-btn" @click="sendQuick(t('proceedWithPlan'))">{{ t('proceedWithPlan') }}</button>
              <button class="option-btn" @click="focusInput">{{ t('proposeChanges') }}</button>
            </div>
          </div>
          <!-- 首 token 前不再是「空气泡+光标」：给一句状态，别让人以为卡死了 -->
          <div v-if="msg.streaming && !msg.text" class="bubble assistant-bubble pending-bubble">
            {{ msg.tools && msg.tools.length ? t('workingOnDocument') : t('thinkingEllipsis') }}
          </div>
          <div v-else class="bubble assistant-bubble">
            <span>{{ msg.text }}</span>
            <span v-if="msg.streaming" class="cursor"></span>
          </div>
          <!-- 引用定位：回答里引用的原文片段可点击，在文档中选中滚动到位（仅 Word 宿主） -->
          <div v-if="citations(msg).length" class="cite-row">
            <button
              v-for="(c, ci) in citations(msg)"
              :key="ci"
              class="cite-chip"
              :title="t('locateInDocumentTitle', { text: c })"
              @click="locateQuote(c)"
            >{{ t('locateQuoteButton', { text: c.length > 18 ? c.slice(0, 18) + '…' : c }) }}</button>
          </div>
          <!-- 显式完成态：光标消失太隐晦，最新一轮收尾后明示（dev-board#147） -->
          <div v-if="msg.done && !msg.streaming && i === messages.length - 1" class="done-line">
            <template v-if="msg.durationMs">{{ t('doneWithDuration', { seconds: Math.round(msg.durationMs / 1000) }) }}</template>
            <template v-else>{{ t('done') }}</template>
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
            <span v-if="msg.question.answered" class="option-answered">{{ t('answered') }}</span>
          </div>
          <div v-if="msg.error" class="msg-error">{{ msg.error }}</div>
        </template>
      </div>
    </div>

    <!-- 历史会话面板：窄窗格用覆盖层而不是常驻侧栏 -->
    <div v-if="historyOpen" class="overlay" @click.self="historyOpen = false">
      <div class="panel">
        <div class="panel-head">
          <span>{{ t('historyTitle') }}</span>
          <button class="panel-close" @click="historyOpen = false">x</button>
        </div>
        <div v-if="historyLoading" class="panel-empty">{{ t('loading') }}</div>
        <div v-else-if="!conversations.length" class="panel-empty">{{ t('noConversationsYet') }}</div>
        <div v-for="c in conversations" :key="c.conversationId" class="conv-item">
          <template v-if="renamingId === c.conversationId">
            <input
              v-model="renameDraft"
              class="rename-input"
              maxlength="60"
              @keydown.enter.prevent="confirmRename(c)"
              @keydown.esc="renamingId = ''"
            />
            <div class="conv-actions">
              <button class="conv-act" @click="confirmRename(c)">{{ t('save') }}</button>
              <button class="conv-act" @click="renamingId = ''">{{ t('cancel') }}</button>
            </div>
          </template>
          <template v-else>
            <button class="conv-main" @click="pickConversation(c)">
              <span class="conv-title">{{ c.title || c.lastMessage || t('untitledConversation') }}</span>
              <span class="conv-meta">{{ formatTime(c.updatedAt) }}<template v-if="c.runStatus === 'RUNNING'">{{ t('runningSuffix') }}</template></span>
            </button>
            <div class="conv-actions">
              <button class="conv-act" :title="t('rename')" @click="startRename(c)">{{ t('rename') }}</button>
              <button
                class="conv-act danger"
                :title="deletingId === c.conversationId ? t('confirmDeleteAgain') : t('deleteConversation')"
                @click="confirmDelete(c)"
              >{{ deletingId === c.conversationId ? t('confirmDelete') : t('delete') }}</button>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 技能面板：勾选的 skillIds 随每条消息上送（后端与触发词并集激活） -->
    <div v-if="skillsOpen" class="overlay" @click.self="skillsOpen = false">
      <div class="panel">
        <div class="panel-head">
          <span>{{ t('skillsTitle') }}</span>
          <button class="panel-close" @click="skillsOpen = false">x</button>
        </div>
        <div v-if="!skillList.length" class="panel-empty">{{ t('noSkillsAvailable') }}</div>
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

    <!-- 附件面板：项目文件作为额外上下文（contextItems，后端按 fileId 读内容） -->
    <div v-if="attachOpen" class="overlay" @click.self="attachOpen = false">
      <div class="panel">
        <div class="panel-head">
          <span>{{ t('attachFilesTitle') }}</span>
          <button class="panel-close" @click="attachOpen = false">x</button>
        </div>
        <div v-if="attachLoading" class="panel-empty">{{ t('loading') }}</div>
        <div v-else-if="!projectFiles.length" class="panel-empty">{{ t('noProjectFiles') }}</div>
        <label v-for="f in projectFiles" :key="f.id" class="skill-item">
          <input
            type="checkbox"
            :checked="attachedFiles.some(a => String(a.id) === String(f.id))"
            @change="toggleAttachedFile(f)"
          />
          <span class="skill-name">{{ f.name }}</span>
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
          :title="includeDocument ? t('docPillOnTitle') : t('docPillOffTitle')"
          @click="includeDocument = !includeDocument"
        >{{ docLabel }}</button>
        <button
          class="pill"
          :class="{ active: attachedFiles.length }"
          :title="t('attachPillTitle')"
          @click="openAttach"
        >{{ t('attachButton') }}<template v-if="attachedFiles.length"> {{ attachedFiles.length }}</template></button>
        <button
          v-if="skillList.length"
          class="pill"
          :class="{ active: selectedSkillIds.length }"
          :title="t('skillsPillTitle')"
          @click="skillsOpen = true"
        >{{ t('skillsButton') }}<template v-if="selectedSkillIds.length"> {{ selectedSkillIds.length }}</template></button>
        <select
          v-if="modelCatalog && modelCatalog.models.length"
          class="model-select"
          :value="selectedModel"
          :title="t('modelSelectTitle')"
          @change="chooseModel($event.target.value)"
        >
          <option value="">{{ t('defaultModelOption') }}</option>
          <option v-for="m in modelCatalog.models" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
        <span class="context-spacer"></span>
        <button class="pill" :title="t('historyPillTitle')" @click="openHistory">{{ t('historyButton') }}</button>
        <button class="pill" :title="t('newConversationTitle')" :disabled="streaming" @click="newConversation">{{ t('newConversationButton') }}</button>
      </div>
      <div class="input-row">
        <textarea
          v-model="input"
          rows="2"
          :placeholder="t('inputPlaceholder')"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button v-if="streaming" class="btn stop" @click="stop">{{ t('stop') }}</button>
        <button v-else class="btn send" :disabled="!canSend" @click="send">{{ t('send') }}</button>
      </div>
      <p v-if="reconnecting" class="banner conn">{{ t('reconnectingBanner') }}</p>
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
  toggleSkill, loadConversationList, switchConversation, attachedFiles, toggleAttachedFile,
  loadProjectFiles, removeConversation, retitleConversation
} from '../lib/chatSession.js'
import { readDocumentMeta, detectHost } from '../lib/wordDoc.js'
import { locateInDocument } from '../lib/officeExecutor.js'
import { t } from '../lib/i18n.js'

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
  const name = docMeta.value && docMeta.value.name ? docMeta.value.name : t('currentDocument')
  return (includeDocument.value ? '' : t('docPillOffPrefix')) + name
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

// ==================== 附件 / 计划卡 / 引用定位 / 批注快捷 ====================

const attachOpen = ref(false)
const attachLoading = ref(false)
const projectFiles = ref([])
const renamingId = ref('')
const renameDraft = ref('')
const deletingId = ref('')

async function openAttach() {
  attachOpen.value = true
  attachLoading.value = true
  try {
    projectFiles.value = await loadProjectFiles()
  } finally {
    attachLoading.value = false
  }
}

async function sendQuick(text) {
  const result = await sendMessage(text)
  if (result && result.needSettings) emit('need-settings')
}

function focusInput() {
  const el = document.querySelector('.input-row textarea')
  if (el) el.focus()
}

/** 空态快捷入口：批注队列处理只在 Word 宿主给（命令面按宿主分） */
const quickPrompts = computed(() => {
  const host = detectHost()
  const list = [{ label: t('quickSummarizeLabel'), text: t('quickSummarizeText') }]
  if (host === 'word') {
    list.push({ label: t('quickCommentsLabel'), text: t('quickCommentsText') })
    list.push({ label: t('quickProofreadLabel'), text: t('quickProofreadText') })
  }
  return list
})

/** 回答里被「」或 “” 包住的原文引用（6-80 字符），可点击定位（仅 Word 宿主） */
function citations(msg) {
  if (!msg.text || detectHost() !== 'word') return []
  const out = []
  const seen = new Set()
  const re = /「([^「」\n]{6,80})」|“([^“”\n]{6,80})”/g
  let m
  while ((m = re.exec(msg.text)) && out.length < 4) {
    const t = (m[1] || m[2] || '').trim()
    if (t && !seen.has(t)) { seen.add(t); out.push(t) }
  }
  return out
}

async function locateQuote(text) {
  const r = await locateInDocument(text)
  notice.value = r.found ? '' : t('quoteNotFound')
}

function startRename(c) {
  renamingId.value = c.conversationId
  renameDraft.value = c.title || ''
  deletingId.value = ''
}

async function confirmRename(c) {
  const title = renameDraft.value.trim()
  if (!title) { renamingId.value = ''; return }
  try {
    await retitleConversation(c.conversationId, title)
    c.title = title
    renamingId.value = ''
  } catch (e) {
    banner.value = (e && e.message) || t('renameFailed')
  }
}

/** 删除走两段式确认（Office webview 里不用 window.confirm） */
async function confirmDelete(c) {
  if (deletingId.value !== c.conversationId) {
    deletingId.value = c.conversationId
    return
  }
  try {
    await removeConversation(c.conversationId)
    conversations.value = conversations.value.filter((x) => x.conversationId !== c.conversationId)
    deletingId.value = ''
  } catch (e) {
    banner.value = (e && e.message) || t('deleteFailed')
    deletingId.value = ''
  }
}
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

/* 计划/交付物卡：区别于普通气泡，左侧主色描边 */
.artifact-card {
  border: 1px solid var(--awd-border);
  border-left: 3px solid var(--awd-primary);
  border-radius: 8px;
  background: var(--awd-surface);
  padding: 8px 10px;
  margin-bottom: 6px;
  max-width: 92%;
}

.artifact-head {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-primary);
  margin-bottom: 4px;
}

.artifact-body {
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
}

.artifact-actions { display: flex; gap: 6px; margin-top: 6px; }

.cite-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.cite-chip {
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px dashed var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
  cursor: pointer;
}

.cite-chip:hover { border-color: var(--awd-primary); color: var(--awd-primary); }

.quick-prompts {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.quick-btn {
  padding: 5px 14px;
  border-radius: 999px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  cursor: pointer;
}

.quick-btn:hover { border-color: var(--awd-primary); color: var(--awd-primary); }

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
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  background: var(--awd-surface);
  padding: 6px 9px;
  margin-bottom: 5px;
}

.conv-item:hover { border-color: var(--awd-primary); }

.conv-main {
  flex: 1;
  min-width: 0;
  text-align: left;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
}

.conv-actions { display: flex; gap: 4px; flex-shrink: 0; }

.conv-act {
  border: none;
  background: none;
  color: var(--awd-text-secondary);
  font-size: 11px;
  cursor: pointer;
  padding: 2px 4px;
}

.conv-act:hover { color: var(--awd-primary); }
.conv-act.danger:hover { color: var(--awd-danger); }

.rename-input {
  flex: 1;
  min-width: 0;
  padding: 3px 6px;
  border: 1px solid var(--awd-primary);
  border-radius: 4px;
  font-size: 12px;
}

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

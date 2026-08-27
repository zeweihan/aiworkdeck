<template>
  <div class="chat">
    <div ref="listEl" class="message-list" @scroll.passive="onListScroll">
      <div v-if="!messages.length" class="empty">
        <!-- 未登录空态（dev-board#192）：品牌化欢迎卡替代一句红字 -->
        <div v-if="!configured" class="welcome">
          <img class="welcome-logo" :src="logoSrc" alt="AI WorkDeck" />
          <div class="welcome-title">{{ t('signInWelcomeTitle') }}</div>
          <p class="welcome-hint">{{ t('signInWelcomeHint') }}</p>
          <button class="welcome-btn" @click="goSignIn">{{ t('login') }}</button>
        </div>
        <template v-else>
          <p>{{ t('emptyHint') }}</p>
          <p v-if="!projectId" class="empty-warn">{{ t('noProjectSelected') }}</p>
          <div v-else ref="quickPromptsEl" class="quick-prompts">
            <button v-for="q in quickPrompts" :key="q.label" class="quick-btn" @click="sendQuick(q.text)">{{ q.label }}</button>
          </div>
        </template>
      </div>

      <TransitionGroup :css="false" @enter="onMessageEnter" @leave="onMessageLeave">
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
            <div class="artifact-body md" v-html="renderMarkdown(msg.artifact)"></div>
            <div v-if="i === messages.length - 1 && !streaming" class="artifact-actions">
              <button class="option-btn" @click="sendQuick(t('proceedWithPlan'))">{{ t('proceedWithPlan') }}</button>
              <button class="option-btn" @click="focusInput">{{ t('proposeChanges') }}</button>
            </div>
          </div>
          <!-- 首 token 前不再是「空气泡+光标」：给一句状态，别让人以为卡死了 -->
          <div v-if="msg.streaming && !msg.text" class="bubble assistant-bubble pending-bubble">
            {{ msg.tools && msg.tools.length ? t('workingOnDocument') : t('thinkingEllipsis') }}
          </div>
          <!-- Markdown 渲染（dev-board#197）：加粗/列表/代码不再以星号裸奔；
               renderMarkdown 先整体 HTML 转义再套标签，v-html 无注入面 -->
          <div v-else class="bubble assistant-bubble">
            <div class="md" :class="{ 'md-streaming': msg.streaming }" v-html="renderMarkdown(msg.text)"></div>
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
      </TransitionGroup>
    </div>

    <!-- 历史会话面板：窄窗格用覆盖层而不是常驻侧栏 -->
    <div v-if="historyOpen" class="overlay" @click.self="historyOpen = false">
      <div ref="historyPanelEl" class="panel glass">
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
      <div ref="skillsPanelEl" class="panel glass">
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
      <div ref="attachPanelEl" class="panel glass">
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

    <footer class="composer glass">
      <!-- 更多菜单（dev-board#176 多层菜单）：低频操作收进两级菜单，底部只留高频项。
           点击捕获层盖住菜单以外的区域，点空白即收起 -->
      <div v-if="moreOpen" class="click-catcher" @click="closeMore"></div>
      <div v-if="moreOpen" ref="morePanelEl" class="menu-panel glass">
        <template v-if="moreLevel === 'root'">
          <button class="menu-row" @click="fromMore(openAttach)">
            <span class="row-label">{{ t('menuAttach') }}</span>
            <span v-if="attachedFiles.length" class="row-badge">{{ attachedFiles.length }}</span>
            <span class="row-chevron">›</span>
          </button>
          <button v-if="skillList.length" class="menu-row" @click="fromMore(() => { skillsOpen = true })">
            <span class="row-label">{{ t('menuSkills') }}</span>
            <span v-if="selectedSkillIds.length" class="row-badge">{{ selectedSkillIds.length }}</span>
            <span class="row-chevron">›</span>
          </button>
          <!-- 历史与模型不再收在这里：它们是高频入口，常驻 composer 一行（dev-board#195） -->
        </template>
        <template v-else>
          <!-- 第二级：模型选择 -->
          <button class="menu-row back-row" @click="moreLevel = 'root'">
            <span class="row-chevron back">‹</span>
            <span class="row-label">{{ t('menuModel') }}</span>
          </button>
          <button class="menu-row" @click="pickModel('')">
            <span class="row-label">{{ t('defaultModelOption') }}</span>
            <span v-if="!selectedModel" class="row-check">✓</span>
          </button>
          <button
            v-for="m in modelCatalog.models"
            :key="m.id"
            class="menu-row"
            @click="pickModel(m.id)"
          >
            <span class="row-label">{{ m.name }}</span>
            <span v-if="selectedModel === m.id" class="row-check">✓</span>
          </button>
        </template>
      </div>

      <!-- 高频三件常驻：文档 pill / 更多菜单 / 新对话；其余收进「更多」两级菜单 -->
      <div class="context-row">
        <button
          class="pill doc-pill"
          :class="{ off: !includeDocument }"
          :title="includeDocument ? t('docPillOnTitle') : t('docPillOffTitle')"
          @click="includeDocument = !includeDocument"
        >{{ docLabel }}</button>
        <button
          class="pill more-pill"
          :class="{ active: moreOpen || attachedFiles.length || selectedSkillIds.length }"
          :title="t('moreMenuTitle')"
          @click="moreOpen ? closeMore() : openMore()"
        >+</button>
        <!-- 历史与模型常驻（dev-board#195）：此前收在 + 二级菜单里，用户找不到 -->
        <button class="pill" :title="t('historyPillTitle')" @click="openHistory">{{ t('historyButton') }}</button>
        <button
          v-if="modelCatalog && modelCatalog.models.length"
          class="pill model-pill"
          :class="{ active: moreOpen && moreLevel === 'model' || selectedModel }"
          :title="t('modelSelectTitle')"
          @click="moreOpen ? closeMore() : openModelMenu()"
        >{{ currentModelName }}</button>
        <span class="context-spacer"></span>
        <button class="pill" :title="t('newConversationTitle')" :disabled="streaming" @click="newConversation">{{ t('newConversationButton') }}</button>
      </div>
      <div class="input-row">
        <textarea
          v-model="input"
          rows="2"
          :placeholder="t('inputPlaceholder')"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button
          v-if="micAvailable"
          class="btn mic"
          :class="{ recording: dictState === 'recording' }"
          :disabled="dictState === 'transcribing'"
          :title="dictState === 'recording' ? t('dictateRecording') : t('dictate')"
          @click="toggleDictation"
        >
          <span v-if="dictState === 'transcribing'" class="chip-spinner"></span>
          <span v-else-if="dictState === 'recording'" class="mic-dot"></span>
          <svg v-else class="mic-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="3" width="6" height="11" rx="3"/>
            <path d="M5 11a7 7 0 0 0 14 0"/>
            <line x1="12" y1="18" x2="12" y2="21"/>
          </svg>
        </button>
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
import { computed, nextTick, onMounted, ref, watch, TransitionGroup } from 'vue'
import {
  messages, input, streaming, reconnecting, banner, notice, includeDocument, scrollSignal,
  activateSession, send as sendMessage, stop as stopRun, newConversation,
  answerQuestion, modelCatalog, selectedModel, chooseModel, skillList, selectedSkillIds,
  toggleSkill, loadConversationList, switchConversation, attachedFiles, toggleAttachedFile,
  loadProjectFiles, removeConversation, retitleConversation
} from '../lib/chatSession.js'
import { readDocumentMeta, detectHost } from '../lib/wordDoc.js'
import { locateInDocument } from '../lib/officeExecutor.js'
import { micSupported, startRecording, MAX_RECORD_MS } from '../lib/wavRecorder.js'
import { postDictate } from '../lib/api.js'
import { t } from '../lib/i18n.js'
import { renderMarkdown } from '../lib/markdown.js'
import { riseIn, panelUp, popIn, staggerIn } from '../lib/motion.js'

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

// 未登录欢迎卡的 Logo：与 App.vue 同款运行时相对路径（绕开 vite 静态改写）
const logoSrc = 'icon-64.png'

const canSend = computed(() =>
  props.configured && props.projectId && input.value.trim().length > 0 && !streaming.value)

/**
 * 吸底守卫（dev-board#197）：只有用户本来就贴着底部时，流式增量才继续吸底；
 * 用户上滚回看时不再被每个 text_delta 拽回去——「内容老往下蹦」的另一半根因。
 */
const stickToBottom = ref(true)

function onListScroll() {
  const el = listEl.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 48
}

function scrollToBottom(force = false) {
  nextTick(() => {
    if (listEl.value && (force || stickToBottom.value)) {
      listEl.value.scrollTop = listEl.value.scrollHeight
      stickToBottom.value = true
    }
  })
}

function goSignIn() {
  emit('need-settings')
}

// 连接配置或项目变化时重新激活会话（同一身份是空操作，不打断进行中的对话）
watch(
  () => [props.settings.serverUrl, props.settings.token, props.projectId],
  () => { activateSession({ settings: props.settings, projectId: props.projectId }) },
  { immediate: true }
)

// store 不碰 DOM：由它发出滚动信号，视图负责滚（吸底与否由守卫决定）
watch(scrollSignal, () => scrollToBottom())

// 重新挂载（从设置视图切回来）时恢复到最新一条消息
onMounted(() => scrollToBottom(true))

async function send() {
  const result = await sendMessage()
  // 自己发的消息永远滚到底：哪怕刚才上滚回看，发送就是「回到对话前沿」的意图
  scrollToBottom(true)
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

// 更多菜单（两级）：root=功能清单，model=模型选择二级页
const moreOpen = ref(false)
const moreLevel = ref('root')
const morePanelEl = ref(null)
const historyPanelEl = ref(null)
const skillsPanelEl = ref(null)
const attachPanelEl = ref(null)
const quickPromptsEl = ref(null)

onMounted(() => { docMeta.value = readDocumentMeta() })

// ==================== 动效（有动机才动，reduced-motion 自动退化） ====================

function onMessageEnter(el) {
  riseIn(el)
}

function onMessageLeave(el, done) {
  done() // 移除（新对话清屏）不做退场动画，即时反馈
}

// 覆盖层面板浮现：v-if 挂载后下一帧做升起动画
watch(historyOpen, (open) => { if (open) nextTick(() => panelUp(historyPanelEl.value)) })
watch(skillsOpen, (open) => { if (open) nextTick(() => panelUp(skillsPanelEl.value)) })

// 空态快捷入口逐个浮现（只在出现时动一次）
watch(quickPromptsEl, (el) => {
  if (el) staggerIn(el.querySelectorAll('.quick-btn'))
})

// ==================== 更多菜单 ====================

const currentModelName = computed(() => {
  if (!selectedModel.value || !modelCatalog.value) return t('defaultModelOption')
  const hit = modelCatalog.value.models.find((m) => m.id === selectedModel.value)
  return hit ? hit.name : t('defaultModelOption')
})

function openMore() {
  moreLevel.value = 'root'
  moreOpen.value = true
  nextTick(() => popIn(morePanelEl.value))
}

/** 模型 pill 直达模型选择页（复用更多菜单的二级面板，dev-board#195） */
function openModelMenu() {
  moreLevel.value = 'model'
  moreOpen.value = true
  nextTick(() => popIn(morePanelEl.value))
}

function closeMore() {
  moreOpen.value = false
}

/** 一级菜单项：关掉菜单再打开对应面板 */
function fromMore(action) {
  closeMore()
  action()
}

function pickModel(id) {
  chooseModel(id)
  closeMore()
}

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

// ==================== 语音听写（dev-board#153）====================

const micAvailable = micSupported()
/** '' | 'recording' | 'transcribing' */
const dictState = ref('')
let recorder = null
let recordTimer = null

async function toggleDictation() {
  if (dictState.value === 'transcribing') return
  if (dictState.value === 'recording') {
    await finishDictation()
    return
  }
  banner.value = ''
  try {
    recorder = await startRecording()
  } catch (e) {
    banner.value = (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError'))
      ? t('dictateDenied') : t('dictateUnsupported')
    return
  }
  dictState.value = 'recording'
  // 到上限自动收束，别让用户录到天荒地老再被服务端拒
  recordTimer = setTimeout(() => { finishDictation() }, MAX_RECORD_MS)
}

async function finishDictation() {
  if (!recorder) { dictState.value = ''; return }
  if (recordTimer) { clearTimeout(recordTimer); recordTimer = null }
  const active = recorder
  recorder = null
  dictState.value = 'transcribing'
  try {
    const result = await active.stop()
    if (!result || result.durationMs < 300) { dictState.value = ''; return }
    const text = await postDictate(props.settings, {
      audioBase64: result.base64, format: 'wav', durationMs: result.durationMs
    })
    if (text) {
      input.value = input.value ? input.value + text : text
      focusInput()
    } else {
      banner.value = t('dictateEmpty')
    }
  } catch (e) {
    banner.value = t('dictateFailed', { message: (e && e.message) || '' })
  } finally {
    dictState.value = ''
  }
}

// ==================== 附件 / 计划卡 / 引用定位 / 批注快捷 ====================

const attachOpen = ref(false)
const attachLoading = ref(false)
const projectFiles = ref([])
const renamingId = ref('')
const renameDraft = ref('')
const deletingId = ref('')

watch(attachOpen, (open) => { if (open) nextTick(() => panelUp(attachPanelEl.value)) })

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
  scrollToBottom(true)
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

/* 未登录欢迎卡（dev-board#192）：品牌化空态，替代一句孤零零的红字 */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  max-width: 260px;
  margin: 24px auto 0;
  padding: 26px 20px 22px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-md);
  box-shadow: var(--awd-shadow-soft);
}

.welcome-logo {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  box-shadow: 0 4px 14px rgba(26, 83, 54, 0.16);
}

.welcome-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.welcome-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--awd-text-secondary);
}

.welcome-btn {
  margin-top: 4px;
  padding: 7px 30px;
  border: none;
  border-radius: 999px;
  background: var(--awd-primary);
  color: #fff;
  font-size: 13px;
  transition: background 0.2s ease, transform 0.1s ease, box-shadow 0.2s ease;
  box-shadow: 0 4px 14px rgba(26, 83, 54, 0.22);
}

.welcome-btn:hover { background: var(--awd-primary-hover); }
.welcome-btn:active { transform: translateY(1px); }

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
  border: 1px solid rgba(45, 122, 82, 0.18);
  border-radius: 10px 10px 3px 10px;
}

.assistant-bubble {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px 10px 10px 3px;
  box-shadow: var(--awd-shadow-soft);
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
  padding: 6px 16px;
  border-radius: 999px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  cursor: pointer;
  box-shadow: var(--awd-shadow-soft);
  transition: border-color 0.2s ease, color 0.2s ease, transform 0.15s ease, box-shadow 0.2s ease;
}

.quick-btn:hover {
  border-color: var(--awd-accent);
  color: var(--awd-primary);
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(26, 83, 54, 0.12);
}

.quick-btn:active { transform: translateY(0); }

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

@keyframes blink { 50% { opacity: 0; } }

/* 不给 composer 设 z-index：历史/技能/附件 overlay（z-20）要能盖住它；
   更多菜单的捕获层/面板自带更高层级（24/26），不依赖 composer 的层叠上下文 */
.composer {
  position: relative;
  border-top: 1px solid rgba(26, 83, 54, 0.10);
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

/* 「更多」pill：加号入口，命中态（菜单开着/有已选项）用品牌绿 */
.more-pill {
  min-width: 26px;
  text-align: center;
  font-size: 13px;
  line-height: 1.2;
}

/* 模型 pill：显示当前模型名，太长截断 */
.model-pill {
  max-width: 30%;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 点击捕获层：盖住菜单以外的区域（composer 自身是 stacking context，fixed 仍覆盖全窗） */
.click-catcher {
  position: fixed;
  inset: 0;
  z-index: 24;
  background: rgba(14, 33, 23, 0.14);
}

/* 更多菜单面板：毛玻璃浮层，锚在 composer 正上方 */
.menu-panel {
  position: absolute;
  bottom: 100%;
  left: 10px;
  margin-bottom: 6px;
  width: 230px;
  max-height: 300px;
  overflow-y: auto;
  z-index: 26;
  border: 1px solid rgba(26, 83, 54, 0.12);
  border-radius: var(--awd-radius-md);
  box-shadow: var(--awd-shadow-float);
  padding: 6px;
}

.menu-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  text-align: left;
  padding: 8px 9px;
  border: none;
  border-radius: var(--awd-radius-sm);
  background: none;
  color: var(--awd-text);
  font-size: 12px;
  transition: background 0.15s ease, color 0.15s ease;
}

.menu-row:hover {
  background: var(--awd-mint-pale);
  color: var(--awd-primary);
}

.back-row {
  border-bottom: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm) var(--awd-radius-sm) 0 0;
  margin-bottom: 4px;
  font-weight: 600;
}

.row-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.back-row .row-label { flex: none; }

.row-meta {
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--awd-text-secondary);
  font-size: 11px;
}

.row-badge {
  min-width: 16px;
  text-align: center;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--awd-accent);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
}

.row-chevron {
  color: var(--awd-border-strong);
  font-size: 13px;
}

.row-chevron.back { color: var(--awd-accent); }

.row-check {
  color: var(--awd-accent);
  font-weight: 700;
}

.input-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

/* 覆盖层面板（历史/技能/附件）：窄窗格从底部铺开，遮罩染品牌绿灰调 */
.overlay {
  position: absolute;
  inset: 0;
  background: rgba(14, 33, 23, 0.24);
  display: flex;
  align-items: flex-end;
  z-index: 20;
}

.panel {
  width: 100%;
  max-height: 65%;
  overflow-y: auto;
  border-top: 1px solid rgba(26, 83, 54, 0.12);
  border-radius: var(--awd-radius-md) var(--awd-radius-md) 0 0;
  box-shadow: 0 -8px 32px rgba(18, 58, 38, 0.14);
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
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  resize: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

textarea:focus {
  outline: none;
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.18);
}

.btn-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: flex-end;
}

.btn {
  padding: 5px 12px;
  border-radius: var(--awd-radius-sm);
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  white-space: nowrap;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease, transform 0.1s ease;
}

.btn:active { transform: translateY(1px); }

.btn.send {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.btn.send:hover:not(:disabled) { background: var(--awd-primary-hover); }
.btn.send:disabled { opacity: 0.5; cursor: default; }

.btn.mic {
  padding: 5px 9px;
  color: var(--awd-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn.mic:hover:not(:disabled) { color: var(--awd-primary); border-color: var(--awd-primary); }

.btn.mic.recording {
  color: var(--awd-danger);
  border-color: var(--awd-danger);
}

.mic-icon { width: 15px; height: 15px; }

.mic-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--awd-danger);
  animation: blink 1.2s ease-in-out infinite;
}

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

<!-- v-html 注入的 Markdown 内容拿不到 scoped 属性，样式放非 scoped 块（.md 前缀限定作用面） -->
<style>
.md { white-space: normal; }

.md p { margin: 0 0 8px; }
.md ul, .md ol { margin: 0 0 8px; padding-left: 18px; }
.md li { margin: 2px 0; }
.md h4, .md h5 { margin: 10px 0 6px; font-size: 13px; }
.md h4:first-child, .md h5:first-child { margin-top: 0; }
.md > :last-child { margin-bottom: 0; }

.md code {
  font-family: var(--awd-font-mono);
  font-size: 12px;
  background: var(--awd-bone);
  border-radius: 4px;
  padding: 1px 4px;
}

.md pre {
  margin: 0 0 8px;
  padding: 8px 10px;
  background: var(--awd-bone);
  border-radius: 6px;
  overflow-x: auto;
}

.md pre code { background: none; padding: 0; }

.md a { color: var(--awd-accent); }

/* 流式光标挂在最后一个块的尾部，跟着 Markdown 排版走 */
.md-streaming > :last-child::after {
  content: '';
  display: inline-block;
  width: 7px;
  height: 13px;
  margin-left: 2px;
  background: var(--awd-primary);
  vertical-align: text-bottom;
  /* scoped 块里的 @keyframes 会被加 hash 改名，这里用非 scoped 的独立定义 */
  animation: md-caret-blink 1s step-start infinite;
}

@keyframes md-caret-blink { 50% { opacity: 0; } }
</style>

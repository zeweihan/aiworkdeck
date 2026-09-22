<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="root-bubble-wrapper">

    <div class="active-bubble-wrapper">
        <div class="root-bubble-container">
          <template v-for="(entry, index) in timeline" :key="entry.key">
            <ThinkingCard
              v-if="entry.type === 'thinking'"
              :status="isActive(entry, index) ? 'thinking' : 'done'"
              :duration="entry.data.duration || 0"
              :content="entry.data.content"
              :start-time="entry.data.startTime || 0"
              variant="ghost"
            />
            <div v-else-if="entry.type === 'title'" class="stream-title">{{ bubble.title }}</div>
            <section v-else-if="entry.type === 'execution' || entry.type === 'plan'" class="activity-entry">
              <button type="button" class="activity-summary" :class="{ 'is-working': isActive(entry, index), 'has-error': hasError(entry) }"
                :aria-expanded="isExpanded(entry, index)" @click="toggleEntry(entry, index)">
                <span>{{ activityLabel(entry, index) }}</span>
                <span class="activity-chevron" :class="{ open: isExpanded(entry, index) }" aria-hidden="true">›</span>
              </button>
              <div v-if="isExpanded(entry, index)" class="activity-details">
                <TodoProgressCard v-if="entry.type === 'plan'" :todos="entry.data" :live="bubble.isStreaming" />
                <ProcessCard v-else v-for="(proc, pi) in entry.procs" :key="pi" :process="proc" />
              </div>
            </section>
            <template v-else-if="entry.type === 'artifact'">
               <div
                 class="artifact-wrapper"
                 :data-chat-attention="isApprovalPending(entry.data) ? '' : null"
                 :class="{ 'artifact-wrapper--approval': isApprovalPending(entry.data) }"
               >
                  <div v-if="isApprovalPending(entry.data)" class="approval-flag">
                     <span class="approval-flag-dot"></span>
                     <span class="approval-flag-text">{{ $t('chat.approvalNeededFlag') }}</span>
                  </div>
                  <ArtifactCard
                    :artifact="entry.data"
                    :id="entry.data.id"
                    :type="entry.data.type"
                    :status="entry.data.status"
                    :file-name="entry.data.fileName"
                    :data="entry.data.data"
                    :actionable="isLatest && !bubble.isStreaming"
                    @open-tab="$emit('open-artifact-tab', $event)"
                    @approve="$emit('approve', $event)"
                  />
               </div>
            </template>
            <div v-else-if="entry.type === 'text'" class="main-content" data-chat-answer>
              <MarkdownPreview :content="bubble.content.slice(entry.start, entry.end)" />
            </div>
          </template>

            <!-- 5b. 气泡底部操作条。
                 「复制」与「重新生成」刻意<b>不</b>挂在 showUseInDocument 那条判据上：
                 那条判据管的是「这段话值不值得放进当前文书」，而复制的去处是邮件、微信、
                 另一份文档——恰恰是 AI 刚写过文档的那一轮（showUseInDocument 判 false）
                 用户最想把修改说明拷走（审查 D-07 / F2）。判据只要「有正文、流已结束」。
                 「重新生成」只给最新一条：回退会连带删掉它之后的所有对话，
                 对着历史中间某条点下去会静默毁掉后面好几轮（dev-board#790）。 -->
            <div v-if="showCopy || showRegenerate || showUseInDocument" class="bubble-toolbar">
               <div v-if="showCopy" class="msg-act-trigger msg-copy-btn" :title="$t('chat.copyAnswer')" @click.stop="copyAnswer">
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                     <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                     <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                  </svg>
                  <span>{{ $t('chat.copyAnswer') }}</span>
               </div>
               <div v-if="showRegenerate" class="msg-act-trigger msg-regen-btn" :title="$t('chat.regenerateTitle')" @click.stop="$emit('regenerate')">
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                     <path d="M21 12a9 9 0 1 1-2.64-6.36"></path>
                     <polyline points="21 3 21 9 15 9"></polyline>
                  </svg>
                  <span>{{ $t('chat.regenerate') }}</span>
               </div>
            <!-- 插入/替换/导出收进一个图标，点开再选（用户反馈三个平铺按钮太占地方）。
                 菜单向上弹，透明遮罩点外即收。按需展示：本轮已经改过文档、正在反问、
                 或只是一句回执时都不出（判据在 utils/useInDocumentVisibility.js，dev-board#728）。 -->
            <div v-if="showUseInDocument" class="message-actions">
               <div class="msg-act-trigger" :class="{ active: showActions }" @click.stop="showActions = !showActions">
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                     <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"></path>
                     <polyline points="14 2 14 8 20 8"></polyline>
                     <line x1="9" y1="15" x2="15" y2="15"></line>
                  </svg>
                  <span>{{ $t('chat.useInDocument') }}</span>
               </div>
               <div v-if="showActions" class="msg-act-menu">
                  <div class="msg-act-item" @click="pickAction('insert')">{{ $t('chat.insertToDocument') }}</div>
                  <div class="msg-act-item" @click="pickAction('replace')">{{ $t('chat.replaceSelection') }}</div>
                  <div class="msg-act-item" @click="pickAction('export')">{{ $t('chat.exportAsWord') }}</div>
               </div>
               <div v-if="showActions" class="msg-act-mask" @click.stop="showActions = false"></div>
            </div>
            </div>

            <!-- 5c. 反问卡（<question>）：模型缺关键前提时停机等回答。
                 放在正文之后——若正文还在 bubble.content 里（旧格式历史消息），
                 选项也要出现在那段话下面，读起来才是「先问、再给选项」。
                 可操作性与计划卡同一条链（仅最新一条助手消息、且流已结束）。 -->
            <QuestionCard
              data-chat-attention
              v-if="bubble.question"
              :text="bubble.question.text || ''"
              :options="bubble.question.options || []"
              :answered="!!bubble.question.answered"
              :actionable="isLatest && !bubble.isStreaming && !bubble.question.answered"
              @answer="$emit('answer-question', $event)"
            />

            <!-- 6. Walkthrough (Summary) - Temporarily hidden as per user request -->
            <!-- <WalkthroughCard
              v-if="bubble.walkthrough"
              :content="bubble.walkthrough"
              :is-streaming="bubble.isStreaming"
              :show-header="true"
              @open-tab="$emit('open-artifact-tab', $event)"
            /> -->
        </div>

        <!-- 7. 停止提示：系统状态行，刻意放在 root-bubble-container 之外且不参与
             isReady/hasContent 判定——它不是模型正文，不该让空产出的回合长出
             「用到文档」操作 chip（dev-board#212）。 -->
        <div v-if="bubble.stopNotice" class="stop-notice">{{ bubble.stopNotice }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ThinkingCard from './ThinkingCard.vue'
import TodoProgressCard from './TodoProgressCard.vue'
import ProcessCard from './ProcessCard.vue'
import ArtifactCard from '../ArtifactCard.vue'
import QuestionCard from './QuestionCard.vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import { t } from '@/i18n'
import { visibleChatTimeline, isTimelineEntryActive } from './chatTimeline.mjs'
import { shouldShowUseInDocument } from '@/utils/useInDocumentVisibility.js'
import { answerPlainText, copyToClipboard } from '@/utils/chatClipboard.js'
import { toolDisplayName } from '@/utils/toolDisplayNames.js'

const props = defineProps({
  bubble: { type: Object, required: true },
  /** 是否为最新一条助手消息（决定计划卡是否可操作） */
  isLatest: { type: Boolean, default: false }
})

const emit = defineEmits(['open-artifact-tab', 'approve', 'message-action', 'answer-question', 'regenerate'])

// 载荷形状对齐 project-overview.handleChatInterfaceAction({ type, msg })，msg 只需 content
function sendAction(type) {
  emit('message-action', { type, msg: { content: props.bubble.content } })
}

const showActions = ref(false)
// 「用到文档」那组操作的可见性。判定整体在纯函数里（可单测），这里只做响应式包装。
const showUseInDocument = computed(() => shouldShowUseInDocument(props.bubble))
function pickAction(type) {
  showActions.value = false
  sendAction(type)
}

// ---- 复制 / 重新生成（dev-board#790）----
// 复制拿的是纯文本化之后的正文：粘进邮件里的不该是 **加粗** 与 <final>（见 utils/chatClipboard.js）。
// 这里同时当作可见性判据——纯文本化后为空（整条只有工具执行、没有一个字的回答）就不出按钮，
// 出一颗点下去什么都没复制走的按钮比没有按钮更糟。
const copyableText = computed(() => (props.bubble.isStreaming ? '' : answerPlainText(props.bubble.content)))
const showCopy = computed(() => !!copyableText.value)
const showRegenerate = computed(() => props.isLatest && !props.bubble.isStreaming && !!props.bubble.content)
function copyAnswer() {
  copyToClipboard(copyableText.value)
}

// ---- 运行状态条的秒表（dev-board#792）----
// 只在本条气泡还在流式时走，1 秒一跳；写法与 ThinkingCard 的思考秒表同源。
// beforeUnmount 必须清：气泡在切换会话 / 回退时会整片销毁，留着的 interval
// 会一直持有已销毁组件的 ref。
const now = ref(Date.now())
let ticker = null
const stopTicker = () => {
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}
const startTicker = () => {
  stopTicker()
  now.value = Date.now()
  ticker = setInterval(() => { now.value = Date.now() }, 1000)
}
watch(() => props.bubble.isStreaming, streaming => (streaming ? startTicker() : stopTicker()), { immediate: true })
onBeforeUnmount(stopTicker)

/**
 * 这一段执行里最后一个仍在跑的工具。
 * items 只追加不重排，所以倒着找到的第一个 loading 就是当前这个。
 */
const runningTool = entry => {
  for (let p = entry.procs.length - 1; p >= 0; p--) {
    const items = entry.procs[p].items || []
    for (let i = items.length - 1; i >= 0; i--) {
      if (items[i].type === 'tool' && items[i].status === 'loading') return items[i]
    }
  }
  return null
}

const timeline = computed(() => visibleChatTimeline(props.bubble))
const entryToggles = ref({})
const isActive = (entry, index) => isTimelineEntryActive(entry, index, timeline.value, props.bubble.isStreaming)
const isExpanded = (entry, index) => {
  const active = isActive(entry, index)
  const override = entryToggles.value[entry.key]
  return override && override.active === active ? override.expanded : active
}
const toggleEntry = (entry, index) => {
  entryToggles.value = { ...entryToggles.value, [entry.key]: { active: isActive(entry, index), expanded: !isExpanded(entry, index) } }
}
const hasError = entry => entry.type === 'execution' && entry.procs.some(proc => (proc.items || []).some(item => item.status === 'error'))
const activityLabel = (entry, index) => {
  if (entry.type === 'plan') return t('chat.timelinePlan', { done: entry.data.filter(todo => todo.status === 'completed').length, total: entry.data.length })
  const count = entry.procs.reduce((n, proc) => n + (proc.items || []).filter(item => item.type === 'tool').length, 0) || entry.procs.length
  // 正在跑某个工具时先报工具名与秒数：「正在执行 3 项操作」分不清 AI 是在读合同、
  // 在查企查查、还是卡在某个超时的外部调用上，用户只能盯着一个不动的计数干等（审查 F5）。
  // 先于 hasError 判：前面某一步失败了、现在又在跑下一个，说「有操作未完成」既不准也没信息量——
  // 那个红色徽章展开就在里面。没有工具在跑时才退回原来的两条文案，行为一字未变。
  const running = isActive(entry, index) ? runningTool(entry) : null
  if (running) {
    return t('chat.timelineExecutingTool', {
      name: toolDisplayName(running.code) || t('chat.toolCallFallback'),
      sec: running.startTime ? Math.max(0, Math.round((now.value - running.startTime) / 1000)) : 0
    })
  }
  if (hasError(entry)) return t('chat.timelineExecutionError', { n: count })
  return t(isActive(entry, index) ? 'chat.timelineExecuting' : 'chat.timelineExecuted', { n: count })
}

// ---- 审批卡视觉强调 ----
// 判据刻意跟 ArtifactCard.showApprovalBar 完全对齐（isPlanType && actionable &&
// effectiveStatus === 'draft'）：只有「确实弹出了按此推进/修订按钮」的那一张才
// 值得强调，历史消息里已解决/不可操作的计划卡保持普通样式，不制造假的紧迫感。
const APPROVAL_ARTIFACT_TYPES = ['task_list', 'plan', 'implementation_plan']
function isApprovalPending(art) {
  return APPROVAL_ARTIFACT_TYPES.includes(art.type) && art.status === 'draft' && props.isLatest && !props.bubble.isStreaming
}

</script>

<style scoped>
.root-bubble-wrapper {
    width: 100%;
}

.root-bubble-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: transparent;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  word-wrap: break-word;
  overflow-wrap: break-word;
  margin-bottom: 14px; /* Compact spacing between bubbles */
  user-select: text;
  -webkit-user-select: text;
}

/* Connect artifacts visually */
.artifact-wrapper {
  border-bottom: 1px solid var(--awd-border-subtle);
}

.artifact-wrapper:last-child {
    border-bottom: none;
}

/* ---- 审批卡（需要用户点按的 task_list/plan/implementation_plan draft） ----
   与普通过程卡/已确认的产出卡明显区分：独立卡片 + 留白 + 强调边框 + 顶部色条 +
   「待确认」标识，一眼能看出这里需要点按，不再被夹在执行过程和正文之间漏看。 */
.artifact-wrapper.artifact-wrapper--approval {
  border-bottom: none;
  margin: 10px 12px 12px;
  border: 1.5px solid var(--awd-mint); /* Mint Green */
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(46, 90, 80, 0.10);
}

/* 顶部色条：森林绿→薄荷绿，视觉上先声夺人 */
.artifact-wrapper--approval::before {
  content: '';
  display: block;
  height: 4px;
  background: linear-gradient(90deg, var(--awd-accent), var(--awd-mint));
}

.approval-flag {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px 0;
  background: var(--awd-surface);
}

.approval-flag-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--awd-mint); /* Mint Green */
  flex-shrink: 0;
}

.approval-flag-text {
  font-size: 11px;
  font-weight: 700;
  color: var(--awd-accent-text); /* Forest Green */
  letter-spacing: 0.3px;
}

.stop-notice {
  font-size: 12px;
  color: var(--awd-text-2);
  padding: 2px 4px 0;
  margin-bottom: 14px;
}

.main-content {
  padding: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--awd-text); /* Gray-Dark */
}

/* 气泡底部操作条：复制 / 重新生成 / 用到文档，横排在一行 */
.bubble-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.message-actions {
  position: relative;
  display: flex;
  padding: 0;
  margin-top: 0;
}

.msg-act-trigger {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 5px;
  border: 1px solid var(--awd-border);
  color: var(--awd-text-2);
  background: var(--awd-surface);
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}

.msg-act-trigger:hover, .msg-act-trigger.active {
  border-color: var(--awd-mint);
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

.msg-act-menu {
  position: absolute;
  left: 16px;
  bottom: calc(100% - 2px);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.10);
  padding: 4px;
  z-index: 99;
  min-width: 140px;
}

.msg-act-item {
  font-size: 12px;
  color: var(--awd-text);
  padding: 6px 10px;
  border-radius: 5px;
  cursor: pointer;
  white-space: nowrap;
}

.msg-act-item:hover {
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
}

.msg-act-mask {
  position: fixed;
  inset: 0;
  z-index: 98;
  background: transparent;
}

.stream-title { color: var(--awd-text-2); font-size: 13px; }
.activity-summary {
  display: flex; align-items: center; gap: 8px; max-width: 100%;
  border: 0; background: transparent; padding: 0; margin: 0;
  color: var(--awd-text-2); font: inherit; font-size: 12px; line-height: 1.6;
  text-align: left; cursor: pointer; overflow-wrap: anywhere;
}
.activity-summary::after { border: 0; }
.activity-summary:hover { color: var(--awd-text); }
.activity-summary:focus-visible { outline: 2px solid var(--awd-accent); outline-offset: 4px; border-radius: 3px; }
.activity-summary.has-error { color: var(--awd-danger); }
.activity-chevron { display: inline-block; transition: transform .18s; font-size: 18px; }
.activity-chevron.open { transform: rotate(90deg); }
.activity-summary.is-working > span:first-child { animation: activity-breathe 1.8s ease-in-out infinite; }
@keyframes activity-breathe { 50% { opacity: .5; } }
@media (prefers-reduced-motion: reduce) { .activity-summary.is-working > span:first-child { animation: none; } }
.activity-details { margin: 8px 0 0 3px; padding-left: 12px; border-left: 1px solid var(--awd-border); }
.activity-details :deep(.process-card) { border: 0; background: transparent; box-shadow: none; }
.activity-details :deep(.process-header) { background: transparent; padding-left: 0; }
.activity-details :deep(.title), .activity-details :deep(.step-text) { color: var(--awd-text-2); font-weight: 400; }
.activity-details :deep(.todo-progress-card) { margin: 0; border: 0; background: transparent; }
.activity-details :deep(.todo-header) { background: transparent; }

.main-content:deep(p) {
  margin: 0 0 8px 0;
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
  background: var(--awd-accent-soft); /* Subtle Mint Green Tint */
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 85%;
  color: var(--awd-accent-text); /* Forest Green */
  font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;
}

/* Block Code Style */
.main-content:deep(pre) {
  background: var(--awd-bg); /* Gray-Pale */
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  border: 1px solid var(--awd-border);
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
  font-size: 13px;
  line-height: 1.7;
  margin: 0;
  padding: 0;
  color: var(--awd-text);
}

/* Headings */
.main-content :deep(.markdown-body h1),
.main-content :deep(.markdown-body h2),
.main-content :deep(.markdown-body h3) {
  margin-top: 14px !important;
  margin-bottom: 8px !important;
  font-weight: 600;
  color: var(--awd-text);
}

/* 定位条跳过来之后的短暂高亮（ChatInterface.jumpToAttention 挂/摘这个 class）。
   样式留在本文件：带 data-chat-attention 的两处（审批卡外壳、QuestionCard 根节点）
   都由本组件模板渲染，父组件的 scoped 选择器匹配不到它们。 */
.chat-attention-flash {
  border-radius: 10px;
  box-shadow: 0 0 0 2px var(--awd-warning);
  animation: awd-attention-flash 1.4s ease-out;
}
@keyframes awd-attention-flash {
  from { box-shadow: 0 0 0 6px var(--awd-warning-soft), 0 0 0 2px var(--awd-warning); }
  to { box-shadow: 0 0 0 2px var(--awd-warning); }
}
@media (prefers-reduced-motion: reduce) {
  .chat-attention-flash { animation: none; }
}
</style>

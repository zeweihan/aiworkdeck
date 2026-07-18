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

            <!-- 2b. 计划卡（线性时序结构）：思考结束、制定计划后先展示计划框，
                 下方才是各步骤的执行进展；每步内部再嵌工具调用记录。
                 快照挂在气泡上（plan_update 时写入），历史消息各自保留当轮计划。 -->
            <div v-if="bubble.planTodos && bubble.planTodos.length" class="inline-plan">
               <TodoProgressCard :todos="bubble.planTodos" />
            </div>

            <!-- 3. Process Stream（工具执行按 plan 步骤分组：最新步骤展开，旧步骤收起，
                 避免整个面板被工具卡片刷满；无步骤归属的保持平铺） -->
            <div class="process-stream">
               <template v-for="(group, gi) in processGroups" :key="group.key + '-' + gi">
                  <div v-if="group.title" class="step-group">
                     <div class="step-group-header" @click="toggleGroup(group.key, gi)">
                        <span class="step-group-marker" :class="{ done: isGroupDone(group), error: groupHasError(group) }"></span>
                        <span class="step-group-title">{{ group.title }}</span>
                        <span class="step-group-count">{{ group.procs.length }}</span>
                        <div class="step-chevron" :class="{ 'is-rotated': isGroupExpanded(group.key, gi) }">
                           <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                              <polyline points="6 9 12 15 18 9"></polyline>
                           </svg>
                        </div>
                     </div>
                     <div v-show="isGroupExpanded(group.key, gi)" class="step-group-body">
                        <ProcessCard
                          v-for="proc in group.procs"
                          :key="proc.id"
                          :process="proc"
                        />
                     </div>
                  </div>
                  <template v-else>
                     <ProcessCard
                       v-for="proc in group.procs"
                       :key="proc.id"
                       :process="proc"
                     />
                  </template>
               </template>
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

            <!-- 5b. Message Actions：插入/替换/导出收进一个图标，点开再选（用户反馈三个
                 平铺按钮太占地方）。菜单向上弹，透明遮罩点外即收。 -->
            <div v-if="bubble.content && !bubble.isStreaming" class="message-actions">
               <div class="msg-act-trigger" :class="{ active: showActions }" @click.stop="showActions = !showActions">
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                     <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"></path>
                     <polyline points="14 2 14 8 20 8"></polyline>
                     <line x1="9" y1="15" x2="15" y2="15"></line>
                  </svg>
                  <span>用到文档</span>
               </div>
               <div v-if="showActions" class="msg-act-menu">
                  <div class="msg-act-item" @click="pickAction('insert')">插入当前文档</div>
                  <div class="msg-act-item" @click="pickAction('replace')">替换选区</div>
                  <div class="msg-act-item" @click="pickAction('export')">导出为Word</div>
               </div>
               <div v-if="showActions" class="msg-act-mask" @click.stop="showActions = false"></div>
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
import { computed, ref } from 'vue'
import ThinkingCard from './ThinkingCard.vue'
import TitleCard from './TitleCard.vue'
import TodoProgressCard from './TodoProgressCard.vue'
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

const showActions = ref(false)
function pickAction(type) {
  showActions.value = false
  sendAction(type)
}

// ---- 工具执行按 plan 步骤分组 ----
// process 在创建时被 useAgentStream 打上 stepIndex/stepTitle（当时 in_progress 的
// plan 项）。这里把相邻同步骤的 process 收进一个可折叠组；历史消息没有该标记，
// 落入 key='' 的平铺组，渲染不变。
const processGroups = computed(() => {
  const groups = []
  let cur = null
  for (const p of props.bubble.processes) {
    const grouped = typeof p.stepIndex === 'number' && p.stepIndex >= 0
    const key = grouped ? `s${p.stepIndex}` : ''
    if (!cur || cur.key !== key) {
      cur = { key, title: grouped ? (p.stepTitle || `步骤 ${p.stepIndex + 1}`) : '', procs: [] }
      groups.push(cur)
    }
    cur.procs.push(p)
  }
  return groups
})

// 默认只展开最新一组（正在进行的步骤）；用户手动开合后以用户为准
const groupToggles = ref({})
const isGroupExpanded = (key, gi) => {
  if (key in groupToggles.value) return groupToggles.value[key]
  return gi === processGroups.value.length - 1
}
const toggleGroup = (key, gi) => {
  groupToggles.value = { ...groupToggles.value, [key]: !isGroupExpanded(key, gi) }
}
const isGroupDone = (g) => g.procs.every(p =>
  !(p.items || []).some(it => it.status === 'doing' || it.status === 'loading' || it.status === 'thinking')
)
const groupHasError = (g) => g.procs.some(p => (p.items || []).some(it => it.status === 'error'))

const hasPlan = computed(() => !!(props.bubble.planTodos && props.bubble.planTodos.length > 0))

const isReady = computed(() => {
    // Show full card if we have a Title OR Plan OR Processes OR Main Content
    // If only "Thinking", remain in Ghost state (unless it's done thinking and has no other content? No, unlikely)
    return !!(props.bubble.title || hasPlan.value || props.bubble.processes.length > 0 || props.bubble.content)
})

const hasContent = computed(() => {
    // Check if the bubble has any content to display
    return !!(
        props.bubble.title ||
        hasPlan.value ||
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
    margin-bottom: 6px;
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
  margin-bottom: 14px; /* Compact spacing between bubbles */
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
  padding: 6px 12px;
  font-size: 13px;
  line-height: 1.55;
  color: #2C3338; /* Gray-Dark */
}

.message-actions {
  position: relative;
  display: flex;
  padding: 8px 16px 12px;
  border-top: 1px solid #f1f5f9;
  margin-top: 6px;
}

.msg-act-trigger {
  display: flex;
  align-items: center;
  gap: 5px;
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

.msg-act-trigger:hover, .msg-act-trigger.active {
  border-color: #5BD197;
  color: #1A5336;
  background: #E6F9F0;
}

.msg-act-menu {
  position: absolute;
  left: 16px;
  bottom: calc(100% - 2px);
  background: #FFFFFF;
  border: 1px solid #E9ECEF;
  border-radius: 8px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.10);
  padding: 4px;
  z-index: 99;
  min-width: 140px;
}

.msg-act-item {
  font-size: 12px;
  color: #2C3338;
  padding: 6px 10px;
  border-radius: 5px;
  cursor: pointer;
  white-space: nowrap;
}

.msg-act-item:hover {
  background: #E6F9F0;
  color: #1A5336;
}

.msg-act-mask {
  position: fixed;
  inset: 0;
  z-index: 98;
  background: transparent;
}

/* 内联计划卡：卡片容器内左右留边，与步骤分组视觉对齐 */
.inline-plan {
  padding: 8px 10px 2px;
}

.inline-plan :deep(.todo-progress-card) {
  margin: 0;
}

/* ---- plan 步骤分组 ---- */
.step-group {
  border-bottom: 1px solid #f1f5f9;
}

.step-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.step-group-header:hover {
  background: #F8F9FA;
}

.step-group-marker {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ADB5BD;
  flex-shrink: 0;
}

.step-group-marker.done {
  background: #5BD197;
}

.step-group-marker.error {
  background: #E74C3C;
}

.step-group-title {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: #2C3338;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.step-group-count {
  font-size: 10px;
  color: #6C757D;
  background: #F1F3F5;
  border-radius: 99px;
  padding: 0 6px;
  flex-shrink: 0;
}

.step-chevron {
  color: #ADB5BD;
  transition: transform 0.2s ease;
  display: flex;
  align-items: center;
}

.step-chevron.is-rotated {
  transform: rotate(180deg);
}

.step-group-body {
  padding-left: 10px;
  border-left: 2px solid #E6F9F0;
  margin-left: 14px;
}

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
  font-size: 13px;
  line-height: 1.55;
  margin: 0;
  padding: 0;
  color: #2C3338;
}

/* Headings */
.main-content :deep(.markdown-body h1),
.main-content :deep(.markdown-body h2),
.main-content :deep(.markdown-body h3) {
  margin-top: 14px !important;
  margin-bottom: 8px !important;
  font-weight: 600;
  color: #1A5336; /* Forest Green for headings */
}
</style>

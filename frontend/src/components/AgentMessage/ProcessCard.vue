<template>
  <div
    class="process-card"
    :class="{
      'is-expanded': (isExpanded || isHeadless),
      'is-system-actions': isSystemActions,
      'headless-process': isHeadless
    }"
  >
    <!-- Header Area -->
    <div class="process-header" @click="toggle" v-if="!isHeadless">
      <div class="left">
        <div class="header-icon-wrapper">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="header-action-icon">
            <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
          </svg>
        </div>
        <span class="title">{{ processTitle }}</span>
      </div>
      <div class="right">

        <div v-if="hasError" class="status-badge error">{{ $t('chat.statusError') }}</div>
        <div v-else-if="isFinished" class="status-badge success">{{ $t('chat.statusSuccess') }}</div>
        <div v-else class="status-badge processing">{{ $t('chat.statusRunning') }}</div>
        <div class="chevron-wrapper" :class="{ 'is-rotated': isExpanded }">
          <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
      </div>
    </div>

    <div class="process-body" v-if="isExpanded || isHeadless">
      <!-- Items List -->
      <div class="items-list" v-if="renderItems.length > 0">
        <div v-for="(item, idx) in renderItems" :key="idx" class="process-item">

            <!-- CASE 1: Normal Step or File Attachment -->
            <div v-if="item.type === 'step'" class="step-container">
                <template v-if="detectFile(item.text)">
                    <div class="file-attachment-card">
                        <div class="file-icon-area">
                            <FileTypeIcon :type="getFileExtension(detectFile(item.text))" />
                        </div>
                        <div class="file-details">
                            <div class="file-name">{{ detectFile(item.text) }}</div>
                            <div class="file-meta">{{ item.text.replace(`《${detectFile(item.text)}》`, '').trim() }}</div>
                        </div>
                        <div class="file-actions">
                            <div class="action-btn">
                                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                                    <polyline points="7 10 12 15 17 10"></polyline>
                                    <line x1="12" y1="15" x2="12" y2="3"></line>
                                </svg>
                            </div>
                        </div>
                    </div>
                </template>
                <div v-else class="step-row">
                    <span class="step-dot" :class="{ 'done': item.status !== 'doing' }"></span>
                    <span class="step-text" :class="{ 'is-meta': isSecondaryContent(item.text) }">{{ item.text || $t('chat.processing') }}</span>
                </div>
            </div>

            <!-- CASE 2: Nested Thinking -->
            <div v-else-if="item.type === 'thinking'" class="thinking-row">
                <ThinkingCard
                   variant="inline"
                   :status="item.status"
                   :content="item.content"
                   :duration="item.duration || 0"
                   :start-time="item.startTime"
                />
            </div>

            <!-- CASE 3: Tool Execution（单行：人性化名称 + 状态；原始代号收进 title 提示）
                 有输出时整行可点开看结果——这是本领域「可核验」的底线：AI 说「已核对 12 处
                 股东名册」，律师必须能自己点开看那 12 处。默认收起（PR#180 线性密度口径），
                 展开只能是用户主动动作。 -->
            <div v-else-if="item.type === 'tool'" class="tool-block">
                <div
                  class="tool-row"
                  :class="{ 'is-clickable': hasOutput(item) }"
                  :title="hasOutput(item) ? $t('chat.toolClickToView', { name: rawToolName(item.code) }) : rawToolName(item.code)"
                  @click="hasOutput(item) && toggleOutput(idx)"
                >
                    <div class="tool-content">
                         <span class="tool-name">{{ formatToolName(item.code) }}</span>
                    </div>
                    <div class="tool-right">
                         <div class="tool-status">
                              <span v-if="item.status === 'loading'" class="status-loading">{{ $t('chat.toolCalling') }}</span>
                              <span v-else-if="item.status === 'success'" class="status-success">{{ $t('chat.done') }}</span>
                              <span v-else class="status-error">{{ $t('chat.statusError') }}</span>
                         </div>
                         <div v-if="hasOutput(item)" class="output-chevron" :class="{ 'is-rotated': isOutputOpen(idx) }">
                            <svg xmlns="http://www.w3.org/2000/svg" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                               <polyline points="6 9 12 15 18 9"></polyline>
                            </svg>
                         </div>
                    </div>
                </div>

                <div v-if="hasOutput(item) && isOutputOpen(idx)" class="tool-output">
                    <!-- 子任务结果单独渲染：dispatch_subtask 的输出是 SubAgentResult 的 JSON，
                         裸 JSON 对律师毫无意义。解析不出预期结构就退回纯文本（下方分支）。 -->
                    <SubtaskResultCard v-if="subtaskResult(item)" :result="subtaskResult(item)" />
                    <!-- 原始 JSON 对律师是一段「代码」（dev-board#178）：能解析的一律
                         渲染成缩进键值文本（空字段/语法噪音剥掉），解析不了的才按原文展示。 -->
                    <div v-else class="output-text">{{ humanOutput(item) }}</div>
                    <!-- 截断标记由后端 SSE 侧加（AgentOrchestrator.toolOutputDisplayLimit 按
                         工具分档：结果型工具 16000，其余 4000），必须明示：模型看到的是全文，
                         这里没有。刻意不写具体字数——上限是分档的，写死数字就会说谎。 -->
                    <div v-if="isTruncated(item)" class="output-truncated">
                        {{ $t('chat.outputTruncatedNote') }}
                    </div>
                </div>
            </div>

        </div>
      </div>

      <!-- Fallback for legacy 'steps' array -->
      <div class="steps-list" v-else-if="process.steps && process.steps.length > 0">
         <div class="step-item" v-for="(step, idx) in process.steps" :key="idx">
            <span class="step-dot done"></span>
            <span class="step-text">{{ step.text }}</span>
         </div>
      </div>
      <div style="width: 100%; border-bottom: 1px solid #eee; margin: 0 auto; margin-top: 6px;"></div>


    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ThinkingCard from './ThinkingCard.vue'
import SubtaskResultCard from './SubtaskResultCard.vue'
import FileTypeIcon from '../FileTypeIcon.vue'
import { toolDisplayName, toolRawName } from '@/utils/toolDisplayNames.js'
import { humanizeToolOutput } from '@/utils/toolOutputHumanize.js'
import { isEnglish } from '@/utils/appLanguage.js'
import { t } from '@/i18n'

const props = defineProps({
  process: { type: Object, required: true }
})

const isExpanded = ref(false)

const isSystemActions = computed(() => {
  // '系统操作' 是现名；'System Actions' 兼容历史数据
  return props.process.title === '系统操作' || props.process.title === 'System Actions'
})

const isHeadless = computed(() => {
    return !props.process.title || props.process.title === 'Processing...'
})

const processTitle = computed(() => {
    const title = props.process.title || 'Processing...'
    const firstTool = (props.process.items || []).find(it => it.type === 'tool' && it.code)
    // 英文界面：后端 <process name> 里的 displayName 是中文（PR4 之前后端不分语言），
    // 只要能解析出首个工具代号就优先用前端表的 en 列；解析不出再回退后端标题。
    if (isEnglish() && firstTool) return toolDisplayName(firstTool.code)
    // 模型偶尔把 process 命名成笼统的「工具执行」——与内部的「执行工具 xxx」行
    // 冗余（用户反馈）。此时直接用第一个工具的人性化名称当标题。
    if (title === '工具执行' || title === 'Processing...' || title === 'Tool Execution') {
        if (firstTool) return toolDisplayName(firstTool.code)
    }
    return title
})

const isFinished = computed(() => {
    // 有任何条目仍在进行中（步骤 doing / 工具 loading / 思考 thinking）都算未完成
    if (!props.process.items || props.process.items.length === 0) return false
    return !props.process.items.some(item =>
        item.status === 'doing' || item.status === 'loading' || item.status === 'thinking'
    )
})

const hasError = computed(() => {
    if (!props.process.items) return false
    return props.process.items.some(item => item.status === 'error')
})

const userHasToggled = ref(false)

const toggle = () => {
  userHasToggled.value = true
  isExpanded.value = !isExpanded.value
}

watch(() => props.process.items?.length, (newLen, oldLen) => {
    if (newLen > 0 && (!oldLen || oldLen === 0) && !userHasToggled.value) {
        isExpanded.value = true
    }
}, { immediate: true })

const formatToolName = (code) => {
    if (!code) return t('chat.toolCallFallback')
    const name = toolDisplayName(code)
    return name.length > 40 ? name.substring(0, 37) + '...' : name
}

const rawToolName = (code) => toolRawName(code)

// ---- 工具返回结果的折叠区 ----
// 后端一直把每个工具的输出以 <tool_output status=…> 发到前端（流式与历史都有，
// 落在 item.output 上），此前面板从不渲染它。默认收起，按 idx 记开合状态
// （items 只追加不重排，idx 稳定）。
const openOutputs = ref({})

const hasOutput = (item) => !!(item && item.output && String(item.output).trim())

const outputText = (item) => String((item && item.output) || '').trim()

// 可读化缓存：与 subtaskCache 同理，流式中 output 每个 token 都在变。
const humanCache = new Map()
const humanOutput = (item) => {
    const raw = outputText(item)
    if (humanCache.has(raw)) return humanCache.get(raw)
    const human = humanizeToolOutput(raw)
    const shown = human != null ? human : raw
    if (humanCache.size > 16) humanCache.clear()
    humanCache.set(raw, shown)
    return shown
}

const isOutputOpen = (idx) => !!openOutputs.value[idx]

const toggleOutput = (idx) => {
    openOutputs.value[idx] = !openOutputs.value[idx]
}

// 截断标记由 AgentOrchestrator.truncate 拼在 SSE 载荷末尾（历史里存的是全文，
// 所以历史消息通常不会命中这一条）。中英双后缀兼容：历史消息与切语言场景都可能
// 命中任一种后缀，不按当前界面语言二选一判定。
const isTruncated = (item) => {
    const text = outputText(item)
    return text.endsWith('...(截断)') || text.endsWith('...(truncated)')
}

// 子任务结果解析缓存：流式中 output 每个 token 都在变，不缓存会让
// SubtaskResultCard 每帧都收到新对象引用而整卡重建。键是 output 原文，
// 超过 8 条直接清空（一张过程卡不会有那么多子任务）。
const subtaskCache = new Map()

/** dispatch_subtask 的输出是 SubAgentResult 的 JSON；解析不出预期结构返回 null → 调用方退回纯文本 */
const subtaskResult = (item) => {
    const raw = outputText(item)
    if (!raw || raw.charAt(0) !== '{') return null
    if (subtaskCache.has(raw)) return subtaskCache.get(raw)
    let parsed = null
    try {
        const obj = JSON.parse(raw)
        // 只认「像 SubAgentResult」的对象：必须有 subtaskId，否则可能是别的工具返回的 JSON
        if (obj && typeof obj === 'object' && !Array.isArray(obj) && typeof obj.subtaskId === 'string') {
            parsed = obj
        }
    } catch (e) {
        // 截断的 JSON、非 JSON 输出等一律退回纯文本展示，不许抛异常炸掉整个气泡
        parsed = null
    }
    if (subtaskCache.size > 8) subtaskCache.clear()
    subtaskCache.set(raw, parsed)
    return parsed
}

const detectFile = (text) => {
    if (!text) return null
    const match = text.match(/《([^》]+)》/)
    return match ? match[1] : null
}

const getFileExtension = (filename) => {
    if (!filename) return 'file'
    const parts = filename.split('.')
    return parts.length > 1 ? parts.pop().toLowerCase() : 'file'
}

const isSecondaryContent = (text) => {
    if (!text) return false
    return text.includes('主要内容') || text.includes('摘要')
}

// ---- 单子项去重折叠（对齐 Claude 桌面端：一行工具调用 + 一条轻量思考折叠） ----
// 步骤展开后经常还有一层：一行「进度文案」（type: step，模型边做边述的过程文字）
// 复述的内容跟 process 自己的标题（如「读取材料并查阅规范」）大意相同（如「查阅制图
// 规范」）。这层子列表不提供标题之外的新信息，默认折叠掉，只留步骤行（本卡头部）+
// 思考过程折叠入口。纯展示层判定，不改 process.items 数据结构本身。

// 状态尾缀（已完成/成功/进行中…）与「正在/已」前缀只是噪声，参与语义比较前先剥掉
const STATUS_SUFFIX_RE = /[-—–:：]?\s*(已完成|已核对|完成|已就绪|成功|进行中|处理中|done|success|completed|finished|in progress)[。.]?\s*$/i
const normalizeLabel = (text) => {
    if (!text) return ''
    let s = String(text).trim()
    s = s.replace(STATUS_SUFFIX_RE, '')
    s = s.replace(/[。.…]+\s*$/, '')
    s = s.replace(/^(正在|开始|已|正)/, '')
    return s.trim()
}

const charBigrams = (s) => {
    const grams = new Set()
    if (!s) return grams
    if (s.length < 2) { grams.add(s); return grams }
    for (let i = 0; i < s.length - 1; i++) grams.add(s.substr(i, 2))
    return grams
}

// 字符级二元组 Jaccard 相似度（对中文短语比分词简单可靠）+ 互相包含的强信号兜底。
// 阈值 0.34 是经验值——「查阅制图规范」与「读取材料并查阅规范」的重叠度在这附近。
// 纯展示层判定，误判代价只是多显示/少显示一行文字，不影响数据。
const labelsSimilar = (a, b) => {
    const na = normalizeLabel(a)
    const nb = normalizeLabel(b)
    if (!na || !nb) return false
    if (na === nb || na.includes(nb) || nb.includes(na)) return true
    const A = charBigrams(na)
    const B = charBigrams(nb)
    let inter = 0
    A.forEach(g => { if (B.has(g)) inter++ })
    const union = A.size + B.size - inter
    return union > 0 && (inter / union) >= 0.34
}

// 折叠命中条件：process.items 里除 thinking 外只剩一条内容条目，且它是纯文字的
// 「进度文案」（type: step）。type: tool 永远不参与折叠——那是用户点开核验
// 参数/输出的唯一入口，绝不能因为语义撞了标题就被藏起来。出错条目、文件附件
// 卡片同样永不折叠（前者要求永远可见，后者是有信息量的独立展示，不是文字复述）。
const shouldCollapseSoleItem = computed(() => {
    const contentItems = (props.process.items || []).filter(i => i.type === 'step' || i.type === 'tool')
    if (contentItems.length !== 1) return false
    const only = contentItems[0]
    if (only.type !== 'step' || only.status === 'error' || detectFile(only.text)) return false
    return labelsSimilar(only.text, processTitle.value)
})

// 折叠命中时只保留 thinking 条目（思考过程的展开入口不受影响）；未命中原样返回
// process.items 本身的引用与下标，不打乱「items 只追加不重排、按下标记开合」的既有契约。
const renderItems = computed(() => {
    if (!shouldCollapseSoleItem.value) return props.process.items || []
    return (props.process.items || []).filter(i => i.type === 'thinking')
})
</script>

<style scoped>
.process-card {
  background: var(--awd-surface);
  border-radius: 12px 12px 0 0;
  /* border-bottom: 1px solid #1A5336; */
  /* box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05), 0 1px 2px rgba(0, 0, 0, 0.1); */
  /* margin-bottom: 12px; */
  overflow: hidden;
  transition: all 0.2s ease;
}

.process-card.headless-process {
    background: transparent;
    box-shadow: none;
    margin-top: -8px;
}

.process-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px;
  cursor: pointer;
  background: var(--awd-surface);
  transition: background 0.15s;
}

.process-header:hover {
  background: var(--awd-bg); /* Gray-Pale */
}

.left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon-wrapper {
  color: var(--awd-accent-text); /* Forest Green */
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-accent-text); /* Forest Green */
}

.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 99px;
}

.status-badge.success {
  background: var(--awd-accent-soft); /* Mint Lightest */
  color: var(--awd-accent-text); /* Forest Green */
}

.status-badge.processing {
  background: var(--awd-surface-3); /* Gray-Light */
  color: var(--awd-text-2); /* Gray-Medium */
}

.status-badge.error {
  background: var(--awd-bg);
  color: var(--awd-danger-text);
}

.chevron-wrapper {
  color: var(--awd-text-3);
  transition: transform 0.2s ease;
}

.chevron-wrapper.is-rotated {
  transform: rotate(180deg);
}

.process-body {
  padding: 4px 12px 4px 12px;
}

.process-item {
    margin-bottom: 4px;
}

/* Step Styling */
.step-container {
    width: 100%;
}

.step-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-left: 2px;
}

.step-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--awd-surface-3);
  margin-top: 6px;
  flex-shrink: 0;
}

.step-dot.done {
    background: var(--awd-mint); /* Mint Green */
}

.step-text {
  font-size: 12px;
  color: var(--awd-text); /* Gray-Dark */
  line-height: 1.45;
}

.step-text.is-meta {
    color: var(--awd-text-2); /* Gray-Medium */
    font-size: 11px;
}

/* File Attachment Card */
.file-attachment-card {
    display: flex;
    align-items: center;
    background: var(--awd-bg); /* Gray-Pale */
    border: 1px solid var(--awd-border); /* Gray-Light */
    border-radius: 8px;
    padding: 7px 10px;
    gap: 10px;
    margin: 3px 0 5px 0;
}

.file-icon-area {
    flex-shrink: 0;
}

.file-details {
    flex: 1;
    min-width: 0;
}

.file-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--awd-text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.file-meta {
    font-size: 11px;
    color: var(--awd-text-2);
    margin-top: 1px;
}

.file-actions {
    display: flex;
    align-items: center;
}

.action-btn {
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    color: var(--awd-text-2);
    cursor: pointer;
    transition: all 0.2s;
}

.action-btn:hover {
    background: var(--awd-surface-3);
    color: var(--awd-accent-text);
}

/* Tool Row */
.tool-block {
  width: 100%;
}

.tool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 8px;
  background: var(--awd-bg);
  border-radius: 5px;
  margin-left: 0;
}

.tool-row.is-clickable {
  cursor: pointer;
}

.tool-row.is-clickable:hover {
  background: var(--awd-surface-3);
}

.tool-right {
    display: flex;
    align-items: center;
    gap: 5px;
    flex-shrink: 0;
}

.output-chevron {
    color: var(--awd-text-3);
    display: flex;
    align-items: center;
    transition: transform 0.2s ease;
}

.output-chevron.is-rotated {
    transform: rotate(180deg);
}

/* 工具返回结果折叠区 */
.tool-output {
    margin: 3px 0 5px 0;
    border: 1px solid var(--awd-border);
    border-radius: 6px;
    background: var(--awd-surface);
    overflow: hidden;
}

.output-text {
    padding: 6px 8px;
    font-family: 'SF Mono', Menlo, Consolas, monospace;
    font-size: 11px;
    line-height: 1.5;
    color: var(--awd-text);
    white-space: pre-wrap;
    word-break: break-word;
    /* 长输出自己滚，不把气泡撑爆 */
    max-height: 240px;
    overflow-y: auto;
    overflow-x: auto;
}

.output-truncated {
    padding: 4px 8px;
    border-top: 1px solid var(--awd-border-subtle);
    background: var(--awd-bg);
    font-size: 10px;
    color: var(--awd-text-2);
}

.tool-content {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}

.tool-name {
    font-size: 12px;
    color: var(--awd-accent-text);
    font-weight: 500;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.tool-status {
    font-size: 10px;
    font-weight: 500;
    flex-shrink: 0;
}

.status-loading { color: var(--awd-text-2); }
.status-success { color: var(--awd-mint); }
.status-error { color: var(--awd-danger-text); }

/* Thinking Row */
.thinking-row {
    margin-bottom: 4px;
}
</style>

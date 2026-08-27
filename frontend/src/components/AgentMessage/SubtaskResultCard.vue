<template>
  <div class="subtask-result" :class="{ 'is-failed': !isSuccess }">
    <div class="sr-header">
      <span class="sr-title">{{ $t('chat.subtaskResultTitle') }}</span>
      <span class="sr-badge" :class="isSuccess ? 'ok' : 'bad'">{{ isSuccess ? $t('chat.subtaskProduced') : $t('chat.subtaskIncomplete') }}</span>
    </div>

    <!-- 正文：成功取 result，失败取 error。律师要能核验子 Agent 到底拿回了什么，
         所以正文原样展示（保留换行），不做摘要、不折叠成一行。 -->
    <div class="sr-body">{{ bodyText }}</div>

    <!-- 元信息：用了哪些工具 / 轮数 / 实际模型。
         「读了哪份合同、抽了哪一段」不能只信主 Agent 转述，工具清单是最低限度的过程痕迹。 -->
    <div class="sr-meta">
      <div class="sr-meta-row" v-if="toolChips.length > 0">
        <span class="sr-meta-label">{{ $t('chat.toolsUsed') }}</span>
        <div class="sr-chips">
          <span class="sr-chip" v-for="chip in toolChips" :key="chip.raw" :title="chip.raw">
            {{ chip.label }}<span v-if="chip.count > 1" class="sr-chip-count">×{{ chip.count }}</span>
          </span>
        </div>
      </div>
      <div class="sr-meta-row" v-else>
        <span class="sr-meta-label">{{ $t('chat.toolsUsed') }}</span>
        <span class="sr-meta-value is-empty">{{ $t('chat.noToolsUsed') }}</span>
      </div>
      <div class="sr-meta-row">
        <span class="sr-meta-label">{{ $t('chat.roundsLabel') }}</span>
        <span class="sr-meta-value">{{ roundsText }}</span>
      </div>
      <div class="sr-meta-row" v-if="modelText">
        <span class="sr-meta-label">{{ $t('chat.actualModel') }}</span>
        <span class="sr-meta-value is-mono">{{ modelText }}</span>
      </div>
      <div class="sr-meta-row" v-if="subtaskId">
        <span class="sr-meta-label">{{ $t('chat.subtaskIdLabel') }}</span>
        <span class="sr-meta-value is-mono">{{ subtaskId }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { toolDisplayName, toolRawName } from '@/utils/toolDisplayNames.js'
import { t } from '@/i18n'

// result：已由调用方（ProcessCard）解析好的 SubAgentResult 对象。
// 解析失败的情况调用方不会渲染本组件，会退回纯文本——这里只做字段级容错，
// 不假设任何字段一定存在（后端 toJson 对 null 字段是直接不写 key 的）。
const props = defineProps({
  result: { type: Object, required: true }
})

const isSuccess = computed(() => props.result?.success !== false)

const bodyText = computed(() => {
  const r = props.result || {}
  const text = isSuccess.value ? r.result : r.error
  if (typeof text === 'string' && text.trim()) return text
  // 兜底：success=true 但没带 result（不该出现，出现了也别给律师看空白）
  return isSuccess.value ? t('chat.subtaskNoResult') : t('chat.subtaskNoError')
})

const toolChips = computed(() => {
  const used = props.result?.toolsUsed
  if (!Array.isArray(used)) return []
  // 子 Agent 会重复调同一个工具，按顺序去重并计数，比原样罗列一长串更可读
  const order = []
  const counts = new Map()
  for (const item of used) {
    if (item === null || item === undefined) continue
    const raw = toolRawName(String(item))
    if (!raw) continue
    if (!counts.has(raw)) {
      counts.set(raw, 0)
      order.push(raw)
    }
    counts.set(raw, counts.get(raw) + 1)
  }
  return order.map(raw => ({ raw, label: toolDisplayName(raw) || raw, count: counts.get(raw) }))
})

const roundsText = computed(() => {
  const n = Number(props.result?.rounds)
  return Number.isFinite(n) && n > 0 ? t('chat.roundsN', { n }) : t('chat.unknown')
})

// model / modelId 两个字段名都认：后端今天还没在 SubAgentResult 里带模型 ID，
// 带上之后本卡片无需再改（见 needsFromOthers）。
const modelText = computed(() => {
  const r = props.result || {}
  const v = r.model || r.modelId
  return typeof v === 'string' && v.trim() ? v.trim() : ''
})

const subtaskId = computed(() => {
  const v = props.result?.subtaskId
  return typeof v === 'string' && v.trim() ? v.trim() : ''
})
</script>

<style scoped>
.subtask-result {
  border: 1px solid var(--awd-border); /* Gray-Light */
  border-left: 2px solid var(--awd-mint); /* Mint Green */
  border-radius: 6px;
  background: var(--awd-surface);
  overflow: hidden;
}

.subtask-result.is-failed {
  border-left-color: var(--awd-danger);
}

.sr-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.sr-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-accent-text); /* Forest Green */
}

.sr-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 0 6px;
  border-radius: 99px;
}

.sr-badge.ok {
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
}

.sr-badge.bad {
  background: var(--awd-bg);
  color: var(--awd-danger-text);
}

/* 正文可能很长（子 Agent 摘录了合同段落），自己滚，不撑爆气泡 */
.sr-body {
  padding: 7px 8px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--awd-text); /* Gray-Dark */
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
  overflow-x: auto;
}

.sr-meta {
  border-top: 1px solid var(--awd-border-subtle);
  background: var(--awd-bg); /* Gray-Pale */
  padding: 5px 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.sr-meta-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.sr-meta-label {
  font-size: 10px;
  color: var(--awd-text-2); /* Gray-Medium */
  flex-shrink: 0;
  width: 52px;
  line-height: 16px;
}

.sr-meta-value {
  font-size: 11px;
  color: var(--awd-text);
  line-height: 16px;
  word-break: break-all;
}

.sr-meta-value.is-empty {
  color: var(--awd-text-2);
}

.sr-meta-value.is-mono {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 10px;
  color: var(--awd-text-2);
}

.sr-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.sr-chip {
  font-size: 10px;
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
  border-radius: 4px;
  padding: 1px 5px;
  line-height: 14px;
}

.sr-chip-count {
  color: var(--awd-text-2);
  margin-left: 3px;
}
</style>
